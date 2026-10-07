package com.thinkmorestupidless.ankka.operator.cnpg

import java.time.Instant

/**
 * What one read of the cluster found for a CNPG object CNPG reconciles independently — a `Database`
 * or a `DatabaseRole`.
 *
 * `exists` and `applied` are deliberately separate: an object can exist and not yet be applied
 * (still reconciling, or reporting one of the transient messages research R4/R5 identified), and
 * that distinction is exactly what `Provisioning.decide` needs to tell "still waiting" from
 * "genuinely rejected".
 */
final case class CnpgObjectState(
    exists: Boolean = false,
    applied: Boolean = false,
    message: Option[String] = None
)

object CnpgObjectState:
  val absent: CnpgObjectState = CnpgObjectState()

/**
 * What one read of the cluster found for a service's database, in total. The only input to
 * `Provisioning.decide` — which is what keeps every provisioning rule a unit test with no cluster.
 */
final case class DatabaseObservation(
    /**
     * The project's `Cluster`. Fewer than 1 means capacity is not usable yet, whether because it
     * does not exist or because it is still starting.
     */
    clusterReadyInstances: Int = 0,
    role: CnpgObjectState = CnpgObjectState.absent,
    database: CnpgObjectState = CnpgObjectState.absent,
    /**
     * When the `AnkkaService` resource's *current* incarnation was created, and when its `Database`
     * was — if the database predates the resource, it is a leftover from a prior incarnation of
     * this same service name (FR-026), because nothing this platform does ever deletes one. The
     * `Database`, not the credential Secret, because the operator reads no Secret.
     */
    resourceCreatedAt: Option[Instant] = None,
    databaseCreatedAt: Option[Instant] = None,
    /**
     * The role exists and still logs in by password: provisioned before feature 014, and due the
     * one write that moves it to certificate authentication.
     */
    roleHasPassword: Boolean = false
):
  /** True when the database is older than the resource asking for it. See FR-026. */
  def recovered: Boolean =
    (for
      databaseAt <- databaseCreatedAt
      resourceAt <- resourceCreatedAt
    yield databaseAt.isBefore(resourceAt)).getOrElse(false)

object DatabaseObservation:
  val empty: DatabaseObservation = DatabaseObservation()
