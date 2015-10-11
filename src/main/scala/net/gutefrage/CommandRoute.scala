package net.gutefrage

import akka.stream._
import akka.stream.FanOutShape._
import akka.stream.scaladsl.FlexiRoute

/**
 * @see http://doc.akka.io/docs/akka-stream-and-http-experimental/1.0/scala/stream-customize.html#Using_FlexiRoute
 */
class CommandRouteShape(_init: Init[Command] = Name[Command]("CommandRoute"))
    extends FanOutShape[Command](_init) {
  val searchCommands = newOutlet[Search]("search")
  val nextCommands = newOutlet[Next]("next")
  protected override def construct(i: Init[Command]) = new CommandRouteShape(i)
}

class CommandRoute[A] extends FlexiRoute[Command, CommandRouteShape](
  new CommandRouteShape, Attributes.name("CommandRoute")) {
  import FlexiRoute._

  override def createRouteLogic(p: PortT) = new RouteLogic[Command] {
    override def initialState =
      State[Any](DemandFromAll(p.searchCommands, p.nextCommands)) {
        case (ctx, _, element: Search) =>
          ctx.emit(p.searchCommands)(element)
          ctx.emit(p.nextCommands)(Next(2)) // display the first results on a search
          SameState
        case (ctx, _, element: Next) =>
          ctx.emit(p.nextCommands)(element)
          SameState
      }

    override def initialCompletionHandling = eagerClose
  }
}