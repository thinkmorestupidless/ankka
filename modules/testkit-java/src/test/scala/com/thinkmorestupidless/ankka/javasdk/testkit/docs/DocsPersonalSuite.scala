package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Players
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt

/** The Java sample of the personal data page: erasure through the kit's keyring. */
class DocsPersonalSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("an erased player's email reads as erased after a restart, and is in no table") {
    val kit = AnkkaTestKit.start(Ankka.service("players").register(classOf[Players]))
    try assertEquals(PersonalChecks.emailAfterErasure(kit), "erased")
    finally kit.close()
  }
