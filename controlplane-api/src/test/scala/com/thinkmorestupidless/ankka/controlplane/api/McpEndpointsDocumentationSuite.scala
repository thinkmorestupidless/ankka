package com.thinkmorestupidless.ankka.controlplane.api

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/**
 * The scenarios of `features/documentation/mcp-endpoints.feature` that read the published pages,
 * one test each. The coding agent's scenario is a test of the same name in `cli`'s
 * `McpServerSuite`, which asks `ankka mcp` what a local service serves.
 */
class McpEndpointsDocumentationSuite extends munit.FunSuite:

  private val repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("docs")) && Files.exists(p.resolve("mkdocs.yml")))
      .getOrElse(fail("no repository root above the working directory"))

  private def page(path: String): String =
    Files.readString(repoRoot.resolve("docs").resolve(path), UTF_8)

  private def says(path: String, text: String): Unit =
    assert(page(path).contains(text), s"$path does not say: $text")

  private val Guide = "build/mcp-endpoints.md"

  test(
    "the documentation describes declaring an MCP endpoint, its ACL and its client keys in every language"
  ) {
    // declaring an endpoint and its tools in each language
    Seq(
      "/// tab | Scala",
      "/// tab | Java",
      "/// tab | Kotlin",
      "/// tab | Python",
      "/// tab | TypeScript",
      "/// tab | Rust"
    )
      .foreach(says(Guide, _))
    says(Guide, "extends McpEndpoint(\"/mcp\")")
    says(Guide, "## A command, query or route as a tool")
    // stating who may call it, in each of the three kinds
    says(Guide, "## Who may call")
    Seq("ClientKeys.authenticate()", "Oidc.authenticate()", "Acl.allowCallers", "Acl.anyOf")
      .foreach(says(Guide, _))
    // issuing, listing and revoking client keys
    Seq("- **Issuing**", "- **Listing**", "- **Revoking**", "**The key is shown once**")
      .foreach(says(Guide, _))
    // what a client that signs in is told, and when to opt into brokered sign-in
    says(Guide, "/.well-known/oauth-protected-resource<path>")
    says(Guide, "**brokered sign-in**")
    says(Guide, "ANKKA_AUTH_<NAME>_CLIENT_ID")
    says("reference/configuration.md", "### MCP endpoints")
    // every sample shown there is taken from code a test runs: each block follows an include whose
    // region exists in its file
    val includes = "<!-- include: ([^#\\s]+)#([^\\s]+) -->".r.findAllMatchIn(page(Guide)).toVector
    assert(includes.size >= 8, s"the page includes ${includes.size} samples")
    includes.foreach { m =>
      val file = repoRoot.resolve(m.group(1))
      assert(Files.exists(file), s"the page includes ${m.group(1)}, which does not exist")
      assert(
        Files.readString(file, UTF_8).contains(s"docs:start ${m.group(2)}"),
        s"${m.group(1)} has no region ${m.group(2)}"
      )
    }
    val fences =
      "(?m)^```(scala|java|kotlin|python|typescript|rust|go)$".r.findAllMatchIn(page(Guide)).size
    assertEquals(fences, includes.size, "a code sample on the page is not taken from a file")
  }

  test("the documentation says what an MCP endpoint does not do") {
    val limits = "reference/limitations.md"
    says(
      limits,
      "**An MCP endpoint offers tools and nothing else.** It offers no resources or prompts"
    )
    says(limits, "**An MCP endpoint keeps nothing between requests.**")
    says(limits, "a change to a service's tools is seen by an MCP client at its next listing")
    says(limits, "**There is no one MCP endpoint for every service of a project.**")
    says(limits, "**The sign-in broker holds no account and no password.**")
    says(limits, "mints a token only for that endpoint")
  }
