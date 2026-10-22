package com.thinkmorestupidless.ankka.testkit.replication

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

// The service the replication features describe: a car maker's vehicles, built at one factory and
// sold at another. Each installation of "fleet" is one factory or dealership.

/**
 * A vehicle, with where each of its events was written and whether that was here: what the event
 * handler was told, kept in the state so a scenario can read it.
 */
final case class Vehicle(
    id: String = "",
    steps: Vector[String] = Vector.empty,
    inspections: Vector[String] = Vector.empty,
    sold: Boolean = false,
    writtenAt: Vector[Option[String]] = Vector.empty,
    local: Vector[Boolean] = Vector.empty,
    actedBy: Vector[Option[String]] = Vector.empty
)

enum VehicleEvent:
  case Created(id: String)
  case StepAdded(step: String)
  case InspectionRecorded(note: String)
  case Sold

/**
 * What a vehicle's handler answers a command with: which command, where, and whether at home; who
 * acted and where the command was forwarded from (spec 057); and the metadata the handler was
 * given, as `key=value`, so a scenario can see what reached it.
 */
final case class Receipt(
    command: String,
    installation: Option[String],
    home: Boolean,
    records: Int,
    attribution: Option[String] = None,
    forwardedFrom: Option[String] = None,
    heard: Vector[String] = Vector.empty
)

/** Where a query ran. */
final case class Placement(installation: Option[String], home: Boolean)

/** The installations a new vehicle is replicated to besides its home. */
final case class CreateVehicle(replicateTo: Vector[String] = Vector.empty)

final class VehicleEntity(ctx: EventSourcedEntityContext)
    extends EventSourcedEntity[Vehicle, VehicleEvent]:
  import VehicleEvent.*

  def emptyState: Vehicle = Vehicle()

  // docs:start replicated-apply
  def applyEvent(event: VehicleEvent): Vehicle =
    val vehicle = event match
      case Created(id)     => currentState.copy(id = id)
      case StepAdded(step) => currentState.copy(steps = currentState.steps :+ step)
      case InspectionRecorded(note) =>
        currentState.copy(inspections = currentState.inspections :+ note)
      case Sold => currentState.copy(sold = true)
    // Where this event was written, on replay as well as when it is persisted here.
    vehicle.copy(
      writtenAt = vehicle.writtenAt :+ eventContext.installation,
      local = vehicle.local :+ eventContext.isLocal,
      // Who acted on the command that wrote it, as its record says.
      actedBy = vehicle.actedBy :+ eventContext.attribution
    )
  // docs:end replicated-apply

  /** Taken while the handler runs: the reply is computed after the context is gone. */
  private def receipt(command: String): Vehicle => Receipt =
    val here      = commandContext.installation
    val atHome    = commandContext.isHome
    val actor     = commandContext.attribution
    val forwarder = commandContext.forwardedFrom
    val heard     = commandContext.metadata.toSeq.map((k, v) => s"$k=$v").toVector
    vehicle => Receipt(command, here, atHome, vehicle.writtenAt.size, actor, forwarder, heard)

  def create(input: CreateVehicle): Effect[Receipt] =
    input.replicateTo
      .foldLeft(effects.persist(Created(ctx.entityId.toString)))(_.replicateTo(_))
      .thenReply(receipt("create"))

  def addStep(step: String): Effect[Receipt] =
    effects.persist(StepAdded(step)).thenReply(receipt("add-step"))

  def recordInspection(note: String): Effect[Receipt] =
    effects.persist(InspectionRecorded(note)).thenReply(receipt("record-inspection"))

  def recordSale: Effect[Receipt] =
    effects.persist(Sold).thenReply(receipt("record-sale"))

  // docs:start replicated-effects
  /** Ships the vehicle: its home is the dealership's installation from now on. */
  def handOver(installation: String): Effect[Receipt] =
    effects.handOverTo(installation).thenReply(receipt("hand-over"))

  /** Allots the vehicle to a dealership, whose installation holds it from now on. */
  def replicateTo(installation: String): Effect[Receipt] =
    effects.replicateTo(installation).thenReply(receipt("replicate-to"))

  /** The factory no longer needs it: its installation keeps what it holds and mirrors no more. */
  def stopReplicatingTo(installation: String): Effect[Receipt] =
    effects.stopReplicatingTo(installation).thenReply(receipt("stop-replicating-to"))
  // docs:end replicated-effects

  def get: ReadOnlyEffect[Vehicle] = effects.reply(currentState)

  def placement: ReadOnlyEffect[Placement] =
    effects.reply(Placement(commandContext.installation, commandContext.isHome))

object FleetCodecs:
  val vehicle: Serializer[Vehicle]    = Codecs.serializer[Vehicle]("vehicle")
  val event: Serializer[VehicleEvent] = Codecs.serializer[VehicleEvent]("vehicle-event")
  given Serializer[Receipt]           = Codecs.serializer[Receipt]("receipt")
  given Serializer[Placement]         = Codecs.serializer[Placement]("placement")
  given Serializer[CreateVehicle]     = Codecs.serializer[CreateVehicle]("create-vehicle")
  val floor: Serializer[Floor]        = Codecs.serializer[Floor]("floor")
  val build: Serializer[Build]        = Codecs.serializer[Build]("build")

import FleetCodecs.given

/**
 * The vehicle entity under a component id of its own, replicated or not: one kind of thing, which a
 * scenario names ("vehicle", "order") and declares replicated or not.
 */
// docs:start replicated-companion
final class VehicleKind(id: String, replicatedHere: Boolean = true)
    extends EventSourcedEntity.Companion[VehicleEntity, Vehicle, VehicleEvent](
      ComponentId(id),
      FleetCodecs.vehicle,
      FleetCodecs.event
    ):
  override def replicated: Boolean = replicatedHere

  def create(ctx: EventSourcedEntityContext) = new VehicleEntity(ctx)

  val createVehicle     = command("create")(_.create)
  val addStep           = command("add-step")(_.addStep)
  val recordInspection  = command("record-inspection")(_.recordInspection)
  val recordSale        = command("record-sale")(_.recordSale)
  val handOver          = command("hand-over")(_.handOver)
  val replicateTo       = command("replicate-to")(_.replicateTo)
  val stopReplicatingTo = command("stop-replicating-to")(_.stopReplicatingTo)
  val get               = query("get")(_.get)
  val placement         = query("placement")(_.placement)
// docs:end replicated-companion

object Fleet:
  val vehicles: VehicleKind = VehicleKind("vehicle")

/** A row of the view "floor": the steps a vehicle has had. */
final case class Floor(steps: Vector[String])

final class FloorView extends View[VehicleEvent, Floor]:
  def onChange(change: VehicleEvent): Effect = change match
    case VehicleEvent.StepAdded(step) =>
      effects.updateRow(Floor(rowState.fold(Vector(step))(_.steps :+ step)))
    case _ => effects.updateRow(rowState.getOrElse(Floor(Vector.empty)))

final class FloorKit(vehicles: VehicleKind)
    extends View.Companion[FloorView, VehicleEvent, Floor](
      componentId = ComponentId("floor"),
      source = ChangeSource.eventsOf(vehicles),
      rowSerializer = FleetCodecs.floor
    ):
  def create(context: ViewComponentContext) = new FloorView

/** A message the consumer "builds" publishes: a vehicle's step, and where it was written. */
final case class Build(vehicle: String, step: String, installation: Option[String], local: Boolean)

/** A change a consumer was given at an installation, as its context described it. */
final case class Handed(
    at: String,
    vehicle: String,
    event: String,
    installation: Option[String],
    local: Boolean
)

/** What every consumer of a scenario was given, at every installation. */
final class ChangesGiven:
  private val all                              = ConcurrentLinkedQueue[Handed]()
  def record(change: Handed): Unit             = all.add(change): Unit
  def at(installation: String): Vector[Handed] = all.asScala.toVector.filter(_.at == installation)
  def clear(): Unit                            = all.clear()

/** The consumer "builds": publishes each step to "vehicle-builds", and records what it is given. */
final class BuildsConsumer(at: String, log: ChangesGiven) extends Consumer[VehicleEvent, Build]:
  // docs:start mirrored-change
  def onMessage(message: VehicleEvent): Effect =
    val change = messageContext
    log.record(
      Handed(at, change.subject, message.toString, change.installation, change.localOrigin)
    )
    message match
      case VehicleEvent.StepAdded(step) =>
        effects.produce(Build(change.subject, step, change.installation, change.localOrigin))
      case _ => effects.ignore()
  // docs:end mirrored-change

/** The consumer "builds" at one installation, declaring mirrored changes or not. */
final class BuildsKit(at: String, log: ChangesGiven, vehicles: VehicleKind, mirrored: Boolean)
    extends Consumer.Companion[BuildsConsumer, VehicleEvent, Build](
      componentId = ComponentId("builds"),
      source = ChangeSource.eventsOf(vehicles)
    ):
  def create(context: ConsumerContext)                     = new BuildsConsumer(at, log)
  override def outputSerializer: Option[Serializer[Build]] = Some(FleetCodecs.build)
  override def produceTo: Option[String]                   = Some("vehicle-builds")
  override def mirroredChanges: Boolean                    = mirrored

/** A consumer that publishes nothing and records every change it is given. */
final class LedgerConsumer(at: String, log: ChangesGiven) extends Consumer[VehicleEvent, Nothing]:
  def onMessage(message: VehicleEvent): Effect =
    val change = messageContext
    log.record(
      Handed(at, change.subject, message.toString, change.installation, change.localOrigin)
    )
    effects.ignore()

final class LedgerKit(at: String, log: ChangesGiven, kind: VehicleKind)
    extends Consumer.Companion[LedgerConsumer, VehicleEvent, Nothing](
      componentId = ComponentId("ledger"),
      source = ChangeSource.eventsOf(kind)
    ):
  def create(context: ConsumerContext) = new LedgerConsumer(at, log)

/**
 * Where a person records an inspection at an installation (spec 057): an endpoint that
 * authenticates them by their bearer token, which names them, and calls the vehicle. The receipt is
 * kept for the scenario, since what it says is what the home's handler was told.
 */
final class InspectionsEndpoint(
    clients: com.thinkmorestupidless.ankka.http.EndpointClients,
    kind: VehicleKind,
    receipts: java.util.concurrent.atomic.AtomicReference[Option[Receipt]]
) extends com.thinkmorestupidless.ankka.http.HttpEndpoint("/vehicles"):
  import com.thinkmorestupidless.ankka.http.{Acl, AuthDecision, Principal}

  val acl: Acl = Acl.Authenticate { request =>
    request.header("Authorization").map(_.stripPrefix("Bearer ")) match
      case Some(token) if token.endsWith("-token") =>
        AuthDecision.Allow(Principal(token.stripSuffix("-token")))
      case _ => AuthDecision.Unauthenticated("""realm="fleet"""")
  }

  post("/{id}/inspections") { (id: String) =>
    val receipt = clients.componentClient
      .forEventSourcedEntity(EntityId(id))
      .call(kind.recordInspection)
      .invoke(s"inspected by ${principal.subject}")
    receipts.set(Some(receipt))
    receipt.command
  }
