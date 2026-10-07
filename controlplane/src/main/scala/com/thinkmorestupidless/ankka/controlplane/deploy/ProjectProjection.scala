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
      }
    )
