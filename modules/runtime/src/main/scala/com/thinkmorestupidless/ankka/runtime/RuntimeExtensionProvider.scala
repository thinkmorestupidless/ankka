package com.thinkmorestupidless.ankka.runtime

import com.typesafe.config.Config

/**
 * A platform extension a module offers by being on a service's classpath, with nothing in the
 * service's code naming it.
 *
 * Components still reach the runtime only by being handed over: nothing scans for them. This is for
 * the platform's own extensions that a service is meant to opt into nothing for — telemetry export
 * is the first — which an embedded service could otherwise only get by adding a line to a `Main`
 * that is the developer's. A module declares its provider in
 * `META-INF/services/com.thinkmorestupidless.ankka.runtime.RuntimeExtensionProvider`, the JDK's own
 * `ServiceLoader` file: a named file a jar declares, not a scan.
 *
 * Asked once, when the service starts, with the service's configuration. A provider that has
 * nothing to do for that configuration returns none and costs nothing after; one that throws fails
 * the start, naming itself, rather than leaving a service running without what it was configured
 * for. Its extension is started before the service's own and stopped after them, so it neither
 * holds a server open while it stops nor misses what the service did last.
 */
trait RuntimeExtensionProvider:
  def extension(config: Config): Option[RuntimeExtension]

object RuntimeExtensionProvider:

  /** Every declared provider's extension for this configuration, in the order they were found. */
  def provided(config: Config): Vector[RuntimeExtension] =
    import scala.jdk.CollectionConverters.*
    java.util.ServiceLoader
      .load(classOf[RuntimeExtensionProvider])
      .iterator()
      .asScala
      .toVector
      .flatMap { provider =>
        try provider.extension(config)
        catch
          case e: Exception =>
            throw IllegalStateException(
              s"the runtime extension provider ${provider.getClass.getName} failed: ${e.getMessage}",
              e
            )
      }
