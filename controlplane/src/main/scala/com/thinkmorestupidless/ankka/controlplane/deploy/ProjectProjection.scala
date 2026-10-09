package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.domain.{DeclaredBroker, DeclaredTopic}
import com.thinkmorestupidless.ankka.crd.{AnkkaProjectSpec, ProjectBrokerEntry, ProjectTopicEntry}

/**
 * A project's declarations as the `AnkkaProject` the operator reads (feature 027): its topics,
 * sorted by name so the same declarations always project to the same spec, which is what lets an
 * unchanged project perform no write.
 */
object ProjectProjection:

  def spec(
      projectId: String,
      topics: Map[String, DeclaredTopic],
      brokers: Map[String, DeclaredBroker] = Map.empty
  ): AnkkaProjectSpec =
    AnkkaProjectSpec(
      projectId,
      topics.toList.sortBy(_._1).map { (name, topic) =>
        val settings = topic.settings
        // Feature 043: every setting as the topic's Kafka configuration says it, so the operator
        // states each one on the topic. A topic with no settings yet projects as it did before.
        ProjectTopicEntry(
          name,
          topic.partitions,
          topic.declaredAt.fold("")(_.toString),
          settings.fold(topic.compacted)(_.compacted),
          topic.contract.map(_.name),
          topic.contract.map(_.fingerprint),
          retentionMs = settings.map(_.retention.kafka),
          retentionBytes = settings.map(_.retentionSize.kafka),
          cleanupPolicy = settings.map(_.cleanup.text),
          deleteRetentionMs = settings.map(_.tombstoneWindow.millis),
          minCompactionLagMs = settings.map(_.minCompactionLag.millis),
          maxCompactionLagMs = settings.map(_.maxCompactionLag.kafka),
          replicas = settings.flatMap(_.copies),
          minInsyncReplicas = settings.flatMap(_.minInSync)
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
      }
    )
