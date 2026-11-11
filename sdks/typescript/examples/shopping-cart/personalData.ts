// docs:start marking
import { personal, s, subject, type Infer } from "ankka"
// docs:end marking
import { Acl, Ankka, ComponentClient, Endpoint, post } from "ankka"

// The personal data guide's samples (docs/build/personal-data.md): a personal field marked on an event,
// an agent session and an instance started with a data subject, the erasure handler, and an erasure
// asked from an endpoint.

// docs:start marking

export const PlayerRegistered = s.record("PlayerRegistered", {
  playerId: s.string,
  email: s.personal(s.string),
  currency: s.string,
})
export type PlayerRegistered = Infer<typeof PlayerRegistered>

export function registered(id: string, email: string): PlayerRegistered {
  return { playerId: id, email: personal(subject(`player/${id}`), email), currency: "EUR" }
}
// docs:end marking

/** Asks a helper agent about a player's card, and starts a resolver's instance on the case, both about the player. */
export async function askAboutAPlayer(client: ComponentClient): Promise<void> {
  // docs:start agent-subject
  const calls = client.forAgent("helper", "support-91", { subject: "player/8c1f" })
  await calls.call("ask", s.string, s.string).invoke("my card was declined")

  const resolver = client.forAutonomousAgent("resolver", "case-91", { subject: "player/8c1f" })
  // docs:end agent-subject
  await resolver.assign("t-1")
}

/** A players service whose erasure handler erases the subject's objects in the `kyc` bucket. */
export function playersService(client: ComponentClient) {
  // docs:start erasure-handler
  const service = Ankka.service({ client }).erasureHandler(async (ctx, subject) => {
    const removed = await ctx.storage.bucket("kyc").erase(subject)
    return { objectsErased: removed.count, finality: removed.finality }
  })
  // docs:end erasure-handler
  return service
}

/** Closes an account: its player's data is to be forgotten once the retention period ends. */
export class AccountsEndpoint extends Endpoint {
  static readonly prefix = "/accounts"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    close: post("/close", s.string, (ep: AccountsEndpoint) => ep.close()),
  }

  async close(): Promise<string> {
    // docs:start ask-erasure
    const asked = await this.erasures.ask({ subject: "player/8c1f", reason: "aml-retention", correlation: "closure-4411" })
    // docs:end ask-erasure
    return asked.state
  }
}
