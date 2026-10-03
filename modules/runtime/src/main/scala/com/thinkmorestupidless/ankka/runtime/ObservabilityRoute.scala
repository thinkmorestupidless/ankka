package com.thinkmorestupidless.ankka.runtime

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.typed.scaladsl.adapter.*
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity}
import org.apache.pekko.http.scaladsl.server.Directives.*
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.management.scaladsl.{
  ManagementRouteProvider,
  ManagementRouteProviderSettings
}

/**
 * `GET /ankka/metrics` on the management port: the deployed exposure of the same recorder the local
 * console reads.
 *
 * One recorder, two exposures, chosen by where the process runs — the same shape cluster formation
 * already has. Locally there is no management server at all (it binds a fixed port, which two
 * services on one laptop would fight over), so the console gets its own loopback endpoint; in
 * Kubernetes management is already running, already named `management` in the pod spec, and is
 * where a scraper looks. Registered in `ankka-cluster-kubernetes.conf` beside `ankka-version`.
 *
 * The exposition format is written by hand. `ankka-runtime` is published, so a metrics library here
 * would land in the build of every application using the platform to serve a text format that is a
 * dozen lines of string building. The platform ships no dashboards and no alerts either: an
 * installation brings its own monitoring stack or gets nothing from this.
 */
final class ObservabilityRoute(system: ExtendedActorSystem) extends ManagementRouteProvider:

  // Management hands a classic system; the extension is registered on the typed one. Same system.
  private val typed = system.toTyped

  def routes(settings: ManagementRouteProviderSettings): Route =
    path("ankka" / "metrics") {
      get {
        complete(
          HttpEntity(
            ContentTypes.`text/plain(UTF-8)`,
            Metrics.render(Observability(typed), TopicSources(typed).all)
          )
        )
      }
    }

/** Prometheus text exposition, hand-rolled. */
private[runtime] object Metrics:

  def render(
      observability: Observability,
      topicSources: Vector[TopicSourceStatus] = Vector.empty
  ): String =
    val spans = observability.recorder.snapshot()
    val names = observability.names

    val builder = StringBuilder()

    // A service that has served nothing answers with zeroed series rather than an error or an
    // empty body: a scraper that sees nothing cannot tell "no traffic" from "target broken", and
    // those want different reactions from whoever is on call.
    builder ++= "# HELP ankka_invocations_total Component invocations recorded.\n"
    builder ++= "# TYPE ankka_invocations_total counter\n"
    if spans.isEmpty then builder ++= "ankka_invocations_total 0\n"
    else
      spans
        .groupBy(s =>
          (label(names, s.componentRef), label(names, s.handlerRef), s.outcome.toString)
        )
        .toVector
        .sortBy(_._1)
        .foreach { case ((component, handler, outcome), group) =>
          builder ++= s"""ankka_invocations_total{component="$component",handler="$handler",outcome="$outcome"} ${group.size}\n"""
        }

    builder ++= "# HELP ankka_invocation_duration_seconds_sum Time spent in component handlers.\n"
    builder ++= "# TYPE ankka_invocation_duration_seconds_sum counter\n"
    if spans.isEmpty then builder ++= "ankka_invocation_duration_seconds_sum 0\n"
    else
      spans
        .groupBy(s => (label(names, s.componentRef), label(names, s.handlerRef)))
        .toVector
        .sortBy(_._1)
        .foreach { case ((component, handler), group) =>
          val seconds = group.map(_.durationNanos).sum.toDouble / 1e9
          builder ++= s"""ankka_invocation_duration_seconds_sum{component="$component",handler="$handler"} $seconds\n"""
        }

    // What this window can and cannot say, stated in the output rather than left to be assumed.
    // These are a bounded recent window, not counters since start: a scraper differencing them
    // across intervals would be differencing a ring, and the numbers would be nonsense.
    builder ++= "# HELP ankka_recorder_spans_recorded_total Spans begun since this process started.\n"
    builder ++= "# TYPE ankka_recorder_spans_recorded_total counter\n"
    builder ++= s"ankka_recorder_spans_recorded_total ${observability.recorder.recorded}\n"
    builder ++= "# HELP ankka_recorder_capacity Size of the in-memory span window.\n"
    builder ++= "# TYPE ankka_recorder_capacity gauge\n"
    builder ++= s"ankka_recorder_capacity ${observability.recorder.capacity}\n"

    // Each topic source, from the list the runtime keeps of them: never through the recorder's
    // name table. Bounded, because a component id is declared and each has one group.
    builder ++= "# HELP ankka_topic_source_info A view or consumer reading a topic, and the group " +
      "it reads under.\n"
    builder ++= "# TYPE ankka_topic_source_info gauge\n"
    topicSources.foreach { s =>
      val labels = Vector(
        "kind"      -> s.kindWord,
        "component" -> s.componentId,
        "topic"     -> s.topic,
        "group"     -> s.group,
        "start"     -> s.startFrom.toString,
        "version"   -> s.version.toString
      )
      builder ++= s"ankka_topic_source_info${labelSet(labels)} 1\n"
    }
    builder ++= "# HELP ankka_topic_source_behind 1 when this instance declares a lower version " +
      "of a view than the one recorded, and is not updating it.\n"
    builder ++= "# TYPE ankka_topic_source_behind gauge\n"
    topicSources.filter(_.kind == com.thinkmorestupidless.ankka.core.ComponentKind.View).foreach {
      s =>
        val labels = Vector(
          "component" -> s.componentId,
          "declared"  -> s.version.toString,
          "recorded"  -> s.recordedVersion.getOrElse(s.version).toString
        )
        builder ++= s"ankka_topic_source_behind${labelSet(labels)} ${if s.behind then 1 else 0}\n"
    }

    builder.toString

  private def labelSet(labels: Vector[(String, String)]): String =
    labels
      .map((name, value) =>
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        s"$name=\"$escaped\""
      )
      .mkString("{", ",", "}")

  /**
   * A label value, with the characters Prometheus reserves escaped.
   *
   * An unresolved reference reads as `unknown` rather than an empty string, which would render as
   * `component=""` and be read as "no component" instead of "a component this reader cannot name".
   */
  private def label(names: Names, ref: Int): String =
    names
      .nameOf(ref)
      .getOrElse("unknown")
      .replace("\\", "\\\\")
      .replace("\"", "\\\"")
      .replace("\n", "\\n")
