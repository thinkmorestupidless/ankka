package com.thinkmorestupidless.ankka.runtime.replication

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.runtime.{
  EntityProtocol,
  EventSourcedEntityHost,
  JournalRecord,
  MetaEntry
}
import com.thinkmorestupidless.ankka.sdk.*
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.persistence.testkit.scaladsl.PersistenceTestKit
import org.apache.pekko.persistence.testkit.{
  PersistenceTestKitPlugin,
  PersistenceTestKitSnapshotPlugin
}
import org.apache.pekko.persistence.typed.PersistenceId
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.{Done, NotUsed}

import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.*
import scala.concurrent.{Future, Promise}
import scala.jdk.CollectionConverters.*

// The event sourced host's replication, driven directly (feature 051): one copy per actor system,
// each on Pekko's in-memory journal, its transport a script the test writes.

final case class Track(steps: Vector[String] = Vector.empty)
final case class StepAdded(step: String)

final class TrackEntity extends EventSourcedEntity[Track, StepAdded]:
  def emptyState: Track                     = Track()
  def applyEvent(event: StepAdded): Track   = Track(currentState.steps :+ event.step)
  def addStep(step: String): Effect[String] = effects.persist(StepAdded(step)).thenReply(_ => step)
  def replicateTo(name: String): Effect[String] = effects.replicateTo(name).thenReply(_ => name)
  def stopReplicatingTo(name: String): Effect[String] =
    effects.stopReplicatingTo(name).thenReply(_ => name)
  def get(): ReadOnlyEffect[Track] = effects.reply(currentState)

object TrackEntity
    extends EventSourcedEntity.Companion[TrackEntity, Track, StepAdded](
      ComponentId("track"),
      Codecs.serializer[Track]("track"),
      Codecs.serializer[StepAdded]("step-added")
    ):
  given Serializer[Track]                    = Codecs.serializer[Track]("track")
  override def replicated: Boolean           = true
  def create(ctx: EventSourcedEntityContext) = new TrackEntity
  val addStep                                = command("add-step")(_.addStep)
  val replicateTo                            = command("replicate-to")(_.replicateTo)
  val stopReplicatingTo                      = command("stop-replicating-to")(_.stopReplicatingTo)
  val get                                    = query("get")(_.get())

/** A transport whose every answer the test decides, recording every call it is asked. */
final class ScriptedTransport extends ReplicationTransport:
  /** Each `records` call: the installation and the sequence number it was asked from. */
  val opened = ConcurrentLinkedQueue[(String, Long)]()

  @volatile var source: (String, Long) => Source[Replicated, NotUsed] = (_, _) => Source.never

  val forwarded = ConcurrentLinkedQueue[(String, ForwardedCall)]()

  @volatile var forwardReply: ForwardedCall => Future[ForwardedReply] =
    _ => Promise[ForwardedReply]().future

  val locates = AtomicInteger()

  @volatile var located: () => Future[Option[Located]] = () => Future.successful(None)

  @volatile var reachableNow: String => Boolean = _ => true

  def name: String = "scripted"

  def records(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      from: Long
  ): Source[Replicated, NotUsed] =
    opened.add(installation -> from)
    source(installation, from)

  def forward(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      call: ForwardedCall
  ): Future[ForwardedReply] =
    forwarded.add(installation -> call)
    forwardReply(call)

  def report(
      installation: String,
      componentId: ComponentId,
      entityId: EntityId,
      position: Long
  ): Future[Done] = Future.successful(Done)

  def locate(componentId: ComponentId, entityId: EntityId): Future[Option[Located]] =
    locates.incrementAndGet()
    located()

  def advanced: Source[Advanced, NotUsed] = Source.never

  def reachable(installation: String): Boolean = reachableNow(installation)

  def openedNow: Vector[(String, Long)] = opened.asScala.toVector

/** A component client no handler here calls. */
private object NoCalls extends CallTransport:
  def ask(
      componentId: ComponentId,
      entityId: EntityId,
      method: MethodName,
      payload: Array[Byte],
      metadata: Metadata
  ): Future[Array[Byte]] = Future.failed(UnsupportedOperationException("no calls here"))
  def askTimeout: FiniteDuration                                             = 5.seconds
  def tell(componentId: ComponentId, entityId: EntityId, message: Any): Unit = ()

/** One copy of `track|<id>` at installation `self`, in an actor system of its own. */
final class Copy(val self: String, val transport: ScriptedTransport, val id: String = "t1"):
  val kit: ActorTestKit = ActorTestKit(s"copy-$self", Copy.config)

  val settings: ReplicationSettings =
    ReplicationSettings(
      Some(self),
      Copy.Installations - self,
      1.second,
      holdWrites = false,
      handoverTimeout = 30.seconds
    )

  val ref: ActorRef[EntityProtocol.Command] = kit.spawn(
    EventSourcedEntityHost.behavior(
      TrackEntity.descriptor,
      EntityId(id),
      ComponentClient(NoCalls),
      Some(Replication(settings, transport, Set(TrackEntity.descriptor.componentId)))
    )
  )

  val acks: TestProbe[EntityProtocol.ReplicateAck] =
    kit.createTestProbe[EntityProtocol.ReplicateAck]()
  val replies: TestProbe[EntityProtocol.Reply] = kit.createTestProbe[EntityProtocol.Reply]()

  /** What this copy's journal holds, in order. */
  def journal: Vector[JournalRecord] =
    PersistenceTestKit(kit.system)
      .persistedInStorage(PersistenceId(TrackEntity.descriptor.componentId, EntityId(id)).id)
      .collect { case r: JournalRecord => r }
      .toVector

  def replicate(sequenceNr: Long, record: JournalRecord, source: String = "vienna"): Unit =
    ref ! EntityProtocol.Replicate(sequenceNr, record, source, acks.ref)

  def invoke(method: String, input: String = "", metadata: Vector[MetaEntry] = Vector.empty): Unit =
    ref ! EntityProtocol.Invoke(method, input.getBytes(UTF_8), metadata, replies.ref)

  /** A follower of `home`: its home and set records, then a step each, mirrored as they came. */
  def follow(home: String, set: Set[String], steps: String*): Unit =
    val records = Copy.home(home) +: Copy.set(home, set) +: steps.map(Copy.step(home, _))
    records.zipWithIndex.foreach { (record, i) =>
      replicate(i + 1L, record, home)
      acks.expectMessage(EntityProtocol.ReplicateAck.Persisted(i + 1L))
    }

  def stop(): Unit = kit.shutdownTestKit()

object Copy:
  val Installations: Set[String] = Set("leipzig", "vienna", "prague")

  val config: Config =
    ConfigFactory
      .parseString("pekko.actor.provider = local")
      .withFallback(PersistenceTestKitPlugin.config)
      .withFallback(PersistenceTestKitSnapshotPlugin.config)
      .withFallback(ConfigFactory.load())

  def home(installation: String, writer: String = ""): JournalRecord =
    JournalRecord.home(installation).at(Some(if writer.isEmpty then installation else writer))

  def set(writer: String, installations: Set[String]): JournalRecord =
    JournalRecord.replicationSet(installations).at(Some(writer))

  def step(writer: String, step: String): JournalRecord =
    JournalRecord
      .domain("step-added", TrackEntity.descriptor.eventSerializer.toBytes(StepAdded(step)))
      .at(Some(writer))

  /** A record's fields, payload as bytes, so two records compare by what they hold. */
  def fields(record: JournalRecord): (Int, String, Seq[Byte], Long, Option[String]) =
    (record.kind, record.manifest, record.payload.toSeq, record.expiryMillis, record.installation)

  def text(reply: EntityProtocol.Reply): String = reply match
    case EntityProtocol.Succeeded(payload, _) => String(payload, UTF_8)
    case other                                => throw AssertionError(s"not a success: $other")

  def track(reply: EntityProtocol.Reply): Track = reply match
    case EntityProtocol.Succeeded(payload, _) =>
      TrackEntity.descriptor.stateSerializer.fromBytes(payload)
    case other => throw AssertionError(s"not a success: $other")

  def rejected(reply: EntityProtocol.Reply): CommandError = reply match
    case r: EntityProtocol.Rejected => r.toCommandError
    case other                      => throw AssertionError(s"not a refusal: $other")

/** Each test gets copies of its own, stopped after it. */
trait CopiesSuite extends munit.FunSuite:
  override def munitTimeout: Duration = 60.seconds

  private val running = ConcurrentLinkedQueue[Copy]()

  def copy(self: String, transport: ScriptedTransport = ScriptedTransport()): Copy =
    val c = Copy(self, transport)
    running.add(c)
    c

  override def afterEach(context: AfterEach): Unit =
    running.asScala.foreach(_.stop())
    running.clear()

  /** Waits for `check`, retrying on the value that changes. */
  def eventually[A](timeout: FiniteDuration = 10.seconds)(check: => Option[A]): A =
    val deadline = System.nanoTime() + timeout.toNanos
    var last     = check
    while last.isEmpty && System.nanoTime() < deadline do
      Thread.sleep(25)
      last = check
    last.getOrElse(fail(s"not observed within $timeout"))
