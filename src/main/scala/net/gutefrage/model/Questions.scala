package net.gutefrage.model

import com.sksamuel.elastic4s._
import com.sksamuel.elastic4s.ElasticDsl._
import com.sksamuel.elastic4s.streams.RequestBuilder

case class Question(id: Int, title: String, body: String) 

/**
 * parsers and model conversions
 */
object Questions {

  val QUESTION_INDEX = "questions"
  val QUESTION_INDEX_TYPE = "live"
  
  private val qid = "qid"
  private val title = "title"
  private val body = "body"

  /**
   * SearchHit model conversion
   */
  implicit object QuestionHitAs extends HitAs[Question] {
    override def as(hit: RichSearchHit): Question = {
      Question(
        hit.sourceAsMap(qid).asInstanceOf[Int],
        hit.sourceAsMap(title).toString,
        hit.sourceAsMap(body).toString)
    }
  }

  /**
   * Subscriber model conversion
   * {{{
   * Source[Question] -> Subscriber[Question] -> 'InsertStmt' (BulkCompatibleDefinition)
   * }}}
   */
  implicit val builder = new RequestBuilder[Question] {
    // the request returned doesn't have to be an index - it can be anything supported by the bulk api
    def request(q: Question): BulkCompatibleDefinition = index into QUESTION_INDEX / QUESTION_INDEX_TYPE fields (
      qid -> q.id,
      title -> q.title,
      body -> q.body
    )
  }

}


