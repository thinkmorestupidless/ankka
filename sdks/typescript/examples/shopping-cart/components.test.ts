// The photos, the credentials, the transfer and the onboarding, with no sidecar.
import { test, describe } from "node:test"
import assert from "node:assert/strict"
import { InMemoryBuckets, InMemorySecrets, done, type ComponentClient, type Secrets } from "ankka"
import { EndpointTestKit, EventSourcedTestKit, WorkflowTestKit } from "ankka/testkit"
import { Listing, PhotosEndpoint } from "./photos.ts"
import { ChargeWorkflow, ProviderCredentialsEndpoint } from "./credentials.ts"
import { Balances, TransferWorkflow } from "./transfer.ts"
import { OnboardingWorkflow } from "./onboarding.ts"

describe("the photos", () => {
  test("the entity keeps the name, never the bytes", async () => {
    const kit = EventSourcedTestKit.of(Listing, "l1")
    await kit.call(Listing.handlers.recordPhoto, "front.jpg")
    assert.deepEqual((await kit.call(Listing.handlers.photos)).reply, { photos: ["front.jpg"] })
  })

  // docs:start kit
  test("the endpoint keeps, describes, lists, signs, addresses and removes objects in the kit's store", async () => {
    const recorded: string[] = []
    const record = async (name: string) => (recorded.push(name), done)
    const client = { withMetadata: () => client, of: () => ({ call: () => ({ invoke: record }) }) }
    const store = new InMemoryBuckets({ buckets: { photos: "public" } })
    const kit = EndpointTestKit.of(PhotosEndpoint, client as unknown as ComponentClient, store)

    const png = { name: "a/b.png", contentType: "image/png", text: "png" }
    assert.equal((await kit.post("/storage/objects?bucket=photos", png)).status, 204)
    assert.equal((await kit.get("/storage/objects?bucket=photos&name=a/b.png")).text(), "png")
    const described = (await kit.get("/storage/stat?bucket=photos&name=a/b.png")).json() as typeof png & { length: number }
    assert.deepEqual([described.contentType, described.length], ["image/png", 3])
    const listed = (await kit.get("/storage/list?bucket=photos&prefix=a/")).json() as { objects: { name: string }[] }
    assert.deepEqual(listed.objects.map((o) => o.name), ["a/b.png"])
    assert.ok((await kit.get("/storage/url?bucket=photos&name=a/b.png")).text().endsWith("/a/b.png"))
    assert.match((await kit.get("/storage/signed?name=a/b.png&access=keep")).text(), /^http.*a\/b\.png/)
    // The test reads what the service kept from the same store.
    assert.equal((await kit.storage.bucket("photos").stat("a/b.png"))?.contentType, "image/png")

    const jpg = { name: "l1/front.jpg", contentType: "image/jpeg", text: "jpg" }
    assert.equal((await kit.post("/storage/listings/l1/photos", jpg)).status, 204)
    assert.deepEqual(recorded, ["l1/front.jpg"])
    assert.equal((await kit.storage.default.get("l1/front.jpg"))?.contentType, "image/jpeg")

    assert.equal((await kit.delete("/storage/objects?bucket=photos&name=a/b.png")).status, 204)
    assert.equal((await kit.get("/storage/objects?bucket=photos&name=a/b.png")).status, 404)
  })
  // docs:end kit
})

describe("the provider's credential", () => {
  const store = new InMemorySecrets()

  test("the endpoint keeps, reads and removes a value in the secret store", async () => {
    class Keeping extends ProviderCredentialsEndpoint {
      override get secrets(): Secrets {
        return store
      }
    }
    const kit = EndpointTestKit.of(Keeping)
    assert.equal((await kit.post("/credentials/", { name: "provider/acme", value: "sk-1" })).status, 204)
    assert.equal((await kit.get("/credentials/?name=provider/acme")).text(), "sk-1")
    assert.equal(store.values.get("provider/acme"), "sk-1")
    assert.equal((await kit.get("/credentials/?name=provider/other")).status, 404)
    assert.equal((await kit.delete("/credentials/?name=provider/acme")).status, 204)
    assert.equal(store.values.has("provider/acme"), false)
  })

  // docs:start unit-test
  test("the step reads the credential and records only that it charged", async () => {
    await store.put("provider/acme", "sk-live-1234")
    class Charging extends ChargeWorkflow {
      override get secrets(): Secrets {
        return store
      }
    }
    const kit = WorkflowTestKit.of(Charging, "ch-1")
    await kit.call(ChargeWorkflow.handlers.start, { provider: "acme", amount: 10 })
    const progress = await kit.runUntilEnd()
    assert.equal(progress.ended, true)
    assert.deepEqual(kit.state, { provider: "acme", credentialLength: 12, status: "charged" })
    assert.equal(JSON.stringify(kit.state).includes("sk-live"), false)
  })
  // docs:end unit-test
})

describe("the transfer", () => {
  /** Balances played by a double: every call is recorded, and a withdrawal from `broke` is refused. */
  function balances() {
    const calls: string[] = []
    const client = {
      withMetadata: () => client,
      of: (_cls: unknown, id: string) => ({
        call: (h: { name: string }) => ({
          invoke: async (amount: number) => {
            calls.push(`${h.name} ${amount} ${id}`)
            if (id === "broke" && h.name === "deposit") throw new Error("the deposit failed")
          },
        }),
      }),
    }
    return { client: client as unknown as ComponentClient, calls }
  }

  test("the balance refuses to go below zero", async () => {
    const kit = EventSourcedTestKit.of(Balances, "a")
    await kit.call(Balances.handlers.deposit, 10)
    assert.equal((await kit.call(Balances.handlers.withdraw, 11)).error?.message, "insufficient funds: 10 < 11")
    assert.equal((await kit.call(Balances.handlers.balance)).reply, 10)
  })

  test("withdraws, then deposits, and ends completed", async () => {
    const { client, calls } = balances()
    const kit = WorkflowTestKit.of(TransferWorkflow, "t1", client)
    const started = await kit.call(TransferWorkflow.handlers.start, { from: "a", to: "b", amount: 10 })
    assert.equal(started.transition?.step, "withdraw")
    assert.equal((await kit.call(TransferWorkflow.handlers.start, { from: "a", to: "b", amount: 10 })).error?.code, "CONFLICT")
    await kit.runUntilEnd()
    assert.deepEqual(calls, ["withdraw 10 a", "deposit 10 b"])
    assert.equal(kit.state.status, "completed")
  })

  test("a deposit that fails is compensated from the state, with no input", async () => {
    const { client, calls } = balances()
    const kit = WorkflowTestKit.of(TransferWorkflow, "t2", client)
    await kit.call(TransferWorkflow.handlers.start, { from: "a", to: "broke", amount: 10 })
    await kit.runStep(TransferWorkflow.steps.withdraw, { from: "a", to: "broke", amount: 10 })
    await assert.rejects(kit.runStep(TransferWorkflow.steps.deposit, { from: "a", to: "broke", amount: 10 }), /the deposit failed/)
    // What the sidecar does after the retry, as `settings` declares: the failover step.
    const ended = await kit.runStep(TransferWorkflow.steps.compensate)
    assert.equal(ended.next.kind, "end")
    assert.deepEqual(calls, ["withdraw 10 a", "deposit 10 broke", "deposit 10 a"])
    assert.equal(kit.state.status, "compensated")
  })

  test("zero is refused before anything starts", async () => {
    const kit = WorkflowTestKit.of(TransferWorkflow, "t3")
    assert.equal((await kit.call(TransferWorkflow.handlers.start, { from: "a", to: "b", amount: 0 })).error?.message, "transfer amount must be greater than zero")
  })
})

describe("one workflow waiting for another", () => {
  function kycDeciding(decision: string) {
    const client = {
      withMetadata: () => client,
      of: () => ({ call: () => ({ thenAwaitEnd: () => ({ invoke: async (applicant: string) => ({ applicant, decision }) }) }) }),
    }
    return client as unknown as ComponentClient
  }

  test("the verify step waits for the KYC workflow's end and goes where its decision says", async () => {
    const approved = WorkflowTestKit.of(OnboardingWorkflow, "o1", kycDeciding("approved"))
    await approved.call(OnboardingWorkflow.handlers.start, "ada")
    await approved.runUntilEnd()
    assert.equal(approved.state.status, "welcomed")

    const declined = WorkflowTestKit.of(OnboardingWorkflow, "o2", kycDeciding("declined"))
    await declined.call(OnboardingWorkflow.handlers.start, "fraudster")
    await declined.runUntilEnd()
    assert.equal(declined.state.status, "declined")
  })
})
