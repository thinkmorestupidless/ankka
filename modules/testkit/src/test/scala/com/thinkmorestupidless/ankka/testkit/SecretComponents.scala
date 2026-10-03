package com.thinkmorestupidless.ankka.testkit

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.*

final case class KeepSecret(name: String, value: String)

// docs:start endpoint
/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
final class ProviderCredentialsEndpoint(secrets: SecretStore) extends HttpEndpoint("/credentials"):

  private given JsonValueCodec[KeepSecret] = Codecs.make[KeepSecret]

  val acl: Acl = Acl.AllowAll

  postBody("/") { (request: KeepSecret) =>
    secrets.put(request.name, request.value)
    Done: Done
  }

  get("/") { () =>
    val name = query.required[String]("name")
    secrets.get(name).getOrElse(throw HttpProblem.notFound(s"no secret '$name'"))
  }

  delete("/") { () =>
    secrets.delete(query.required[String]("name"))
    Done: Done
  }
// docs:end endpoint

final case class Charge(provider: String, amount: Int)
final case class ChargeState(provider: String, credentialLength: Int, status: String)

// docs:start workflow
/**
 * Charges a customer through a payment provider. The step reads the provider's credential from the
 * secret store when it needs it, and records only that it charged.
 */
final class ChargeWorkflow(context: WorkflowContext) extends Workflow[ChargeState]:

  def emptyState: ChargeState = ChargeState("", 0, "not-started")

  def start(charge: Charge): Effect[Done] =
    effects
      .updateState(ChargeState(charge.provider, 0, "started"))
      .transitionTo(ChargeWorkflow.charge.withInput(charge))
      .thenReply(Done)

  def chargeStep(charge: Charge): StepEffect =
    val credential = context.secrets
      .get(s"provider/${charge.provider}")
      .getOrElse(throw IllegalStateException(s"no credential for ${charge.provider}"))
    // Calling the provider with `credential` goes here. The state records that it was charged, and
    // never the credential.
    stepEffects
      .updateState(currentState.copy(credentialLength = credential.length, status = "charged"))
      .thenEnd
  // docs:end workflow

  /** A command that reads a secret, which a workflow may not do outside a step. */
  def peek(provider: String): Effect[Done] =
    context.secrets.get(s"provider/$provider"): Unit
    effects.reply(Done)

  def status: ReadOnlyEffect[ChargeState] = effects.reply(currentState)

object ChargeWorkflow
    extends Workflow.Companion[ChargeWorkflow, ChargeState](
      componentId = ComponentId("charge"),
      stateSerializer = Codecs.serializer[ChargeState]("charge-state")
    ):

  given Serializer[Charge] = Codecs.serializer[Charge]("charge")

  def create(context: WorkflowContext) = new ChargeWorkflow(context)

  val charge = step("charge")(_.chargeStep)

  val start  = command("start")(_.start)
  val peek   = command("peek")(_.peek)
  val status = query("status")(_.status)

/** A consumer that keeps a warehouse's credential the first time it sees the warehouse. */
final class WarehouseCredentialKeeper(context: ConsumerContext)
    extends Consumer[StockEvent, Nothing]:
  def onMessage(event: StockEvent): Effect =
    val name = s"warehouse/${event.warehouse}"
    if context.secrets.get(name).isEmpty then context.secrets.put(name, s"token-${event.warehouse}")
    effects.ignore()

object WarehouseCredentialKeeper
    extends Consumer.Companion[WarehouseCredentialKeeper, StockEvent, Nothing](
      componentId = ComponentId("warehouse-credential-keeper"),
      source = ChangeSource.fromTopic(
        "stock-events",
        Codecs.serializer[StockEvent]("stock-event"),
        StartFrom.Earliest
      )
    ):
  def create(ctx: ConsumerContext) = new WarehouseCredentialKeeper(ctx)
