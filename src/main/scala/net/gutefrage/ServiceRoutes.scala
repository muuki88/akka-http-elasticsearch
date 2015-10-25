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
  def elasticInsert(): Flow[Question, Question, Unit] = ???

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
  def insertWebsocket(): Flow[Message, Message, Unit] = ???

  // ---------------------------------------------------- 
  // ----------- Query (no scrolling) ------------------- 
  // ---------------------------------------------------- 

  /**
   * Outputs each element in one message
   */
  def queryWebsocket() = ???

  // ---------------------------------------------------- 
  // ---------- Scrolling results ----------------------- 
  // ---------------------------------------------------- 

  def scrollingWebsocket = ???

}

