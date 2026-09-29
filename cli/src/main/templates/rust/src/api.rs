//! The HTTP surface. The module never binds a port: the runtime serves these routes, applies the
//! endpoint's access rule, and hands each request here. `request.client()` is already scoped to the
//! request, which is what makes an entity call a child of the request in a trace.
//!
//! `acl` is required. `Acl::AllowAll` is right for a public API and wrong for anything else — and
//! once this service is *exposed* (`ankka services expose`), an `AllowAll` endpoint is reachable from
//! the internet. Exposure changes who can reach an endpoint, not who is allowed to; this line does.

use ankka::prelude::*;

use crate::domain::{AddItem, Item, RemoveItem};
use crate::item_entity::ItemEntity;
use crate::item_rows::{ItemRow, ItemRows};

pub struct ItemApi;

impl ItemApi {
    fn list(request: &Request) -> Result<Vec<ItemRow>, HttpProblem> {
        Ok(request.client().query(ItemRows, "all", ())?)
    }

    fn get(request: &Request) -> Result<Item, HttpProblem> {
        Ok(request.client().invoke(ItemEntity, request.path("id"), "get-item", ())?)
    }

    fn add(request: &Request, body: AddItem) -> Result<Done, HttpProblem> {
        if body.name.is_empty() {
            return Err(HttpProblem::new(400, "an item needs a name"));
        }
        Ok(request.client().invoke(ItemEntity, request.path("id"), "add-item", body)?)
    }

    fn remove(request: &Request, body: RemoveItem) -> Result<Done, HttpProblem> {
        Ok(request.client().invoke(ItemEntity, request.path("id"), "remove-item", body)?)
    }
}

impl Endpoint for ItemApi {
    const ENDPOINT_ID: &'static str = "ItemEndpoint";
    const PREFIX: &'static str = "/items";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<ItemApi> {
        Routes::new()
            .get("/", ItemApi::list)
            .get("/{id}", ItemApi::get)
            .post("/{id}", ItemApi::add)
            .post("/{id}/remove", ItemApi::remove)
    }
}
