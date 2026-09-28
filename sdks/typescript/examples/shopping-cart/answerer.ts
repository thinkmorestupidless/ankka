// An autonomous agent that answers questions about carts: it is handed a task and works it on its own
// until it completes the task with an Answer that cites its sources. The loop, the model and the task's
// record are the sidecar's; this process runs the tools and checks the rule.

import { AutonomousAgent, accepted, rejected, s, taskAcceptance, taskRule, taskType, tool, type Infer } from "ankka"

export const Answer = s.record("Answer", { answer: s.string, sources: s.list(s.string) })
export type Answer = Infer<typeof Answer>

/** "answer" is the wire name, written into every task of this type. */
export const ANSWER = taskType("answer", "Answer a question about a shopping cart, citing what you looked at", {
  result: Answer,
  rules: [taskRule<Answer>("cites-sources", (a) => (a.sources.length > 0 ? accepted() : rejected("say which tools you used in sources")))],
})

const CartRef = s.record("CartRef", { cartId: s.string })
const LineItem = s.record("LineItem", { productId: s.string, name: s.string, quantity: s.int })
const Cart = s.record("ShoppingCart", { cartId: s.string, items: s.list(LineItem), checkedOut: s.boolean })

export class CartAnswerer extends AutonomousAgent {
  static readonly componentId = "cart-answerer"
  static readonly description = "Answers questions about shopping carts"
  static readonly instructions = "Look carts up rather than guessing. Be brief."
  static readonly tools = {
    cartTotal: tool("cart_total", "Counts the items in a cart.", CartRef, async (a: CartAnswerer, ref) => {
      const cart = await a.client.forEventSourcedEntity("shopping-cart", ref.cartId).call("get-cart", undefined, Cart).invoke()
      return `cart ${ref.cartId} holds ${cart.items.reduce((n, i) => n + i.quantity, 0)} items`
    }),
  }
  static readonly accepts = [taskAcceptance(ANSWER, { maxIterations: 5 })]
}
