package com.thinkmorestupidless.ankka.mcp.signin

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.http.Principal
import com.thinkmorestupidless.ankka.sdk.*

import java.time.Instant
import scala.concurrent.duration.DurationLong

/** Who a service's issuer signed in, as the sign-in broker keeps them between redirects. */
final case class SignedIn(
    subject: String,
    name: Option[String],
    email: Option[String],
    emailVerified: Boolean,
    roles: Set[String],
    claims: Map[String, String],
    /** The service's word for the issuer that signed them in. */
    issuer: String
)

object SignedIn:
  def of(principal: Principal, issuer: String): SignedIn =
    SignedIn(
      principal.subject,
      principal.name,
      principal.email,
      principal.emailVerified,
      principal.roles,
      principal.claims,
      issuer
    )

/**
 * One thing the sign-in broker keeps (feature 054, R11): a registered client, an authorization in
 * flight, or a refresh token. Every one is short lived and the entity forgets it at its expiry; no
 * secret is kept, only digests.
 */
enum SignInRecord:
  case Absent

  case Client(
      clientId: String,
      redirectUris: Vector[String],
      clientName: Option[String],
      registeredAt: Instant,
      lastUsedAt: Instant
  )

  /**
   * An authorization: pending while the person is at the issuer, then coded once the broker has
   * handed the client a code, whose digest is all that is kept.
   */
  case Grant(
      clientId: String,
      redirectUri: String,
      codeChallenge: String,
      resource: String,
      clientState: Option[String],
      nonce: String,
      /** The PKCE verifier the broker sent the issuer: the broker's, never the client's. */
      issuerVerifier: String,
      issuer: String,
      createdAt: Instant,
      signedIn: Option[SignedIn] = None,
      codeDigest: Option[String] = None
  )

  case RefreshToken(
      clientId: String,
      signedIn: SignedIn,
      resource: String,
      issuedAt: Instant,
      expiresAt: Instant
  )

/** What every write carries: the record, and how long until it is forgotten. */
final case class PutSignInRecord(record: SignInRecord, lifetimeMillis: Long)

/** The person the issuer signed in, and the digest of the code the client is handed. */
final case class CompleteGrant(signedIn: SignedIn, codeDigest: String, lifetimeMillis: Long)

/** The digest of a code a client presents. */
final case class TakeCode(codeDigest: String)

/**
 * The sign-in broker's records, one per id: `client:<id>`, `grant:<state>`, `refresh:<digest>`.
 *
 * Written only by the broker. A record is forgotten at its expiry (the state's own retention, which
 * a read sees at once) and deleted then by `ankka-sign-in-expiry`; a code and a refresh token are
 * taken once, which deletes them.
 */
final class SignInRecordEntity(context: KeyValueEntityContext) extends KeyValueEntity[SignInRecord]:
  import SignInRecord.*

  def emptyState: SignInRecord = Absent

  private def id = context.entityId

  /** A client registered, or registered again; a refresh token written. */
  def put(request: PutSignInRecord): Effect[Done] =
    request.record match
      case Absent => effects.error(s"'$id' cannot be written empty", ErrorCode.BadRequest)
      case record =>
        effects.updateState(record).expireAfter(request.lifetimeMillis.millis).thenReply(_ => Done)

  /** An authorization starts: its id is the broker's state, which only ever starts once. */
  def beginGrant(request: PutSignInRecord): Effect[Done] =
    (currentState, request.record) match
      case (Absent, grant: Grant) if grant.signedIn.isEmpty && grant.codeDigest.isEmpty =>
        effects.updateState(grant).expireAfter(request.lifetimeMillis.millis).thenReply(_ => Done)
      case (Absent, _) =>
        effects.error(s"'$id' must begin as a pending grant", ErrorCode.BadRequest)
      case _ => effects.error(s"'$id' has already begun", ErrorCode.Conflict)

  /** The issuer signed the person in: the grant is coded, once. Answers the grant as it was. */
  def completeGrant(request: CompleteGrant): Effect[SignInRecord] =
    currentState match
      case grant: Grant if grant.codeDigest.isEmpty =>
        val coded =
          grant.copy(signedIn = Some(request.signedIn), codeDigest = Some(request.codeDigest))
        effects.updateState(coded).expireAfter(request.lifetimeMillis.millis).thenReply(_ => grant)
      case _: Grant => effects.error(s"'$id' already has a code", ErrorCode.Conflict)
      case _        => effects.error(s"no grant '$id'", ErrorCode.NotFound)

  /**
   * The client exchanges its code: a coded grant whose digest matches is answered and forgotten.
   */
  def takeCode(request: TakeCode): Effect[SignInRecord] =
    currentState match
      case grant: Grant if grant.codeDigest.contains(request.codeDigest) =>
        effects.deleteEntity().thenReply(_ => grant)
      case _: Grant => effects.error(s"'$id' was not given that code", ErrorCode.BadRequest)
      case _        => effects.error(s"no grant '$id'", ErrorCode.NotFound)

  /** A refresh token is used: answered and forgotten, so it is used once. */
  def takeRefresh: Effect[SignInRecord] =
    currentState match
      case refresh: RefreshToken => effects.deleteEntity().thenReply(_ => refresh)
      case _                     => effects.error(s"no refresh token '$id'", ErrorCode.NotFound)

  def delete: Effect[Done] = effects.deleteEntity().thenReply(_ => Done)

  def get: ReadOnlyEffect[SignInRecord] = effects.reply(currentState)

object SignInRecordEntity
    extends KeyValueEntity.Companion[SignInRecordEntity, SignInRecord](
      componentId = ComponentId("ankka-sign-in-record"),
      stateSerializer = Codecs.serializer[SignInRecord]("sign-in-record")
    ):

  override private[ankka] def platform: Boolean = true

  given Serializer[PutSignInRecord] = Codecs.serializer[PutSignInRecord]("put-sign-in-record")
  given Serializer[CompleteGrant]   = Codecs.serializer[CompleteGrant]("complete-grant")
  given Serializer[TakeCode]        = Codecs.serializer[TakeCode]("take-code")

  def create(context: KeyValueEntityContext) = new SignInRecordEntity(context)

  val put           = command("put")(_.put)
  val beginGrant    = command("begin-grant")(_.beginGrant)
  val completeGrant = command("complete-grant")(_.completeGrant)
  val takeCode      = command("take-code")(_.takeCode)
  val takeRefresh   = command("take-refresh")(_.takeRefresh)
  val delete        = command("delete")(_.delete)
  val get           = query("get")(_.get)

  def clientId(id: String): String      = s"client:$id"
  def grantId(state: String): String    = s"grant:$state"
  def refreshId(digest: String): String = s"refresh:$digest"
