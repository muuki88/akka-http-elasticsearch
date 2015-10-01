package net.gutefrage

import akka.actor.ActorSystem
import akka.event.{ LoggingAdapter, Logging }
import akka.http.scaladsl.Http
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
import scala.concurrent.{ ExecutionContextExecutor, Future }
import com.sksamuel.elastic4s.ElasticClient

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
  import Questions._

  val QUESTION_INDEX = "questions"

  def query(searchTerm: String): Source[Question, Unit] = {
    val publisher = elasticsearch.publisher(search in QUESTION_INDEX query searchTerm scroll "1m")
    Source(publisher).map(_.as[Question])
  }

}

trait Service { self: ActorSystemComponent with ElasticSearchStreaming =>

  def config: Config
  val logger: LoggingAdapter

  val queryWebsocket = Flow[Message].collect {
    case tm: TextMessage => ""
  }

  val routes = {
    logRequestResult("akka-http-microservice") {
      pathPrefix("es") {
        (get & path(Segment)) { ip =>
          complete {
            OK
          }
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
  override val elasticsearch = ElasticClient.local

  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}
