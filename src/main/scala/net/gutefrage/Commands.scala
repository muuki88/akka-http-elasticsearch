package net.gutefrage

object Commands {

  def parse(str: String): Command = str.split(":").toList match {
    case "next" :: _ => Next
    case "search" :: terms => Search(terms.mkString(":"))
  }
}

sealed trait Command
case object Next extends Command
case class Search(searchTerm: String) extends Command