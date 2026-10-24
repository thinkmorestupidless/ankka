package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, MethodName}
import com.thinkmorestupidless.ankka.mcp.features.Fixtures.{CartEntity, JobEntity}
import com.thinkmorestupidless.ankka.sdk.CallTransport

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.concurrent.duration.*

/**
 * A tool declared by reference to a command or query (US6): the arguments it is offered with, and
 * the bytes the referenced handler is sent — encoded by the handle's own serializer, never passed
 * through as JSON, because a `String` input is raw UTF-8 (research R4).
 */
class ToolInputSuite extends munit.FunSuite:
  import TestTools.*

  /** A transport that remembers what it was sent and answers what the test says. */
  private final class Recording(reply: Array[Byte]) extends CallTransport:
    val sent = ConcurrentLinkedQueue[(ComponentId, EntityId, MethodName, String, Boolean)]()
    def ask(c: ComponentId, e: EntityId, m: MethodName, payload: Array[Byte], md: Metadata) =
      sent.add((c, e, m, String(payload, "UTF-8"), false))
      Future.successful(reply)
    override def askQuery(
        c: ComponentId,
        e: EntityId,
        m: MethodName,
        payload: Array[Byte],
        md: Metadata
    ) =
      sent.add((c, e, m, String(payload, "UTF-8"), true))
      Future.successful(reply)
    def tell(c: ComponentId, e: EntityId, message: Any): Unit = ()
    def askTimeout: FiniteDuration                            = 5.seconds

  private def bound(d: Declaring, tool: String, transport: CallTransport): Unit =
    d.bindings.transports(tool).bind(Reference.Transport(transport, 5.seconds))

  private def propertiesOf(tool: McpTool): Vector[String] =
    tool.spec.inputSchema("properties").collect { case Json.Obj(f) => f.keySet.toVector.sorted }.get

  test("a case class input offers the id beside its fields, and is sent as JSON") {
    val d    = Declaring()
    val tool = d.tool("add_item").describedAs("Adds").command(CartEntity.addItem, id = "cartId")
    assertEquals(propertiesOf(tool), Vector("cartId", "productId", "quantity"))
    assertEquals(
      tool.spec.inputSchema("required").flatMap(_.asArray).map(_.flatMap(_.asString).toSet),
      Some(Set("cartId", "productId", "quantity"))
    )
    val transport = Recording(Array.emptyByteArray)
    bound(d, "add_item", transport)
    assertEquals(
      tool.run(ToolCall(json("""{"cartId":"c1","productId":"p","quantity":2}"""), context)),
      ToolOutcome.Answered(ToolAnswer("done"))
    )
    val (component, entity, method, payload, query) = transport.sent.peek()
    assertEquals(
      (component.toString, entity.toString, method.toString, query),
      ("cart", "c1", "add-item", false)
    )
    assertEquals(json(payload), json("""{"productId":"p","quantity":2}"""))
  }

  test("a scalar input named with input offers two string properties, and is sent as raw UTF-8") {
    val d    = Declaring()
    val tool =
      d.tool("book_job").describedAs("Books").command(JobEntity.book, id = "jobId", input = "slot")
    assertEquals(propertiesOf(tool), Vector("jobId", "slot"))
    assertEquals(
      tool.spec.inputSchema("properties").flatMap(_("slot")).flatMap(_("type")),
      Some(Json.str("string"))
    )
    val transport = Recording("""{"jobId":"j1","slot":"thursday"}""".getBytes("UTF-8"))
    bound(d, "book_job", transport)
    tool.run(ToolCall(json("""{"jobId":"j1","slot":"thursday"}"""), context)) match
      case ToolOutcome.Answered(answer) =>
        assertEquals(answer.structured.flatMap(_("slot")), Some(Json.str("thursday")))
      case other => fail(s"answered $other")
    // The trap: a model's "thursday" must reach a String handler as thursday, not as "thursday".
    assertEquals(transport.sent.peek()._4, "thursday")
  }

  test("a handle that takes nothing offers the id alone") {
    val d    = Declaring()
    val tool =
      d.tool("checkout").describedAs("Checks out").command(CartEntity.checkout, id = "cartId")
    assertEquals(propertiesOf(tool), Vector("cartId"))
  }

  test("a query reference is sent as a query, so a transport may send it again") {
    val d         = Declaring()
    val tool      = d.tool("get_job").describedAs("Reads").query(JobEntity.get, id = "jobId")
    val transport = Recording("""{"jobId":"j1","slot":null}""".getBytes("UTF-8"))
    bound(d, "get_job", transport)
    assert(
      tool.run(ToolCall(json("""{"jobId":"j1"}"""), context)).isInstanceOf[ToolOutcome.Answered]
    )
    assertEquals(transport.sent.peek()._5, true)
    assertEquals(tool.kind, ToolKind.Query("job", "get"))
  }

  test("a missing or blank id is refused before anything is sent") {
    val d         = Declaring()
    val tool      = d.tool("get_job").describedAs("Reads").query(JobEntity.get, id = "jobId")
    val transport = Recording(Array.emptyByteArray)
    bound(d, "get_job", transport)
    assertEquals(
      tool.run(ToolCall(json("{}"), context)),
      ToolOutcome.BadArguments("argument 'jobId' is required")
    )
    assert(
      tool.run(ToolCall(json("""{"jobId":""}"""), context)).isInstanceOf[ToolOutcome.BadArguments]
    )
    assert(transport.sent.isEmpty)
  }

  test("a reference called before the endpoint is mounted is a tool error, not a throw") {
    val d    = Declaring()
    val tool = d.tool("get_job").describedAs("Reads").query(JobEntity.get, id = "jobId")
    tool.run(ToolCall(json("""{"jobId":"j1"}"""), context)) match
      case ToolOutcome.Failed(reason) => assert(reason.contains("get_job"), reason)
      case other                      => fail(s"answered $other")
  }

  test("a scalar input without input does not compile") {
    val errors = compileErrors(
      """
      val d = TestTools.Declaring()
      d.tool("book_job").describedAs("Books").command(JobEntity.book, id = "jobId")
      """
    )
    assert(errors.contains("ToolInput"), errors)
  }
