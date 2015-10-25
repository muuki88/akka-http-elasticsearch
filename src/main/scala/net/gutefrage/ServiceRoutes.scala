package net.gutefrage

import scala.util.Try
import com.typesafe.config.Config
import akka.event.LoggingAdapter
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.http.scaladsl.server.Directives._
import akka.stream._
import akka.stream.scaladsl._
import net.gutefrage.model.{ Command, Commands, Next, Question, Search }
import scala.util.control.NonFatal
import scala.util.Failure

trait ServiceRoutes { self: ActorSystemComponent with ElasticSearchStreaming =>

  // dependencies

  def config: Config
  val logger: LoggingAdapter

  // ---------------------------------------------------- 
  // ----------------- Routes --------------------------- 
  // ---------------------------------------------------- 

  // format: OFF 
  val routes = {
    //
    logRequestResult("akka-http-elasticsearch") {
      pathPrefix("es") {
        path("scroll") {
          handleWebsocketMessages(scrollingWebsocket)
        } ~
        path("get") {
          handleWebsocketMessages(queryWebsocket)
        } ~
        path("insert") {
          handleWebsocketMessages(insertWebsocket)
        }
      }
    }
  }
  // format: ON 

  // ---------------------------------------------------- 
  // ----------- Inserting ------------------------------ 
  // ---------------------------------------------------- 

  /**
   * Creates a second sink with the elasticsubscriber and broadcasts
   * to this sink and a second ouput for the flow
   *
   * {{{
   *                   /  ~~~~~~~~~~~~~~~~~~~>  out
   * in ~> broadcast ->
   *                   \ ~>   elastic subscriber ]
   * }}}
   */
  def elasticInsert(): Flow[Question, Question, Unit] = Flow() { implicit b =>
    import FlowGraph.Implicits._

    val broadcast = b.add(Broadcast[Question](2))
    broadcast.out(0) ~> insert

    // expose ports
    (broadcast.in, broadcast.out(1))
  }

  /**
   * {{{
   *
   *
   *                                        / filter(success) ~> elastiInsert() ~> successMessage \
   * in ~> parse: Try[Question] ~> bcast ~>                                                          ~> merge: Message ~> out
   *                                        \ filter(failure) ~> errorMessage                     /
   *
   * }}}
   */
  def elasticPersitFlow(): Flow[String, Message, Unit] = {
    // Flow parsing the question string into a question object
    val parseFlow = Flow[String].map { questionStr =>
      Try {
        require(questionStr.split(";").length == 3, "Input must have 3 fields: <id:Int>;<title:String>;<body:String>")
        val Array(id, title, body) = questionStr.split(";")
        Question(id.toInt, title, body)
      } recoverWith {
        case NonFatal(e) => Failure(new IllegalArgumentException(s"Error parsing input $questionStr:\n${e.getClass.getSimpleName}: ${e.getMessage}", e))
      }
    }

    // flows mapping results to a valid message
    val successMessage = Flow[Question].map(q => TextMessage(s"Inserted: $q"))
    val errorMessage = Flow[Throwable].map(e => TextMessage(e.getMessage))

    // final persist flow. 
    val persistFlow = Flow() { implicit b =>
      import FlowGraph.Implicits._

      val bcast = b.add(Broadcast[Try[Question]](2))
      val merge = b.add(Merge[TextMessage](2))

      bcast.out(0).filter(_.isSuccess).map(_.get) ~> elasticInsert() ~> successMessage ~> merge
      bcast.out(1).filter(_.isFailure).map(_.failed.get) ~> errorMessage ~> merge

      (bcast.in, merge.out)
    }

    parseFlow.via(persistFlow)
  }

  /**
   * Maps the stream of messages to questions that get inserted into elasticsearch
   *
   * {{{
   *                               / ~> TextMessage(question)
   * Message ~> String ~> Question
   *                               \ ~> elasticsearch subscriber
   * }}}
   */
  def insertWebsocket(): Flow[Message, Message, Unit] = Flow[Message].collect {
    case tm: TextMessage => tm.textStream
  }.mapAsync(1) { stream =>
    stream.runFold("")(_ ++ _)
  }.via(elasticPersitFlow)

  // ---------------------------------------------------- 
  // ----------- Query (no scrolling) ------------------- 
  // ---------------------------------------------------- 

  /**
   * Outputs each element in one message
   */
  def queryWebsocket() = Flow[Message].collect {
    case tm: TextMessage => tm.textStream
  }.mapAsync(1) { stream =>
    stream.runFold("")(_ ++ _)
  }.map(query)
    // flatten the Source of Sources in a single Source
    .flatten(FlattenStrategy.concat)
    // json stuff happens here later
    .map(q => TextMessage(q.toString))

  // ---------------------------------------------------- 
  // ---------- Scrolling results ----------------------- 
  // ---------------------------------------------------- 

  def scrollingWebsocket = ???

}

