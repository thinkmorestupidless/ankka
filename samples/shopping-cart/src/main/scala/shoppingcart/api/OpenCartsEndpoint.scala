package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.ViewClient
import shoppingcart.application.{CartRow, CartRows}

/**
 * The open carts as they change, for a page that lists them: every open cart, then `caught-up`,
 * then each cart as it is opened or changes and a removal when it is checked out or discarded.
 */
final class OpenCartsEndpoint(views: ViewClient) extends HttpEndpoint("/open-carts"):

  private given JsonValueCodec[CartRow] = Codecs.make[CartRow]

  val acl: Acl = Acl.AllowAll

  // docs:start watch-sse
  sseEvents("/")(() => views.forView(CartRows).watch(CartRows.openCarts).asSse)
  // docs:end watch-sse
