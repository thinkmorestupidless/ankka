package com.thinkmorestupidless.ankka.testkit

import munit.FunSuite

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/**
 * That a service announces itself to the local console, and takes it back on the way out.
 *
 * The second half is the reason this exists. The endpoint's `stop()` — which withdraws the entry —
 * was held in a `var` on the `Ankka` builder object that **nothing ever read**, so it was never
 * called: every service ever run locally left its file in `~/.ankka/running` permanently. Nothing
 * complained, because the console sweeps entries nothing answers for when it lists them, so the
 * only symptom was a directory quietly filling up on the developer's own machine — 38 entries
 * before anyone looked.
 *
 * A test of `announce` and `withdraw` in isolation would have passed throughout: both functions
 * were correct, and the defect was that one of them was unreachable. So this drives the lifecycle
 * that actually runs — start a real service, stop it, look at the directory — which is the only
 * shape that can catch a call that is never made.
 */
final class ServiceRegistrationSuite extends FunSuite:

  private def entries(directory: Path): Vector[String] =
    if !Files.isDirectory(directory) then Vector.empty
    else
      Files
        .list(directory)
        .iterator()
        .asScala
        .filter(_.toString.endsWith(".json"))
        .map(Files.readString)
        .toVector

  test("a service announces itself while it runs, and withdraws when it terminates") {
    val directory = Files.createTempDirectory("ankka-registration")
    val previous  = sys.props.get("ankka.running.dir")
    sys.props.put("ankka.running.dir", directory.toString): Unit

    try
      val testKit = AnkkaTestKit.start(Seq(ProfileEntity.descriptor))
      try
        val announced = entries(directory)
        assertEquals(announced.size, 1, s"exactly one entry, for this service: $announced")
        assert(
          announced.head.contains("\"observabilityAddress\":\"http://127.0.0.1:"),
          s"naming where the console can reach it: ${announced.head}"
        )
      finally testKit.stop()

      assertEquals(
        entries(directory),
        Vector.empty,
        "and the entry is gone once the service has stopped — it was not, for the whole of " +
          "feature 007, because the call that removes it was never reached"
      )
    finally
      previous.fold(sys.props.remove("ankka.running.dir"))(
        sys.props.put("ankka.running.dir", _)
      ): Unit
      try Files.deleteIfExists(directory): Unit
      catch case _: Throwable => ()
  }

  test("a suite that chooses no directory does not write into the developer's home") {
    // The test kit claims a temp directory when a suite has not set one. Without this, every suite
    // that boots a service leaves an entry in `~/.ankka/running` on the machine that ran it.
    val previous = sys.props.get("ankka.running.dir")
    sys.props.remove("ankka.running.dir"): Unit

    try
      val testKit = AnkkaTestKit.start(Seq(ProfileEntity.descriptor))
      try
        val claimed = sys.props.get("ankka.running.dir")
        assert(claimed.isDefined, "the test kit chose a directory rather than defaulting to $HOME")
        val home = Path.of(sys.props("user.home"), ".ankka", "running").toString
        assertNotEquals(claimed, Some(home), "and it is not the developer's own registry")
      finally testKit.stop()

      assertEquals(
        sys.props.get("ankka.running.dir"),
        None,
        "and it puts the property back, so it cannot leak into the next suite in this JVM"
      )
    finally previous.foreach(sys.props.put("ankka.running.dir", _))
  }
