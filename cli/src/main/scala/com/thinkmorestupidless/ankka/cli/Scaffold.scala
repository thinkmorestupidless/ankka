package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.controlplane.api.Protocol

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.{Files, Path}

/**
 * The Python, TypeScript and Rust templates, carried by the CLI and rendered by it.
 *
 * Scala's template is expanded by `sbt new`, because every Scala developer has sbt and Giter8 is
 * how Scala templates are expanded. Neither Python nor Node has a standard template tool, and a
 * developer in any of them should not need one to start — so these templates are resources of this
 * CLI, at this CLI's version, and `ankka init` is their only front door. The build copies them from
 * `cli/src/main/templates/<language>` (plus `common/`, and the rendered agent skills) with an
 * `index.txt` per language, because a directory inside a jar or a native image cannot be listed.
 *
 * Rendering replaces exact tokens and nothing else — `{{name}}`, `{{module}}`, `{{module_snake}}`,
 * `{{ankka_version}}`, `{{protocol_version}}`, in paths and contents — so a GitHub expression such
 * as `${{ secrets.ANKKA_TOKEN }}` passes through untouched and no template file needs escaping.
 */
object Scaffold:

  private val Root = "ankka/templates/"

  /** Python's keywords: a module named after one cannot be imported. */
  private val PythonKeywords: Set[String] =
    ("and as assert async await break class continue def del elif else except finally for from " +
      "global if import in is lambda nonlocal not or pass raise return try while with yield")
      .split(' ')
      .toSet

  /**
   * The module a service is written in: for Python the package, the name with `-` as `_` unless one
   * is given; for Rust the crate, which keeps the name's `-` (cargo turns it into `_` itself, in
   * the module's file name, which is `{{module_snake}}`).
   */
  def module(request: Init.Request): String =
    if request.language == Language.Rust then request.name
    else request.pkg.getOrElse(request.name.replace('-', '_'))

  /** A crate's name: lowercase letters, digits and `-`, starting with a letter. */
  def crateProblems(name: String): Vector[String] =
    if name.matches("[a-z][a-z0-9-]*") then Vector.empty
    else
      Vector(
        s"crate name '$name' is invalid: lowercase letters, digits and '-', starting with a letter"
      )

  // Java's reserved words and literals, and Kotlin's hard keywords: none may be a package segment.
  private val JavaKeywords: Set[String] =
    ("abstract assert boolean break byte case catch char class const continue default do double " +
      "else enum extends final finally float for goto if implements import instanceof int " +
      "interface long native new package private protected public return short static strictfp " +
      "super switch synchronized this throw throws transient try void volatile while true false " +
      "null _").split(' ').toSet

  private val KotlinKeywords: Set[String] =
    ("as break class continue do else false for fun if in interface is null object package " +
      "return super this throw true try typealias typeof val var when while").split(' ').toSet

  /** The package a Java or Kotlin project is written in: `--package`, or `com.example.<name>`. */
  def jvmPackage(request: Init.Request): String =
    request.pkg.getOrElse("com.example." + request.name.replaceAll("[^A-Za-z0-9]", "").toLowerCase)

  def packageProblems(pkg: String, language: Language): Vector[String] =
    val keywords =
      if language == Language.Kotlin then JavaKeywords ++ KotlinKeywords else JavaKeywords
    val segments  = pkg.split("\\.", -1).toVector
    val malformed = segments.filterNot(_.matches("[A-Za-z_$][A-Za-z0-9_$]*"))
    val reserved  = segments.filter(keywords)
    if malformed.nonEmpty then
      Vector(
        s"${language.id} package '$pkg' is invalid: dotted names, each a letter or '_' then " +
          "letters, digits or '_'"
      )
    else if reserved.nonEmpty then
      Vector(
        s"${language.id} package '$pkg' uses the keyword '${reserved.head}'; name one with --package"
      )
    else Vector.empty

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
    "{{module_snake}}"     -> module(request).replace('-', '_'),
    "{{ankka_version}}"    -> version,
    "{{protocol_version}}" -> Protocol.version.toString,
    "{{package}}"          -> jvmPackage(request),
    "{{package_path}}"     -> jvmPackage(request).replace('.', '/')
  )

  /**
   * How a template file is written: filled with the tokens, copied as it is, or made executable.
   */
  enum Mode:
    case Text, Binary, Exec

  /** One file of a template, as its `index.txt` lists it: `path`, or `path  binary|exec`. */
  final case class Entry(path: String, mode: Mode)

  def entries(language: Language): Vector[Entry] =
    resource(s"${language.id}/index.txt") match
      case Some(bytes) =>
        new String(bytes, UTF_8).linesIterator.filter(_.nonEmpty).toVector.map { line =>
          line.split("\\s+") match
            case Array(path)           => Entry(path, Mode.Text)
            case Array(path, "binary") => Entry(path, Mode.Binary)
            case Array(path, "exec")   => Entry(path, Mode.Exec)
            case _ =>
              throw IllegalStateException(s"the ${language.id} template's index has '$line'")
        }
      case None => throw IllegalStateException(s"no ${language.id} template in this CLI")

  /** The template's files, as paths relative to its root, before rendering. */
  def files(language: Language): Vector[String] = entries(language).map(_.path)

  /** Writes the rendered template into `directory/name`; the directory must be absent or empty. */
  def render(request: Init.Request, version: String): Path =
    val target             = request.directory.resolve(request.name)
    val values             = tokens(request, version)
    def fill(text: String) = values.foldLeft(text) { case (acc, (token, value)) =>
      acc.replace(token, value)
    }
    for Entry(path, mode) <- entries(request.language) do
      val bytes = resource(s"${request.language.id}/$path")
        .getOrElse(
          throw IllegalStateException(
            s"the ${request.language.id} template lists $path but lacks it"
          )
        )
      val out = target.resolve(fill(path))
      Files.createDirectories(out.getParent)
      mode match
        case Mode.Binary => Files.write(out, bytes): Unit
        case _           => Files.writeString(out, fill(new String(bytes, UTF_8))): Unit
      if mode == Mode.Exec then executable(out)
    target

  // The owner may run it; a filesystem without POSIX permissions has nothing to set.
  private def executable(file: Path): Unit =
    try
      val permissions = Files.getPosixFilePermissions(file)
      permissions.add(PosixFilePermission.OWNER_EXECUTE)
      Files.setPosixFilePermissions(file, permissions): Unit
    catch case _: UnsupportedOperationException => ()

  private def resource(path: String): Option[Array[Byte]] =
    Option(getClass.getClassLoader.getResourceAsStream(Root + path)).map { stream =>
      try stream.readAllBytes()
      finally stream.close()
    }
