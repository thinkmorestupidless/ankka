package com.thinkmorestupidless.ankka.agent

/**
 * What every agent loop needs from a stored conversation: its turns replayed in the shape a
 * provider takes. Running a tool is `ToolRunner`'s.
 *
 * Shared by the request agent's loop and the autonomous agent's so the two cannot disagree about
 * what a summary or a tool call looks like to the model.
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
