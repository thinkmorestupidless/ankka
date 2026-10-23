package com.thinkmorestupidless.ankka.sidecar

import ankka.protocol.v1.discovery.{McpAcl, McpEndpoint as McpEndpointSpec, McpTool as McpToolSpec}
import com.thinkmorestupidless.ankka.agent.Json
import com.thinkmorestupidless.ankka.core.{CommandError, ComponentId, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.http.{Caller, Principal, QueryParams, SimpleRequestContext}
import com.thinkmorestupidless.ankka.mcp.{ToolAnswer, ToolCall, ToolOutcome}
import com.thinkmorestupidless.ankka.runtime.remote.*
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}

/**
 * A tool of a process's MCP endpoint, run against a scripted conversation: what the process is
 * asked (the checked arguments, the principal and the caller, never the credential), and what each
 * of its answers becomes (feature 054, FR-014, R19.4).
 */
class RemoteMcpEndpointSuite extends munit.FunSuite:

  private val Schema =
    """{"type":"object","properties":{"cartId":{"type":"string"}},"required":["cartId"]}"""

  private val Key   = "ak_live_3f9c2b7e41d0a8c56e2f9b13d7a04c8e"
  private val Token = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJhZGEifQ.c2lnbmF0dXJlLW9mLXRoZS10b2tlbg"

  /** A conversation that answers MCP tool calls with `answer`, and records what it was asked. */
  private final class Scripted(
      answer: RemoteMcpCall => Future[
        Either[ProcessFailure, Either[String, RemoteMcpAnswer]]
      ]
  ) extends Conversation:
    val asked = AtomicReference[Option[RemoteMcpCall]](None)
    override def invokeMcpTool(
        call: RemoteMcpCall
    ): Future[Either[ProcessFailure, Either[String, RemoteMcpAnswer]]] =
      asked.set(Some(call))
      answer(call)
    def open(init: Init): InstanceSession                                                  = ???
    def handleView(request: ViewRequest): Future[ViewOutcome]                              = ???
    def handleConsumer(request: ConsumerRequest): Future[ConsumerOutcome]                  = ???
    def invokeTimedAction(request: TimedActionRequest): Future[Either[CommandError, Unit]] = ???
    def plan(request: PlanRequest): Future[Either[ProcessFailure, RemotePlan]]             = ???
    def invokeTool(
        componentId: ComponentId,
        sessionId: String,
        tool: String,
        argumentsJson: String,
        metadata: Metadata
    ): Future[Either[String, String]] = ???
    def checkGuardrail(
        componentId: ComponentId,
        sessionId: String,
        guardrail: String,
        stage: GuardrailStage,
        text: String,
        metadata: Metadata
    ): Future[Either[String, Unit]] = ???
    def checkTaskResult(
        componentId: ComponentId,
        taskId: String,
        taskType: String,
        resultJson: String,
        metadata: Metadata
    ): Future[TaskResultVerdict] = ???
    def handleHttp(request: HttpForward): Future[Either[ProcessFailure, HttpResult]] = ???
    def handleHttpStream(request: HttpForward): Source[String, NotUsed]              = ???
    def reachable(): Boolean                                                         = true

  private val settings =
    Settings("127.0.0.1:0", 0, "127.0.0.1", 5.seconds, 1.second, 2.seconds, 300.millis)

  private val spec = McpEndpointSpec(
    id = "cart-mcp",
    path = "/mcp",
    acl = Some(McpAcl(clientKeys = true, tokens = true)),
    tools = Vector(McpToolSpec("get_cart", "Reads one cart", Schema))
  )

  private def ok(text: String, structured: Option[String] = None) =
    (_: RemoteMcpCall) => Future.successful(Right(Right(RemoteMcpAnswer(text, structured))))

  private def run(
      conversation: Scripted,
      arguments: String = """{"cartId":"c1"}""",
      principal: Option[Principal] = None,
      caller: Caller = Caller.Local,
      headers: Vector[(String, String)] = Vector.empty
  ): ToolOutcome =
    val endpoint = RemoteMcpEndpoint.from(spec, conversation, settings)
    val tool     =
      endpoint.tools.find(_.spec.name == "get_cart").getOrElse(fail("get_cart not declared"))
    val context =
      SimpleRequestContext("POST", "/mcp", QueryParams.empty, headers, None, principal, caller)
    tool.run(ToolCall(Json.parse(arguments).toOption.get, context))

  test("the process is asked to run the tool with its checked arguments and the caller") {
    val c = Scripted(ok("{}"))
    run(c, caller = Caller.Service("shop", "office"))
    val asked = c.asked.get.getOrElse(fail("the process was not asked"))
    assertEquals(asked.endpointId, "cart-mcp")
    assertEquals(asked.tool, "get_cart")
    assertEquals(
      Json.parse(asked.argumentsJson).toOption.flatMap(_("cartId")).flatMap(_.asString),
      Some("c1")
    )
    assertEquals(asked.caller, RemoteCaller.Service("shop", "office"))
    assert(
      com.thinkmorestupidless.ankka.runtime.Trace.traceIdOf(asked.metadata).isDefined,
      asked.metadata.toString
    )
  }

  test("arguments the schema refuses never reach the process") {
    val c       = Scripted(ok("{}"))
    val outcome = run(c, arguments = """{"cartId":7}""")
    assert(outcome.isInstanceOf[ToolOutcome.BadArguments], outcome.toString)
    assertEquals(c.asked.get, None)
  }

  test("a client key's principal reaches the process, and the key does not") {
    val c         = Scripted(ok("{}"))
    val principal = Principal("acme", claims = Map("kind" -> "client-key", "person" -> "alice"))
    run(c, principal = Some(principal), headers = Vector("Authorization" -> s"Bearer $Key"))
    val asked = c.asked.get.getOrElse(fail("the process was not asked"))
    assertEquals(asked.principal.map(_.subject), Some("acme"))
    assertEquals(asked.principal.flatMap(_.claims.get("kind")), Some("client-key"))
    assertEquals(asked.principal.flatMap(_.claims.get("person")), Some("alice"))
    assert(!asked.toString.contains(Key), s"the key is in the message: $asked")
    assert(
      !asked.toString.contains(Key.takeRight(12)),
      s"part of the key is in the message: $asked"
    )
  }

  test("a token's principal reaches the process as its claims, and the token does not") {
    val c         = Scripted(ok("{}"))
    val principal =
      Principal("ada", roles = Set("buyer"), claims = Map("tier" -> "gold"), issuer = Some("test"))
    run(c, principal = Some(principal), headers = Vector("Authorization" -> s"Bearer $Token"))
    val asked = c.asked.get.getOrElse(fail("the process was not asked"))
    assertEquals(asked.principal.map(_.subject), Some("ada"))
    assertEquals(asked.principal.map(_.roles), Some(Set("buyer")))
    assertEquals(asked.principal.flatMap(_.issuer), Some("test"))
    val signature = Token.split('.').last
    assert(!asked.toString.contains(Token), s"the token is in the message: $asked")
    assert(!asked.toString.contains(signature), s"the signature is in the message: $asked")
  }

  test("an answer with structured content becomes text and structured content") {
    val outcome = run(Scripted(ok("""{"cartId":"c1"}""", Some("""{"cartId":"c1"}"""))))
    outcome match
      case ToolOutcome.Answered(ToolAnswer(text, Some(structured))) =>
        assertEquals(text, """{"cartId":"c1"}""")
        assertEquals(structured("cartId").flatMap(_.asString), Some("c1"))
      case other => fail(s"expected an answer with structured content, got $other")
  }

  test("an answer without structured content is text alone") {
    assertEquals(run(Scripted(ok("seven"))), ToolOutcome.Answered(ToolAnswer("seven", None)))
  }

  test("the process's error is a tool error with its message") {
    val c = Scripted(_ => Future.successful(Right(Left("quantity must be greater than zero"))))
    assertEquals(run(c), ToolOutcome.Failed("quantity must be greater than zero"))
  }

  test("a process that could not run the tool is a tool error naming the tool") {
    val c = Scripted(_ =>
      Future.successful(
        Left(ProcessFailure(0L, CommandError("the handler raised", ErrorCode.Internal)))
      )
    )
    run(c) match
      case ToolOutcome.Failed(message) =>
        assert(message.contains("get_cart"), message)
        assert(message.contains("the handler raised"), message)
      case other => fail(s"expected a tool error, got $other")
  }

  test("a process that does not answer within the request timeout is a tool error") {
    val c = Scripted(_ => Promise[Either[ProcessFailure, Either[String, RemoteMcpAnswer]]]().future)
    run(c) match
      case ToolOutcome.Failed(message) =>
        assert(message.contains("get_cart"), message)
        assert(message.contains("did not answer"), message)
      case other => fail(s"expected a tool error, got $other")
  }
