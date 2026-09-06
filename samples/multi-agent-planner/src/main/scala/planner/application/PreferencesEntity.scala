package planner.application

import nakka.core.*
import nakka.core.Serializers.given
import nakka.sdk.*
import planner.domain.Preferences

/** A user's stated preferences, so an agent can be given context it did not ask for. */
final class PreferencesEntity(context: KeyValueEntityContext) extends KeyValueEntity[Preferences]:

  private val userId: String = context.entityId

  def emptyState: Preferences = Preferences.empty(userId)

  def set(preferences: Preferences): Effect[Done] =
    if preferences.maxBudget < 0 then effects.error("budget cannot be negative")
    else effects.updateState(preferences.copy(userId = userId)).thenReply(_ => Done)

  def addLike(activity: String): Effect[Preferences] =
    if activity.isBlank then effects.error("an activity needs a name")
    else
      effects
        .updateState(currentState.copy(likes = (currentState.likes :+ activity).distinct))
        .thenReplyState

  def get: ReadOnlyEffect[Preferences] = effects.reply(currentState)

object PreferencesEntity
    extends KeyValueEntity.Companion[PreferencesEntity, Preferences](
      componentId = ComponentId("preferences"),
      stateSerializer = Codecs.serializer[Preferences]("preferences")
    ):
  def create(context: KeyValueEntityContext) = new PreferencesEntity(context)

  val set     = command("set")(_.set)
  val addLike = command("add-like")(_.addLike)
  val get     = query("get")(_.get)
