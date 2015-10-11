package net.gutefrage

import akka.actor.ActorSystem
import akka.event.{ LoggingAdapter, Logging }
import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.Http
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model.ws._
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContextExecutor, Future }
import com.sksamuel.elastic4s.ElasticClient

trait ActorSystemComponent {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer
}

trait Service { self: ActorSystemComponent with ElasticSearchStreaming =>

  def config: Config
  val logger: LoggingAdapter

  // ---------------------------------------------------- 
  // ----------- Inserting ------------------------------ 
  // ---------------------------------------------------- 

  /**
   * Creates a second sink with the elasticsubscriber and broadcasts
   * to this sink and a second ouput for the flow
   *
   * {{{
   *                   /  ----------------->  out
   * in -> broadcast ->
   *                   \ ->   elastic subscriber ]
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
   * Maps the stream of messages to questions that get inserted into elasticsearch
   *
   * {{{
   *                               / -> TextMessage(question)
   * Message -> String -> Question
   *                               \ -> elasticsearch subscriber
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

  def scrollingWebsocket = Flow[Message].collect {
    case tm: TextMessage => tm.textStream
  }.mapAsync(1) { stream =>
    stream.runFold("")(_ ++ _)
  }.map(Commands.parse)
    .via(commandTriggeredFlow)
    .map(q => TextMessage(q.toString))

  def commandTriggeredFlow: Flow[Command, Question, Unit] = Flow() { implicit b =>
    import FlowGraph.Implicits._

    // routes the questions to different output streams
    val route = b.add(new CommandRoute[Question])

    // search commands create new output streams (without canceling the old)
    val questions = route.searchCommands.map {
      case Search(term) => query(term)
    }.flatten(FlattenStrategy.concat)

    // zip next commands and questions together
    val zip = b.add(ZipWith((msg: Question, trigger: Command) => msg))

    questions ~> zip.in0
    route.nextCommands.mapConcat {
      case Next(num) => (0 until num).map(_ => Next(1))
    } ~> zip.in1

    (route.in, zip.out)
  }

  // ---------------------------------------------------- 
  // ----------------- Routes --------------------------- 
  // ---------------------------------------------------- 

  val routes = {

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
}

object AkkaHttpMicroservice extends App with Service with ElasticSearchStreaming with ActorSystemComponent {
  override implicit val system = ActorSystem("elastic-streams")
  override implicit val executor = system.dispatcher
  override implicit val materializer = ActorMaterializer()

  override val config = ConfigFactory.load()
  override val logger = Logging(system, getClass)
  override val elasticsearch = ElasticClient.remote("localhost", 9300)

  val create = createIndex()
  create.onSuccess {
    case true => println("Index created")
    case false => println("Index hasn't been acknowledged")
  }
  create.onFailure {
    case e => e.printStackTrace()
  }

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
