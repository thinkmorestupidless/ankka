package main

import (
	"context"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ItemEndpoint is the HTTP surface. The process never binds an HTTP port: the sidecar serves these routes,
// applies the endpoint's access rule, and forwards each request here. The context a route is handed
// carries the request, which is what makes an entity call a child of the request in a trace.
//
// The access rule is required. AllowAll is right for a public ItemEndpoint and wrong for anything else, and
// once this service is exposed (`ankka services expose`), an AllowAll endpoint is reachable from the
// internet. Exposure changes who can reach an endpoint, not who is allowed to; this line does.
var ItemEndpoint = ankka.NewEndpoint("/items", ankka.AllowAll(),
	ankka.Get("/", func(ctx context.Context, _ *ankka.Request) ([]ItemRow, error) {
		return ankka.QueryView[ItemRow](ctx, ItemRowsID, ankka.All())
	}),
	ankka.Get("/{id}", func(ctx context.Context, req *ankka.Request) (Item, error) {
		return ankka.Invoke[Item](ctx, ItemEntityID, req.Param("id"), "get-item", ankka.Unit{})
	}),
	ankka.Post("/{id}", func(ctx context.Context, req *ankka.Request, body AddItem) (ankka.Done, error) {
		if body.Name == "" {
			return ankka.Done{}, &ankka.HttpProblem{Status: 400, Message: "an item needs a name"}
		}
		return ankka.Invoke[ankka.Done](ctx, ItemEntityID, req.Param("id"), "add-item", body)
	}),
	ankka.Post("/{id}/remove", func(ctx context.Context, req *ankka.Request, body RemoveItem) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, ItemEntityID, req.Param("id"), "remove-item", body)
	}),
)

// Service is the whole service definition. Registration is explicit, nothing is found by scanning,
// so this is also the complete inventory of what the service hosts: a component left out is simply
// not there.
func service() *ankka.Service { return ankka.NewService(ItemEntity, ItemRows, ItemEndpoint) }
