package com.thinkmorestupidless.ankka.cli.local

import com.thinkmorestupidless.ankka.cli.console.LocalSource
import com.thinkmorestupidless.ankka.controlplane.api.ServiceDescriptor
import com.thinkmorestupidless.ankka.proxy.core.{
  Located,
  Locator,
  ProxyEngine,
  ProxySettings,
  Transport
}

import java.io.PrintStream
import java.net.{BindException, InetAddress, ServerSocket, URI}
import java.util.concurrent.CountDownLatch
import scala.jdk.CollectionConverters.*

/**
 * Where a service is on a developer's machine: where `--service` says, else where the local console
 * would find it. The project is ignored, as the service client ignores it locally: on one machine
 * there is one of each name.
 */
final class LocalLocator(named: Map[String, String], source: LocalSource) extends Locator:
  def locate(project: String, service: String): Option[Located] =
    named
      .get(service)
      .orElse(source.httpAddressOf(service))
      .map(url => Located(URI.create(url.stripSuffix("/"))))

/**
 * `ankka local web`: a web-hosted service's process on this machine, behind the same proxy a
 * cluster runs, so its mounts answer at their paths and it calls services by name (feature 021).
 *
 * The engine is `proxy-core`'s, the one the cluster's proxy runs, over plain HTTP on loopback with
 * every caller this machine. A service that cannot be found is not an error at start: a request for
 * it is answered 503, naming it, so a developer can start things in any order.
 */
object LocalWeb:

  private val Loopback = InetAddress.getLoopbackAddress

  final case class Options(
      file: String,
      port: Int,
      services: Map[String, String],
      command: Vector[String]
  )

  /**
   * Runs until the command ends, answering its exit code, or until interrupted when there is none.
   * Problems with the descriptor or the options are 2, as for any misuse; a port in use is 1.
   */
  def run(
      descriptor: ServiceDescriptor,
      options: Options,
      out: PrintStream,
      err: PrintStream,
      source: LocalSource = new LocalSource()
  ): Int =
    val spec = descriptor.service
    if !spec.isWebHosted then
      err.println(
        s"""ankka local web is for a service with web hosting; this one is "${spec.hosting}""""
      )
      return 2
    val descriptorPort = spec.resolvedProcessPort.getOrElse(8080)
    if options.command.isEmpty && options.port == descriptorPort then
      err.println(
        s"--port ${options.port} is the process's own port; choose another, or state processPort " +
          s"in ${options.file}"
      )
      return 2
    // With a command, its port is a free one: the descriptor's is for a cluster, and the default
    // 8080 is where a local installation's gateway listens.
    val processPort = if options.command.nonEmpty then freePort() else descriptorPort

    val settings = ProxySettings(
      project = "local",
      service = descriptor.name,
      port = options.port,
      processPort = processPort,
      probePort = 0,
      callingPort = 0,
      mounts = spec.mounts.map(m => m.path -> m.service)
    )
    val engine = ProxyEngine(
      settings,
      Transport.plain(Loopback),
      probeAddress = Loopback,
      locator = LocalLocator(options.services, source)
    )
    try engine.start()
    catch
      case _: BindException =>
        err.println(s"port ${options.port} is in use; choose another with --port")
        return 1

    val literal = spec.env.flatMap(v => v.value.map(v.name -> _))
    for v <- spec.env; ref <- v.secretKeyRef do
      err.println(
        s"${v.name} is taken from the secret '${ref.name}', which this machine does not have; " +
          "it is left unset"
      )
    for (path, service) <- settings.mounts do out.println(s"mount  $path → $service")
    out.println(s"serving ${descriptor.name} at http://127.0.0.1:${engine.ports.public}")
    out.println(s"PORT=$processPort")
    out.println(s"ANKKA_SERVICES_URL=${engine.callingUrl}")

    try
      if options.command.nonEmpty then
        val builder = new ProcessBuilder(options.command.asJava).inheritIO()
        val env     = builder.environment()
        literal.foreach((k, v) => env.put(k, v))
        env.put("PORT", processPort.toString)
        env.put("ANKKA_SERVICES_URL", engine.callingUrl)
        val process = builder.start()
        val stop    = new Thread(() => process.destroy())
        Runtime.getRuntime.addShutdownHook(stop)
        try process.waitFor()
        catch
          case _: InterruptedException =>
            // Stopped from outside: the process and whatever it started go too (`npm start` runs
            // the server as its child).
            process.descendants().forEach(_.destroy(): Unit)
            process.destroy()
            130
        finally
          try Runtime.getRuntime.removeShutdownHook(stop): Unit
          catch case _: IllegalStateException => ()
      else
        out.println(s"start the process on port $processPort; ctrl-c stops the proxy")
        val stopped = new CountDownLatch(1)
        val hook    = new Thread(() => stopped.countDown())
        Runtime.getRuntime.addShutdownHook(hook)
        try
          stopped.await()
          0
        catch case _: InterruptedException => 130
        finally
          try Runtime.getRuntime.removeShutdownHook(hook): Unit
          catch case _: IllegalStateException => ()
    finally engine.stop()

  private def freePort(): Int =
    val socket = new ServerSocket(0, 0, Loopback)
    try socket.getLocalPort
    finally socket.close()
