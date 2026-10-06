package com.thinkmorestupidless.ankka.operator

import io.fabric8.kubernetes.client.KubernetesClient
import org.testcontainers.images.builder.Transferable
import org.testcontainers.k3s.K3sContainer

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.concurrent.duration.*

/**
 * The platform's collector in a test cluster, from the `otel-collector` component's own manifests,
 * and what it received, read back from its log. The suites use it rather than the telemetry store:
 * it is a small image on a node already holding several JVMs, and its log says every span's ids,
 * parent, name, kind and attributes.
 */
object CollectorStack:

  /** What a workload is told to export to. */
  val endpoint: String = "http://otel-collector.ankka-telemetry.svc.cluster.local:4318"

  def install(k3s: K3sContainer, k8s: KubernetesClient): Unit =
    val component = PkiStack.repoRoot.resolve("kustomization/components/otel-collector")
    for file <- Vector(
        "namespace.yaml",
        "config.yaml",
        "deployment.yaml",
        "service.yaml",
        "network-policy.yaml"
      )
    do
      val target = s"/tmp/otel-collector-$file"
      k3s.copyFileToContainer(
        Transferable.of(Files.readString(component.resolve(file)).getBytes(StandardCharsets.UTF_8)),
        target
      )
      PkiStack.kubectl(k3s, "apply", "--server-side", "--force-conflicts", "-f", target): Unit
    val deadline = 180.seconds.fromNow
    while !ready(k8s) && deadline.hasTimeLeft() do Thread.sleep(1000)
    if !ready(k8s) then
      throw new AssertionError("the platform's collector was not ready within 180s")

  private def ready(k8s: KubernetesClient): Boolean =
    Option(k8s.apps().deployments().inNamespace("ankka-telemetry").withName("otel-collector").get())
      .flatMap(d => Option(d.getStatus))
      .flatMap(s => Option(s.getReadyReplicas))
      .exists(_.intValue > 0)

  /** Every span the collector has printed so far. */
  def spans(k3s: K3sContainer): Vector[CollectorLog.Span] =
    CollectorLog.spans(
      PkiStack.kubectl(k3s, "-n", "ankka-telemetry", "logs", "deploy/otel-collector", "--tail=-1")
    )

  /** The spans the collector has printed that satisfy `found`, waited for; fails naming `what`. */
  def waitFor(k3s: K3sContainer, what: String, within: FiniteDuration = 120.seconds)(
      found: Vector[CollectorLog.Span] => Boolean
  ): Vector[CollectorLog.Span] =
    val deadline = within.fromNow
    var seen     = spans(k3s)
    while !found(seen) && deadline.hasTimeLeft() do
      Thread.sleep(2000)
      seen = spans(k3s)
    if !found(seen) then
      throw new AssertionError(
        s"the collector never received $what: ${seen.map(s => s"${s.service} ${s.name}")}"
      )
    seen

/** The `debug` exporter's detailed output, read back into spans. */
object CollectorLog:

  final case class Span(
      resource: Map[String, String],
      traceId: String,
      parentId: String,
      spanId: String,
      name: String,
      kind: String,
      attributes: Map[String, String],
      start: String = "",
      end: String = ""
  ):
    def service: String  = resource.getOrElse("service.name", "")
    def instance: String = resource.getOrElse("service.instance.id", "")

    /** Nanoseconds since the epoch, from the debug exporter's `Start time`; 0 when absent. */
    def startNanos: Long     = CollectorLog.nanos(start)
    def durationMillis: Long = (CollectorLog.nanos(end) - startNanos) / 1000000

  private val Attribute = """\s+-> ([^:]+): \w+\((.*)\)""".r
  private val Field     = """\s{4}([A-Za-z ]+?)\s*: ?(.*)""".r

  /** The debug exporter prints `2026-10-06 10:21:47.423988 +0000 UTC`; always UTC. */
  private[operator] def nanos(time: String): Long =
    time.split(' ') match
      case Array(date, clock, _*) =>
        scala.util
          .Try(java.time.LocalDateTime.parse(s"${date}T$clock"))
          .map { t =>
            val i = t.toInstant(java.time.ZoneOffset.UTC)
            i.getEpochSecond * 1000000000L + i.getNano
          }
          .getOrElse(0L)
      case _ => 0L

  def spans(log: String): Vector[Span] =
    val lines    = log.linesIterator.toVector
    val result   = Vector.newBuilder[Span]
    var resource = Map.empty[String, String]
    var at       = 0
    while at < lines.size do
      val line = lines(at)
      if line.contains("ResourceSpans #") then
        resource = Map.empty
        at += 1
        if at < lines.size && lines(at).startsWith("Resource SchemaURL") then at += 1
        if at < lines.size && lines(at).startsWith("Resource attributes:") then
          at += 1
          while at < lines.size && Attribute.matches(lines(at)) do
            val Attribute(k, v) = lines(at): @unchecked
            resource += k -> v
            at += 1
      else if line.startsWith("Span #") then
        at += 1
        var fields = Map.empty[String, String]
        while at < lines.size && lines(at).startsWith("    ") do
          lines(at) match
            case Field(k, v) => fields += k.trim -> v.trim
            case _           => ()
          at += 1
        var attributes = Map.empty[String, String]
        if at < lines.size && lines(at).startsWith("Attributes:") then
          at += 1
          while at < lines.size && Attribute.matches(lines(at)) do
            val Attribute(k, v) = lines(at): @unchecked
            attributes += k -> v
            at += 1
        result += Span(
          resource,
          fields.getOrElse("Trace ID", ""),
          fields.getOrElse("Parent ID", ""),
          fields.getOrElse("ID", ""),
          fields.getOrElse("Name", ""),
          fields.getOrElse("Kind", ""),
          attributes,
          fields.getOrElse("Start time", ""),
          fields.getOrElse("End time", "")
        )
      else at += 1
    result.result()
