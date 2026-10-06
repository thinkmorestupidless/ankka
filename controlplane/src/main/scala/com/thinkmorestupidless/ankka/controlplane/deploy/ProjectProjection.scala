package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.domain.DeclaredTopic
import com.thinkmorestupidless.ankka.crd.{AnkkaProjectSpec, ProjectTopicEntry}

/**
 * A project's declarations as the `AnkkaProject` the operator reads (feature 027): its topics,
 * sorted by name so the same declarations always project to the same spec, which is what lets an
 * unchanged project perform no write.
 */
object ProjectProjection:

  def spec(projectId: String, topics: Map[String, DeclaredTopic]): AnkkaProjectSpec =
    AnkkaProjectSpec(
      projectId,
      topics.toList.sortBy(_._1).map { (name, topic) =>
        ProjectTopicEntry(name, topic.partitions, topic.declaredAt.fold("")(_.toString))
      }
    )
