// Run the cart: `npm run example`, beside `docker compose --profile polyglot up -d` in the repository root.
// docs:start main
import { Ankka } from "ankka"
import { ShoppingCartEntity } from "./entity.ts"
import { CartRows } from "./cartRows.ts"
import { CheckoutNotifier } from "./checkoutNotifier.ts"
import { CartGraph } from "./cartGraph.ts"
import { CartContentsGraph } from "./cartContentsGraph.ts"
import { CheckoutLog } from "./checkoutLog.ts"
import { CheckoutWorkflow } from "./checkoutWorkflow.ts"
import { CartAssistant } from "./assistant.ts"
import { ShoppingCartEndpoint } from "./endpoint.ts"
import { CallingEndpoint } from "./calling.ts"

/**
 * The whole inventory: registration is explicit, so nothing is discovered by scanning. The cart's graph
 * is published to a topic, so it is registered only where there is a broker to publish to; without one
 * the sidecar refuses a component that needs it.
 */
export function service() {
  const cart = Ankka.service()
    .register(ShoppingCartEntity)
    .register(CartRows)
    .register(CheckoutNotifier)
    .register(CheckoutLog)
    .register(CheckoutWorkflow)
    .register(CartAssistant)
    .register(ShoppingCartEndpoint)
    .register(CallingEndpoint)
  return process.env.ANKKA_KAFKA_BOOTSTRAP_SERVERS ? cart.register(CartGraph).register(CartContentsGraph) : cart
}

if (import.meta.main) {
  await service().listen()
}
// docs:end main
