package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId}
import com.thinkmorestupidless.ankka.runtime.EntityProtocol
import com.thinkmorestupidless.ankka.sdk.CallTransport
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{Flow, Source}
import org.apache.pekko.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import org.apache.pekko.stream.{Attributes, FlowShape, Inlet, OverflowStrategy, Outlet}

/**
 * The subscriber's end of an instance's notifications.
 *
 * The instance pushes to every subscriber and waits for none of them. A subscriber that reads too
 * slowly therefore loses notifications rather than slowing the instance down: the oldest buffered
 * ones are dropped, and one `Dropped` notification, counting them, is delivered ahead of the next.
 */
private[ankka] object NotificationSource:

  val Capacity = 1024

  def apply(
      transport: CallTransport,
      componentId: ComponentId,
      instanceId: String
  ): Source[Notification, NotUsed] =
    ActorSource
      .actorRef[EntityProtocol.StreamToken](
        completionMatcher = { case EntityProtocol.StreamCompleted => () },
        failureMatcher = { case failed: EntityProtocol.StreamFailed => failed.toCommandError },
        // Drained at once by the stage below, which never refuses an element, so this never fills.
        bufferSize = Capacity,
        overflowStrategy = OverflowStrategy.dropHead
      )
      .mapMaterializedValue { subscriber =>
        transport.tell(
          componentId,
          EntityId(instanceId),
          EntityProtocol.InvokeStream(
            HostProtocol.Notifications.toString,
            Array.emptyByteArray,
            Vector.empty,
            subscriber
          )
        )
        NotUsed
      }
      .collect { case EntityProtocol.Token(text) =>
        Notification.serializer.fromBytes(text.getBytes("UTF-8"))
      }
      .via(
        dropOldest(
          Capacity,
          n => Notification.Dropped(componentId.toString, instanceId, n, System.currentTimeMillis())
        )
      )

  /**
   * A buffer of `capacity` that never back-pressures upstream: when full it drops its oldest
   * element, and it says how many it dropped, with `dropped(count)`, before the next it delivers.
   */
  def dropOldest[A](capacity: Int, dropped: Int => A): Flow[A, A, NotUsed] =
    Flow.fromGraph(DropOldest(capacity, dropped))

  private final class DropOldest[A](capacity: Int, dropped: Int => A)
      extends GraphStage[FlowShape[A, A]]:
    val in: Inlet[A]           = Inlet("DropOldest.in")
    val out: Outlet[A]         = Outlet("DropOldest.out")
    val shape: FlowShape[A, A] = FlowShape(in, out)

    def createLogic(attributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler:
        private val buffer = scala.collection.mutable.Queue.empty[A]
        private var lost   = 0

        override def preStart(): Unit = pull(in)

        def onPush(): Unit =
          if buffer.size >= capacity then
            buffer.dequeue(): Unit
            lost += 1
          buffer.enqueue(grab(in))
          if isAvailable(out) then deliver()
          pull(in)

        def onPull(): Unit = deliver()

        override def onUpstreamFinish(): Unit =
          if buffer.isEmpty && lost == 0 then completeStage()

        private def deliver(): Unit =
          if lost > 0 then
            push(out, dropped(lost))
            lost = 0
          else if buffer.nonEmpty then
            push(out, buffer.dequeue())
            if buffer.isEmpty && isClosed(in) then completeStage()

        setHandlers(in, out, this)
