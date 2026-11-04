package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.Database
import com.thinkmorestupidless.ankka.runtime.keyring.{KeyStoreClient, LocalKeyring}
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*

/** A note with a personal text, and the same note with none: the two journals recovered. */
final case class PersonalNote(text: Personal[String], n: Int)
final case class PlainNote(text: String, n: Int)

final class PersonalNotebook(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Int, PersonalNote]:
  private val subject = DataSubject("writer/" + context.entityId)
  def emptyState: Int = 0
  // Reads the field, so every event's envelope is opened, as a handler that uses it would.
  def applyEvent(event: PersonalNote): Int = currentState + event.text.orElse("").length.min(1)
  def write(count: Int): Effect[Done]      =
    effects
      .persistAll((1 to count).map(n => PersonalNote(Personal(subject, s"note number $n"), n)))
      .thenReply(_ => Done)
  def count: ReadOnlyEffect[Int] = effects.reply(currentState)

object PersonalNotebook
    extends EventSourcedEntity.Companion[PersonalNotebook, Int, PersonalNote](
      componentId = ComponentId("personal-notebook"),
      stateSerializer = Codecs.serializer[Int]("count"),
      eventSerializer = Codecs.serializer[PersonalNote]("personal-note")
    ):
  def create(context: EventSourcedEntityContext) = new PersonalNotebook(context)
  // Recovered from every event, not a snapshot of the count, which holds no personal field.
  override def snapshotEvery: Option[Int] = None
  val write                               = command("write")(_.write)
  val count                               = query("count")(_.count)

final class PlainNotebook extends EventSourcedEntity[Int, PlainNote]:
  def emptyState: Int                   = 0
  def applyEvent(event: PlainNote): Int = currentState + event.text.length.min(1)
  def write(count: Int): Effect[Done]   =
    effects
      .persistAll((1 to count).map(n => PlainNote(s"note number $n", n)))
      .thenReply(_ => Done)
  def count: ReadOnlyEffect[Int] = effects.reply(currentState)

object PlainNotebook
    extends EventSourcedEntity.Companion[PlainNotebook, Int, PlainNote](
      componentId = ComponentId("plain-notebook"),
      stateSerializer = Codecs.serializer[Int]("count"),
      eventSerializer = Codecs.serializer[PlainNote]("plain-note")
    ):
  def create(context: EventSourcedEntityContext) = new PlainNotebook
  override def snapshotEvery: Option[Int]        = None
  val write                                      = command("write")(_.write)
  val count                                      = query("count")(_.count)

/**
 * Feature 042, SC-007 and FR-023: an entity of 1,000 events with a personal field of one data
 * subject, recovered cold against the local keyring, takes no more than 1.3 times as long as the
 * same entity with no personal field; and each recovery fetches the subject's key once.
 */
class PersonalRecoveryBenchmark extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 10.minutes

  private val Events = 1000
  private val Rounds = 7

  /** The local keyring, counting the keys asked of it. */
  private final class Counting(inner: KeyStoreClient) extends KeyStoreClient:
    val fetches                                                     = AtomicInteger()
    def key(project: String, subject: DataSubject, create: Boolean) =
      fetches.incrementAndGet()
      inner.key(project, subject, create)
    def lookupKey(project: String)                                      = inner.lookupKey(project)
    def subscribe(after: Long)(listener: keyring.KeyringNotice => Unit) =
      inner.subscribe(after)(listener)
    def confirmedAt()                      = inner.confirmedAt()
    def ack(ack: keyring.KeyringAck): Unit = inner.ack(ack)
    override def close(): Unit             = inner.close()

  @volatile private var counting: Option[Counting] = None

  test(
    "recovering 1,000 events with a personal field costs at most 1.3 times as much, with one fetch"
  ) {
    val kit = AnkkaTestKit.start(
      Seq(PersonalNotebook.descriptor, PlainNotebook.descriptor),
      configure = _.withKeyStore { (system, project) =>
        val c =
          Counting(LocalKeyring(project, Database()(using system))(using system.executionContext))
        counting = Some(c)
        c
      }
    )
    try
      def personal = kit.componentClient.forEventSourcedEntity(EntityId("p"))
      def plain    = kit.componentClient.forEventSourcedEntity(EntityId("q"))
      (1 to Events / 100).foreach { _ =>
        assertEquals(personal.call(PersonalNotebook.write).invoke(100), Done)
        assertEquals(plain.call(PlainNotebook.write).invoke(100), Done)
      }

      def recover(read: => Int): Long =
        kit.restartService()
        val started = System.nanoTime()
        assertEquals(read, Events)
        System.nanoTime() - started

      // One round to warm the code paths, then alternate so the machine's drift falls on both.
      recover(personal.call(PersonalNotebook.count).invoke()): Unit
      recover(plain.call(PlainNotebook.count).invoke()): Unit
      val rounds = (1 to Rounds).map { _ =>
        val withKey = recover(personal.call(PersonalNotebook.count).invoke())
        // The restart made a fresh client, so what it counted is this recovery's alone.
        val fetched = counting.map(_.fetches.get).getOrElse(0)
        val without = recover(plain.call(PlainNotebook.count).invoke())
        (withKey, without, fetched)
      }
      def median(xs: Seq[Long]) = xs.sorted.apply(xs.size / 2)
      val withKey               = median(rounds.map(_._1))
      val without               = median(rounds.map(_._2))
      val ratio                 = withKey.toDouble / without
      println(
        f"personal recovery of $Events events: ${withKey / 1e6}%.1f ms; plain: ${without / 1e6}%.1f ms; ratio $ratio%.2f"
      )
      // Each restart hands the service a fresh keyring client: one fetch per recovery.
      assertEquals(rounds.map(_._3).toSet, Set(1), rounds.toString)
      assert(ratio <= 1.3, f"the ratio is $ratio%.2f, above 1.3")
    finally kit.stop()
  }
