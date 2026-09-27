// The HTTP surface. Your process never binds an HTTP port: the sidecar serves these routes, applies the
// endpoint's access rule, and forwards each request here. `this.client` inside a route is already scoped
// to the request, which is what makes an entity call a child of the request in a trace.
//
// `acl` is required. `Acl.allowAll` is right for a public API and wrong for anything else — and once
// this service is *exposed* (`ankka services expose`), an `allowAll` endpoint is reachable from the
// internet. Exposure changes who can reach an endpoint, not who is allowed to; this line does.
import { Acl, Done, Endpoint, HttpProblem, get, post, s } from "ankka"
import { AddItem, Item, RemoveItem } from "./domain.ts"
import { ItemEntity } from "./itemEntity.ts"
import { ItemRow, ItemRows } from "./itemRows.ts"

export class ItemEndpoint extends Endpoint {
  static readonly prefix = "/items"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    list: get("/", s.list(ItemRow), (ep: ItemEndpoint) => ep.client.views.all(ItemRows.componentId, ItemRow)),
    get: get("/{id}", Item, (ep: ItemEndpoint, req) => ep.item(req.params.id).call(ItemEntity.handlers.getItem).invoke()),
    add: post("/{id}", AddItem, Done, (ep: ItemEndpoint, req, request) => {
      if (request.name === "") throw new HttpProblem(400, "an item needs a name")
      return ep.item(req.params.id).call(ItemEntity.handlers.addItem).invoke(request)
    }),
    remove: post("/{id}/remove", RemoveItem, Done, (ep: ItemEndpoint, req, request) =>
      ep.item(req.params.id).call(ItemEntity.handlers.removeItem).invoke(request),
    ),
  }

  item(id: string) {
    return this.client.of(ItemEntity, id)
  }
}
