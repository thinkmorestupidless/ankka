// An autonomous agent that answers questions about carts: it is handed a task and works it on its own
// until it completes the task with an Answer that cites its sources. The loop, the model and the task's
// record are the sidecar's; this process runs the tools and checks the rule.

import { AutonomousAgent, accepted, guardrail, rejected, s, taskAcceptance, taskRule, taskType, tool, type Infer } from "ankka"
import { totalQuantity } from "./domain.ts"
import { ShoppingCartEntity } from "./entity.ts"

// docs:start task-type
/** An answer, and what the agent looked at to reach it. */
export const Answer = s.record("Answer", { answer: s.string, sources: s.list(s.string) })
export type Answer = Infer<typeof Answer>

/** "answer" is the wire name, written into every task of this type: renaming this constant changes nothing stored. */
export const ANSWER = taskType("answer", "Answer a question about a shopping cart, citing what you looked at", {
  result: Answer,
  rules: [
    taskRule<Answer>("cites-sources", (answer) =>
      answer.sources.length === 0 ? rejected("say which tools you used in sources") : accepted(),
    ),
  ],
})
// docs:end task-type

// docs:start agent
const CartRef = s.record("CartRef", { cartId: s.string })

/**
 * Answers questions about carts, on its own: it is handed a task and works it, iteration by iteration,
 * until it completes it with an `Answer` that cites its sources.
 *
 * Its tools read the cart entity through the client. A tool may run more than once for one request of
 * the model — after a crash the last recorded request's tools run again — and these only read, so that
 * costs nothing.
 */
export class CartAnswerer extends AutonomousAgent {
  static readonly componentId = "cart-answerer"
  static readonly description = "Answers questions about shopping carts"
  static readonly instructions = "Look carts up rather than guessing. Be brief."

  static readonly tools = {
    cartContents: tool("cart_contents", "Lists what is in a cart, by its id.", CartRef, async (a: CartAnswerer, ref) => {
      const { items } = await a.cart(ref.cartId)
      if (items.length === 0) return `cart ${ref.cartId} is empty`
      return items.map((i) => `${i.quantity} x ${i.name}`).join(", ")
    }),
    cartTotal: tool("cart_total", "Counts the items in a cart, by its id.", CartRef, async (a: CartAnswerer, ref) =>
      `cart ${ref.cartId} holds ${totalQuantity(await a.cart(ref.cartId))} items`,
    ),
  }
  static readonly guardrails = {
    maxInputLength: guardrail("max-input-length", (stage, text) => (stage === "input" && text.length > 2000 ? "too long" : null)),
  }
  static readonly accepts = [taskAcceptance(ANSWER, { maxIterations: 5 })]

  cart(cartId: string) {
    return this.client.of(ShoppingCartEntity, cartId).call(ShoppingCartEntity.handlers.getCart).invoke()
  }
}
// docs:end agent
