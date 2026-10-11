package com.thinkmorestupidless.ankka.controlplane.application

import com.thinkmorestupidless.ankka.controlplane.api.{MachineSummary, Machines}
import com.thinkmorestupidless.ankka.controlplane.auth.MachineSecrets
import com.thinkmorestupidless.ankka.controlplane.domain.*
import com.thinkmorestupidless.ankka.controlplane.domain.MachineEvent.*
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/**
 * A machine registered on an organization (feature 040), keyed `<organization>/<name>`.
 *
 * Who may register, delete or limit one is the endpoint's question, answered from the organization.
 * The entity holds the secret's digest and answers whether a presented secret matches it; it never
 * holds the secret, and no reply carries the digest.
 */
final class MachineEntity(context: EventSourcedEntityContext)
    extends EventSourcedEntity[Machine, MachineEvent]:

  def emptyState: Machine = Machine(context.entityId)

  def applyEvent(event: MachineEvent): Machine = event match
    case MachineRegistered(organizationId, name, digest, actor, at) =>
      currentState.onRegistered(organizationId, name, digest, actor, at)
    case MachineByteRatesSet(produce, consume, percentage, _, _) =>
      currentState.onByteRatesSet(ByteRates(produce, consume, percentage))
    case MachineDeleted(actor, at) => currentState.onDeleted(actor, at)

  /** Registers the machine, or registers it again over a deleted one: a new machine either way. */
  def register(request: RegisterMachine): Effect[MachineSummary] =
    val problems = Machines.nameProblems(request.name)
    if currentState.exists then
      effects.error(
        s"machine '${request.name}' is already registered on '${request.organizationId}'",
        ErrorCode.Conflict
      )
    else if problems.nonEmpty then effects.error(problems.mkString("; "))
    else if request.organizationId.isEmpty || request.digest.isEmpty then
      effects.error("a machine needs an organization and a secret")
    else
      effects
        .persist(MachineRegistered(request.organizationId, request.name, request.digest, actor, at))
        .thenReply(_.summary)

  def setByteRates(request: SetMachineByteRates): Effect[MachineSummary] =
    if !currentState.exists then notFound
    else
      effects
        .persist(
          MachineByteRatesSet(
            request.produceBytesPerSecond,
            request.consumeBytesPerSecond,
            request.requestPercentage,
            actor,
            at
          )
        )
        .thenReply(_.summary)

  def delete: Effect[Done] =
    if !currentState.exists then notFound
    else effects.persist(MachineDeleted(actor, at)).thenReply(_ => Done)

  /** Who deleted the machine and when; nothing while it exists or was never registered. */
  def deletion: ReadOnlyEffect[Option[Deletion]] =
    effects.reply(Option.when(!currentState.exists)(currentState.deletion).flatten)

  def get: ReadOnlyEffect[MachineSummary] =
    if !currentState.exists then effects.error(notFoundMessage, ErrorCode.NotFound)
    else effects.reply(currentState.summary)

  /**
   * The machine, when `secret` is its own and it is not deleted; nothing otherwise, and nothing
   * that says which of the two it was. Compared in constant time.
   */
  def checkSecret(secret: String): ReadOnlyEffect[Option[MachineSummary]] =
    effects.reply(
      Option.when(currentState.exists && MachineSecrets.matches(currentState.digest, secret))(
        currentState.summary
      )
    )

  private def attribution: Option[Attribution] = Attribution.from(commandContext.metadata)
  private def actor: Option[Actor]             = attribution.map(_.actor)
  private def at: Option[java.time.Instant]    = attribution.map(_.at)

  private def notFoundMessage = s"no such machine '${context.entityId}'"
  private def notFound        = effects.error(notFoundMessage, ErrorCode.NotFound)

object MachineEntity
    extends EventSourcedEntity.Companion[MachineEntity, Machine, MachineEvent](
      componentId = ComponentId("machine"),
      stateSerializer = Codecs.serializer[Machine]("machine"),
      eventSerializer = Codecs.serializer[MachineEvent]("machine-event")
    ):

  given Serializer[RegisterMachine] = Codecs.serializer[RegisterMachine]("register-machine")
  given Serializer[SetMachineByteRates] =
    Codecs.serializer[SetMachineByteRates]("set-machine-byte-rates")
  given Serializer[MachineSummary] = Codecs.serializer[MachineSummary]("machine-summary")
  given deletionSerializer: Serializer[Option[Deletion]] =
    Codecs.serializer[Option[Deletion]]("deletion-option")
  given Serializer[Option[MachineSummary]] =
    Codecs.serializer[Option[MachineSummary]]("machine-summary-option")

  def create(context: EventSourcedEntityContext) = new MachineEntity(context)

  val register     = command("register")(_.register)
  val setByteRates = command("set-byte-rates")(_.setByteRates)
  val delete       = command("delete")(_.delete)
  val get          = query("get")(_.get)
  val deletion     = query("deletion")(_.deletion)
  val checkSecret  = query("check-secret")(_.checkSecret)
