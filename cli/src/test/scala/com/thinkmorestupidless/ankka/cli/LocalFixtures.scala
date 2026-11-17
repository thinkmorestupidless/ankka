package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.core.ContextMap

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * A project on disk for the local project suites (feature 066): `shop`, with `orders` calling
 * `billing` by a route, each with a descriptor, the context map and its rendering. A suite adds
 * what a case needs (topics, local values, companions) with `write`.
 */
object LocalFixtures:

  final case class Service(
      name: String,
      port: Int,
      image: String = "",
      extra: String = "",
      env: String = ""
  ):
    def descriptor: String =
      val img       = if image.isEmpty then s"$name:1" else image
      val variables = if env.isEmpty then "" else s""","env":[$env]"""
      s"""{"name":"$name","service":{"image":"$img","port":$port$variables$extra}}"""

  def write(root: Path, path: String, text: String): Path =
    val file = root.resolve(path)
    Files.createDirectories(file.getParent)
    Files.writeString(file, text, UTF_8)

  /** A project of `services`; `crossings` are the context map's, as JSON objects. */
  def project(
      services: Vector[Service] = Vector(Service("orders", 9000), Service("billing", 9001)),
      crossings: Vector[String] = Vector(
        """{"from":"orders","to":"billing","route":"POST /invoices"}"""
      ),
      name: String = "shop",
      root: Path = Files.createTempDirectory("local-project")
  ): Path =
    services.foreach(s => write(root, s"services/${s.name}/service.json", s.descriptor))
    val entries = services.map { s =>
      s"""{"name":"${s.name}","owns":"${s.name}","boundary":{"criterion":"ownership","why":"its team"}}"""
    }
    val text =
      s"""{"project":"$name","services":[${entries.mkString(",")}],"crossings":[${crossings
          .mkString(",")}]}"""
    val map = ContextMap.parse(text).fold(p => throw AssertionError(p.mkString("; ")), identity)
    write(root, ContextMap.FileName, ContextMap.write(map))
    write(root, ContextMap.RenderingName, ContextMap.render(map))
    root

  /** Writes `map` and its rendering, after a case changed the map. */
  def rewrite(root: Path, map: ContextMap): Unit =
    write(root, ContextMap.FileName, ContextMap.write(map))
    write(root, ContextMap.RenderingName, ContextMap.render(map)): Unit
