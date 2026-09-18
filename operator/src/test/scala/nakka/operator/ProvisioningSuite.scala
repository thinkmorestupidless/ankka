package nakka.operator

import nakka.crd.NakkaServiceSpec
import nakka.operator.cnpg.{CnpgObjectState, DatabaseObservation}

import java.time.Instant

/**
 * Every rule in `contracts/provisioning-rules.md`, proven with no cluster.
 *
 * `Provisioning.decide` takes `DatabaseObservation` as a plain value, so every case here is
 * constructed by hand — no fake, no seam, just data (see tasks.md's note on T019).
 */
class ProvisioningSuite extends munit.FunSuite:

  private val spec = NakkaServiceSpec(projectId = "checkout", serviceName = "cart", generation = 1L)

  test("rule 1: the escape hatch decides Supplied regardless of what is observed") {
    // Even an observation that would otherwise decide Failed must not override the escape hatch.
    val plan = Provisioning.decide(
      spec.copy(provisionDatabase = false),
      DatabaseObservation(role =
        CnpgObjectState(exists = true, applied = false, message = Some("boom"))
      )
    )
    assertEquals(plan, ProvisioningPlan.Supplied)
    assertEquals(plan.reportedPhase, "Supplied")
  }

  test("rule 3: no cluster yet asks for everything") {
    val plan = Provisioning.decide(spec, DatabaseObservation.empty)
    assertEquals(
      plan,
      ProvisioningPlan.Waiting(
        needsCluster = true,
        needsCredentials = true,
        needsRole = true,
        needsDatabase = true,
        detail = None
      )
    )
    assertEquals(plan.reportedPhase, "Waiting")
  }

  test("rule 4: a cluster that exists but is not ready yet is the same as no cluster") {
    val plan = Provisioning.decide(spec, DatabaseObservation(clusterReadyInstances = 0))
    assertEquals(plan, ProvisioningPlan.Waiting(true, true, true, true, None))
  }

  test("rule 5: capacity ready, no credentials yet") {
    val plan = Provisioning.decide(spec, DatabaseObservation(clusterReadyInstances = 1))
    assertEquals(plan, ProvisioningPlan.Waiting(false, true, true, true, None))
  }

  test("rule 6: capacity and credentials exist, role and database not created yet") {
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(clusterReadyInstances = 1, secretExists = true)
    )
    assertEquals(plan, ProvisioningPlan.Waiting(false, false, true, true, None))
  }

  test("rule 7a: the role reports the transient 'forbidden' message — waiting, not failed") {
    // Research R5: CNPG's per-cluster secret RBAC allowlist has not caught up yet.
    val message = "secrets \"cart-db\" is forbidden: User \"...\" cannot get resource \"secrets\""
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role = CnpgObjectState(exists = true, applied = false, message = Some(message))
      )
    )
    assertEquals(plan, ProvisioningPlan.Waiting(false, false, true, true, Some(message)))
    assertEquals(plan.reportedPhase, "Waiting")
  }

  test("rule 7b: the database reports 'role does not exist' — waiting, not failed") {
    // Research R4: the role and the database reconcile independently and the database can lose
    // the race.
    val message = "while creating database \"cart\": ERROR: role \"cart\" does not exist"
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role = CnpgObjectState(exists = true, applied = true),
        database = CnpgObjectState(exists = true, applied = false, message = Some(message))
      )
    )
    assertEquals(plan, ProvisioningPlan.Waiting(false, false, false, true, Some(message)))
  }

  test("rule 8a: a real rejection on the role is Failed") {
    val message = "ERROR: quota exceeded for role creation"
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role = CnpgObjectState(exists = true, applied = false, message = Some(message))
      )
    )
    assertEquals(plan, ProvisioningPlan.Failed(Vector(message)))
    assertEquals(plan.reportedPhase, "Failed")
  }

  test("rule 8b: a real rejection on the database is Failed") {
    val message = "ERROR: could not extend file: No space left on device"
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role = CnpgObjectState(exists = true, applied = true),
        database = CnpgObjectState(exists = true, applied = false, message = Some(message))
      )
    )
    assertEquals(plan, ProvisioningPlan.Failed(Vector(message)))
  }

  test("rule 8 wins over rule 7 — a role rejection is checked before a database wait state") {
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role =
          CnpgObjectState(exists = true, applied = false, message = Some("ERROR: real failure")),
        database = CnpgObjectState(exists = false)
      )
    )
    assert(plan.isInstanceOf[ProvisioningPlan.Failed], s"expected Failed, got $plan")
  }

  test("rule 10: everything applied, freshly provisioned — Provisioned, not Recovered") {
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role = CnpgObjectState(exists = true, applied = true),
        database = CnpgObjectState(exists = true, applied = true),
        resourceCreatedAt = Some(Instant.parse("2026-09-17T10:00:00Z")),
        secretCreatedAt = Some(Instant.parse("2026-09-17T10:00:05Z")) // after the resource
      )
    )
    assertEquals(plan, ProvisioningPlan.Ready(recovered = false))
    assertEquals(plan.reportedPhase, "Provisioned")
  }

  test("rule 9: everything applied, the secret predates this resource — Recovered") {
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role = CnpgObjectState(exists = true, applied = true),
        database = CnpgObjectState(exists = true, applied = true),
        resourceCreatedAt = Some(Instant.parse("2026-09-17T10:00:00Z")),
        secretCreatedAt = Some(Instant.parse("2026-09-10T08:00:00Z")) // well before the resource
      )
    )
    assertEquals(plan, ProvisioningPlan.Ready(recovered = true))
    assertEquals(plan.reportedPhase, "Recovered")
  }

  test("no timestamps at all defaults to not-recovered rather than throwing") {
    val plan = Provisioning.decide(
      spec,
      DatabaseObservation(
        clusterReadyInstances = 1,
        secretExists = true,
        role = CnpgObjectState(exists = true, applied = true),
        database = CnpgObjectState(exists = true, applied = true)
      )
    )
    assertEquals(plan, ProvisioningPlan.Ready(recovered = false))
  }

  test("idempotence: NothingToDo/Ready is the only plan that implies zero further writes") {
    // A steady-state service must decide Ready and nothing else — asserted by construction here;
    // the write-count assertion against a real target belongs in the cluster suite (T028/US1).
    val steady = DatabaseObservation(
      clusterReadyInstances = 1,
      secretExists = true,
      role = CnpgObjectState(exists = true, applied = true),
      database = CnpgObjectState(exists = true, applied = true)
    )
    val plan1 = Provisioning.decide(spec, steady)
    val plan2 = Provisioning.decide(spec, steady)
    assertEquals(plan1, plan2)
    assert(plan1.isInstanceOf[ProvisioningPlan.Ready])
  }
