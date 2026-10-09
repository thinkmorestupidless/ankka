package shoppingcart.api

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, Done}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.sdk.SecretStore

/**
 * A service secret the service keeps, and whether it holds one (feature 038).
 *
 * Not part of the cart's domain: it is here so the platform's own suites have a real service whose
 * reads of a secret leave a record, in a cluster as on a laptop. It admits only this service's own
 * instances, and never answers a value: a read answers only that something is kept.
 */
final class SecretsEndpoint(secrets: SecretStore) extends HttpEndpoint("/secrets"):

  val acl: Acl = Acl.allowCallers(Callers.self)

  putBody("/{name}") { (name: String, request: SecretsEndpoint.Keep) =>
    secrets.put(name, request.value)
    Done: Done
  }

  get("/{name}") { (name: String) =>
    secrets.get(name).map(_ => "kept").getOrElse(throw HttpProblem.notFound(s"no secret '$name'"))
  }

  delete("/{name}") { (name: String) =>
    secrets.delete(name)
    Done: Done
  }

object SecretsEndpoint:

  final case class Keep(value: String)

  given JsonValueCodec[Keep] = Codecs.make[Keep]
