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
  def elasticPersitFlow(): Flow[String, Message, Unit] = ???

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
  }.map { questionStr =>
    // Parsing is a bit rough
    val Array(id, title, body) = questionStr.split(";")
    Question(id.toInt, title, body)
  }.via(elasticInsert()).map { q =>
    TextMessage(s"Inserted $q")
  }

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

