package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.ViewClient
import shoppingcart.application.{CheckoutSeen, CheckoutsSeen}

/** The checkouts this service has read from the topic, by cart. */
final class CheckoutsSeenEndpoint(views: ViewClient) extends HttpEndpoint("/checkouts-seen"):

  private given JsonValueCodec[CheckoutSeen] = Codecs.make[CheckoutSeen]

  val acl: Acl = Acl.AllowAll

  get("/{cartId}") { (cartId: String) =>
    views
      .forView(CheckoutsSeen)
      .get(cartId)
      .getOrElse(throw CommandError(s"no checkout of '$cartId' has been read", ErrorCode.NotFound))
  }
