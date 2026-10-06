package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.{Config, ConfigFactory}
import munit.FunSuite

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/**
 * Declared in this module's test resources: an extension only for a configuration that asks for it
 * (`ankka.test.provider`), so every other suite of `runtime` starts exactly as it did.
 */
final class TestProvider extends RuntimeExtensionProvider:
  def extension(config: Config): Option[RuntimeExtension] =
    if !config.hasPath("ankka.test.provider") then None
    else
      config.getString("ankka.test.provider") match
        case "on"    => Some(RuntimeExtensionProviderSuite.Recording("provided"))
        case "throw" => throw IllegalArgumentException("asked to fail")
        case _       => None

object RuntimeExtensionProviderSuite:
  val events = ConcurrentLinkedQueue[String]()

  final case class Recording(name: String) extends RuntimeExtension:
    def start(service: AnkkaService): Unit = events.add(s"start $name"): Unit
    override def stop(): Unit              = events.add(s"stop $name"): Unit

/** A module offers an extension by being on the classpath; components are still handed over. */
final class RuntimeExtensionProviderSuite extends FunSuite:
  import RuntimeExtensionProviderSuite.*

  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")

  private def config(provider: String) =
    ConfigFactory
      .parseString(s"""ankka.test.provider = "$provider"""")
      .withFallback(ClusterConfig.load())

  test("a provided extension starts before the service's own, and stops after them") {
    events.clear()
    val service = Ankka.service
      .withExtension(Recording("own"))
      .start("provider-suite", config("on"))
    try assertEquals(events.asScala.toVector, Vector("start provided", "start own"))
    finally service.terminate()
    assertEquals(events.asScala.toVector.drop(2), Vector("stop own", "stop provided"))
    assert(service.extensionNames.contains("provided"))
  }

  test("a provider with nothing to do for the configuration adds nothing") {
    events.clear()
    val service =
      Ankka.service.withExtension(Recording("own")).start("provider-suite", config("off"))
    try assertEquals(service.extensionNames, Vector("own"))
    finally service.terminate()
  }

  test("a provider that fails fails the start, naming itself") {
    val failure = intercept[IllegalStateException](
      Ankka.service.start("provider-suite", config("throw"))
    )
    assert(failure.getMessage.contains("TestProvider"), failure.getMessage)
  }
