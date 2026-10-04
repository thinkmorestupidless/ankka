package com.thinkmorestupidless.ankka.telemetry

import com.thinkmorestupidless.ankka.runtime.{RecordedSpan, Recorder}
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.`export`.SpanExporter

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters.*

/**
 * One thread that reads recorded spans with a cursor and sends them, so nothing on a handler's
 * thread ever waits on a collector.
 *
 * A batch is committed only once the collector has taken it: one that fails is read again from the
 * trace window when the loop next tries, so a collector that comes back is sent everything the
 * window still holds and nothing is buffered beyond it. While the collector cannot be reached the
 * loop waits longer between tries, doubling up to `maxBackoff`; otherwise it reads every
 * `interval`, and sooner when the unread spans pass half the window, so a busy instance loses fewer
 * to the window's overwrite. There is no sampling beyond that overwrite.
 */
final class ExportLoop(
    cursor: Recorder.Cursor,
    capacity: Int,
    exporter: SpanExporter,
    toData: RecordedSpan => SpanData,
    settings: TelemetrySettings,
    outage: Outage
):

  @volatile private var running = true
  @volatile private var tries   = 0L

  private val thread =
    Thread.ofPlatform().daemon().name("ankka-telemetry").unstarted(() => run())

  /** How many exports the loop has attempted: for a test that asks how often it tried. */
  def attempts: Long = tries

  def start(): ExportLoop =
    thread.start()
    this

  private def run(): Unit =
    var wait = settings.interval
    while running do
      pause(wait, early = !outage.inProgress)
      if running then
        wait =
          if drain(settings.exportTimeout) then settings.interval
          else (wait * 2).min(settings.maxBackoff)

  /** Waits up to `wait`, waking sooner when the window is half unread, unless in an outage. */
  private def pause(wait: FiniteDuration, early: Boolean): Unit =
    val until = System.nanoTime() + wait.toNanos
    try
      while running && System.nanoTime() < until && !(early && cursor.unread > capacity / 2) do
        Thread.sleep(math.min(100L, math.max(1L, (until - System.nanoTime()) / 1_000_000L)))
    catch case _: InterruptedException => ()

  /**
   * Sends batches until the window holds nothing unread, or one fails. True when the collector took
   * everything sent, including the case of nothing to send.
   */
  private def drain(timeout: FiniteDuration): Boolean =
    var sending = true
    var ok      = true
    while sending do
      val batch = cursor.read(settings.batchSize)
      if batch.spans.isEmpty then
        cursor.commit(batch)
        sending = false
      else
        tries += 1
        val result = exporter
          .`export`(batch.spans.map(toData).asJava)
          .join(timeout.toMillis, TimeUnit.MILLISECONDS)
        if result.isSuccess then
          cursor.commit(batch)
          outage.succeeded(cursor.lost)
          sending = batch.spans.size >= settings.batchSize
        else
          outage.failed(reasonOf(result), cursor.lost)
          ok = false
          sending = false
    ok

  private def reasonOf(result: io.opentelemetry.sdk.common.CompletableResultCode): String =
    Option(result.getFailureThrowable)
      .map(t => Option(t.getMessage).getOrElse(t.getClass.getSimpleName))
      .getOrElse(if result.isDone then "the collector refused the export" else "no answer in time")

  /**
   * Stops the loop, then sends what the window holds once more, all within `timeout`, collector or
   * no collector: a stopping instance is never held up by telemetry.
   */
  def stop(timeout: FiniteDuration): Unit =
    val deadline = System.nanoTime() + timeout.toNanos
    running = false
    thread.interrupt()
    thread.join(math.max(1L, timeout.toMillis / 2))
    val left = (deadline - System.nanoTime()) / 1_000_000L
    if left > 0 then drain(FiniteDuration(left, TimeUnit.MILLISECONDS)): Unit
