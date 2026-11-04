package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.core.*
import com.thinkmorestupidless.ankka.core.Serializers.given
import com.thinkmorestupidless.ankka.sdk.*

/** A contact card whose email is a personal field of the player the card is for (feature 042). */
final case class ContactCard(email: Option[Personal[String]])

final class ContactCardEntity(context: KeyValueEntityContext) extends KeyValueEntity[ContactCard]:

  private val subject = DataSubject(s"player/${context.entityId}")

  def emptyState: ContactCard = ContactCard(None)

  def setEmail(email: String): Effect[Done] =
    effects.updateState(ContactCard(Some(Personal(subject, email)))).thenReply(_ => Done)

  /** The email, `erased` once the player is, or `none`. */
  def email: ReadOnlyEffect[String] =
    effects.reply(currentState.email.fold("none")(_.orElse("erased")))

object ContactCardEntity
    extends KeyValueEntity.Companion[ContactCardEntity, ContactCard](
      componentId = ComponentId("contact-card"),
      stateSerializer = Codecs.serializer[ContactCard]("contact-card")
    ):
  def create(context: KeyValueEntityContext) = new ContactCardEntity(context)

  val setEmail = command("set-email")(_.setEmail)
  val email    = query("email")(_.email)
