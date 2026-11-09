// A provider's credential: entered through an endpoint into the secret store, and read by a workflow
// step when it needs it. The value reaches no journal, snapshot or view.
import { Acl, Done, done, Endpoint, HttpProblem, Workflow, command, del, get, jsonCodec, post, query, s, step, type Infer } from "ankka"

// docs:start endpoint
const KeepSecret = s.record("KeepSecret", { name: s.string, value: s.string })

/**
 * Where a person gives the service a credential it must keep. The value goes to the secret store
 * and nowhere else: not to an entity, so it reaches no journal, snapshot or view.
 */
export class ProviderCredentialsEndpoint extends Endpoint {
  static readonly prefix = "/credentials"
  static readonly acl = Acl.allowAll

  static readonly routes = {
    keep: post("/", KeepSecret, Done, async (ep: ProviderCredentialsEndpoint, _req, request) => {
      await ep.secrets.put(request.name, request.value)
      return done
    }),
    read: get("/", s.string, async (ep: ProviderCredentialsEndpoint, req) => {
      const name = req.query.get("name") ?? ""
      const value = await ep.secrets.get(name)
      if (value === undefined) throw new HttpProblem(404, `no secret '${name}'`)
      return value
    }),
    remove: del("/", Done, async (ep: ProviderCredentialsEndpoint, req) => {
      await ep.secrets.delete(req.query.get("name") ?? "")
      return done
    }),
  }
}
// docs:end endpoint

// docs:start workflow
export const Charge = s.record("Charge", { provider: s.string, amount: s.int })
export type Charge = Infer<typeof Charge>
export const ChargeState = s.record("ChargeState", { provider: s.string, credentialLength: s.int, status: s.string })
export type ChargeState = Infer<typeof ChargeState>

/**
 * Charges a customer through a payment provider. The step reads the provider's credential from the
 * secret store when it needs it, and records only that it charged.
 */
export class ChargeWorkflow extends Workflow<ChargeState> {
  static readonly componentId = "charge"
  static readonly state = jsonCodec(ChargeState, "charge-state")

  static readonly handlers = {
    start: command("start", Charge, Done, (w: ChargeWorkflow, charge) =>
      w.effects
        .updateState({ provider: charge.provider, credentialLength: 0, status: "started" })
        .thenTransitionTo("charge", charge)
        .thenReply(() => done),
    ),
    status: query("status", ChargeState, (w: ChargeWorkflow) => w.effects.reply(w.state)),
  }

  static readonly steps = {
    charge: step("charge", Charge, (w: ChargeWorkflow, charge) => w.charge(charge)),
  }

  emptyState(): ChargeState {
    return { provider: "", credentialLength: 0, status: "not-started" }
  }

  async charge(charge: Charge) {
    const credential = await this.secrets.get(`provider/${charge.provider}`)
    if (credential === undefined) throw new Error(`no credential for ${charge.provider}`)
    // Calling the provider with `credential` goes here. The state records that it was charged, and
    // never the credential.
    return this.stepEffects.updateState({ ...this.state, credentialLength: credential.length, status: "charged" }).thenEnd()
  }
}
// docs:end workflow
