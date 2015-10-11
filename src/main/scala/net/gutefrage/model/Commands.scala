package net.gutefrage.model

import scala.util.Try

object Commands {

  def parse(str: String): Command = str.split(":").toList match {
    case "next" :: Nil => Next(1)
    case "n" :: Nil => Next(1)
    //
    case "next" :: num :: _ => Next(safeInt(num))
    case "n" :: num :: _ => Next(safeInt(num))
    //
    case "search" :: terms => Search(terms.mkString(":"))
    case "s" :: terms => Search(terms.mkString(":"))
  }

  private def safeInt(str: String): Int = Try(str.toInt).getOrElse(1)
}

sealed trait Command
case class Next(num: Int) extends Command
case class Search(searchTerm: String) extends Command