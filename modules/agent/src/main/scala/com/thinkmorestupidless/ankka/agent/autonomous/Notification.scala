package com.thinkmorestupidless.ankka.agent.autonomous

import com.thinkmorestupidless.ankka.agent.{ApprovalRequest, TokenUsage}
import com.thinkmorestupidless.ankka.core.{Codecs, Serializer}

/**
 * Something an autonomous agent instance did, as it happened.
 *
 * Live only: a subscriber sees what happens from the moment it subscribes, and nothing is kept for
 * one that was not listening. The durable account of a task is its record. Every notification names
 * its instance and when it happened; those about a task name the task.
 *
 * On the wire each is a JSON object discriminated by `type`, which is how an endpoint forwards them
 * over server-sent events and how the Python and TypeScript SDKs read them.
 */
enum Notification:
  // ── The instance ──────────────────────────────────────────────────────────
  case Activated(componentId: String, instanceId: String, at: Long)
  case Deactivated(componentId: String, instanceId: String, at: Long)
  case Suspended(componentId: String, instanceId: String, at: Long)
  case Resumed(componentId: String, instanceId: String, at: Long)
  case Terminated(componentId: String, instanceId: String, at: Long)

  // ── Iterations ────────────────────────────────────────────────────────────
  case IterationStarted(
      componentId: String,
      instanceId: String,
      taskId: String,
      iteration: Int,
      remaining: Int,
      at: Long
  )
  case IterationCompleted(
      componentId: String,
      instanceId: String,
      taskId: String,
      iteration: Int,
      usage: TokenUsage,
      at: Long
  )
  case IterationFailed(
      componentId: String,
      instanceId: String,
      taskId: String,
      iteration: Int,
      error: String,
      at: Long
  )

  // ── Tasks ─────────────────────────────────────────────────────────────────
  case TaskAssigned(componentId: String, instanceId: String, taskId: String, at: Long)
  case TaskStarted(componentId: String, instanceId: String, taskId: String, at: Long)
  case TaskCompleted(
      componentId: String,
      instanceId: String,
      taskId: String,
      iterations: Int,
      usage: TokenUsage,
      at: Long
  )
  case TaskFailed(
      componentId: String,
      instanceId: String,
      taskId: String,
      reason: String,
      iterations: Int,
      at: Long
  )
  case TaskCancelled(
      componentId: String,
      instanceId: String,
      taskId: String,
      reason: String,
      at: Long
  )
  case TaskResultRejected(
      componentId: String,
      instanceId: String,
      taskId: String,
      reason: String,
      iteration: Int,
      at: Long
  )
  case TaskDependencyWait(
      componentId: String,
      instanceId: String,
      taskId: String,
      dependencyId: String,
      at: Long
  )
  case DependencyResolved(
      componentId: String,
      instanceId: String,
      taskId: String,
      dependencyId: String,
      at: Long
  )

  // ── Struggling ────────────────────────────────────────────────────────────
  case TaskApproachingMaxIterations(
      componentId: String,
      instanceId: String,
      taskId: String,
      iteration: Int,
      budget: Int,
      at: Long
  )
  case RepeatedIterationFailure(
      componentId: String,
      instanceId: String,
      taskId: String,
      failures: Int,
      at: Long
  )
  case TaskDependencyStuck(
      componentId: String,
      instanceId: String,
      taskId: String,
      dependencyId: String,
      waitedMillis: Long,
      at: Long
  )

  // ── Approvals ─────────────────────────────────────────────────────────────
  /**
   * The model called a tool that requires approval; nothing more happens on the task until decided.
   */
  case ApprovalRequested(
      componentId: String,
      instanceId: String,
      taskId: String,
      approvalId: String,
      tool: String,
      arguments: String,
      expiresAt: Option[Long],
      at: Long
  )

  /**
   * An approval request was decided — by a person, or by the platform when its time limit passed.
   */
  case ApprovalDecided(
      componentId: String,
      instanceId: String,
      taskId: String,
      approvalId: String,
      approved: Boolean,
      by: String,
      note: Option[String],
      expired: Boolean,
      at: Long
  )

  // ── The stream itself ─────────────────────────────────────────────────────
  /**
   * The subscriber fell behind and `count` notifications were dropped here. Emitted by the
   * subscriber's own buffer, never by the instance, which does not slow down for anyone.
   */
  case Dropped(componentId: String, instanceId: String, count: Int, at: Long)

object Notification:
  val serializer: Serializer[Notification] =
    Codecs.serializer[Notification]("agent-notification")

/** An instance's state, as a caller is told it. */
final case class AgentState(
    componentId: String,
    instanceId: String,
    phase: Phase,
    suspended: Boolean,
    terminated: Boolean,
    currentTask: Option[AgentState.Current],
    queued: Vector[String],
    usage: TokenUsage,
    taskUsage: TokenUsage,
    awaiting: Vector[ApprovalRequest] = Vector.empty
)

object AgentState:
  final case class Current(taskId: String, iteration: Int, budget: Option[Int])

  val serializer: Serializer[AgentState] = Codecs.serializer[AgentState]("agent-state")

  /**
   * `budget` is looked up from the definition by the current task's type; a caller that does not
   * know the type passes `None` and the state says only how far the task has got.
   */
  def from(record: InstanceRecord, budget: Option[Int]): AgentState =
    AgentState(
      record.componentId,
      record.instanceId,
      record.phase,
      record.suspended,
      record.terminated,
      record.current.map(w => Current(w.taskId, w.iteration, budget)),
      record.queue,
      record.usage,
      record.taskUsage,
      record.current.map(_.awaiting).getOrElse(Vector.empty)
    )
