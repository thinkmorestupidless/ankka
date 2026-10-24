package com.thinkmorestupidless.ankka.mcp

import com.thinkmorestupidless.ankka.core.{ComponentRegistry, MethodName}
import com.thinkmorestupidless.ankka.http.{Acl, HttpEndpoint, Mounting}
import com.thinkmorestupidless.ankka.mcp.features.Fixtures.{Job, JobEntity, JobsEndpoint}
import com.thinkmorestupidless.ankka.sdk.CommandHandle

/** Every refusal of contracts/scala-api.md's "What fails the start", from `problems(mounting)`. */
class McpEndpointProblemsSuite extends munit.FunSuite:

  private val registry =
    ComponentRegistry.from(Seq(JobEntity.descriptor)).fold(p => fail(p.mkString), identity)

  private class Mcp(path: String = "/mcp")(declare: Mcp => Any) extends McpEndpoint(path):
    def acl: Acl               = Acl.AllowAll
    def declared(name: String) = tool(name)
    def verb(): Unit           = get("/x")(() => "x")
    declare(this)

  private def problemsOf(endpoint: McpEndpoint, others: HttpEndpoint*): Vector[String] =
    endpoint.problems(Mounting(registry, endpoint +: others.toVector, None))

  private def wellFormed(m: Mcp) =
    m.declared("get_job").describedAs("Reads one job").query(JobEntity.get, id = "jobId")
    m.declared("book_job")
      .describedAs("Books a job")
      .command(JobEntity.book, id = "jobId", input = "slot")
    m.declared("todays_jobs").describedAs("The day's jobs").route("/jobs", "GET", "/jobs/{day}")
    m.declared("ping").describedAs("Pings").handle(() => "pong")

  test("a well-formed endpoint has no problem") {
    assertEquals(problemsOf(Mcp()(wellFormed), JobsEndpoint()), Vector.empty)
  }

  test("an endpoint with no tool") {
    assertEquals(problemsOf(Mcp()(_ => ())), Vector("MCP endpoint /mcp declares no tool"))
  }

  test("a tool name outside [a-z0-9_-]") {
    assertEquals(
      problemsOf(Mcp()(_.declared("Get Cart").describedAs("Reads").handle(() => "x"))),
      Vector(
        "MCP endpoint /mcp: tool 'Get Cart' is not a tool name ([a-z0-9_-]+, at most 128 characters)"
      )
    )
    assertEquals(
      problemsOf(Mcp()(_.declared("x" * 129).describedAs("Long").handle(() => "x"))).size,
      1
    )
  }

  test("two tools under one name") {
    val twice = Mcp() { m =>
      m.declared("get_cart").describedAs("One").handle(() => "1")
      m.declared("get_cart").describedAs("Two").handle(() => "2")
    }
    assertEquals(
      problemsOf(twice),
      Vector("MCP endpoint /mcp declares the tool 'get_cart' 2 times")
    )
  }

  test("a tool with no description") {
    assertEquals(
      problemsOf(Mcp()(_.declared("get_cart").handle(() => "x"))),
      Vector("MCP endpoint /mcp: tool 'get_cart' has no description")
    )
  }

  test("a command the component does not have") {
    val book   = JobEntity.book
    val vanish = CommandHandle[JobEntity, String, Job](
      book.componentId,
      MethodName("vanish"),
      readOnly = false,
      book.inputSerializer,
      book.outputSerializer,
      (_, _) => ()
    )
    assertEquals(
      problemsOf(
        Mcp()(
          _.declared("book_job").describedAs("Books").command(vanish, id = "jobId", input = "slot")
        )
      ),
      Vector(
        "MCP endpoint /mcp: tool 'book_job' refers to the command 'vanish' of 'job', which has no such handler"
      )
    )
  }

  test("a component the service does not register") {
    val endpoint = Mcp()(
      _.declared("book_job")
        .describedAs("Books")
        .command(JobEntity.book, id = "jobId", input = "slot")
    )
    assertEquals(
      endpoint.problems(Mounting(ComponentRegistry.empty, Vector(endpoint), None)),
      Vector(
        "MCP endpoint /mcp: tool 'book_job' refers to the component 'job', which this service does not register"
      )
    )
  }

  test("a route no mounted endpoint has") {
    assertEquals(
      problemsOf(
        Mcp()(_.declared("todays_jobs").describedAs("Jobs").route("/jobs", "GET", "/nowhere")),
        JobsEndpoint()
      ),
      Vector(
        "MCP endpoint /mcp: tool 'todays_jobs' refers to the route 'GET /nowhere' of '/jobs', which has no such route"
      )
    )
  }

  test("the MCP endpoint's path and another endpoint's route") {
    final class Root extends HttpEndpoint("/"):
      val acl: Acl = Acl.AllowAll
      post("/mcp")(() => "root")
    assertEquals(
      problemsOf(Mcp()(wellFormed), Root(), JobsEndpoint()),
      Vector("MCP endpoint /mcp and the route 'POST /mcp' of '/' claim one path")
    )
  }

  test("an MCP endpoint that declares routes") {
    val routed = Mcp() { m =>
      wellFormed(m)
      m.verb()
    }
    assertEquals(
      problemsOf(routed, JobsEndpoint()),
      Vector("MCP endpoint /mcp declares routes; an MCP endpoint declares tools")
    )
  }

  test("a reserved path") {
    Seq("/_ankka/x", "/.well-known/x").foreach { path =>
      assertEquals(
        problemsOf(Mcp(path)(m => m.declared("ping").describedAs("Pings").handle(() => "p"))),
        Vector(s"MCP endpoint $path path is reserved")
      )
    }
  }

  test("two MCP endpoints at one path are refused naming both; at two paths they are not") {
    val first  = Mcp("/mcp")(m => m.declared("ping").describedAs("Pings").handle(() => "p"))
    val second = Mcp("/mcp")(m => m.declared("ping").describedAs("Pings").handle(() => "p"))
    val both   = Mounting(registry, Vector(first, second), None)
    val found  = first.problems(both) ++ second.problems(both)
    assertEquals(found.size, 1, found)
    assert(found.head.contains("are both at /mcp"), found)
    val office = Mcp("/office")(m => m.declared("ping").describedAs("Pings").handle(() => "p"))
    val apart  = Mounting(registry, Vector(first, office), None)
    assertEquals(first.problems(apart) ++ office.problems(apart), Vector.empty)
  }

  test("an MCP endpoint is served as one record per tool") {
    val endpoint = Mcp()(wellFormed)
    assertEquals(
      endpoint.served.map(r => s"${r.method} ${r.path}"),
      Vector("MCP /mcp get_job", "MCP /mcp book_job", "MCP /mcp todays_jobs", "MCP /mcp ping")
    )
    assert(endpoint.served.forall(_.endpoint == "endpoint:/mcp"))
  }
