package net.gutefrage

import akka.stream._
import akka.stream.scaladsl._
import akka.http.scaladsl.model.ws._

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.Future

import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.ElasticClient
import com.sksamuel.elastic4s.streams.ReactiveElastic._
import com.sksamuel.elastic4s.mappings.FieldType._
import com.sksamuel.elastic4s.streams.RequestBuilder
import com.sksamuel.elastic4s.BulkCompatibleDefinition
import Questions._

/**
 * Shows the streaming part
 */
trait ElasticSearchStreaming { self: ActorSystemComponent =>

  val elasticsearch: ElasticClient

  val QUESTION_INDEX = "questions"
  val LIVE = "live"

  implicit val builder = new RequestBuilder[Question] {
    // the request returned doesn't have to be an index - it can be anything supported by the bulk api
    def request(q: Question): BulkCompatibleDefinition = index into QUESTION_INDEX / LIVE fields (
      "qid" -> q.id,
      "title" -> q.title,
      "body" -> q.body
    )
  }

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

  def insert: Sink[Question, Unit] = {
    val subscriber = elasticsearch.subscriber[Question](
      batchSize = 1,
      concurrentRequests = 1
    )
    Sink(subscriber)
  }


}
