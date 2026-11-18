package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.openapi.OpenApiCommand
import com.thinkmorestupidless.ankka.core.graph.GraphJson

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * `ankka openapi` on a project a template made (feature 067, T034): the real command against the
 * project's own build, with Docker made unreachable so a build or a `main` that consulted it would
 * fail. The document's routes, as `METHOD path`.
 */
object TemplateDocument:

  def routes(project: Path, env: Map[String, String] = Map.empty): Either[String, Set[String]] =
    val out  = Files.createTempDirectory("template-openapi").resolve("openapi.json")
    val so   = ByteArrayOutputStream()
    val se   = ByteArrayOutputStream()
    val code = OpenApiCommand.run(
      OpenApiCommand.Options(project, out = Some(out.toString)),
      PrintStream(so, true, UTF_8),
      PrintStream(se, true, UTF_8),
      sys.env ++ env + ("DOCKER_HOST" -> "tcp://127.0.0.1:9")
    )
    val said = s"${so.toString(UTF_8)}\n${se.toString(UTF_8)}"
    if code != 0 then Left(s"ankka openapi exited $code:\n$said")
    else
      GraphJson.parse(Files.readAllBytes(out)).left.map(e => s"$e in:\n$said").flatMap { doc =>
        if !doc.field("openapi").contains(GraphJson.Str("3.1.0")) then
          Left(s"not OpenAPI 3.1: $said")
        else
          doc.field("paths") match
            case Some(GraphJson.Obj(paths)) =>
              Right(paths.flatMap {
                case (path, GraphJson.Obj(ops)) => ops.map((m, _) => s"${m.toUpperCase} $path")
                case _                          => Vector.empty
              }.toSet)
            case _ => Left(s"no paths in the document: $said")
      }
