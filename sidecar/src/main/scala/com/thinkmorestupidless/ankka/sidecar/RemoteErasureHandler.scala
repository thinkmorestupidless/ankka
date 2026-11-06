package com.thinkmorestupidless.ankka.sidecar

import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.runtime.Trace
import com.thinkmorestupidless.ankka.runtime.remote.{Conversation, RemoteErasureCall}
import com.thinkmorestupidless.ankka.sdk.{ErasureHandler, ErasureReport, Finality}

import scala.concurrent.Await
import scala.concurrent.duration.*

/**
 * The process's erasure handler as the runtime runs any other (feature 042, protocol 1.23): on the
 * one instance holding the handler's singleton, for each application and re-run. A handler that
 * threw, or answered a finality that is not one, throws here, so the run is recorded as failed with
 * why and run again on the next pass.
 */
object RemoteErasureHandler:

  def apply(conversation: Conversation, timeout: FiniteDuration): ErasureHandler =
    ErasureHandler { ctx => subject =>
      // The handler's metadata, as anything the runtime asks a process to do on a handler's behalf
      // carries (sidecar.md): the trace and the caller current where the handler runs, so a call
      // the process's handler makes is traced and counted under it.
      val metadata = Trace.outbound(Metadata.empty)
      val report   = Await.result(
        conversation.invokeErasure(
          RemoteErasureCall(subject.value, ctx.erasureSeq, ctx.rerun, metadata)
        ),
        timeout
      )
      report.error.foreach(why => throw IllegalStateException(why))
      ErasureReport(report.objectsErased, finality(report.finality))
    }

  /** `immediate` (or nothing said), or `after:` and an ISO-8601 duration. */
  def finality(said: String): Finality = said match
    case "" | "immediate"            => Finality.Immediate
    case s if s.startsWith("after:") =>
      scala.util
        .Try(java.time.Duration.parse(s.stripPrefix("after:")))
        .toOption
        .filter(d => !d.isNegative)
        .map(d => Finality.After(d.toMillis.millis))
        .getOrElse(throw IllegalStateException(s"the handler answered the finality '$said'"))
    case other => throw IllegalStateException(s"the handler answered the finality '$other'")
