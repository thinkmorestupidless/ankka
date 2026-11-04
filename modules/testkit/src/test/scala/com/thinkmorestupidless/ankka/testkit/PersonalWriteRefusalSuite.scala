package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.ServiceIdentity
import com.thinkmorestupidless.ankka.sdk.*

/** Feature 042, T030 (FR-005, FR-013): a write for an erased data subject, through an entity. */
final case class Noted(note: Personal[String], tag: String)

final class Notebook(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Vector[String], Noted]:
  private val subject                      = DataSubject(s"player/${context.entityId}")
  def emptyState: Vector[String]           = Vector.empty
  def applyEvent(e: Noted): Vector[String] = currentState :+ e.tag
  def note(text: String): Effect[Done]     =
    effects.persist(Noted(Personal(subject, text), text.length.toString)).thenReply(_ => Done)
  def tags: ReadOnlyEffect[String] = effects.reply(currentState.mkString(","))

object Notebook
    extends EventSourcedEntity.Companion[Notebook, Vector[String], Noted](
      componentId = ComponentId("notebook"),
      stateSerializer = Codecs.serializer[Vector[String]]("notebook"),
      eventSerializer = Codecs.serializer[Noted]("noted")
    ):
  def create(context: EventSourcedEntityContext) = new Notebook(context)
  val note                                       = command("note")(_.note)
  val tags                                       = query("tags")(_.tags)

final class PersonalWriteRefusalSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = scala.concurrent.duration.Duration(2, "min")

  test("a write for an erased data subject is refused, naming it, and nothing is recorded") {
    val keyring = InMemoryKeyring()
    val kit     = AnkkaTestKit.start(
      Seq(Notebook.descriptor),
      Nil,
      serviceIdentity = ServiceIdentity.deployed("brand", "notes"),
      keyring = keyring
    )
    try
      def book(id: String) = kit.componentClient.forEventSourcedEntity(EntityId(id))
      assertEquals(book("8c1f").call(Notebook.note).invoke("hello"), Done)
      kit.erase(DataSubject("player/8c1f")): Unit
      val refused = intercept[CommandError](book("8c1f").call(Notebook.note).invoke("again"))
      assertEquals(refused.code, ErrorCode.Conflict)
      assert(refused.getMessage.contains("'player/8c1f' is erased"), refused.getMessage)
      assert(refused.getMessage.contains("nothing was written"), refused.getMessage)
      // The entity still answers, and holds only what was written before the erasure.
      assertEquals(book("8c1f").call(Notebook.tags).invoke(), "5")
      kit.restartService()
      assertEquals(book("8c1f").call(Notebook.tags).invoke(), "5")

      // A subject erased before it was ever written: its first write is refused (FR-005).
      kit.erase(DataSubject("player/41d2")): Unit
      val first = intercept[CommandError](book("41d2").call(Notebook.note).invoke("first"))
      assert(first.getMessage.contains("'player/41d2' is erased"), first.getMessage)
      assert(!keyring.holdsKey("brand", DataSubject("player/41d2")))
      assertEquals(book("41d2").call(Notebook.tags).invoke(), "")
    finally kit.stop()
  }
