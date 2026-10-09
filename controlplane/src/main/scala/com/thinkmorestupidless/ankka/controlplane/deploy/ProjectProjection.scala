package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.ProjectSecretSummary
import com.thinkmorestupidless.ankka.controlplane.domain.{DeclaredBroker, DeclaredTopic}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  ProjectBrokerEntry,
  ProjectSecretEntry,
  ProjectTopicEntry
}

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

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
      secrets: Vector[ProjectSecretSummary] = Vector.empty
  ): AnkkaProjectSpec =
    val sortedSecrets = secrets.sortBy(_.name).map(s => s.copy(entries = s.entries.sorted))
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
      sortedSecrets.toList.map(s =>
        ProjectSecretEntry(s.name, s.entries.toList, s.setAt.fold(0L)(_.toEpochMilli))
      ),
      Option.when(sortedSecrets.nonEmpty)(fingerprint(sortedSecrets))
    )

  /**
   * Of the names, the entries and when each secret was last set: a value set again changes it as
   * surely as an entry added or removed, and nothing of a value goes into it.
   */
  def fingerprint(secrets: Vector[ProjectSecretSummary]): String =
    val text = secrets
      .map(s => s"${s.name}=${s.entries.mkString(",")}@${s.setAt.fold("")(_.toString)}")
      .mkString("\n")
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.getBytes(StandardCharsets.UTF_8))
      .map(b => f"${b & 0xff}%02x")
      .mkString
      .take(32)
