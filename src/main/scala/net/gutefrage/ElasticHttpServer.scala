package net.gutefrage

import scala.concurrent.ExecutionContextExecutor

import com.sksamuel.elastic4s.ElasticClient
import com.typesafe.config.ConfigFactory

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.Http
import akka.stream.{ ActorMaterializer, Materializer }

/**
 * Main entry point. Sets up all dependencies
 */
object ElasticHttpServer extends App with ServiceRoutes with ElasticSearchStreaming with ActorSystemComponent {
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

  // start http server
  Http().bindAndHandle(routes, config.getString("http.interface"), config.getInt("http.port"))
}

trait ActorSystemComponent {
  implicit val system: ActorSystem
  implicit def executor: ExecutionContextExecutor
  implicit val materializer: Materializer
}