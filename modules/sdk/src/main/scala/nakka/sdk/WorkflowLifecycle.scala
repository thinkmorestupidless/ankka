package nakka.sdk

import nakka.core.{Codecs, MethodName, Serializer}

/**
 * Where a workflow instance has got to, as seen from outside.
 *
 * This exists because a workflow's own state is the *developer's* type, and it says
 * nothing about whether the engine is running, paused, or gave up. Without this, a
 * workflow that failed looks identical to one that is merely slow — which is precisely
 * how a timeout bug in the engine stayed invisible while every domain assertion still
 * read "started".
 */
final case class WorkflowLifecycle(
    status: String,
    pendingStep: Option[String],
    retries: Map[String, Int],
    failure: Option[String]
):
  def isRunning: Boolean   = status == "Running"
  def isPaused: Boolean    = status == "Paused"
  def isCompleted: Boolean = status == "Completed"
  def isFailed: Boolean    = status == "Failed"
  def isTerminal: Boolean  = isCompleted || isFailed

object WorkflowLifecycle:

  /**
   * Reserved handler name the engine answers itself.
   *
   * Prefixed so it cannot collide with a developer's own handler; `Workflow.Companion`
   * rejects registrations using the prefix.
   */
  val MethodPrefix: String = "nakka:"

  private[nakka] val Method: MethodName = MethodName("nakka:lifecycle")

  private[nakka] val serializer: Serializer[WorkflowLifecycle] =
    Codecs.serializer[WorkflowLifecycle]("workflow-lifecycle")
