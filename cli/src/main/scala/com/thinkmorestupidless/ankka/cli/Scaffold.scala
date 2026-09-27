package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.Protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * The Python and TypeScript templates, carried by the CLI and rendered by it.
 *
 * Scala's template is expanded by `sbt new`, because every Scala developer has sbt and Giter8 is
 * how Scala templates are expanded. Neither Python nor Node has a standard template tool, and a
 * developer in either language should not need one to start — so these templates are resources of
 * this CLI, at this CLI's version, and `ankka init` is their only front door. The build copies them
 * from `cli/src/main/templates/<language>` (plus `common/`, and the rendered agent skills) with an
 * `index.txt` per language, because a directory inside a jar or a native image cannot be listed.
 *
 * Rendering replaces exact tokens and nothing else — `{{name}}`, `{{module}}`, `{{ankka_version}}`,
 * `{{protocol_version}}`, in paths and contents — so a GitHub expression such as
 * `${{ secrets.ANKKA_TOKEN }}` passes through untouched and no template file needs escaping.
 */
object Scaffold:

  private val Root = "ankka/templates/"

  /** Python's keywords: a module named after one cannot be imported. */
  private val PythonKeywords: Set[String] =
    ("and as assert async await break class continue def del elif else except finally for from " +
      "global if import in is lambda nonlocal not or pass raise return try while with yield")
      .split(' ')
      .toSet

  /** The Python package a service is written in: the name with `-` as `_`, unless one is given. */
  def module(request: Init.Request): String =
    request.pkg.getOrElse(request.name.replace('-', '_'))

  def moduleProblems(module: String): Vector[String] =
    if !module.matches("[a-z_][a-z0-9_]*") then
      Vector(
        s"python package '$module' is invalid: lowercase letters, digits and '_', not starting with a digit"
      )
    else if PythonKeywords(module) then
      Vector(s"python package '$module' is a Python keyword; name one with --package")
    else Vector.empty

  def tokens(request: Init.Request, version: String): Map[String, String] = Map(
    "{{name}}"             -> request.name,
    "{{module}}"           -> module(request),
    "{{ankka_version}}"    -> version,
    "{{protocol_version}}" -> Protocol.version.toString
  )

  /** The template's files, as paths relative to its root, before rendering. */
  def files(language: Language): Vector[String] =
    resource(s"${language.id}/index.txt") match
      case Some(bytes) => new String(bytes, UTF_8).linesIterator.filter(_.nonEmpty).toVector
      case None        => throw IllegalStateException(s"no ${language.id} template in this CLI")

  /** Writes the rendered template into `directory/name`; the directory must be absent or empty. */
  def render(request: Init.Request, version: String): Path =
    val target = request.directory.resolve(request.name)
    val values = tokens(request, version)
    def fill(text: String) = values.foldLeft(text) { case (acc, (token, value)) =>
      acc.replace(token, value)
    }
    for path <- files(request.language) do
      val bytes = resource(s"${request.language.id}/$path")
        .getOrElse(
          throw IllegalStateException(
            s"the ${request.language.id} template lists $path but lacks it"
          )
        )
      val out = target.resolve(fill(path))
      Files.createDirectories(out.getParent)
      Files.writeString(out, fill(new String(bytes, UTF_8)))
    target

  private def resource(path: String): Option[Array[Byte]] =
    Option(getClass.getClassLoader.getResourceAsStream(Root + path)).map { stream =>
      try stream.readAllBytes()
      finally stream.close()
    }
