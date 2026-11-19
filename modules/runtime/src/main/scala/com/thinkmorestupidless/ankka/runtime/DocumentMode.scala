package com.thinkmorestupidless.ankka.runtime

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

/**
 * `ankka.document`'s mode (feature 067, contracts/command.md): a service's `main` that reaches
 * `start` writes a document of what it serves and ends, with nothing bound, formed or connected and
 * no actor system made. `run` is the whole of it and does not exit, so a suite can call it.
 */
private[ankka] object DocumentMode:
  val Property       = "ankka.document"
  private val Prefix = s"$Property."
  private val Out    = s"${Prefix}out"

  private val Version = s"${Prefix}version"
  private val Name    = "ankka.service.name"

  /**
   * A document's title: `ankka.service.name` as a property, else as configured, else `fallback`.
   * The one rule for the written document and the served one, which FR-029 holds equal.
   */
  def title(config: com.typesafe.config.Config, fallback: String): String =
    sys.props
      .get(Name)
      .orElse(Option.when(config.hasPath(Name))(config.getString(Name)).filter(_.nonEmpty))
      .getOrElse(fallback)

  /**
   * A document's version: `ankka.document.version` as a property, else as configured, else none.
   */
  def version(config: com.typesafe.config.Config): String =
    sys.props
      .get(Version)
      .orElse(Option.when(config.hasPath(Version))(config.getString(Version)))
      .getOrElse("")

  /** The kinds a service may be asked for. */
  val Kinds: Set[String] = Set("openapi")

  /** The `ankka.document*` properties, when the mode is asked for. */
  def requested(
      properties: collection.Map[String, String] = sys.props
  ): Option[Map[String, String]] =
    properties.get(Property).map { _ =>
      properties.iterator.filter((k, _) => k == Property || k.startsWith(Prefix)).toMap
    }

  /**
   * Writes the document `properties` ask for and answers `0`, or the problems and `1`. The document
   * is written to a temporary file beside `ankka.document.out` and moved over it, so a reader never
   * sees half of one and a failure leaves whatever was there; with no `out` it is printed.
   */
  def run(
      builder: ServiceBuilder,
      properties: Map[String, String],
      serviceName: String
  ): (Int, Vector[String]) =
    val kind = properties.getOrElse(Property, "")
    if !Kinds(kind) then (1, Vector(s"unknown document kind: $kind"))
    else
      builder.validate match
        case Left(problems)  => (1, problems)
        case Right(registry) =>
          val options = properties.collect {
            case (k, v) if k.startsWith(Prefix) && k != Out => k.stripPrefix(Prefix) -> v
          } + ("name" -> serviceName)
          builder.declaredExtensions.flatMap(_.document(kind, options, registry)) match
            case Vector()            => (1, Vector("this service has no HTTP endpoints"))
            case Vector(Left(found)) => (1, found)
            case Vector(Right(text)) => write(text, properties.get(Out))
            case several             =>
              (1, Vector(s"${several.size} extensions each write a $kind document; one may"))

  private def write(text: String, out: Option[String]): (Int, Vector[String]) =
    out match
      case None =>
        print(text)
        Console.flush()
        (0, Vector.empty)
      case Some(name) =>
        val target    = Paths.get(name).toAbsolutePath
        val directory = Option(target.getParent).getOrElse(Paths.get(".").toAbsolutePath)
        var temporary: Option[Path] = None
        try
          Files.createDirectories(directory)
          val written = Files.createTempFile(directory, ".openapi-", ".tmp")
          temporary = Some(written)
          Files.writeString(written, text, StandardCharsets.UTF_8)
          Files.move(
            written,
            target,
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE
          )
          (0, Vector.empty)
        catch
          case failure: java.io.IOException =>
            temporary.foreach(Files.deleteIfExists)
            (1, Vector(s"the document could not be written to $target: ${failure.getMessage}"))
