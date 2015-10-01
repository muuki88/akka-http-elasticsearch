package net.gutefrage

import com.sksamuel.elastic4s._

/**
 * parsers
 */
object Questions {

  implicit object QuestionHitAs extends HitAs[Question] {
    override def as(hit: RichSearchHit): Question = {
      Question(hit.sourceAsMap("id").asInstanceOf[Int])
    }
  }
}

case class Question(id: Int) 

