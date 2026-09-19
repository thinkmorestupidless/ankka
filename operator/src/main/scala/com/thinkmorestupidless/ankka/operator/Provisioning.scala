package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.AnkkaServiceSpec
import com.thinkmorestupidless.ankka.operator.cnpg.DatabaseObservation

/**
 * What the operator should do about one service's database, and what it should report.
 *
 * The reported phase is a property of the case itself (`reportedPhase`), not a value threaded
 * alongside it, so the two can never drift apart — asking "what happened" and "what do we tell an
 * operator" always agree because they are the same match.
 */
enum ProvisioningPlan:
  /**
   * The descriptor supplied its own database. Render nothing — no CNPG objects, no init container.
   */
  case Supplied

  /**
   * Provisioning is under way. `needs*` says which of the four objects still have to be ensured
   * this pass; the others are already present and applied, or not yet reachable because something
   * earlier in the chain (the cluster, then the secret, then the role) is not ready yet.
   */
  case Waiting(
      needsCluster: Boolean,
      needsCredentials: Boolean,
      needsRole: Boolean,
      needsDatabase: Boolean,
      detail: Option[String]
  )

  /** Everything is present and applied. No CNPG action is rendered — this is the steady state. */
  case Ready(recovered: Boolean)

  /** A real, non-transient rejection. Nothing here resolves on its own. */
  case Failed(problems: Vector[String])

  def reportedPhase: String = this match
    case ProvisioningPlan.Supplied               => "Supplied"
    case ProvisioningPlan.Waiting(_, _, _, _, _) => "Waiting"
    case ProvisioningPlan.Ready(true)            => "Recovered"
    case ProvisioningPlan.Ready(false)           => "Provisioned"
    case ProvisioningPlan.Failed(_)              => "Failed"

/**
 * Decides between provisioning, the escape hatch, waiting and failure — total, pure, no clock.
 *
 * Every rule here is proven with no cluster and no Docker: `DatabaseObservation` is the only input,
 * and it is a plain value a test can construct by hand. See
 * `specs/002-cnpg-database-provisioning/contracts/provisioning-rules.md` for the rule table this
 * function implements rule for rule.
 */
object Provisioning:

  /**
   * CNPG messages that resolve on their own and must never be classified as a failure.
   *
   *   - `"is forbidden"` — CNPG's per-cluster secret RBAC allowlist has not caught up yet (research
   *     R5); measured at 20–40 seconds during planning.
   *   - `"does not exist"` — the `Database`'s owner role has not landed yet, because the role and
   *     the database reconcile independently (research R4).
   *
   * Anything else — a quota rejection, an invalid value, a missing storage class — is real and
   * belongs in [[ProvisioningPlan.Failed]].
   */
  private def isTransient(message: String): Boolean =
    message.contains("is forbidden") || message.contains("does not exist")

  def decide(spec: AnkkaServiceSpec, observed: DatabaseObservation): ProvisioningPlan =
    if !spec.provisionDatabase then ProvisioningPlan.Supplied
    else if observed.clusterReadyInstances < 1 then
      // Rules 3 and 4: no cluster yet, or one still starting. Nothing downstream of it can be
      // meaningfully attempted, so ask for everything that is not already there.
      ProvisioningPlan.Waiting(
        needsCluster = true,
        needsCredentials = !observed.secretExists,
        needsRole = !observed.role.exists,
        needsDatabase = !observed.database.exists,
        detail = None
      )
    else if !observed.secretExists then
      // Rule 5: capacity exists, but nothing to authenticate with yet.
      ProvisioningPlan.Waiting(
        needsCluster = false,
        needsCredentials = true,
        needsRole = !observed.role.exists,
        needsDatabase = !observed.database.exists,
        detail = None
      )
    else
      // Capacity and credentials exist. A terminal rejection on either object, if there is one,
      // is checked before anything else — rule 8 must win over rule 6/7's "just keep trying".
      val roleRejected     = objectRejected(observed.role)
      val databaseRejected = objectRejected(observed.database)

      if roleRejected.isDefined then ProvisioningPlan.Failed(roleRejected.toVector)
      else if databaseRejected.isDefined then ProvisioningPlan.Failed(databaseRejected.toVector)
      else if !observed.role.applied || !observed.database.applied then
        // Rules 6 and 7 collapse into one case: an absent object and a not-yet-applied object
        // both need the same action (ensure it), and ensuring an already-created-but-pending
        // object is idempotent, so there is nothing to gain from telling them apart here.
        ProvisioningPlan.Waiting(
          needsCluster = false,
          needsCredentials = false,
          needsRole = !observed.role.applied,
          needsDatabase = !observed.database.applied,
          detail = observed.role.message.orElse(observed.database.message)
        )
      else
        // Rules 9 and 10: everything is present and applied. No action, and the only question
        // left is whether this database is new or a leftover from a prior incarnation.
        ProvisioningPlan.Ready(recovered = observed.recovered)

  /** `Some(reason)` only for a message that is present and not one of the transient ones. */
  private def objectRejected(
      state: com.thinkmorestupidless.ankka.operator.cnpg.CnpgObjectState
  ): Option[String] =
    if state.exists && !state.applied then state.message.filterNot(isTransient)
    else None
