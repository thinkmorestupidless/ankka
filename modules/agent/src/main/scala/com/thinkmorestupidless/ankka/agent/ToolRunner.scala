package com.thinkmorestupidless.ankka.agent

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
  def run(tools: Map[String, FunctionTool], call: ToolCall): ToolResult =
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
        tool.invoke(call.arguments) match
          case Right(content) => ToolResult(call.id, call.name, content)
          case Left(problem)  => ToolResult(call.id, call.name, problem, isError = true)
