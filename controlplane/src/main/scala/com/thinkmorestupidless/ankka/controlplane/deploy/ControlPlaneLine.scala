package com.thinkmorestupidless.ankka.controlplane.deploy

import com.thinkmorestupidless.ankka.crd.LineStatus

import scala.jdk.CollectionConverters.*

/**
 * The control plane's own line of history (feature 041), from the `status` of its archiver's
 * ObjectStore and of its Cluster, read as untyped resources. Untyped, those come back as whatever
 * the client's mapper makes of JSON: Java maps and lists from fabric8's own, Scala ones from a
 * mapper with Jackson's Scala module, which the platform registers for its own types. A read that
 * matched only Java's answered nothing at all, every time, in the cluster suite.
 */
object ControlPlaneLine:

  val Namespace: String = "ankka-controlplane"
  val Cluster: String   = "ankka-controlplane-db"

  private def map(value: Any): Option[collection.Map[String, Any]] =
    value match
      case m: java.util.Map[?, ?]  => Some(m.asScala.map((k, v) => k.toString -> v))
      case m: collection.Map[?, ?] => Some(m.map((k, v) => k.toString -> v))
      case _                       => None

  private def list(value: Any): Vector[Any] =
    value match
      case l: java.util.List[?] => l.asScala.toVector
      case l: collection.Seq[?] => l.toVector
      case _                    => Vector.empty

  private def string(m: collection.Map[String, Any], key: String): Option[String] =
    m.get(key).filter(_ != null).map(_.toString).filter(_.nonEmpty)

  /** From the two statuses, or nothing when neither says anything of the line. */
  def from(objectStoreStatus: Any, clusterStatus: Any): Option[LineStatus] =
    val window = map(objectStoreStatus)
      .flatMap(_.get("serverRecoveryWindow"))
      .flatMap(map)
      .flatMap(_.get(Cluster))
      .flatMap(map)
    val archiving = map(clusterStatus)
      .flatMap(_.get("conditions"))
      .map(list)
      .getOrElse(Vector.empty)
      .flatMap(map)
      .find(c => string(c, "type").contains("ContinuousArchiving"))
    val last    = window.flatMap(string(_, "lastSuccessfulBackupTime"))
    val failing = archiving.filter(c => string(c, "status").contains("False"))
    val (phase, reason) =
      if failing.isDefined then
        "Failing" -> failing
          .flatMap(string(_, "message"))
          .orElse(Some("the archive is not working"))
      else if last.isEmpty then "NotBackedUp" -> Some("waiting for the first base backup")
      else "BackingUp"                        -> None
    Option.when(window.isDefined || archiving.isDefined)(
      LineStatus(
        line = Cluster,
        cluster = Cluster,
        phase = phase,
        lastBaseBackup = last,
        firstRestorable = window.flatMap(string(_, "firstRecoverabilityPoint")),
        failing = reason
      )
    )
