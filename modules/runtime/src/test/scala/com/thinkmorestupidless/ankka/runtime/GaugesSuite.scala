package com.thinkmorestupidless.ankka.runtime

import java.nio.file.{Files, Paths}

/** The registry of watched numbers (feature 041): set, replaced, removed, announced once. */
class GaugesSuite extends munit.FunSuite:

  private val shop = Map("ankka.project" -> "shop")
  private val lab  = Map("ankka.project" -> "lab")

  test("a value set is in the snapshot, and a second set replaces it") {
    val gauges = new Gauges
    gauges.set("ankka.backups.failing", shop, 1.0)
    assertEquals(gauges.snapshot("ankka.backups.failing"), Vector(shop -> 1.0))
    gauges.set("ankka.backups.failing", shop, 0.0)
    assertEquals(gauges.snapshot("ankka.backups.failing"), Vector(shop -> 0.0))
  }

  test("each attribute set has its own value, and one removed is no longer reported") {
    val gauges = new Gauges
    gauges.set("ankka.backups.archive_lag_seconds", shop, 12.5)
    gauges.set("ankka.backups.archive_lag_seconds", lab, 3.0)
    assertEquals(gauges.attributesOf("ankka.backups.archive_lag_seconds"), Set(shop, lab))
    gauges.remove("ankka.backups.archive_lag_seconds", lab)
    assertEquals(gauges.snapshot("ankka.backups.archive_lag_seconds"), Vector(shop -> 12.5))
  }

  test("a listener hears every name once: those set before it, and those set after") {
    val gauges = new Gauges
    gauges.set("before", shop, 1.0, "set before")
    val heard = scala.collection.mutable.ArrayBuffer.empty[(String, String)]
    gauges.onNewName((name, description) => heard += name -> description)
    gauges.set("after", shop, 2.0, "set after")
    gauges.set("after", lab, 3.0)
    gauges.set("before", lab, 4.0)
    assertEquals(heard.toVector, Vector("before" -> "set before", "after" -> "set after"))
    assertEquals(gauges.names, Set("before", "after"))
  }

  test("an unknown name has no values") {
    assertEquals((new Gauges).snapshot("nothing"), Vector.empty)
  }

  test("the registry needs no library: it imports nothing but the JDK and Scala") {
    val source = Files.readString(
      Iterator
        .iterate(Paths.get("").toAbsolutePath)(_.getParent)
        .takeWhile(_ != null)
        .map(
          _.resolve(
            "modules/runtime/src/main/scala/com/thinkmorestupidless/ankka/runtime/Gauges.scala"
          )
        )
        .find(Files.exists(_))
        .getOrElse(fail("Gauges.scala not found"))
    )
    val imports = source.linesIterator.filter(_.startsWith("import ")).toVector
    assert(
      imports.forall(i => i.startsWith("import java.") || i.startsWith("import scala.")),
      imports
    )
  }
