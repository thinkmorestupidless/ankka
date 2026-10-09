package com.thinkmorestupidless.ankka.testkit.erasure

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.core.personal.Personal
import com.thinkmorestupidless.ankka.sdk.*
import com.thinkmorestupidless.ankka.testkit.{AnkkaTestKit, LogCapturing}

import scala.concurrent.duration.*

/** One entry of a ledger: the cardholder's name, personal in one ledger and plain in the other. */
enum MarkedEntry:
  case Paid(amount: Int, cardHolder: Personal[String])

enum PlainEntry:
  case Paid(amount: Int, cardHolder: String)

final class MarkedLedger(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Int, MarkedEntry]:
  def emptyState: Int = 0
  def applyEvent(event: MarkedEntry): Int = event match
    case MarkedEntry.Paid(amount, _) => currentState + amount
  def pay(count: Int): Effect[Done] =
    val subject = s"payer/${context.entityId}"
    effects
      .persistAll(
        (1 to count).map(_ => MarkedEntry.Paid(1, Personal.present(subject, "Ada Byron")))
      )
      .thenReply(_ => Done)
  def total: ReadOnlyEffect[Int] = effects.reply(currentState)

object MarkedLedger
    extends EventSourcedEntity.Companion[MarkedLedger, Int, MarkedEntry](
      componentId = ComponentId("marked-ledger"),
      stateSerializer = Codecs.serializer[Int]("marked-total"),
      eventSerializer = Codecs.serializer[MarkedEntry]("marked-entry")
    ):
  def create(context: EventSourcedEntityContext) = new MarkedLedger(context)
  override def snapshotEvery: Option[Int]        = None
  val pay                                        = command("pay")(_.pay)
  val total                                      = query("total")(_.total)

final class PlainLedger(@scala.annotation.unused context: EventSourcedEntityContext)
    extends EventSourcedEntity[Int, PlainEntry]:
  def emptyState: Int = 0
  def applyEvent(event: PlainEntry): Int = event match
    case PlainEntry.Paid(amount, _) => currentState + amount
  def pay(count: Int): Effect[Done] =
    effects
      .persistAll((1 to count).map(_ => PlainEntry.Paid(1, "Ann Byron")))
      .thenReply(_ => Done)
  def total: ReadOnlyEffect[Int] = effects.reply(currentState)

object PlainLedger
    extends EventSourcedEntity.Companion[PlainLedger, Int, PlainEntry](
      componentId = ComponentId("plain-ledger"),
      stateSerializer = Codecs.serializer[Int]("plain-total"),
      eventSerializer = Codecs.serializer[PlainEntry]("plain-entry")
    ):
  def create(context: EventSourcedEntityContext) = new PlainLedger(context)
  override def snapshotEvery: Option[Int]        = None
  val pay                                        = command("pay")(_.pay)
  val total                                      = query("total")(_.total)

/**
 * SC-007: recovering an entity of 1,000 events, each with a personal field, after a restart takes
 * no more than 1.3 times as long as recovering the same entity without one. Neither ledger takes a
 * snapshot, so recovery is a replay of every event; the personal one's replay opens 1,000 envelopes
 * under one subject key fetched once. Three runs of each, interleaved, the median compared.
 */
class PersonalReplayBenchmark extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 5.minutes

  private val Events = 1_000
  private val Runs   = 3

  test(
    "recovering 1,000 events with a personal field costs at most 1.3x recovering them without".tag(
      munit.Slow
    )
  ) {
    val kit = AnkkaTestKit.start(Seq(MarkedLedger.descriptor, PlainLedger.descriptor), Nil)
    try
      // A warm-up ledger of each, so neither kind pays the JIT's first compilation alone.
      val ids = ("warm" +: (1 to Runs).map(i => s"run-$i")).toVector
      ids.foreach { id =>
        kit.componentClient
          .forEventSourcedEntity(EntityId(id))
          .call(MarkedLedger.pay)
          .invoke(Events): Unit
        kit.componentClient
          .forEventSourcedEntity(EntityId(id))
          .call(PlainLedger.pay)
          .invoke(Events): Unit
      }
      // The personal ledger's journal holds envelopes, not the name: replay has them to open.
      kit.assertNoPersonalValue("Ada Byron")
      kit.restartService()

      def recover(timed: => Int): Long =
        val start = System.nanoTime()
        assertEquals(timed, Events)
        System.nanoTime() - start

      def marked(id: String) = recover(
        kit.componentClient.forEventSourcedEntity(EntityId(id)).call(MarkedLedger.total).invoke()
      )
      def plain(id: String) = recover(
        kit.componentClient.forEventSourcedEntity(EntityId(id)).call(PlainLedger.total).invoke()
      )

      marked("warm"): Unit
      plain("warm"): Unit
      val runs                     = ids.tail.map(id => (marked(id), plain(id)))
      def median(xs: Vector[Long]) = xs.sorted.apply(xs.size / 2)
      val withPersonal             = median(runs.map(_._1))
      val without                  = median(runs.map(_._2))
      val ratio                    = withPersonal.toDouble / without
      println(
        f"recovery of $Events events: personal ${withPersonal / 1e6}%.1f ms, plain ${without / 1e6}%.1f ms, ratio $ratio%.2f"
      )
      assert(ratio <= 1.3, f"personal fields cost $ratio%.2fx on replay; SC-007 allows 1.3x")
    finally kit.stop()
  }
