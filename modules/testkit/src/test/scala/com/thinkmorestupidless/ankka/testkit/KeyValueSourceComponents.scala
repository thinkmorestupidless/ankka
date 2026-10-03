package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.sdk.*

import java.util.concurrent.ConcurrentLinkedQueue

/** A view over a key value entity: one row per profile, gone when the profile is. */
final case class ProfileRow(userId: String, name: String, logins: Int)

final class ProfileRowsView extends View[Profile, ProfileRow]:
  def onChange(profile: Profile): Effect =
    effects.updateRow(ProfileRow(updateContext.subject, profile.name, profile.logins))

object ProfileRows
    extends View.Companion[ProfileRowsView, Profile, ProfileRow](
      componentId = ComponentId("profile-rows"),
      source = ChangeSource.stateOf(ProfileEntity),
      rowSerializer = Codecs.serializer[ProfileRow]("profile-row")
    ):
  def create(ctx: ViewComponentContext) = new ProfileRowsView

/** A consumer over the same entity that records what it was told, and at which revision. */
final class ProfileWatcher extends Consumer[Profile, Nothing]:

  def onMessage(profile: Profile): Effect =
    ProfileWatcher.seen.add(
      ProfileWatcher.Seen(messageContext.subject, messageContext.sequenceNumber, Some(profile))
    ): Unit
    effects.done()

  override def onDelete: Effect =
    ProfileWatcher.seen.add(
      ProfileWatcher.Seen(messageContext.subject, messageContext.sequenceNumber, None)
    ): Unit
    effects.done()

object ProfileWatcher
    extends Consumer.Companion[ProfileWatcher, Profile, Nothing](
      componentId = ComponentId("profile-watcher"),
      source = ChangeSource.stateOf(ProfileEntity)
    ):

  /** `profile` absent: the deletion. */
  final case class Seen(subject: String, sequenceNumber: Long, profile: Option[Profile])

  val seen: ConcurrentLinkedQueue[Seen] = ConcurrentLinkedQueue[Seen]()

  def create(ctx: ConsumerContext) = new ProfileWatcher
