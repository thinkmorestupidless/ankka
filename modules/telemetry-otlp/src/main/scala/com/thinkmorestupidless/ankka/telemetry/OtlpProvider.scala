package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.runtime.{RuntimeExtension, RuntimeExtensionProvider}
import com.typesafe.config.Config
import org.slf4j.LoggerFactory

/**
 * How the runtime finds telemetry export without a service's code naming it: declared in
 * `META-INF/services`, asked once when the service starts.
 *
 * No collector named, nothing: no thread, no connection, not one class of the exporter loaded. A
 * collector named badly is said once and also nothing — the service starts, since telemetry is
 * never a reason for a service not to serve.
 */
final class OtlpProvider extends RuntimeExtensionProvider:
  def extension(config: Config): Option[RuntimeExtension] =
    TelemetrySettings.from(config) match
      case Right(None)           => None
      case Right(Some(settings)) => Some(OtlpTelemetry(settings))
      case Left(problem) =>
        LoggerFactory
          .getLogger(classOf[OtlpProvider])
          .error("telemetry: not exporting, because {}", problem)
        None
