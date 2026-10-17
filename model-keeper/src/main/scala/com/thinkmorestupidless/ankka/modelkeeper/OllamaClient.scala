package com.thinkmorestupidless.ankka.modelkeeper

import com.github.plokhotnyuk.jsoniter_scala.core.*
import com.github.plokhotnyuk.jsoniter_scala.macros.*

import java.io.{BufferedReader, InputStreamReader}
import java.net.URI
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.concurrent.Executors
import scala.util.control.NonFatal

/**
 * Ollama's administration API on the pod's loopback, as the keeper uses it: what is held, what is
 * loaded, what a model can do, and fetching, loading and removing one. Every call answers `Left`
 * with why it failed rather than throwing, so one model's trouble never stops a pass.
 *
 * A fetch has no read timeout: it is minutes of download, and Ollama streams a status line as it
 * goes, the last of which says whether it ended well.
 */
final class OllamaClient(base: URI):
  import OllamaClient.*

  private val client = HttpClient
    .newBuilder()
    .version(HttpClient.Version.HTTP_1_1)
    .connectTimeout(Duration.ofSeconds(2))
    .executor(Executors.newVirtualThreadPerTaskExecutor())
    .build()

  private val root = base.toString.stripSuffix("/")

  def version(): Either[String, String] =
    get("/api/version").flatMap(body => decode[VersionAnswer](body)).map(_.version)

  /** Each held model's digest, by the name Ollama gives it. */
  def tags(): Either[String, Map[String, String]] =
    get("/api/tags").flatMap(decode[Listing]).map(_.models.map(m => m.name -> m.digest).toMap)

  /** The models loaded now. */
  def ps(): Either[String, Set[String]] =
    get("/api/ps").flatMap(decode[Listing]).map(_.models.map(_.name).toSet)

  /** What the model can do: `completion`, `tools`, `thinking`, … */
  def show(tag: String): Either[String, Vector[String]] =
    post("/api/show", s"""{"model":${quote(tag)}}""", Some(RequestTimeout))
      .flatMap(decode[ShowAnswer])
      .map(_.capabilities)

  /** Fetches the model; `Left` with Ollama's last line when the fetch did not end in success. */
  def pull(tag: String): Either[String, Unit] =
    val request = HttpRequest
      .newBuilder(URI.create(root + "/api/pull"))
      .header("Content-Type", "application/json")
      .POST(BodyPublishers.ofString(s"""{"model":${quote(tag)},"stream":true}"""))
      .build()
    attempt {
      val response = client.send(request, BodyHandlers.ofInputStream())
      val reader   = new BufferedReader(new InputStreamReader(response.body, UTF_8))
      try
        if response.statusCode != 200 then
          Left(
            s"fetch refused (${response.statusCode}): ${Iterator.continually(reader.readLine()).takeWhile(_ != null).mkString(" ")}"
          )
        else
          var status: Option[String] = None
          var error: Option[String]  = None
          var line                   = reader.readLine()
          while line != null do
            if line.trim.nonEmpty && error.isEmpty then
              decode[PullLine](line) match
                case Right(PullLine(_, Some(e)))      => error = Some(e)
                case Right(PullLine(Some(now), None)) => status = Some(now)
                case Right(_)                         => ()
                case Left(problem)                    => error = Some(problem)
            line = reader.readLine()
          (error, status) match
            case (Some(e), _)            => Left(e)
            case (None, Some("success")) => Right(())
            case (None, Some(other))     => Left(s"the fetch ended at: $other")
            case (None, None)            => Left("the fetch ended without a status")
      finally reader.close()
    }

  /** Loads the model and keeps it loaded for ever: a generate with no prompt, `keep_alive: -1`. */
  def load(tag: String): Either[String, Unit] =
    post("/api/generate", s"""{"model":${quote(tag)},"keep_alive":-1}""", Some(LoadTimeout)).map(
      _ => ()
    )

  def delete(tag: String): Either[String, Unit] =
    send(
      HttpRequest
        .newBuilder(URI.create(root + "/api/delete"))
        .timeout(RequestTimeout)
        .header("Content-Type", "application/json")
        .method("DELETE", BodyPublishers.ofString(s"""{"model":${quote(tag)}}"""))
        .build()
    ).map(_ => ())

  private def get(path: String): Either[String, String] =
    send(HttpRequest.newBuilder(URI.create(root + path)).timeout(RequestTimeout).GET().build())

  private def post(path: String, body: String, timeout: Option[Duration]): Either[String, String] =
    val builder = HttpRequest
      .newBuilder(URI.create(root + path))
      .header("Content-Type", "application/json")
      .POST(BodyPublishers.ofString(body))
    timeout.foreach(builder.timeout)
    send(builder.build())

  private def send(request: HttpRequest): Either[String, String] =
    attempt {
      val response = client.send(request, BodyHandlers.ofString())
      if response.statusCode / 100 == 2 then Right(response.body)
      else
        val reason = decode[ErrorAnswer](response.body).toOption.flatMap(_.error)
        Left(
          reason.getOrElse(s"Ollama answered ${response.statusCode}: ${response.body.take(200)}")
        )
    }

  private def attempt[A](body: => Either[String, A]): Either[String, A] =
    try body
    catch
      case _: java.net.ConnectException          => Left(s"Ollama is not answering at $root")
      case _: java.net.http.HttpTimeoutException => Left("Ollama did not answer in time")
      case NonFatal(e) =>
        Left(s"Ollama could not be asked: ${e.getClass.getSimpleName}: ${e.getMessage}")

object OllamaClient:

  val RequestTimeout: Duration = Duration.ofSeconds(10)

  /** Loading a model into memory is seconds on a GPU and can be a minute on a CPU. */
  val LoadTimeout: Duration = Duration.ofMinutes(5)

  final case class VersionAnswer(version: String = "")
  final case class Entry(name: String = "", digest: String = "")
  final case class Listing(models: Vector[Entry] = Vector.empty)
  final case class ShowAnswer(capabilities: Vector[String] = Vector.empty)
  final case class PullLine(status: Option[String] = None, error: Option[String] = None)
  final case class ErrorAnswer(error: Option[String] = None)

  given JsonValueCodec[VersionAnswer] = JsonCodecMaker.make
  given JsonValueCodec[Listing]       = JsonCodecMaker.make
  given JsonValueCodec[ShowAnswer]    = JsonCodecMaker.make
  given JsonValueCodec[PullLine]      = JsonCodecMaker.make
  given JsonValueCodec[ErrorAnswer]   = JsonCodecMaker.make

  def decode[A: JsonValueCodec](text: String): Either[String, A] =
    try Right(readFromString[A](text))
    catch
      case e: JsonReaderException => Left(s"Ollama answered something unreadable: ${e.getMessage}")

  /** A JSON string literal. */
  def quote(text: String): String =
    "\"" + text.flatMap {
      case '"'          => "\\\""
      case '\\'         => "\\\\"
      case c if c < ' ' => f"\\u${c.toInt}%04x"
      case c            => c.toString
    } + "\""
