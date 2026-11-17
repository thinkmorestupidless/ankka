package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.{Engine, Schemas}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Where a runtime version's schema comes from (feature 066, research R6). */
class SchemasSuite extends munit.FunSuite:

  private def repoRoot: Path =
    Iterator
      .iterate(Paths.get("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .find(p => Files.isDirectory(p.resolve("kustomization/components/postgres/ddl")))
      .getOrElse(fail("no repository root"))

  test("the command line's own files are the platform's schema, every one of them") {
    val ddl      = repoRoot.resolve("kustomization/components/postgres/ddl")
    val expected = Files
      .list(ddl)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".sql"))
      .toVector
      .sorted
    val schema = Schemas.files(None, "0.17.0", ScriptedEngine()).fold(fail(_), identity)
    assertEquals(schema.files.map(_._1), expected)
    assertEquals(schema.version, "0.17.0")
    assertEquals(schema.files.head._2, Files.readString(ddl.resolve(expected.head), UTF_8))
  }

  test("a cached version is read without the engine") {
    val cache = Files.createTempDirectory("cache")
    Files.createDirectories(cache.resolve("ddl/0.15.0"))
    Files.writeString(cache.resolve("ddl/0.15.0/10-journal.sql"), "CREATE TABLE a();", UTF_8)
    val engine = ScriptedEngine()
    val schema =
      Schemas.files(Some("0.15.0"), "0.17.0", engine, cache = cache).fold(fail(_), identity)
    assertEquals(schema.files, Vector("10-journal.sql" -> "CREATE TABLE a();"))
    assertEquals(engine.calls, Vector.empty)
  }

  test("a version not cached is copied out of its sidecar image once, then cached") {
    val cache  = Files.createTempDirectory("cache")
    val engine = ScriptedEngine()
    engine.copied = (_, _, to) =>
      Files.createDirectories(to)
      Files.writeString(to.resolve("10-journal.sql"), "CREATE TABLE b();", UTF_8)
      Engine.Result(0, "", "")
    val first = Schemas.files(Some("0.15.0"), "0.17.0", engine, cache = cache)
    val again = Schemas.files(Some("0.15.0"), "0.17.0", engine, cache = cache)
    assertEquals(first, again)
    assertEquals(
      engine.calls,
      Vector("copy ghcr.io/thinkmorestupidless/ankka-sidecar:0.15.0 /opt/docker/ddl")
    )
  }

  test("an image with no schema is said, naming it") {
    val engine = ScriptedEngine()
    engine.copied = (_, _, _) => Engine.Result(1, "", "No such image")
    assertEquals(
      Schemas.files(Some("0.15.0"), "0.17.0", engine, cache = Files.createTempDirectory("cache")),
      Left(
        "the schema of ankka 0.15.0 could not be read from ghcr.io/thinkmorestupidless/ankka-sidecar:0.15.0: No such image"
      )
    )
  }

  test("a development command line names the sidecar it built, and an override wins") {
    assertEquals(Schemas.sidecarImage("0.16.3+4-abcdef12-SNAPSHOT"), "ankka-sidecar:latest")
    assertEquals(Schemas.sidecarImage("0.16.3"), "ghcr.io/thinkmorestupidless/ankka-sidecar:0.16.3")
    assertEquals(Schemas.sidecarImage("0.16.3", Some("mine:1")), "mine:1")
  }

  test("the comment a database carries reads back as its version and digest") {
    val schema = Schemas.files(None, "0.17.0", ScriptedEngine()).fold(fail(_), identity)
    assertEquals(Schemas.recorded(Schemas.comment(schema)), Some("0.17.0" -> schema.digest))
    assertEquals(Schemas.recorded("someone else's comment"), None)
  }
