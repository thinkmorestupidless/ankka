package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.core.BuildInfo
import com.thinkmorestupidless.ankka.runtime.RotatingTls
import com.typesafe.config.Config
import io.opentelemetry.api.common.{AttributeKey, Attributes}
import io.opentelemetry.sdk.resources.Resource

import java.nio.file.Paths
import scala.concurrent.duration.DurationInt
import scala.util.Try

/**
 * Who an instance's telemetry says it is from: the service, its project, the instance and the
 * platform's version.
 *
 * On the platform an instance's own certificate says which service and project it is
 * (`ankka://<project>/<service>`) — what every other workload identifies it by, read the same way —
 * so no variable has to be rendered for it and none can disagree with it. Without a certificate (a
 * developer's machine, a test) the configuration says, and failing that the actor system's name.
 */
final case class Identity(service: String, project: Option[String], instance: String):

  def resource: Resource =
    val builder = Attributes
      .builder()
      .put(Identity.ServiceName, service)
      .put(Identity.InstanceId, instance)
      .put(Identity.RuntimeVersion, BuildInfo.version)
    project.foreach { p =>
      builder.put(Identity.ServiceNamespace, p)
      builder.put(Identity.Project, p)
    }
    Resource.create(builder.build())

object Identity:
  val ServiceName: AttributeKey[String]      = AttributeKey.stringKey("service.name")
  val ServiceNamespace: AttributeKey[String] = AttributeKey.stringKey("service.namespace")
  val InstanceId: AttributeKey[String]       = AttributeKey.stringKey("service.instance.id")
  val Project: AttributeKey[String]          = AttributeKey.stringKey("ankka.project")
  val RuntimeVersion: AttributeKey[String]   = AttributeKey.stringKey("ankka.runtime.version")

  def of(config: Config, systemName: String, settings: TelemetrySettings): Identity =
    val certified = certificateIdentity(config)
    Identity(
      service = certified.map(_.service).orElse(settings.serviceName).getOrElse(systemName),
      project = certified.map(_.project).orElse(settings.project),
      instance = Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse(systemName)
    )

  private def certificateIdentity(config: Config): Option[RotatingTls.Identity] =
    val directory = config.getString("ankka.tls.service-directory")
    if directory.isEmpty then None
    else Try(RotatingTls(Paths.get(directory), 1.minute).identity).toOption.flatten
