package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, Done, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.sdk.*

/**
 * Cancels the tasks that depend on one that failed or was cancelled.
 *
 * A task cannot see another's state, so something that can call them has to: this consumer reads
 * the ended task's dependents, which each dependent recorded on it when it was created, and cancels
 * them through the client — which also tells an agent working one. Cancelling a dependent emits its
 * own `Cancelled`, so the cancellation reaches their dependents in turn.
 *
 * Redelivery is harmless: a dependent that has already ended refuses the cancel with `Conflict`,
 * and that is the answer this wants.
 */
final class TaskCascade(context: ConsumerContext) extends Consumer[TaskEvent, Nothing]:

  private val client = context.componentClient

  def onMessage(event: TaskEvent): Effect = event match
    case TaskEvent.Failed(_, _, _, _) => cascade("failed")
    case TaskEvent.Cancelled(_, _)    => cascade("was cancelled")
    case _                            => effects.ignore()

  private def cascade(how: String): Effect =
    val taskId = messageContext.subject
    val record = client.forEventSourcedEntity(EntityId(taskId)).call(TaskEntity.get).invoke()
    record.dependents.foreach { dependent =>
      try client.forTask(dependent).cancel(s"dependency '$taskId' $how")
      catch
        case e: CommandError if e.code == ErrorCode.Conflict || e.code == ErrorCode.NotFound => Done
    }
    effects.done()

object TaskCascade:
  val ComponentId: ComponentId =
    com.thinkmorestupidless.ankka.core.ComponentId("ankka-task-cascade")

  /** One worker: a cascade is rare, and ordering it is simpler than reasoning about overlap. */
  val descriptor: ConsumerDescriptor[TaskCascade, TaskEvent, Nothing] =
    ConsumerDescriptor(
      componentId = ComponentId,
      source = ChangeSource.eventsOf(TaskEntity),
      outputSerializer = None,
      produceTo = None,
      create = context => new TaskCascade(context),
      parallelism = 1
    )
