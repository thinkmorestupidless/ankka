package com.thinkmorestupidless.ankka.agent.autonomous

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.Codecs

class TaskTypeSuite extends munit.FunSuite:

  import TaskTypeSuite.*

  private val answer: TaskType[Answer] = Task
    .named("answer")
    .describedAs("Answer a question")
    .resultConformsTo[Answer]
    .rule("cites-sources")(a =>
      if a.sources.isEmpty then TaskRule.Rejected("sources must not be empty")
      else TaskRule.Accepted
    )
    .rule("confident")(a =>
      if a.confidence < 50 then TaskRule.Rejected("not confident enough") else TaskRule.Accepted
    )

  private val summary: TaskType[String] = Task.named("summary").describedAs("Summarise a document")

  test("the wire name is the declared string, not the Scala name") {
    assertEquals(answer.name, "answer")
    assertEquals(summary.name, "summary")
  }

  test("rules keep the order they were declared in") {
    assertEquals(answer.rules.map(_.name), Vector("cites-sources", "confident"))
  }

  test("a typed result is decoded from the model's arguments and stored canonically") {
    val arguments = Json.parse("""{"sources":["a"],"confidence":90,"answer":"yes"}""").toOption.get
    val decoded   = answer.decodeCompletion(arguments)
    assertEquals(decoded, Right(Answer("yes", 90, List("a"))))
    val stored = answer.encode(decoded.toOption.get)
    assertEquals(stored, """{"answer":"yes","confidence":90,"sources":["a"]}""")
    assertEquals(answer.decode(stored), decoded)
  }

  test("a result the codec refuses explains why, rather than throwing") {
    val arguments = Json.parse("""{"answer":1}""").toOption.get
    assert(answer.decodeCompletion(arguments).isLeft)
  }

  test("a text result is completed with {result} and stored as a JSON string") {
    assert(!summary.hasResultShape)
    val arguments = Json.parse("""{"result":"It says \"no\"."}""").toOption.get
    assertEquals(summary.decodeCompletion(arguments), Right("It says \"no\"."))
    assertEquals(summary.encode("It says \"no\"."), "\"It says \\\"no\\\".\"")
    assertEquals(summary.decode("\"hi\""), Right("hi"))
    assertEquals(
      summary.completionSchema("required"),
      Some(Json.arr(Json.str("result")))
    )
    assert(summary.decodeCompletion(Json.obj()).isLeft)
  }

  test("the completion schema of a typed result is the result's own schema") {
    assertEquals(answer.completionSchema, JsonSchema[Answer].schema)
  }

  test("empty names and descriptions are refused where they are written") {
    intercept[IllegalArgumentException](Task.named(""))
    intercept[IllegalArgumentException](Task.named("x").describedAs(""))
    intercept[IllegalArgumentException](summary.rule("")(_ => TaskRule.Accepted))
    intercept[IllegalArgumentException](
      answer.rule("confident")(_ => TaskRule.Accepted)
    )
  }

  test("a result type cannot be declared after rules that assumed text") {
    val withRule = summary.rule("short")(s =>
      if s.length > 10 then TaskRule.Rejected("long") else TaskRule.Accepted
    )
    intercept[IllegalArgumentException](withRule.resultConformsTo[Answer])
  }

object TaskTypeSuite:
  final case class Answer(answer: String, confidence: Int, sources: List[String])
  object Answer:
    given JsonValueCodec[Answer] = Codecs.make
    given JsonSchema[Answer]     = JsonSchema.derived

/** The scripted model's task helpers produce what an autonomous agent's built-ins read. */
class ScriptedTaskSuite extends munit.FunSuite:

  import com.thinkmorestupidless.ankka.agent.*
  import TaskTypeSuite.Answer
  import scala.concurrent.Await
  import scala.concurrent.duration.DurationInt

  private val answer = Task.named("answer").describedAs("Answer").resultConformsTo[Answer]
  private val text   = Task.named("summary").describedAs("Summarise")

  private def next(model: TestModelProvider, request: ModelRequest = blank): ModelResponse =
    Await.result(model.complete(request), 1.second)

  private val blank = ModelRequest(ModelSettings("test"), None, Vector.empty)

  test("a scripted completion decodes as the task type's result") {
    val model = TestModelProvider().expectCompleteTask(Answer("3", 90, List("count")))
    val call  = next(model).toolCalls.head
    assertEquals(call.name, "complete_task")
    assertEquals(answer.decodeCompletion(call.arguments), Right(Answer("3", 90, List("count"))))
  }

  test("a text completion, a raw one and a failure") {
    val model = TestModelProvider()
      .expectCompleteTaskText("done")
      .expectCompleteTaskJson("""{"answer":1}""")
      .expectFailTask("cannot find it")
    assertEquals(text.decodeCompletion(next(model).toolCalls.head.arguments), Right("done"))
    assert(answer.decodeCompletion(next(model).toolCalls.head.arguments).isLeft)
    val failure = next(model).toolCalls.head
    assertEquals(
      (failure.name, failure.arguments("reason").flatMap(_.asString)),
      ("fail_task", Some("cannot find it"))
    )
  }

  test("a rule on tool results answers only when the latest results match") {
    val model = TestModelProvider().whenToolResult("3 items")(ModelResponse("three"))
    val matching = blank.copy(messages =
      Vector(ChatMessage.ToolResults(Vector(ToolResult("c", "count_items", "there are 3 items"))))
    )
    assertEquals(next(model, matching).text, "three")
    val other = blank.copy(messages =
      Vector(ChatMessage.ToolResults(Vector(ToolResult("c", "count_items", "none"))))
    )
    intercept[ModelCallFailed](next(model, other))
  }
