package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant

/** One of the broker's signing keys, as the journal holds it: the public half only. */
final case class SignInKey(kid: String, publicKeyJwk: String, createdAt: Instant)

/** The broker's keys' journal. Never change a shape: add beside it. */
enum SignInKeyEvent:
  case SignInKeyAdded(kid: String, publicKeyJwk: String, createdAt: Instant)
  case SignInKeyRemoved(kid: String)

final case class SignInKeys(keys: Vector[SignInKey]):
  def newest: Option[SignInKey] = keys.lastOption

/**
 * The service's sign-in broker keys (feature 054): one entity per service, id `keys`. Each key's
 * private half is in the service's secret store under `ankka-sign-in-key-<kid>`, written before the
 * key is added here and deleted after it is removed; the journal never holds it.
 */
final class SignInKeysEntity extends EventSourcedEntity[SignInKeys, SignInKeyEvent]:
  import SignInKeyEvent.*

  def emptyState: SignInKeys = SignInKeys(Vector.empty)

  def applyEvent(event: SignInKeyEvent): SignInKeys = event match
    case SignInKeyAdded(kid, jwk, at) => SignInKeys(currentState.keys :+ SignInKey(kid, jwk, at))
    case SignInKeyRemoved(kid)        => SignInKeys(currentState.keys.filterNot(_.kid == kid))

  /** Adds a key; adding one already held is done already. */
  def add(key: SignInKey): Effect[Done] =
    if currentState.keys.exists(_.kid == key.kid) then effects.reply(Done)
    else
      effects.persist(SignInKeyAdded(key.kid, key.publicKeyJwk, key.createdAt)).thenReply(_ => Done)

  /** Removes a key, never the last: a broker with no key could verify nothing it had minted. */
  def remove(kid: String): Effect[Done] =
    if !currentState.keys.exists(_.kid == kid) then effects.reply(Done)
    else if currentState.keys.sizeIs == 1 then
      effects.error("the broker's last key is never removed", ErrorCode.Conflict)
    else effects.persist(SignInKeyRemoved(kid)).thenReply(_ => Done)

  def get: ReadOnlyEffect[SignInKeys] = effects.reply(currentState)

object SignInKeysEntity
    extends EventSourcedEntity.Companion[SignInKeysEntity, SignInKeys, SignInKeyEvent](
      componentId = ComponentId("ankka-sign-in-keys"),
      stateSerializer = Codecs.serializer[SignInKeys]("sign-in-keys"),
      eventSerializer = Codecs.serializer[SignInKeyEvent]("sign-in-key-event")
    ):

  override private[ankka] def platform: Boolean = true

  /** The one entity's id. */
  val Id: String = "keys"

  given Serializer[SignInKey] = Codecs.serializer[SignInKey]("sign-in-key")

  def create(@scala.annotation.unused context: EventSourcedEntityContext) = new SignInKeysEntity

  val add    = command("add")(_.add)
  val remove = command("remove")(_.remove)
  val get    = query("get")(_.get)
