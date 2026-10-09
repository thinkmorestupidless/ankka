package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.controlplane.api.{GrantState, GrantTarget}
import com.thinkmorestupidless.ankka.controlplane.domain.{DeclaredBroker, DeclaredTopic, Grant}
import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  ProjectBrokerEntry,
  ProjectGrantEntry,
  ProjectTopicEntry
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
      grants: Iterable[Grant] = Nil
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
      // Accepted grants only (feature 040): a pending or ended grant is not in the cluster at all,
      // so nothing there can open what an organization has not agreed to.
      grants.toList.filter(_.state == GrantState.Accepted).sortBy(_.id).map(entry)
    )

  def entry(grant: Grant): ProjectGrantEntry =
    val t = grant.target
    ProjectGrantEntry(
      grant.id,
      grant.grantee.text,
      t.kind,
      service = t.service,
      httpMethod = t.method.filter(_ => t.kind == GrantTarget.Route),
      path = t.path,
      method = t.method.filter(_ => t.kind == GrantTarget.Method),
      topic = t.topic,
      right = t.right,
      decrypt = t.decrypt,
      grantedAt = grant.granted.at.fold("")(_.toString)
    )
