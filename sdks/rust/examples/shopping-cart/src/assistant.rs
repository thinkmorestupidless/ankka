//! An agent that answers questions about a cart. It declares its instructions, one tool and one
//! guardrail; the runtime runs the loop — the model, memory, compaction — and calls the module to
//! run the tool and check the guardrail. No model key lives here.

use ankka::effects::agent;
use ankka::prelude::*;

use crate::domain::ShoppingCart as Cart;
use crate::entity::ShoppingCart;

#[derive(Debug, Deserialize)]
pub struct CartLookup {
    #[serde(rename = "cartId")]
    pub cart_id: String,
}

// docs:start agent
pub struct CartAssistant;

impl CartAssistant {
    fn ask(question: String, _: &Context) -> AgentEffect {
        agent::system_message(
            "You help shoppers with their carts. Use the lookup tool before answering about a cart.",
        )
        .user_message(question)
        .tools(["lookup"])
        .guardrails(["no-secrets"])
        .then_reply()
    }

    fn lookup(args: CartLookup, ctx: &Context) -> Result<String, String> {
        let cart: Cart = ctx
            .client()
            .invoke(ShoppingCart, &args.cart_id, "get-cart", ())
            .map_err(|e| e.message)?;
        if cart.items.is_empty() {
            return Ok(format!("cart {} is empty", args.cart_id));
        }
        let lines: Vec<String> = cart
            .items
            .iter()
            .map(|i| format!("{} x {}", i.quantity, i.name))
            .collect();
        Ok(lines.join(", "))
    }

    fn no_secrets(stage: Stage, text: &str, _: &Context) -> Result<(), String> {
        if stage == Stage::Output && text.contains("sk-") {
            Err("a key leaked".to_string())
        } else {
            Ok(())
        }
    }
}

impl Agent for CartAssistant {
    const COMPONENT_ID: &'static str = "assistant";

    fn handlers() -> AgentHandlers<CartAssistant> {
        AgentHandlers::new().command("ask", CartAssistant::ask)
    }

    fn tools() -> Tools<CartAssistant> {
        Tools::new().tool(
            "lookup",
            "Looks up what is in a cart by its id.",
            Schema::object().string("cartId", "the cart's id"),
            CartAssistant::lookup,
        )
    }

    fn guardrails() -> Guardrails<CartAssistant> {
        Guardrails::new().guardrail("no-secrets", CartAssistant::no_secrets)
    }
}
// docs:end agent
