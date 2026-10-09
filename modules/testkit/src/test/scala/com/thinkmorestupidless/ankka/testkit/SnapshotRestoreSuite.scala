package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

/** A counter, so what a restore puts back is visible as a number. */
final class Tally(context: KeyValueEntityContext) extends KeyValueEntity[Int]:
  def emptyState: Int            = 0
  def add(n: Int): Effect[Int]   = effects.updateState(currentState + n).thenReply(identity)
  def total: ReadOnlyEffect[Int] = effects.reply(currentState)
  private val _                  = context

object Tally
    extends KeyValueEntity.Companion[Tally, Int](
      componentId = ComponentId("tally"),
      stateSerializer = Codecs.serializer[Int]("tally")
    ):
  def create(context: KeyValueEntityContext) = new Tally(context)
  val add                                    = command("add")(_.add)
  val total                                  = query("total")(_.total)

/** A kit's database copied and put back, as a backup is taken and restored. */
class SnapshotRestoreSuite extends munit.FunSuite with LogCapturing:

  // Two stops and two starts of a service, each waiting for the cluster to form.
  override val munitTimeout = scala.concurrent.duration.Duration(3, "minutes")

  test("a write made after the snapshot is gone after the restore, and one made before is kept") {
    val kit = AnkkaTestKit.start(Tally.descriptor)
    try
      def tally = kit.componentClient.forKeyValueEntity(EntityId("t1"))
      tally.call(Tally.add).invoke(2): Unit
      val backup = kit.snapshotDatabase()
      tally.call(Tally.add).invoke(40): Unit
      assertEquals(tally.call(Tally.total).invoke(), 42)
      kit.restoreDatabase(backup)
      assertEquals(tally.call(Tally.total).invoke(), 2)
    finally kit.stop()
  }
