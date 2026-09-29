package com.thinkmorestupidless.ankka.sidecar

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

/**
 * What the sidecar reads from its environment and configuration.
 *
 * Two modes. Given `ANKKA_WASM_MODULE`, the runtime hosts that WebAssembly module in its own
 * process and there is no process to dial: `ANKKA_WASM_INSTANCES` sizes the pool of reused
 * instances that serves commands (default the carrier count), and `ANKKA_WASM_MAX_MEMORY_PAGES`
 * caps each instance's linear memory in 64 KiB pages (default 4096, 256 MiB). Otherwise it is a
 * sidecar, and dials the developer's process at `ANKKA_PROCESS_ADDRESS`.
 *
 * The callback server's bind address is deliberately not a setting: it is loopback, always, so a
 * process in another pod cannot reach it (FR-009). The process address defaults to loopback for the
 * same reason and is overridden only by compose and the Python testkit, where the process is on the
 * host.
 */
final case class Settings(
    processAddress: String,
    callbackPort: Int,
    /**
     * Where the callback server binds. Loopback, always, in a pod — the operator never sets this.
     * The one exception is a sidecar running in a container whose process is on the host (compose,
     * the Python integration testkit): Docker publishes a port to the container's own address, not
     * its loopback, so the container binds all interfaces and the *host* side publishes it on
     * loopback only (`127.0.0.1:9011:9011`).
     */
    callbackBind: String,
    discoveryTimeout: FiniteDuration,
    discoveryBackoffMax: FiniteDuration,
    commandTimeout: FiniteDuration,
    requestTimeout: FiniteDuration,
    wasmModule: Option[java.nio.file.Path] = None,
    wasmInstances: Int = Runtime.getRuntime.availableProcessors,
    wasmMaxMemoryPages: Int = Settings.DefaultWasmMaxMemoryPages
):
  def processHost: String = processAddress.split(':').head
  def processPort: Int    = processAddress.split(':').last.toInt

  /** Whether the runtime hosts a module in-process rather than a process beside it. */
  def isModuleMode: Boolean = wasmModule.isDefined

object Settings:
  val CallbackBindAddress: String = "127.0.0.1"
  val DefaultProcessAddress       = "127.0.0.1:9010"
  val DefaultCallbackPort         = 9011
  val DefaultWasmMaxMemoryPages   = 4096

  def load(config: Config): Settings =
    val env = sys.env
    Settings(
      processAddress = env.getOrElse("ANKKA_PROCESS_ADDRESS", DefaultProcessAddress),
      callbackPort = env.get("ANKKA_SIDECAR_PORT").map(_.toInt).getOrElse(DefaultCallbackPort),
      callbackBind = env.getOrElse("ANKKA_SIDECAR_BIND", CallbackBindAddress),
      discoveryTimeout =
        env.get("ANKKA_SIDECAR_DISCOVERY_TIMEOUT").map(parse).getOrElse(60.seconds),
      discoveryBackoffMax = 10.seconds,
      commandTimeout = config.getDuration("ankka.ask-timeout").toScala,
      requestTimeout =
        if config.hasPath("ankka.http.body-timeout") then
          config.getDuration("ankka.http.body-timeout").toScala
        else config.getDuration("ankka.ask-timeout").toScala,
      wasmModule = env.get("ANKKA_WASM_MODULE").filter(_.nonEmpty).map(java.nio.file.Path.of(_)),
      wasmInstances = env
        .get("ANKKA_WASM_INSTANCES")
        .map(_.toInt)
        .getOrElse(Runtime.getRuntime.availableProcessors)
        .max(1),
      wasmMaxMemoryPages =
        env.get("ANKKA_WASM_MAX_MEMORY_PAGES").map(_.toInt).getOrElse(DefaultWasmMaxMemoryPages)
    )

  private def parse(text: String): FiniteDuration =
    val t = text.trim
    if t.endsWith("ms") then t.dropRight(2).trim.toLong.millis
    else if t.endsWith("s") then t.dropRight(1).trim.toLong.seconds
    else if t.endsWith("m") then t.dropRight(1).trim.toLong.minutes
    else t.toLong.seconds
