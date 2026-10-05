package com.thinkmorestupidless.ankka.sidecar.wasm

import com.thinkmorestupidless.ankka.core.Metadata

/**
 * What a call into a module is for, as whoever makes it knows: the component, the handler where the
 * call has one, and the metadata sent with it. The part of a `CallSite` the instance does not know.
 */
final case class Purpose(componentId: String, handler: Option[String], metadata: Metadata)

object Purpose:

  /** A call for no component: discovery, and a suite's direct call. */
  val none: Purpose = Purpose("", None, Metadata.empty)

/**
 * What the host is running in a module, for as long as one call into it lasts: the function called,
 * what it was called for, and the instance running it.
 *
 * An import is handed only Chicory's instance, and the imports are built once for every instance,
 * so an import cannot ask what it was called from. It reads this instead, from the thread: a call
 * into a module runs to its end on the thread that made it, imports included. A thread on which
 * nothing was set has no call site, and an import that needs one refuses — so a call site that
 * failed to arrive shows as every call refused, never as every call allowed.
 */
final case class CallSite(function: String, purpose: Purpose, instance: GuestInstance):
  import CallSite.*

  /**
   * Whether a call to another service may be made from here, and why not. It may from a function
   * the host runs on an instance of its own that nothing else waits for, and only while the host is
   * still waiting for that call's answer.
   */
  def mayRequest: Either[String, Unit] =
    val component = purpose.componentId
    if !Permitted.contains(function) then
      val from = function.stripPrefix(Abi.Prefix) match
        case "handle" => s"from the command $component/${purpose.handler.getOrElse("")}"
        case "fold"   => s"while $component reads an event"
        case "view"   => s"from the view $component"
        case _        => s"from $function"
      Left(s"request may not be called $from: $Where")
    else if instance.broken then
      Left(
        s"request may not be called from a call the runtime has abandoned ($function, $component)"
      )
    else Right(())

object CallSite:

  /**
   * The functions a call to another service may be made from: each runs on a fresh instance, so the
   * wait holds nothing a command needs. A list of what is permitted, so that a function the host
   * gains later is refused until it is added here.
   */
  val Permitted: Set[String] = Set(
    "run_step",
    "consumer",
    "timed_action",
    "plan",
    "invoke_tool",
    "check_guardrail",
    "check_task_result",
    "http"
  ).map(Abi.Prefix + _)

  private val Where =
    "a module calls another service from a workflow step, a consumer, a timed action, an agent, " +
      "a tool, a guardrail, a result check or a route"

  private val held = new ThreadLocal[CallSite]

  /** The call into a module this thread is running, if it is running one. */
  def current: Option[CallSite] = Option(held.get)

  private[wasm] def within[A](site: CallSite)(body: => A): A =
    val before = held.get
    held.set(site)
    try body
    finally if before == null then held.remove() else held.set(before)

/**
 * An import the host refused to serve, thrown from the import itself: the call into the module ends
 * there, as it does for a trap, and its instance is discarded.
 */
final class ImportRefused(val importName: String, message: String) extends RuntimeException(message)
