// The first service's entry point: `node main.ts`, beside `docker compose --profile polyglot up -d`.
// docs:start main
import { Ankka } from "ankka"
import { ShoppingCartEntity } from "./cart.ts"
import { ShoppingCartEndpoint } from "./api.ts"

export const service = () => Ankka.service().register(ShoppingCartEntity).register(ShoppingCartEndpoint)

if (import.meta.main) await service().listen()
// docs:end main
