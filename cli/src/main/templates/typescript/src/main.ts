// The whole service definition: `npm start`.
//
// Registration is explicit — nothing is discovered by scanning — so this is also the complete inventory
// of what the service hosts, and a component you forget is simply not there.
//
// `listen()` serves the sidecar protocol on port 9010 on loopback, where the sidecar finds it. Locally
// the sidecar and Postgres come from `docker compose up -d`; in an ankka deployment the platform runs
// the sidecar beside this process and provides the database.
import { Ankka } from "ankka"
import { ItemEndpoint } from "./api.ts"
import { ItemEntity } from "./itemEntity.ts"
import { ItemRows } from "./itemRows.ts"

export const service = () => Ankka.service().register(ItemEntity).register(ItemRows).register(ItemEndpoint)

if (import.meta.main) await service().listen()
