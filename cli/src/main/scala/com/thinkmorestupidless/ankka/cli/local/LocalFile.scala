package com.thinkmorestupidless.ankka.cli.local

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * `local.json` (feature 066, contracts/project-files.md): what is true of a project only on a
 * developer's machine. The local values each service is given, the command that runs a service from
 * its source where its language's default does not fit, and the companions that run beside the
 * services. Read strictly, as the context map is; a project with nothing to say has no file.
 */
final case class LocalFile(
    values: Map[String, Map[String, String]] = Map.empty,
    run: Map[String, Vector[String]] = Map.empty,
    companions: Vector[LocalFile.Companion] = Vector.empty
):
  /** A service's local values: those for every service, then its own over them. */
  def valuesFor(service: String): Map[String, String] =
    values.getOrElse(LocalFile.EveryService, Map.empty) ++ values.getOrElse(service, Map.empty)

object LocalFile:

  val FileName: String     = "local.json"
  val EveryService: String = "*"

  final case class Port(port: Int, host: Option[Int] = None)
  final case class Mount(from: String, to: String)
  final case class HttpReady(port: Int, path: String = "/")

  /** `{"http": {"port", "path"}}` or `{"tcp": port}`: exactly one. */
  final case class Ready(http: Option[HttpReady] = None, tcp: Option[Int] = None):
    def port: Option[Int] = http.map(_.port).orElse(tcp)

  final case class Companion(
      name: String,
      image: String,
      command: Vector[String] = Vector.empty,
      env: Map[String, String] = Map.empty,
      ports: Vector[Port] = Vector.empty,
      files: Vector[Mount] = Vector.empty,
      ready: Option[Ready] = None,
      database: Boolean = false,
      neededBy: Option[Vector[String]] = None,
      memory: Option[String] = None
  ):
    /** Whether it holds back `service` until it is ready: every service, unless it names some. */
    def needed(service: String): Boolean = neededBy.forall(_.contains(service))

  given codec: JsonValueCodec[LocalFile] = JsonCodecMaker.make(
    CodecMakerConfig.withSkipUnexpectedFields(false)
  )

  private val UnknownField = """unexpected field "([^"]*)"""".r

  /** Reads `text` as a local file, or says why it is not one. */
  def parse(text: String): Either[Vector[String], LocalFile] =
    try Right(readFromString[LocalFile](text))
    catch
      case failure: JsonReaderException =>
        val why = UnknownField.findFirstMatchIn(failure.getMessage) match
          case Some(found) => s"it names a field the platform does not know: \"${found.group(1)}\""
          case None        => failure.getMessage.linesIterator.nextOption().getOrElse("")
        Left(Vector(s"$FileName is not a local values file: $why"))

  /** The file at `root`, or an empty one when the project has none. */
  def read(root: Path): Either[Vector[String], LocalFile] =
    val file = root.resolve(FileName)
    if !Files.isRegularFile(file) then Right(LocalFile())
    else parse(Files.readString(file, UTF_8))

  /**
   * A token in a value: `{{companion.<name>}}` (host and port of its first port),
   * `{{companion.<name>.url}}` (`http://` before them), or `{{database.<field>}}` in a companion's
   * own environment.
   */
  enum Token:
    case CompanionAddress(name: String)
    case CompanionUrl(name: String)
    case Database(field: String)

  /** The fields `{{database.<field>}}` may name. */
  val DatabaseFields: Vector[String] =
    Vector("host", "port", "name", "user", "password", "url", "jdbc")

  private val TokenPattern = """\{\{([^{}]*)\}\}""".r

  private def token(text: String): Option[Token] = text.trim.split('.').toList match
    case "companion" :: name :: Nil          => Some(Token.CompanionAddress(name))
    case "companion" :: name :: "url" :: Nil => Some(Token.CompanionUrl(name))
    case "database" :: field :: Nil          => Some(Token.Database(field))
    case _                                   => None

  /** Every token `value` holds, and the text of each `{{...}}` that is no token. */
  def tokens(value: String): (known: Vector[Token], unknown: Vector[String]) =
    val found = TokenPattern.findAllMatchIn(value).map(_.group(1)).toVector
    (known = found.flatMap(token), unknown = found.filter(token(_).isEmpty))

  /**
   * `value` with each token written by `write`; a token it answers nothing for is left as it is.
   */
  def fill(value: String, write: Token => Option[String]): String =
    TokenPattern.replaceAllIn(
      value,
      m =>
        scala.util.matching.Regex
          .quoteReplacement(token(m.group(1)).flatMap(write).getOrElse(m.matched))
    )
