package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.domain.{DeclaredBroker, DeclaredTopic, Restore}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  ProjectBrokerEntry,
  ProjectTopicEntry,
  RestoreEntry
}

/**
 * A project's declarations as the `AnkkaProject` the operator reads (feature 027): its topics,
 * sorted by name so the same declarations always project to the same spec, which is what lets an
 * unchanged project perform no write.
 */
object ProjectProjection:

  def spec(
      projectId: String,
      topics: Map[String, DeclaredTopic],
      brokers: Map[String, DeclaredBroker] = Map.empty,
      /** Where the project's new buckets in Google Cloud Storage are made (feature 039). */
      bucketLocation: Option[String] = None,
      /** Every restore asked for (feature 041), in the order they were asked for. */
      restores: Map[String, Restore] = Map.empty,
      /** What the project asks of its database (feature 041); none renders nothing. */
      database: Option[com.thinkmorestupidless.ankka.controlplane.api.DatabaseSetting] = None,
      /**
       * The rehearsals asked for that have not ended: one that has is the project's record only.
       */
      rehearsals: Map[String, com.thinkmorestupidless.ankka.controlplane.domain.Rehearsal] =
        Map.empty,
      /** How many times the project's backup credential was issued again; none renders nothing. */
      backupCredentialGeneration: Int = 0
  ): AnkkaProjectSpec =
    AnkkaProjectSpec(
      projectId,
      topics.toList.sortBy(_._1).map { (name, topic) =>
        ProjectTopicEntry(
          name,
          topic.partitions,
          topic.declaredAt.fold("")(_.toString),
          topic.compacted,
          topic.contract.map(_.name),
          topic.contract.map(_.fingerprint)
        )
      },
      brokers.toList.sortBy(_._1).map { (name, b) =>
        ProjectBrokerEntry(
          name,
          b.bootstrap,
          b.shape,
          b.secretName,
          b.declaredAt.fold("")(_.toString)
        )
      },
      bucketLocation,
      restores = restores.toList
        .sortBy((name, r) => (r.requestedAt.getOrElse(java.time.Instant.EPOCH), name))
        .map((name, r) =>
          RestoreEntry(name, r.line, r.targetTime.toString, r.requestedAt.fold("")(_.toString))
        ),
      rehearsals = rehearsals.toList
        .filter(_._2.outcome.isEmpty)
        .sortBy((name, r) => (r.requestedAt.getOrElse(java.time.Instant.EPOCH), name))
        .map((name, r) =>
          com.thinkmorestupidless.ankka.crd.RehearsalEntry(
            name,
            r.line,
            r.targetTime.toString,
            r.requestedAt.fold("")(_.toString)
          )
        ),
      backups = Option.when(backupCredentialGeneration > 0)(
        com.thinkmorestupidless.ankka.crd.ProjectBackupsSpec(backupCredentialGeneration)
      ),
      database = database.map(d =>
        com.thinkmorestupidless.ankka.crd.ProjectDatabaseSpec(
          d.replicas,
          d.synchronous,
          d.retentionDays,
          d.rehearse
        )
      )
    )
