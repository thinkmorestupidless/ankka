package com.thinkmorestupidless.ankka.testkit.blueprint

import com.thinkmorestupidless.ankka.agent.*

/**
 * Reading and answering a scripted model's requests in a blueprint's run, for a suite that scripts
 * a `TestModelProvider` by step: which step a request is for, whether it follows a tool result, the
 * item a for-each step gave it, and the answers and tool calls to reply with.
 */
object Scripted:

  /** The latest user message's text, which for a step names the step. */
  def userText(request: ModelRequest): Option[String] =
    request.messages.reverse.collectFirst { case ChatMessage.User(content) =>
      content.collect { case MessageContent.Text(t) => t }.mkString
    }

  /** Whether the request follows a tool result, as the second call of a tool-using turn does. */
  def afterTool(request: ModelRequest): Boolean =
    request.messages.lastOption.exists {
      case _: ChatMessage.ToolResults => true
      case _                          => false
    }

  /** How many tool results the request carries: an autonomous task's iterations so far. */
  def toolResults(request: ModelRequest): Int =
    request.messages.count {
      case _: ChatMessage.ToolResults => true
      case _                          => false
    }

  /** Whether the request is a step's, by the step's name in the user text. */
  def forStep(request: ModelRequest, step: String): Boolean =
    userText(request).exists(_.contains(s"Step '$step'"))

  /** The item a for-each step's request carries, when it carries one of the form `i<n>`. */
  def itemOf(request: ModelRequest): Option[String] =
    userText(request).flatMap(t => "\"item\":\"(i\\d+)\"".r.findFirstMatchIn(t).map(_.group(1)))

  def answer(json: String): ModelResponse = ModelResponse(json, usage = TokenUsage(10, 5, 0, 0))

  def call(tool: String, arguments: Json = Json.obj()): ModelResponse =
    ModelResponse(
      "",
      Vector(ToolCall(s"c-$tool-${System.nanoTime()}", tool, arguments)),
      StopReason.ToolUse,
      TokenUsage(10, 5, 0, 0)
    )

  /** An autonomous task's completion: `complete_task` with the result as its arguments. */
  def completeTask(result: Json): ModelResponse = call("complete_task", result)

  def refusal(reason: String): ModelResponse =
    ModelResponse("", Vector.empty, StopReason.Refusal, TokenUsage(10, 5, 0, 0), Some(reason))
