package com.thinkmorestupidless.ankka.operator

import com.thinkmorestupidless.ankka.crd.{
  AnkkaProjectSpec,
  ProjectBrokerEntry,
  ProjectGrantEntry,
  ProjectTopicEntry
}
import scala.jdk.CollectionConverters.*
import io.fabric8.kubernetes.api.model.{
  ConfigMap,
  ConfigMapBuilder,
  ConfigMapVolumeSourceBuilder,
  EnvVar,
  EnvVarBuilder,
  ObjectMetaBuilder,
  Volume,
  VolumeBuilder,
  VolumeMount,
  VolumeMountBuilder
}

/**
 * What the project declares, handed to every service of the project (feature 037, research R3): a
 * `ConfigMap` the project reconciler renders from `AnkkaProject` and every platform container
 * mounts, read by the runtime once at start to check each component's contract and broker. The
 * mount is `optional`, so a project with no declarations yet, or a cluster without the project
 * type, starts services as before; and it is never `subPath`, so a changed declaration reaches the
 * file in place and is seen at the next start, restarting nothing.
 */
object ProjectConfig:

  val Name: String       = "ankka-project"
  val Key: String        = "topics.json"
  val VolumeName: String = "ankka-project"
  val MountPath: String  = "/var/run/ankka/project"
  val EnvVar: String     = "ANKKA_PROJECT_DECLARATIONS"

  /**
   * The project's accepted grants (feature 040), in the same ConfigMap, so they reach every pod
   * through the mount it already has: a new volume, or even a new variable, changes the pod and
   * rolls it, and a grant must not restart one. The runtime finds the file beside the declarations
   * it is already told of, refreshed in place by the kubelet, and re-reads it when its modification
   * time changes.
   */
  val GrantsKey: String = "grants.json"

  /** Where machines' tokens come from (feature 040), as `MachineTokens` in the runtime reads it. */
  val MachinesKey: String = "machines.json"

  def renderMachines(machines: Settings.MachineIssuer): String =
    s"""{"issuer":${quote(machines.issuer)},"jwksUrl":${quote(machines.jwksUrl)}}"""

  def configMap(
      namespace: String,
      spec: AnkkaProjectSpec,
      machines: Option[Settings.MachineIssuer] = None
  ): ConfigMap =
    new ConfigMapBuilder()
      .withMetadata(
        new ObjectMetaBuilder()
          .withNamespace(namespace)
          .withName(Name)
          .withLabels(
            java.util.Map.of(
              "app.kubernetes.io/managed-by",
              "ankka",
              "ankka.thinkmorestupidless.com/project",
              spec.projectId
            )
          )
          .build()
      )
      .withData(
        (Map(Key -> render(spec), GrantsKey -> renderGrants(spec)) ++
          machines.map(m => MachinesKey -> renderMachines(m))).asJava
      )
      .build()

  /**
   * The grants file, as `GrantsFile` in the runtime reads it: every accepted grant of the project,
   * sorted by id so equal specs render equal bytes. A service keeps the entries that name its own
   * routes and methods; the topic and erasure entries are for the platform's components that admit
   * by grant.
   */
  def renderGrants(spec: AnkkaProjectSpec): String =
    val grants = spec.grants.sortBy(_.id).map(grant).mkString(",")
    s"""{"project":${quote(spec.projectId)},"grants":[$grants]}"""

  private def grant(g: ProjectGrantEntry): String =
    val optional = Vector(
      "service"    -> g.service,
      "httpMethod" -> g.httpMethod,
      "path"       -> g.path,
      "method"     -> g.method,
      "topic"      -> g.topic,
      "right"      -> g.right
    ).collect { case (field, Some(value)) => s""","$field":${quote(value)}""" }.mkString
    val decrypt = if g.decrypt then ""","decrypt":true""" else ""
    s"""{"id":${quote(g.id)},"grantee":${quote(g.grantee)},"kind":${quote(
        g.kind
      )}$optional$decrypt}"""

  def volume(): Volume =
    new VolumeBuilder()
      .withName(VolumeName)
      .withConfigMap(new ConfigMapVolumeSourceBuilder().withName(Name).withOptional(true).build())
      .build()

  def mount(): VolumeMount =
    new VolumeMountBuilder()
      .withName(VolumeName)
      .withMountPath(MountPath)
      .withReadOnly(true)
      .build()

  def environment(): EnvVar =
    new EnvVarBuilder().withName(EnvVar).withValue(s"$MountPath/$Key").build()

  /**
   * The file, as `ProjectDeclarations` in the runtime reads it. Sorted, so equal specs render equal
   * bytes.
   */
  def render(spec: AnkkaProjectSpec): String =
    val topics  = spec.topics.sortBy(_.name).map(topic).mkString(",")
    val brokers = spec.brokers.sortBy(_.name).map(broker).mkString(",")
    s"""{"project":${quote(spec.projectId)},"topics":[$topics],"brokers":[$brokers]}"""

  private def topic(t: ProjectTopicEntry): String =
    val contract = (t.contractName, t.contractFingerprint) match
      case (Some(name), Some(fingerprint)) =>
        s""","contract":{"name":${quote(name)},"fingerprint":${quote(fingerprint)}}"""
      case _ => ""
    s"""{"name":${quote(
        t.name
      )},"partitions":${t.partitions},"compacted":${t.compacted}$contract}"""

  private def broker(b: ProjectBrokerEntry): String =
    s"""{"name":${quote(b.name)},"bootstrap":${quote(b.bootstrap)},"shape":${quote(b.shape)}}"""

  private def quote(text: String): String =
    val out = new StringBuilder("\"")
    text.foreach {
      case '"'           => out.append("\\\"")
      case '\\'          => out.append("\\\\")
      case '\n'          => out.append("\\n")
      case '\r'          => out.append("\\r")
      case '\t'          => out.append("\\t")
      case c if c < 0x20 => out.append(f"\\u${c.toInt}%04x")
      case c             => out.append(c)
    }
    out.append('"').toString
