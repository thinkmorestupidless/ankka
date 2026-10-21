package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.Codecs
import com.thinkmorestupidless.ankka.http.*

import scala.concurrent.duration.*

// docs:start keys
/**
 * Client keys for the cart's tools: one per customer's assistant, issued by the cart's own code,
 * because only the cart knows who its customers are. The key is in the answer to the issue, once;
 * after that the cart lists and revokes keys by id.
 *
 * Open to every caller here, as the rest of the sample is. A real product puts these routes behind
 * its own sign-in, and issues a key for the customer who is signed in.
 */
final class CartKeysEndpoint(keys: ClientKeys) extends HttpEndpoint("/mcp-keys"):

  final case class KeyRequest(
      subject: String,
      scope: Set[String] = Set.empty,
      label: Option[String] = None
  )

  private given JsonValueCodec[KeyRequest]      = Codecs.make
  private given JsonValueCodec[IssuedClientKey] = Codecs.make

  val acl: Acl = Acl.AllowAll

  postBody("/") { (asked: KeyRequest) =>
    keys.issue(asked.subject, Expiry.After(365.days), scope = asked.scope, label = asked.label)
  }

  delete("/{id}") { (id: String) =>
    keys.revoke(id)
    "revoked"
  }
// docs:end keys
