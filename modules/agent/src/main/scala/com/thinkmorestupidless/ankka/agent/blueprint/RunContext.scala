package com.thinkmorestupidless.ankka.agent.blueprint

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.FunctionTool
import com.thinkmorestupidless.ankka.core.Codecs

/** Which run a tool is serving: the run, the step, and the blueprint version the run is of. */
final case class RunRef(runId: String, step: String, blueprint: String, version: Int)

object RunRef:
  given JsonValueCodec[RunRef] = Codecs.make

/**
 * Tells a tool which run it serves, so what it writes can say which run wrote it. Set around one
 * invocation of the tool on the thread running it, and cleared after; a tool called outside any run
 * reads `None`.
 */
object RunContext:

  private val local = new ThreadLocal[RunRef]

  /** The run the calling tool serves, when it is in one. */
  def current: Option[RunRef] = Option(local.get())

  def within[A](ref: RunRef)(body: => A): A =
    val before = local.get()
    local.set(ref)
    try body
    finally if before == null then local.remove() else local.set(before)

  /**
   * The same tool, told its run when it is called. The tool runs after the handler that chose it
   * has returned, on the agent's thread, so the run is bound to the tool rather than to a thread.
   */
  private[ankka] def wrap(tool: FunctionTool, ref: RunRef): FunctionTool =
    new FunctionTool(
      tool.spec,
      arguments => within(ref)(tool.invoke(arguments)),
      tool.approval,
      tool.origin
    )
