package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.agent.*

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

/** The sidecar's scripted model: ordered turns, and standing rules on what a request contains. */
class ModelsSuite extends munit.FunSuite:

  private def next(model: TestModelProvider, request: ModelRequest): ModelResponse =
    Await.result(model.complete(request), 1.second)

  private def asking(text: String, toolResult: Option[String] = None) =
    ModelRequest(
      ModelSettings("scripted"),
      None,
      Vector(ChatMessage.User.text(text)) ++
        toolResult.map(r => ChatMessage.ToolResults(Vector(ToolResult("c", "t", r)))).toVector
    )

  private def scripted(json: String) = Models.scriptedFrom(json).fold(fail(_), identity)

  test("ordered turns are used in order, then rules") {
    val model = scripted("""[{"text":"first"},{"when":"hello","text":"hi"}]""")
    assertEquals(next(model, asking("hello")).text, "first")
    assertEquals(next(model, asking("hello")).text, "hi")
  }

  test("a rule may answer with a tool call, not only text") {
    val model = scripted("""[{"when":"cart q1","tool":"cart_total","arguments":{"cartId":"q1"}}]""")
    val call  = next(model, asking("How many in cart q1?")).toolCalls.head
    assertEquals(
      (call.name, call.arguments("cartId").flatMap(_.asString)),
      ("cart_total", Some("q1"))
    )
  }

  test("a rule on the latest tool result, checked in the order the rules are declared") {
    val model = scripted(
      """[{"when_tool_result":"holds","tool":"complete_task","arguments":{"answer":"3"}},
        | {"when":"q1","tool":"cart_total","arguments":{"cartId":"q1"}}]""".stripMargin
    )
    assertEquals(next(model, asking("q1")).toolCalls.head.name, "cart_total")
    assertEquals(
      next(model, asking("q1", Some("cart q1 holds 3 items"))).toolCalls.head.name,
      "complete_task"
    )
  }

  test("a turn that is none of text, tool or refusal is refused, naming the turn") {
    assertEquals(
      Models.scriptedFrom("""[{"text":"ok"},{"when":"x"}]"""),
      Left("turn 1: a turn is a text, a tool call, or a refusal")
    )
  }
