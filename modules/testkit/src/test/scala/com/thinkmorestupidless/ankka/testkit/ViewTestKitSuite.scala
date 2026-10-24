package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.effect.ViewEffect
import com.thinkmorestupidless.ankka.sdk.*

/** One row per profile, counting the changes it has seen; a change named "skip" is ignored. */
final case class ProfileCount(name: String, changes: Int, sequence: Long)

final class ProfileCountsView extends View[Profile, ProfileCount]:
  def onChange(profile: Profile): Effect =
    if profile.name == "skip" then effects.ignore()
    else
      effects.updateRow(
        ProfileCount(profile.name, rowState.fold(1)(_.changes + 1), updateContext.sequenceNumber)
      )

object ProfileCounts
    extends View.Companion[ProfileCountsView, Profile, ProfileCount](
      componentId = ComponentId("profile-counts"),
      source = ChangeSource.stateOf(ProfileEntity),
      rowSerializer = Codecs.serializer[ProfileCount]("profile-count")
    ):
  def create(ctx: ViewComponentContext) = new ProfileCountsView

  val named = query("named")(s"SELECT payload FROM $table WHERE payload::jsonb->>'name' = :name")

object BrokenQueries
    extends View.Companion[ProfileCountsView, Profile, ProfileCount](
      componentId = ComponentId("broken-queries"),
      source = ChangeSource.stateOf(ProfileEntity),
      rowSerializer = Codecs.serializer[ProfileCount]("profile-count")
    ):
  def create(ctx: ViewComponentContext) = new ProfileCountsView

  val elsewhere = query("elsewhere")("SELECT payload FROM somebody_else")

class ViewTestKitSuite extends munit.FunSuite with LogCapturing:

  test("a change writes its subject's row, which the next change of that subject is handed") {
    val kit = ViewTestKit(ProfileCounts)
    kit.change("u1", Profile("Ada", "ada@example.com", 1)): Unit
    kit.change("u1", Profile("Ada L", "ada@example.com", 2)): Unit
    kit.change("u2", Profile("Grace", "grace@example.com", 1)): Unit
    assertEquals(kit.row("u1"), Some(ProfileCount("Ada L", 2, 2)))
    assertEquals(kit.rows.keySet, Set("u1", "u2"))
  }

  test("an ignored change keeps the row, and a deletion removes it") {
    val kit = ViewTestKit(ProfileCounts)
    kit.change("u1", Profile("Ada", "ada@example.com", 1)): Unit
    assertEquals(kit.change("u1", Profile("skip", "", 0)), ViewEffect.Ignore)
    assertEquals(kit.row("u1").map(_.changes), Some(1))
    assertEquals(kit.deleted("u1"), ViewEffect.DeleteRow)
    assertEquals(kit.row("u1"), None)
  }

  test("the sequence number is the test's when it gives one") {
    val kit = ViewTestKit(ProfileCounts)
    kit.change("u1", Profile("Ada", "", 1), sequenceNumber = 41): Unit
    assertEquals(kit.row("u1").map(_.sequence), Some(41L))
  }

  test("the view's declared queries are checked as a service's start checks them") {
    assertEquals(ViewTestKit(ProfileCounts).queries.map(_.name), Vector("named"))
    val refused = intercept[IllegalArgumentException](ViewTestKit(BrokenQueries))
    assert(refused.getMessage.contains("somebody_else"), refused.getMessage)
  }
