package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core
import com.thinkmorestupidless.ankka.javasdk.DataSubject
import com.thinkmorestupidless.ankka.javasdk.erasure.{
  ErasureAsk,
  ErasureAsked,
  ErasureContext,
  ErasureHandler,
  Erasures
}
import com.thinkmorestupidless.ankka.javasdk.storage.ErasedObjects
import com.thinkmorestupidless.ankka.sdk

import java.time.Duration
import java.util.Optional
import scala.jdk.OptionConverters.*

/**
 * Erasure as a Java service sees it (feature 042): asking for one through the Scala SDK's
 * `Erasures`, and its handler handed to the runtime as the Scala SDK's `ErasureHandler`, so the
 * re-run window, the retries and the report are the runtime's, as for a Scala service.
 */
private[ankka] object JavaErasures:

  def of(scala: sdk.Erasures): Erasures = new Erasures:
    def ask(subject: DataSubject): ErasureAsked = ask(subject, ErasureAsk.defaults())
    def ask(subject: DataSubject, options: ErasureAsk): ErasureAsked =
      val asked = Stores.javaFailures(
        scala.ask(
          core.DataSubject(subject.value),
          options.project.toScala,
          options.notBefore.toScala,
          options.reason.toScala,
          options.correlation.toScala
        )
      )
      ErasureAsked(asked.id, asked.state)

  val unavailable: Erasures = of(sdk.Erasures.unavailable)

  /** The Java handler as the runtime's, its context's clients the Java service's own. */
  def handler(
      java: ErasureHandler,
      refs: Refs,
      serializers: Declarations.Serializers
  ): sdk.ErasureHandler =
    sdk.ErasureHandler { ctx => subject =>
      val context = new ErasureContext:
        def componentClient()  = Clients.of(ctx.client, refs)
        def services()         = Services.of(ctx.services, serializers)
        def secrets()          = Stores.secrets(ctx.secrets)
        def storage()          = Stores.storage(ctx.storage)
        def erasures()         = of(ctx.erasures)
        def erasureSeq(): Long = ctx.erasureSeq
        def rerun(): Boolean   = ctx.rerun
      val report = java.handle(context, DataSubject(subject.value))
      if report == null then throw IllegalStateException("the erasure handler answered null")
      sdk.ErasureReport(report.objectsErased, finality(report.finalAfter))
    }

  def finality(after: Optional[Duration]): sdk.Finality =
    after.toScala.fold(sdk.Finality.Immediate)(d => sdk.Finality.After(Effects.finite(d)))

  def erased(scala: sdk.ErasedObjects): ErasedObjects =
    ErasedObjects(
      scala.count,
      scala.finality match
        case sdk.Finality.Immediate     => Optional.empty()
        case sdk.Finality.After(within) => Optional.of(Duration.ofMillis(within.toMillis))
    )
