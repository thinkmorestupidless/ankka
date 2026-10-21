package com.thinkmorestupidless.ankka.mcp

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker
import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.agent.autonomous.JsonSchema
import com.thinkmorestupidless.ankka.core.Done
import com.thinkmorestupidless.ankka.http.Acl

/** A tool with a handler: its schema, its decoding and its answer (contracts/scala-api.md). */
class ToolBuilderSuite extends munit.FunSuite:
  import TestTools.*

  final case class Order(cartId: String, quantity: Int, note: Option[String])
  given JsonValueCodec[Order] = JsonCodecMaker.make
  given JsonSchema[Order]     = JsonSchema.derived[Order]

  private def run(tool: McpTool, arguments: String): ToolOutcome =
    tool.run(ToolCall(json(arguments), context))

  test("param up to three builds a closed object schema with the parameters required") {
    val tool = declaring { d =>
      d.tool("add_item")
        .describedAs("Adds an item")
        .param[String]("cartId", "The cart's id")
        .param[Int]("quantity", "How many")
        .param[Option[String]]("note", "Anything to say")
        .handle((_: String, _: Int, _: Option[String]) => "ok")
    }.head
    val schema = tool.spec.inputSchema
    assertEquals(schema("type"), Some(Json.str("object")))
    assertEquals(schema("additionalProperties"), Some(Json.bool(false)))
    assertEquals(schema("required"), Some(Json.arr(Json.str("cartId"), Json.str("quantity"))))
    assertEquals(
      schema("properties").flatMap(_("quantity")),
      Some(json("""{"type":"integer","description":"How many"}"""))
    )
    assertEquals(
      schema("properties").flatMap(_("cartId")).flatMap(_("description")),
      Some(Json.str("The cart's id"))
    )
    assertEquals(tool.description, "Adds an item")
  }

  test("a tool with no parameters takes nothing, and an absent arguments object is empty") {
    val tool = declaring(_.tool("ping").describedAs("Pings").handle(() => "pong")).head
    assertEquals(tool.spec.inputSchema("properties"), Some(Json.obj()))
    assertEquals(tool.run(ToolCall(Json.Null, context)), ToolOutcome.Answered(ToolAnswer("pong")))
  }

  test("arguments[A] derives a case class's schema and reads it with its codec") {
    val tool = declaring(
      _.tool("order")
        .describedAs("Orders")
        .arguments[Order]
        .handle((o: Order) => s"${o.cartId} ${o.quantity}")
    ).head
    val properties = tool.spec.inputSchema("properties").collect { case Json.Obj(f) => f.keySet }
    assertEquals(properties, Some(Set("cartId", "quantity", "note")))
    assertEquals(
      run(tool, """{"cartId":"c1","quantity":2}"""),
      ToolOutcome.Answered(ToolAnswer("c1 2"))
    )
    assert(run(tool, """{"cartId":"c1","quantity":"two"}""").isInstanceOf[ToolOutcome.BadArguments])
  }

  test("withAcl is kept on the tool, and a tool without one has none") {
    val tools = declaring { d =>
      d.tool("open").describedAs("Open").handle(() => "o")
      d.tool("closed").describedAs("Closed").withAcl(Acl.DenyAll).handle(() => "c")
    }
    assertEquals(tools.map(_.acl), Vector(None, Some(Acl.DenyAll)))
  }

  test("handle decodes each parameter and names the one it could not") {
    val tool = declaring(
      _.tool("add_item")
        .describedAs("Adds")
        .param[String]("cartId", "The cart")
        .param[Int]("quantity", "How many")
        .handle((c: String, q: Int) => s"$c $q")
    ).head
    assertEquals(
      run(tool, """{"cartId":"c1","quantity":3}"""),
      ToolOutcome.Answered(ToolAnswer("c1 3"))
    )
    run(tool, """{"cartId":"c1","quantity":"many"}""") match
      case ToolOutcome.BadArguments(reason) =>
        assert(reason.startsWith("parameter 'quantity': "), reason)
      case other => fail(s"decoded: $other")
    run(tool, """{"quantity":1}""") match
      case ToolOutcome.BadArguments(reason) =>
        assert(reason.startsWith("parameter 'cartId': "), reason)
      case other => fail(s"decoded: $other")
    assert(run(tool, "[1,2]").isInstanceOf[ToolOutcome.BadArguments])
  }

  test("an answer is rendered by its ToolOutput: a String and an Int as text alone") {
    val tools = declaring { d =>
      d.tool("s").describedAs("s").handle(() => """{"looks":"like json"}""")
      d.tool("i").describedAs("i").handle(() => 42)
      d.tool("done").describedAs("d").handle(() => Done: Done)
    }
    assertEquals(
      tools(0).run(ToolCall(Json.Null, context)),
      ToolOutcome.Answered(ToolAnswer("""{"looks":"like json"}"""))
    )
    assertEquals(tools(1).run(ToolCall(Json.Null, context)), ToolOutcome.Answered(ToolAnswer("42")))
    assertEquals(
      tools(2).run(ToolCall(Json.Null, context)),
      ToolOutcome.Answered(ToolAnswer("done"))
    )
  }

  test("a case class answer is text and structured content alike") {
    val tool = declaring(_.tool("o").describedAs("o").handle(() => Order("c1", 1, None))).head
    tool.run(ToolCall(Json.Null, context)) match
      case ToolOutcome.Answered(ToolAnswer(text, Some(structured))) =>
        assertEquals(json(text), structured)
        assertEquals(structured("cartId"), Some(Json.str("c1")))
      case other => fail(s"answered $other")
  }

  test("a handler that throws is a failed call carrying the message, never an exception") {
    val tool = declaring(
      _.tool("x")
        .describedAs("x")
        .handle(() => (throw IllegalArgumentException("no such cart")): String)
    ).head
    assertEquals(tool.run(ToolCall(Json.Null, context)), ToolOutcome.Failed("no such cart"))
  }
