package com.thinkmorestupidless.ankka.agent

/**
 * What every agent loop needs from a stored conversation: its turns replayed in the shape a
 * provider takes, and one tool call run.
 *
 * Shared by the request agent's loop and the autonomous agent's so the two cannot disagree about
 * what a summary, a tool call or a failed tool looks like to the model.
 */
private[agent] object PromptReplay:

  /** Turns stored history back into provider-shaped turns. */
  def replay(history: Vector[SessionMessage]): Vector[ChatMessage] =
    history.foldLeft(Vector.empty[ChatMessage]) { (turns, message) =>
      message match
        case m: SessionMessage.UserMessage =>
          turns :+ ChatMessage.User.text(m.text)

        case m: SessionMessage.SummaryMessage =>
          // A summary stands in for the turns it replaced, as context rather than as
          // something the user said.
          turns :+ ChatMessage.User.text(s"[earlier conversation, summarised] ${m.text}")

        case m: SessionMessage.AiMessage =>
          turns :+ ChatMessage.Assistant(
            m.text,
            m.toolCalls.map(call =>
              ToolCall(
                call.id,
                call.name,
                Json.parse(call.arguments).getOrElse(Json.Obj(Map.empty))
              )
            )
          )

        case m: SessionMessage.ToolResultMessage =>
          val result = ToolResult(m.callId, m.toolName, m.content, m.isError)
          // Coalesce consecutive results into the single message the providers expect.
          turns.lastOption match
            case Some(ChatMessage.ToolResults(existing)) =>
              turns.init :+ ChatMessage.ToolResults(existing :+ result)
            case _ =>
              turns :+ ChatMessage.ToolResults(Vector(result))
    }

  /**
   * Runs one tool call.
   *
   * A tool that fails comes back as a tool result flagged as an error rather than as an exception,
   * because that is what lets the model recover — usually by fixing its arguments and trying again.
   * Failing the whole request would deny it the chance.
   */
  def runTool(tools: Map[String, FunctionTool], call: ToolCall): ToolResult =
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
