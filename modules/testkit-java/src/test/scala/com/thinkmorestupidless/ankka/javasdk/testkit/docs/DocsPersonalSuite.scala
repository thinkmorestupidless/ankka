package com.thinkmorestupidless.ankka.javasdk.testkit.docs

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.testkit.AnkkaTestKit
import com.thinkmorestupidless.ankka.javasdk.testkit.kinds.Players
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import scala.concurrent.duration.DurationInt
import scala.jdk.CollectionConverters.*

/** The Java sample of the personal data page: erasure through the kit's keyring. */
class DocsPersonalSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  test("an erased player's email reads as erased after a restart, and is in no table") {
    val kit = AnkkaTestKit.start(Ankka.service("players").register(classOf[Players]))
    try assertEquals(PersonalChecks.emailAfterErasure(kit), "erased")
    finally kit.close()
  }

  test("the erasure handler erases the subject's objects, and keeps everyone else's") {
    val kit = AnkkaTestKit.start(ErasureSamples.players())
    try
      val bucket = kit.storage().defaultBucket()
      bucket.putBytes("subjects/player%2F8c1f/passport.png", Array[Byte](1), "image/png")
      bucket.putBytes("subjects/player%2F77a0/passport.png", Array[Byte](2), "image/png")
      kit.erase("player/8c1f")
      val left = Iterator
        .continually { Thread.sleep(100); bucket.list("subjects/").asScala.map(_.name).toVector }
        .take(300)
        .find(_.size == 1)
      assertEquals(left, Some(Vector("subjects/player%2F77a0/passport.png")))
    finally kit.close()
  }
