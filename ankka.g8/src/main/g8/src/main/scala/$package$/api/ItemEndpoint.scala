package $package$.api

import $package$.application.{AddItem, ItemEntity, ItemRow, ItemRows}
import $package$.domain.Item
import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.thinkmorestupidless.ankka.core.{Codecs, EntityId}
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.SqlSyntax.{jsonText, sql}
import com.thinkmorestupidless.ankka.sdk.ComponentClient

/**
 * The HTTP surface. Handlers run on virtual threads, so calling an entity and waiting for its reply
 * is ordinary sequential code.
 *
 * `acl` is a decision every endpoint makes explicitly. `AllowAll` is right for a public API and
 * wrong for anything else — and once this service is *exposed* (`ankka services expose`), an
 * `AllowAll` endpoint is reachable from the internet. Exposure changes who can reach an endpoint,
 * not who is allowed to; this line does.
 */
final class ItemEndpoint(client: ComponentClient, views: com.thinkmorestupidless.ankka.runtime.ViewClient)
    extends HttpEndpoint("/items"):

  private given JsonValueCodec[Item]    = Codecs.make[Item]
  private given JsonValueCodec[AddItem] = Codecs.make[AddItem]
  private given JsonValueCodec[ItemRow]         = Codecs.make[ItemRow]
  private given JsonValueCodec[Vector[ItemRow]] = Codecs.make[Vector[ItemRow]]

  val acl: Acl = Acl.AllowAll

  private val rows = views.forView(ItemRows)

  get("/") { () =>
    rows.ordered(sql"true", order = jsonText("id"))
  }

  get("/{id}") { (id: String) =>
    item(id).call(ItemEntity.getItem).invoke()
  }

  postBody("/{id}") { (id: String, request: AddItem) =>
    item(id).call(ItemEntity.addItem).invoke(request)
  }

  private def item(id: String) = client.forEventSourcedEntity(EntityId(id))
