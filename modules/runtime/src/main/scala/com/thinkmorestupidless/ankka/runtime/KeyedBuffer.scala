package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.sdk.{Overflow, WatchEnd, WatchEnded, WatchEvent}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, Outlet}

import scala.collection.mutable

/**
 * What a watch holds for a watcher that has not read it: at most `bound` rows, one per row key.
 *
 * A row arriving for a key already held replaces the one held and moves to the back, so a row
 * written faster than the watcher reads reaches it as its latest version, never an older one after
 * a newer. When `bound` keys are held and another key's row arrives, `overflow` decides. The stage
 * always pulls: a watcher that does not read never holds back what feeds it.
 */
private[ankka] final class KeyedBuffer[Row](bound: Int, overflow: Overflow)
    extends GraphStage[FlowShape[WatchEvent[Row], WatchEvent[Row]]]:

  require(bound >= 1, s"a watch's unread bound is 1 or more, not $bound")

  private val in  = Inlet[WatchEvent[Row]]("KeyedBuffer.in")
  private val out = Outlet[WatchEvent[Row]]("KeyedBuffer.out")

  val shape: FlowShape[WatchEvent[Row], WatchEvent[Row]] = FlowShape(in, out)

  def createLogic(attributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler with OutHandler:

      // Insertion order is the order rows were last changed in: the head changed longest ago.
      private val held = mutable.LinkedHashMap.empty[String, WatchEvent[Row]]

      private def keyOf(event: WatchEvent[Row]): String = event match
        case WatchEvent.Row(key, _)  => key
        case WatchEvent.Removed(key) => key
        case WatchEvent.CaughtUp     => ""

      override def preStart(): Unit = pull(in)

      def onPush(): Unit =
        val event = grab(in)
        val key   = keyOf(event)
        if held.contains(key) then
          held.remove(key)
          held.update(key, event)
        else if held.size < bound then held.update(key, event)
        else
          overflow match
            case Overflow.DropHead =>
              held.remove(held.head._1)
              held.update(key, event)
            case Overflow.DropTail =>
              held.remove(held.last._1)
              held.update(key, event)
            case Overflow.DropNew => ()
            case Overflow.DropAll =>
              held.clear()
              held.update(key, event)
            case Overflow.Fail =>
              failStage(WatchEnded(WatchEnd.Unread))
        if !isClosed(out) && isAvailable(out) then deliver()
        if !isClosed(in) && !hasBeenPulled(in) then pull(in)

      def onPull(): Unit =
        deliver()
        if isClosed(in) && held.isEmpty then completeStage()

      override def onUpstreamFinish(): Unit =
        if held.isEmpty then completeStage()

      private def deliver(): Unit =
        held.headOption.foreach { (key, event) =>
          held.remove(key)
          push(out, event)
        }

      setHandlers(in, out, this)
