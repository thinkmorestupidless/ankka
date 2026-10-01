//! The cart's HTTP API: the Scala and Python carts' routes — all but the streaming chat, since a
//! module answers every request whole.

use ankka::prelude::*;

use crate::cart_rows::{CartRow, CartRows};
use crate::checkout_log::{CheckoutLog, CheckoutRecord};
use crate::checkout_workflow::{Checkout, CheckoutWorkflow};
use crate::domain::{LineItem, ShoppingCart as Cart};
use crate::entity::ShoppingCart;

// docs:start endpoint
/// `/carts/{cartId}`, `/total`, `/items`, `/items/{productId}` and `/checkout`, and `DELETE
/// /carts/{cartId}`, open to anyone.
pub struct CartApi;

impl CartApi {
    fn get_cart(request: &Request) -> Result<Cart, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "get-cart", ())?)
    }

    fn total(request: &Request) -> Result<i32, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "total-quantity", ())?)
    }

    fn add_item(request: &Request, item: LineItem) -> Result<Done, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "add-item", item)?)
    }

    fn remove_item(request: &Request) -> Result<Done, HttpProblem> {
        let (cart_id, product_id) = (request.path("cartId"), request.path("productId"));
        let removed = request.client().invoke(
            ShoppingCart,
            cart_id,
            "remove-item",
            product_id.to_string(),
        )?;
        Ok(removed)
    }

    fn checkout(request: &Request, (): ()) -> Result<Cart, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "checkout", ())?)
    }

    fn discard(request: &Request) -> Result<Done, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(ShoppingCart, cart_id, "discard", ())?)
    }
}
// docs:end endpoint

impl CartApi {
    // docs:start problem
    fn row(request: &Request) -> Result<CartRow, HttpProblem> {
        let cart_id = request.path("cartId");
        let rows: Vec<CartRow> = request
            .client()
            .query(CartRows, "by-id", cart_id.to_string())?;
        rows.into_iter()
            .next()
            .ok_or_else(|| HttpProblem::new(404, format!("no row for cart '{cart_id}'")))
    }
    // docs:end problem

    fn rows(request: &Request) -> Result<Vec<CartRow>, HttpProblem> {
        Ok(request.client().query(CartRows, "all", ())?)
    }

    // docs:start start-workflow
    /// `mode` is the body: `ok`, `fail` or `pause`.
    fn start_checkout(request: &Request, mode: String) -> Result<Done, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(CheckoutWorkflow, cart_id, "start", mode)?)
    }
    // docs:end start-workflow

    fn checkout_status(request: &Request) -> Result<Checkout, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request
            .client()
            .invoke(CheckoutWorkflow, cart_id, "status", ())?)
    }

    /// The assistant answers whole: a module cannot stream a reply.
    fn ask(request: &Request, question: String) -> Result<String, HttpProblem> {
        let session = request.path("session");
        Ok(request.client().invoke_by_name(
            ankka::proto::Kind::Agent,
            "assistant",
            session,
            "ask",
            question,
        )?)
    }

    fn checkout_log(request: &Request) -> Result<CheckoutRecord, HttpProblem> {
        let cart_id = request.path("cartId");
        Ok(request.client().invoke(CheckoutLog, cart_id, "get", ())?)
    }
}

// docs:start routes
impl Endpoint for CartApi {
    const ENDPOINT_ID: &'static str = "ShoppingCartEndpoint";
    const PREFIX: &'static str = "/carts";

    fn acl() -> Acl {
        Acl::AllowAll
    }

    fn routes() -> Routes<CartApi> {
        Routes::new()
            .get("/{cartId}", CartApi::get_cart)
            .get("/{cartId}/total", CartApi::total)
            .post("/{cartId}/items", CartApi::add_item)
            .delete("/{cartId}/items/{productId}", CartApi::remove_item)
            .post("/{cartId}/checkout", CartApi::checkout)
            .delete("/{cartId}", CartApi::discard)
            // A literal beside a parameter: the router must prefer it over `/{cartId}`.
            .get("/awkward", |_: &Request| Ok("literal".to_string()))
            .get("/{cartId}/rows", CartApi::row)
            .get("/rows", CartApi::rows)
            .post("/{cartId}/checkouts", CartApi::start_checkout)
            .get("/{cartId}/checkouts", CartApi::checkout_status)
            .post("/ask/{session}", CartApi::ask)
            .get("/{cartId}/checkout-log", CartApi::checkout_log)
    }
}
// docs:end routes
