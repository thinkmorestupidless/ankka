package com.thinkmorestupidless.ankka.grpc

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** The component the gRPC suites' endpoints call: a cart that can be told to refuse. */
final case class FixtureCartState(items: Vector[String])

final class FixtureCart(@scala.annotation.unused context: KeyValueEntityContext)
    extends KeyValueEntity[FixtureCartState]:

  def emptyState: FixtureCartState = FixtureCartState(Vector.empty)

  def add(product: String): Effect[FixtureCartState] =
    effects.updateState(FixtureCartState(currentState.items :+ product)).thenReplyState

  /** `<code>|<message>`: refuses with that code and message, as a domain rule would. */
  def refuse(refusal: String): Effect[FixtureCartState] =
    val (code, message) = refusal.span(_ != '|')
    effects.error(message.drop(1), ErrorCode.valueOf(code))

  def get: ReadOnlyEffect[FixtureCartState] = effects.reply(currentState)

object FixtureCart
    extends KeyValueEntity.Companion[FixtureCart, FixtureCartState](
      componentId = ComponentId("fixture-cart"),
      stateSerializer = Codecs.serializer[FixtureCartState]("fixture-cart")
    ):

  def create(context: KeyValueEntityContext) = new FixtureCart(context)

  val add    = command("add")(_.add)
  val refuse = command("refuse")(_.refuse)
  val get    = query("get")(_.get)
