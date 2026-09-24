package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.auth.AuthConfig
import com.thinkmorestupidless.ankka.core.{ComponentId, EntityId, Metadata, MethodName}
import com.thinkmorestupidless.ankka.http.{Acl, EndpointClients, HttpEndpoint}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.sdk.{CallTransport, ComponentClient}

import java.nio.file.{Files, Path, Paths}
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

/**
 * The control plane API reference lists every route the control plane serves.
 *
 * An endpoint records its routes as it is constructed, so the route table is enumerable without
 * starting anything: the endpoints are built with clients that are never called. The table is
 * written between generated-block comments in `docs/reference/control-plane-api.md`; this suite
 * fails when it differs, and a second case fails when a route has no hand-written section on the
 * page — so a new route is a failing build until someone has said what it does. Rewrite the table
 * with `-Dankka.docs.update=true`.
 */
class ControlPlaneRoutesReferenceSuite extends munit.FunSuite:

  private val PagePath = "docs/reference/control-plane-api.md"
  private val Name     = "control-plane-routes"

  private val unused = new CallTransport:
    def ask(c: ComponentId, e: EntityId, m: MethodName, p: Array[Byte], md: Metadata) =
      Future.failed(IllegalStateException("not called while enumerating routes"))
    def tell(c: ComponentId, e: EntityId, message: Any): Unit = ()
    def askTimeout                                            = 1.second

  private val auth =
    AuthConfig(
      "https://auth.example.test/realms/ankka",
      "",
      "ankka",
      "ankka-cli",
      "ankka",
      30.seconds
    )

  private def endpoints: Vector[HttpEndpoint] =
    val clients =
      new EndpointClients(ComponentClient(unused), new ViewClient(null, 1.second)(using null))
    ControlPlane.endpoints(Acl.DenyAll, auth = Some(auth)).map(_(clients)).toVector

  /** Method and full path of every route, in declaration order. */
  private def routes: Vector[(String, String, Boolean)] =
    endpoints.flatMap { endpoint =>
      endpoint.routes.map(r => (r.method, endpoint.prefix + r.template.render, false)) ++
        endpoint.streamRoutes.map(r => (r.method, endpoint.prefix + r.template.render, true))
    }

  /** `/organizations/` is how the root of an endpoint renders; the page writes `/organizations`. */
  private def shown(path: String): String =
    if path.length > 1 && path.endsWith("/") then path.dropRight(1) else path

  private def render: String =
    val rows = routes.map { (method, path, streaming) =>
      s"| `$method` | `${shown(path)}` |${if streaming then " server-sent events" else ""} |"
    }
    ("| Method | Path | Streaming |" +: "|---|---|---|" +: rows).mkString("", "\n", "\n")

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isRegularFile(p.resolve("mkdocs.yml")))
      .getOrElse(fail("could not find the repository root"))

  private def replaceBlock(page: String, content: String): String =
    val open  = s"<!-- generated:start $Name -->\n"
    val close = s"<!-- generated:end $Name -->"
    val from  = page.indexOf(open)
    val to    = page.indexOf(close)
    if from < 0 || to < from then fail(s"no generated block named '$Name' in $PagePath")
    page.substring(0, from + open.length) + content + page.substring(to)

  test("the API reference's route table is the control plane's route table") {
    val path     = repoRoot.resolve(PagePath)
    val current  = Files.readString(path)
    val expected = replaceBlock(current, render)
    if expected != current then
      if sys.props.get("ankka.docs.update").contains("true") then
        Files.writeString(path, expected): Unit
      else
        fail(
          s"$PagePath is stale: a route was added, removed or changed. Run " +
            "sbt -Dankka.docs.update=true 'controlPlane/testOnly *ControlPlaneRoutesReferenceSuite'."
        )
  }

  test("every route has a hand-written section on the page") {
    val page = Files.readString(repoRoot.resolve(PagePath))
    val prose =
      page.substring(page.indexOf(s"<!-- generated:end $Name -->"))
    val undocumented = routes.collect {
      case (method, path, _) if !prose.contains(s"`$method ${shown(path)}`") =>
        s"$method ${shown(path)}"
    }
    assert(
      undocumented.isEmpty,
      s"routes with no section on $PagePath (a heading or line containing `METHOD /path`): " +
        undocumented.mkString(", ")
    )
  }
