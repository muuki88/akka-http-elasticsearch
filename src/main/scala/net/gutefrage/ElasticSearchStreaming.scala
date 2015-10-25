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
import model.Question
import model.Questions._

/**
 * Shows the streaming part
 */
trait ElasticSearchStreaming { self: ActorSystemComponent =>

  val elasticsearch: ElasticClient

  /**
   * Creates the index if not existing
   * @return true if index exists and is acknowledged
   */
  def createIndex(): Future[Boolean] = elasticsearch.execute {
    index exists QUESTION_INDEX
  }.flatMap {
    case response if response.isExists() => Future.successful(true)
    case response => elasticsearch.execute {
      create index QUESTION_INDEX mappings (
        QUESTION_INDEX_TYPE as (
          "qid" typed IntegerType,
          "title" typed StringType,
          "body" typed StringType
        )
      )
    }.map(_.isAcknowledged)
  }

  /**
   * @param searchTerm to search for in the index
   * @return Source of question objects
   */
  def query(searchTerm: String): Source[Question, Unit] = {
    val publisher = elasticsearch.publisher(search in QUESTION_INDEX query searchTerm scroll "1m")
    Source(publisher).map(_.as[Question])
  }

  /**
   * @return a sink to write questions to
   */
  def insert: Sink[Question, Unit] = {
    val subscriber = elasticsearch.subscriber[Question](
      batchSize = 1,
      concurrentRequests = 1
    )
    Sink(subscriber)
  }

}
