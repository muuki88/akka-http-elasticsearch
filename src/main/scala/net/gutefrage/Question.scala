package net.gutefrage

import com.sksamuel.elastic4s._

/**
 * parsers
 */
object Questions {

  implicit object QuestionHitAs extends HitAs[Question] {
    override def as(hit: RichSearchHit): Question = {
      Question(
        hit.sourceAsMap("qid").asInstanceOf[Int],
        hit.sourceAsMap("title").toString,
        hit.sourceAsMap("body").toString)
    }
  }
}

case class Question(id: Int, title: String, body: String) 

