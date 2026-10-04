package com.thinkmorestupidless.ankka.agent

import com.thinkmorestupidless.ankka.runtime.{CallOrigin, Observability, SpanOutcome, Trace}

/**
 * Records each tool call as a span of its own, nested in the agent's, so that what a tool calls — a
 * component, another service — shows in a trace inside the tool call that made it.
 *
 * The span is named for the agent's component and the tool. Only a tool the agent offered is
 * recorded, and those names are bounded: an agent's own tools are declared, and an MCP server's are
 * read once when the service starts. A call's origin is left as it is — the agent's handler — so a
 * call a tool makes is attributed exactly as before.
 */
private[agent] final class ToolSpans(observability: Option[Observability], component: String):

  def around(tool: String)(body: => ToolResult): ToolResult =
    (observability, Trace.currentTrace) match
      case (Some(obs), Some((traceId, parent))) =>
        val span = obs.recorder.begin(
          traceId,
          parent,
          obs.names.intern(component),
          obs.names.intern(tool)
        )
        val origin  = Trace.currentOrigin.getOrElse(CallOrigin(component, tool))
        var outcome = SpanOutcome.Failed
        try
          val result = Trace.within(traceId, span.id, origin)(body)
          outcome = if result.isError then SpanOutcome.Failed else SpanOutcome.Ok
          result
        finally obs.recorder.complete(span, outcome)
      case _ => body

private[agent] object ToolSpans:
  val none: ToolSpans = ToolSpans(None, "")

/**
 * Runs one tool call, for every agent loop.
 *
 * The request agent's loop and the autonomous agent's both run their tools here, so the two cannot
 * disagree about what an unknown tool, a failed tool — or anything added around a tool call — looks
 * like to the model.
 */
private[agent] object ToolRunner:

  /**
   * Runs one tool call.
   *
   * A tool that fails comes back as a tool result flagged as an error rather than as an exception,
   * because that is what lets the model recover — usually by fixing its arguments and trying again.
   * Failing the whole request would deny it the chance.
   */
  def run(
      tools: Map[String, FunctionTool],
      call: ToolCall,
      spans: ToolSpans = ToolSpans.none
  ): ToolResult =
    tools.get(call.name) match
      case None =>
        ToolResult(
          call.id,
          call.name,
          s"no tool named '${call.name}' is available; available tools: " +
            tools.keys.toVector.sorted.mkString(", "),
          isError = true
        )
      case Some(tool) =>
        spans.around(tool.name) {
          tool.invoke(call.arguments) match
            case Right(content) => ToolResult(call.id, call.name, content)
            case Left(problem)  => ToolResult(call.id, call.name, problem, isError = true)
        }
