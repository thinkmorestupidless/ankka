package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.cli.Release

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * The schema of a runtime version, for a local database (feature 066, research R6): the CLI's own
 * copy for its own version, else the cache, else the files the sidecar image of that version holds,
 * copied into the cache. What a database holds is recorded on it as a comment naming the version
 * and a digest of the files, so a later run can tell whether anything changed.
 */
object Schemas:

  /** A version's schema: its files by name, in the order they are applied, and their digest. */
  final case class Schema(version: String, digest: String, files: Vector[(String, String)]):
    /** Every file, in order, as one input to `psql`. */
    def script: String = files.map(_._2).mkString("\n")

  /** Where the sidecar image holds the schema files. */
  val ImagePath: String = "/opt/docker/ddl"

  def cacheDir: Path =
    sys.props
      .get("ankka.cache.dir")
      .map(Paths.get(_))
      .getOrElse(Paths.get(sys.props("user.home"), ".ankka", "cache"))

  /**
   * The sidecar image of `version`: the override when given, the local build for a development CLI.
   */
  def sidecarImage(version: String, overridden: Option[String] = None): String =
    overridden.getOrElse(
      if Release.parse(version).isEmpty then "ankka-sidecar:latest"
      else s"ghcr.io/thinkmorestupidless/ankka-sidecar:$version"
    )

  /** The schema of `version` (absent: the CLI's), or why it cannot be had. */
  def files(
      version: Option[String],
      cliVersion: String,
      engine: Engine,
      sidecar: Option[String] = None,
      cache: Path = cacheDir
  ): Either[String, Schema] =
    val wanted = version.getOrElse(cliVersion)
    if wanted == cliVersion then own(cliVersion)
    else
      val dir = cache.resolve("ddl").resolve(wanted)
      read(wanted, dir) match
        case Some(schema) => Right(schema)
        case None         =>
          val image = sidecarImage(wanted, sidecar)
          Files.createDirectories(dir.getParent)
          val copied = engine.copyOut(image, ImagePath, dir)
          read(wanted, dir).toRight(
            s"the schema of ankka $wanted could not be read from $image" +
              Option(copied.err.trim).filter(_.nonEmpty).fold("")(e => s": $e")
          )

  /** The comment a database carries once `schema` is applied. */
  def comment(schema: Schema): String = s"ankka-schema ${schema.version} ${schema.digest}"

  /** The version and digest a database's comment records, when it records one. */
  def recorded(comment: String): Option[(String, String)] =
    comment.trim.split(' ') match
      case Array("ankka-schema", version, digest) => Some(version -> digest)
      case _                                      => None

  private def own(version: String): Either[String, Schema] =
    val loader = getClass.getClassLoader
    Option(loader.getResourceAsStream("ankka/ddl/index.txt")) match
      case None        => Left("this build of the command line carries no schema files")
      case Some(index) =>
        val names = String(index.readAllBytes(), UTF_8).linesIterator.map(_.trim).filter(_.nonEmpty)
        val files = names.toVector.sorted.map { name =>
          val in = loader.getResourceAsStream(s"ankka/ddl/$name")
          try name -> String(in.readAllBytes(), UTF_8)
          finally in.close()
        }
        Right(schema(version, files))

  private def read(version: String, dir: Path): Option[Schema] =
    Option
      .when(Files.isDirectory(dir)) {
        val stream = Files.list(dir)
        try
          stream.iterator.asScala
            .filter(_.getFileName.toString.endsWith(".sql"))
            .toVector
            .sortBy(_.getFileName.toString)
            .map(p => p.getFileName.toString -> Files.readString(p, UTF_8))
        finally stream.close()
      }
      .filter(_.nonEmpty)
      .map(schema(version, _))

  private def schema(version: String, files: Vector[(String, String)]): Schema =
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    files.foreach { (name, text) =>
      digest.update(name.getBytes(UTF_8))
      digest.update(0.toByte)
      digest.update(text.getBytes(UTF_8))
      digest.update(0.toByte)
    }
    Schema(version, digest.digest().map(b => f"${b & 0xff}%02x").mkString, files)
