package com.thinkmorestupidless.ankka.sidecar

import com.typesafe.config.Config

import scala.concurrent.duration.*
import scala.jdk.DurationConverters.*

/**
 * What the sidecar reads from its environment and configuration.
 *
 * The callback server's bind address is deliberately not a setting: it is loopback, always, so a
 * process in another pod cannot reach it (FR-009). The process address defaults to loopback for the
 * same reason and is overridden only by compose and the Python testkit, where the process is on the
 * host.
 */
final case class Settings(
    processAddress: String,
    callbackPort: Int,
    discoveryTimeout: FiniteDuration,
    discoveryBackoffMax: FiniteDuration,
    commandTimeout: FiniteDuration,
    requestTimeout: FiniteDuration
):
  def processHost: String = processAddress.split(':').head
  def processPort: Int    = processAddress.split(':').last.toInt

object Settings:
  val CallbackBindAddress: String = "127.0.0.1"
  val DefaultProcessAddress       = "127.0.0.1:9010"
  val DefaultCallbackPort         = 9011

  def load(config: Config): Settings =
    val env = sys.env
    Settings(
      processAddress = env.getOrElse("ANKKA_PROCESS_ADDRESS", DefaultProcessAddress),
      callbackPort = env.get("ANKKA_SIDECAR_PORT").map(_.toInt).getOrElse(DefaultCallbackPort),
      discoveryTimeout =
        env.get("ANKKA_SIDECAR_DISCOVERY_TIMEOUT").map(parse).getOrElse(60.seconds),
      discoveryBackoffMax = 10.seconds,
      commandTimeout = config.getDuration("ankka.ask-timeout").toScala,
      requestTimeout =
        if config.hasPath("ankka.http.body-timeout") then
          config.getDuration("ankka.http.body-timeout").toScala
        else config.getDuration("ankka.ask-timeout").toScala
    )

  private def parse(text: String): FiniteDuration =
    val t = text.trim
    if t.endsWith("ms") then t.dropRight(2).trim.toLong.millis
    else if t.endsWith("s") then t.dropRight(1).trim.toLong.seconds
    else if t.endsWith("m") then t.dropRight(1).trim.toLong.minutes
    else t.toLong.seconds
