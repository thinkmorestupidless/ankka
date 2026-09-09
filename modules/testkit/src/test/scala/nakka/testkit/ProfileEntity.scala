package nakka.testkit

import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*

import scala.concurrent.duration.FiniteDuration

/** A key value entity fixture: latest value only, no history. */
final case class Profile(name: String, email: String, logins: Int)

final class ProfileEntity(context: KeyValueEntityContext) extends KeyValueEntity[Profile]:

  private val userId: String = context.entityId

  def emptyState: Profile = Profile("", "", 0)

  def register(request: Profile): Effect[Done] =
    if request.email.isEmpty then effects.error("email is required")
    else if currentState.logins > 0 then
      effects.error(s"'$userId' is already registered", ErrorCode.Conflict)
    else effects.updateState(request).thenReply(_ => Done)

  def rename(name: String): Effect[Profile] =
    if currentState.email.isEmpty then
      effects.error(s"'$userId' is not registered", ErrorCode.NotFound)
    else effects.updateState(currentState.copy(name = name)).thenReplyState

  def recordLogin: Effect[Int] =
    effects.updateState(currentState.copy(logins = currentState.logins + 1)).thenReply(_.logins)

  def expireIn(duration: FiniteDuration): Effect[Done] =
    effects.updateState(currentState).expireAfter(duration).thenReply(_ => Done)

  def get: ReadOnlyEffect[Profile] = effects.reply(currentState)

  def close: Effect[Done] = effects.deleteEntity().thenReply(_ => Done)

object ProfileEntity
    extends KeyValueEntity.Companion[ProfileEntity, Profile](
      componentId = ComponentId("profile"),
      stateSerializer = Codecs.serializer[Profile]("profile")
    ):

  def create(context: KeyValueEntityContext) = new ProfileEntity(context)

  val register    = command("register")(_.register)
  val rename      = command("rename")(_.rename)
  val recordLogin = command("record-login")(_.recordLogin)
  val expireIn    = command("expire-in")(_.expireIn)
  val get         = query("get")(_.get)
  val close       = command("close")(_.close)
