package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.MethodName
import com.thinkmorestupidless.ankka.sdk.{
  EventSourcedEntityDescriptor,
  HandlerBinding,
  KeyValueEntityDescriptor,
  WorkflowDescriptor
}

/**
 * The two documents a service's instance describes itself with: what it is (`service`) and what it
 * is made of and does (`topology`). Rendered here, once, for both ways they are read — the local
 * console over loopback, and the installation's control plane over the observe port — so the two
 * exposures cannot describe the same service differently.
 */
private[runtime] final class ObservabilityDocuments(running: AnkkaService, serviceName: String)
    extends ObserveServer.Documents:

  private val instanceId = ProcessHandle.current().pid().toString
  private val startedAt  = java.time.Instant.now().toString

  /** Identity and inventory: what the console's Services and Components panels are built from. */
  def service(): String =
    // Its gRPC address, when it has one, is beside the HTTP one.
    val grpc = running.grpcAddresses.headOption
      .map(a => s""","grpc":{"address":${Json.str(a)}}""")
      .getOrElse("")
    val instances = running.boundAddresses match
      case addresses if addresses.nonEmpty =>
        addresses
          .map(a =>
            s"""{"id":${Json.str(instanceId)},"startedAt":${Json.str(startedAt)},""" +
              s""""http":{"address":${Json.str(a)}}$grpc}"""
          )
          .mkString("[", ",", "]")
      // A service with `"http": false` serves nothing addressable over HTTP. Say so, rather than
      // offer an invoke panel that cannot work.
      case _ =>
        s"""[{"id":${Json.str(instanceId)},"startedAt":${Json.str(startedAt)}$grpc}]"""

    val components = running.registry.components
      .map { descriptor =>
        // Only the queries. A command is not offered, because the console will not run one.
        val queries = handlersOf(descriptor.componentId.toString).collect {
          case (name, binding) if binding.readOnly => Json.str(name.toString)
        }
        s"""{"kind":${Json.str(descriptor.kind.toString)},""" +
          s""""id":${Json.str(descriptor.componentId.toString)},""" +
          s""""sharded":${descriptor.kind.sharded},""" +
          s""""queries":${queries.mkString("[", ",", "]")}}"""
      }
      .mkString("[", ",", "]")

    // The routes the console turns into a form. Reported by the extensions that serve them,
    // because `runtime` knows nothing about HTTP and should not start now.
    val routes = running.routes
      .map(r =>
        s"""{"method":${Json.str(r.method)},"path":${Json.str(r.path)},""" +
          s""""streaming":${r.streaming},"endpoint":${Json.str(r.endpoint)}}"""
      )
      .mkString("[", ",", "]")

    // Each topic source: where it reads, under which group, from where, at which version.
    val topicSources = TopicSources(running.system).all
      .map { s =>
        s"""{"kind":${Json.str(s.kindWord)},"component":${Json.str(s.componentId)},""" +
          s""""topic":${Json.str(s.topic)},"group":${Json.str(s.group)},""" +
          s""""start":${Json.str(s.startFrom.toString)},"version":${s.version},""" +
          s""""recordedVersion":${s.recordedVersion.fold("null")(_.toString)},""" +
          s""""behind":${s.behind}}"""
      }
      .mkString("[", ",", "]")

    s"""{"name":${Json.str(serviceName)},""" +
      s""""runtime":${Json.str(com.thinkmorestupidless.ankka.core.BuildInfo.version)},""" +
      s""""instances":$instances,"components":$components,"routes":$routes,""" +
      s""""topicSources":$topicSources}"""

  /** What the service is made of and how the parts are connected. */
  def topology(): String = TopologyJson.of(running, serviceName, instanceId, startedAt)

  /**
   * The handlers a component declares, whatever kind of entity it is.
   *
   * Only the sharded kinds have handlers addressable by entity id; everything else answers with
   * none, so the console offers nothing to click rather than a route that cannot work.
   */
  def handlersOf(component: String): Map[MethodName, HandlerBinding[?]] =
    running.registry.components.find(_.componentId.toString == component) match
      case Some(d: EventSourcedEntityDescriptor[?, ?, ?]) => d.handlers
      case Some(d: KeyValueEntityDescriptor[?, ?])        => d.handlers
      case Some(d: WorkflowDescriptor[?, ?])              => d.handlers
      case _                                              => Map.empty
