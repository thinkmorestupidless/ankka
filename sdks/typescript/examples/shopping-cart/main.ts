// Run the cart: `npm run example`, beside `docker compose --profile polyglot up -d` in the repository root.
// docs:start main
import { Ankka } from "ankka"
import { ShoppingCartEntity } from "./entity.ts"
import { CartRows } from "./cartRows.ts"
import { CheckoutNotifier } from "./checkoutNotifier.ts"
import { CheckoutLog } from "./checkoutLog.ts"
import { CheckoutWorkflow } from "./checkoutWorkflow.ts"
import { CartAssistant } from "./assistant.ts"
import { ShoppingCartEndpoint } from "./endpoint.ts"

/** The whole inventory: registration is explicit, so nothing is discovered by scanning. */
export function service() {
  return Ankka.service()
    .register(ShoppingCartEntity)
    .register(CartRows)
    .register(CheckoutNotifier)
    .register(CheckoutLog)
    .register(CheckoutWorkflow)
    .register(CartAssistant)
    .register(ShoppingCartEndpoint)
}

if (import.meta.main) {
  await service().listen()
}
// docs:end main
