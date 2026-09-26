// An agent that answers questions about a cart. It declares its instructions, one tool and one guardrail;
// the sidecar runs the loop — the model, memory, compaction — and asks this process to run the tool and
// check the guardrail. No model key lives here.
import { Agent, command, guardrail, s, stream, tool } from "ankka"
import { ShoppingCartEntity } from "./entity.ts"

// docs:start agent
const CartLookup = s.record("CartLookup", { cartId: s.string })

export class CartAssistant extends Agent {
  static readonly componentId = "assistant"
  static readonly role = "helps shoppers with their carts"

  static readonly tools = {
    lookup: tool("lookup", "Looks up what is in a cart by its id.", CartLookup, (a: CartAssistant, input) => a.lookup(input.cartId)),
  }

  static readonly guardrails = {
    noSecrets: guardrail("no-secrets", (stage, text) => (stage === "output" && text.includes("sk-") ? "a key leaked" : null)),
  }

  static readonly handlers = {
    ask: command("ask", s.string, s.string, (a: CartAssistant, question) => a.describe(question)),
    chat: stream("chat", s.string, (a: CartAssistant, question) => a.describe(question)),
  }

  describe(question: string) {
    return this.effects
      .systemMessage("You help shoppers with their carts. Use the lookup tool before answering about a cart.")
      .userMessage(question)
      .tools("lookup")
      .guardrails("no-secrets")
      .thenReply()
  }

  async lookup(cartId: string): Promise<string> {
    const cart = await this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
    if (cart.items.length === 0) return `cart ${cartId} is empty`
    return cart.items.map((i) => `${i.quantity} x ${i.name}`).join(", ")
  }
}
// docs:end agent
