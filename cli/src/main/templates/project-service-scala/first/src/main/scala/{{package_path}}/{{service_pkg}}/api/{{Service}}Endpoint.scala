package {{package}}.{{service_pkg}}.api

import {{package}}.{{service_pkg}}.application.{AddItem, ItemEntity, ItemRow, ItemRows}
import {{package}}.{{service_pkg}}.domain.Item
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}

/**
 * {{service}}'s HTTP surface. Handlers run on virtual threads, so calling an entity, or another
 * service, and waiting for the answer is ordinary sequential code.
 *
 * `acl` is a decision every endpoint makes explicitly. `AllowAll` is right for a public API and wrong
 * for anything else; once the service is exposed, an `AllowAll` endpoint is reachable from the internet.
 */
final class {{Service}}Endpoint(clients: EndpointClients) extends HttpEndpoint("/{{service}}"):

  private given JsonValueCodec[Item]            = Codecs.make[Item]
  private given JsonValueCodec[AddItem]         = Codecs.make[AddItem]
  private given JsonValueCodec[ItemRow]         = Codecs.make[ItemRow]
  private given JsonValueCodec[Vector[ItemRow]] = Codecs.make[Vector[ItemRow]]

  val acl: Acl = Acl.AllowAll

  private val rows = clients.viewClient.forView(ItemRows)

  get("/") { () =>
    rows.ordered(sql"true", order = jsonText("id"))
  }

  get("/{id}") { (id: String) =>
    item(id).call(ItemEntity.getItem).invoke()
  }

  postBody("/{id}") { (id: String, request: AddItem) =>
    item(id).call(ItemEntity.addItem).invoke(request)
  }

  // A crossing of the project's context map: a route of {{second}}'s endpoint, called by {{service}}.
  // Locally {{second}} is found by its name, from the services the local console lists or from
  // `ankka.local-services`; deployed, by the platform. Unreachable, the call fails and this answers 503.
  get("/{id}/{{second}}") { (id: String) =>
    try clients.services("{{second}}").get[Item](s"/{{second}}/$id")
    catch case e: com.thinkmorestupidless.ankka.sdk.ServiceUnresolvable => throw HttpProblem(503, e.getMessage)
  }

  private def item(id: String) = clients.componentClient.forEventSourcedEntity(EntityId(id))
