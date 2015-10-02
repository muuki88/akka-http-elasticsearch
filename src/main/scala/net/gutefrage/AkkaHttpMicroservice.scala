package net.gutefrage

import akka.actor.ActorSystem
import akka.event.{ LoggingAdapter, Logging }
import akka.http.scaladsl.Http
import akka.stream.scaladsl._
import akka.http.scaladsl.client.RequestBuilding
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model.{ HttpResponse, HttpRequest }
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.model.ws.{ Message, TextMessage }
import akka.stream.{ ActorMaterializer, Materializer }
import akka.stream.scaladsl.{ Flow, Sink, Source }
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import scala.collection.immutable.Seq
import scala.concurrent.{ ExecutionContextExecutor, Future }
import com.sksamuel.elastic4s.ElasticClient
import akka.stream.OverflowStrategy

trait ActorSystemComponent {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer
}

/**
 * Shows the streaming part
 */
trait ElasticSearchStreaming { self: ActorSystemComponent =>

  val elasticsearch: ElasticClient

  import com.sksamuel.elastic4s.ElasticDsl._
  import com.sksamuel.elastic4s.streams.ReactiveElastic._
  import com.sksamuel.elastic4s.mappings.FieldType._
  import Questions._

  val QUESTION_INDEX = "questions"
  val LIVE = "live"

  def createIndex(): Future[Boolean] = {

    val indexExists = elasticsearch.execute {
      index exists QUESTION_INDEX
    }

    indexExists flatMap { response =>
      if (!response.isExists) {
        elasticsearch.execute {
          create index QUESTION_INDEX mappings (
            LIVE as (
              "qid" typed IntegerType,
              "title" typed StringType,
              "body" typed StringType
            )
          )
        }.map(_.isAcknowledged)
      } else {
        Future.successful(true)
      }
    }

  }

  def query(searchTerm: String): Source[Question, Unit] = {
    val publisher = elasticsearch.publisher(search in QUESTION_INDEX query searchTerm scroll "1m")
    Source(publisher).map(_.as[Question])
  }

}

trait Service { self: ActorSystemComponent with ElasticSearchStreaming =>

  def config: Config
  val logger: LoggingAdapter

  val queryWebsocket = Flow[Message].collect {
    case tm: TextMessage =>
      val questionStream = tm.textStream
        .map(query)
        // flatten the Source of Sources in a single Source
        .flatten(FlattenStrategy.concat)
        // json stuff happens here later
        .map(_.toString)

      //TextMessage(questionStream)
      TextMessage(questionStream)
  }

  val queryScrollingWebsocket = Flow[Message].collect {
    case tm: TextMessage =>

      val commandTriggeredFlow = Flow() { implicit b =>
        import FlowGraph.Implicits._
        val bcast = b.add(Broadcast[Command](2))
        val zip = b.add(Zip[Command, Seq[Question]]())

        bcast.out(0) ~> zip.in0
        bcast.out(1)
          .filter(_.isInstanceOf[Search])
          .map {
            case Search(term) => query(term)
          }
          .flatten(FlattenStrategy.concat).grouped(2) ~> zip.in1

        (bcast.in, zip.out)
      }

      val questionStream = tm.textStream.map(Commands.parse).via(commandTriggeredFlow.map(_.toString))

      TextMessage(questionStream)
  }

  val routes = {
    logRequestResult("akka-http-microservice") {
      pathPrefix("es") {
        path("scroll") {
          handleWebsocketMessages(queryScrollingWebsocket)
        } ~
          path("get") {
            handleWebsocketMessages(queryWebsocket)
          }
      }
    }
  }
}

object AkkaHttpMicroservice extends App with Service with ElasticSearchStreaming with ActorSystemComponent {
  override implicit val system = ActorSystem()
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
