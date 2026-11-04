package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{
  Database,
  Envelopes,
  LookupTokens,
  ProjectionRuntime,
  ServiceIdentity,
  SqlFragment,
  ViewFields
}
import com.thinkmorestupidless.ankka.sdk.*

import scala.concurrent.Await
import scala.concurrent.duration.*

/** Feature 042, T033 (R13): a view's lookup tokens, and the envelopes a row may hold. */
final case class Joined(memberId: String, email: Personal[String], city: String)

final class MemberEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Int, Joined]:
  private val subject                   = DataSubject(s"member/${context.entityId}")
  def emptyState: Int                   = 0
  def applyEvent(e: Joined): Int        = currentState + 1
  def join(email: String): Effect[Done] =
    effects
      .persist(Joined(context.entityId, Personal(subject, email), "Leeds"))
      .thenReply(_ => Done)

object MemberEntity
    extends EventSourcedEntity.Companion[MemberEntity, Int, Joined](
      componentId = ComponentId("member"),
      stateSerializer = Codecs.serializer[Int]("member-count"),
      eventSerializer = Codecs.serializer[Joined]("joined")
    ):
  def create(context: EventSourcedEntityContext) = new MemberEntity(context)
  val join                                       = command("join")(_.join)

final case class MemberRow(memberId: String, email: Personal[String], city: String)

final class MembersView extends View[Joined, MemberRow]:
  def onChange(event: Joined): Effect =
    effects.updateRow(MemberRow(event.memberId, event.email, event.city))

object Members
    extends View.Companion[MembersView, Joined, MemberRow](
      componentId = ComponentId("members"),
      source = ChangeSource.eventsOf(MemberEntity),
      rowSerializer = Codecs.serializer[MemberRow]("member-row")
    ):
  def create(ctx: ViewComponentContext) = new MembersView
  lookup(_.email)
  val byEmail =
    query("by-email")(s"SELECT payload FROM $table WHERE payload::jsonb->>'email' = :email")
  val byCity = query("by-city")(s"SELECT payload FROM $table WHERE payload::jsonb->>'city' = :city")

final class LookupTokenSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 2.minutes

  test("a lookup field is found by equality through its token, and its value is nowhere") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(MemberEntity.descriptor, Members.descriptor),
      Seq(ProjectionRuntime()),
      serviceIdentity = ServiceIdentity.deployed("brand", "members"),
      keyring = keyring
    )
    try
      def member(id: String) = kit.componentClient.forEventSourcedEntity(EntityId(id))
      member("8c1f").call(MemberEntity.join).invoke("ada@example.com"): Unit
      member("9d2e").call(MemberEntity.join).invoke("grace@example.com"): Unit
      val rows  = kit.service.viewClient.forView(Members)
      val found = Iterator
        .continually { Thread.sleep(100); rows.ask(Members.byEmail, "email" -> "ada@example.com") }
        .take(300)
        .find(_.nonEmpty)
        .getOrElse(fail("the row was never found by its email"))
      assertEquals(found.map(_.memberId), Vector("8c1f"))
      assertEquals(found.head.email.orElse("erased"), "ada@example.com")
      assertEquals(rows.ask(Members.byEmail, "email" -> "nobody@example.com"), Vector.empty)
      assertEquals(rows.ask(Members.byCity, "city" -> "Leeds").size, 2)

      // The column holds the token under the project's lookup key, not the value.
      val lookup = Await.result(
        Database()(using kit.service.system).query(
          SqlFragment.raw("SELECT lookup::text FROM ankka_view_members WHERE row_key = '8c1f'")
        )(_.get(0, classOf[String])),
        30.seconds
      )
      val token = LookupTokens.ofValue(keyring.lookupKey("brand"), "email", "ada@example.com")
      assertEquals(lookup, Vector(s"""{"email": "$token"}"""))
      kit.assertNoPersonalValue("ada@example.com", "grace@example.com")
    finally kit.stop()
  }

  test("envelopes are found at their paths, in the order they were written") {
    val json =
      """{"a":{"$personal":1,"project":"p","subject":"s/1","c":"x"},"b":[{"v":{"$personal":1,"project":"p","subject":"s/2"}}],"c":"{\"$personal\":1}"}"""
    assertEquals(
      Envelopes.in(json),
      Vector(Envelopes.Found("a", "s/1"), Envelopes.Found("b[*].v", "s/2"))
    )
  }

  test(
    "a row holding an envelope where the view declares no personal field is refused, naming it"
  ) {
    val json    = """{"note":{"$personal":1,"project":"p","subject":"s/1","c":"x"}}"""
    val refused =
      LookupTokens.forRow(ComponentId("notes"), json, Vector(None), ViewFields.none, IArray.empty)
    assert(refused.left.exists(_.contains("'note'")), refused.toString)
    assert(refused.left.exists(_.contains("view 'notes'")), refused.toString)
  }

  test("a token binds the field's path, and a string field is tokened as its text") {
    val key = IArray.fill[Byte](32)(3)
    assertEquals(
      LookupTokens.token(key, "email", LookupTokens.canonical("\"a\\u0040b\"".getBytes)),
      LookupTokens.ofValue(key, "email", "a@b")
    )
    assertNotEquals(
      LookupTokens.ofValue(key, "email", "a@b"),
      LookupTokens.ofValue(key, "name", "a@b")
    )
    assertEquals(LookupTokens.canonical("42".getBytes).toVector, "42".getBytes.toVector)
  }
