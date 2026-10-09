package com.thinkmorestupidless.ankka.runtime.secrets

import com.thinkmorestupidless.ankka.core.{CommandError, ErrorCode}
import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.runtime.Trace
import com.thinkmorestupidless.ankka.sdk.{SecretRules, SecretStore}

import java.time.Clock

/**
 * Every read, keep and removal of a secret leaves a record, before it returns.
 *
 * The backend is asked first; then the record of what it answered is written; only then is the
 * value returned. A record that cannot be written turns any outcome into `Unavailable` and drops
 * the value, so a value is never handed to a component without a record of it. A backend failure is
 * recorded with its outcome and then thrown as it was.
 *
 * A call the rules refuse never reaches the backend and is not recorded: nothing was read.
 *
 * What the record says of the caller comes from the handler's own thread — its trace, its span, its
 * component — so it is known exactly where the runtime ran the handler, and through a process or a
 * module, where the sidecar cannot tell which component asked, it is left out rather than guessed.
 */
private[ankka] final class RecordingSecretStore(
    underlying: SecretStore,
    recorder: ReadRecorder,
    project: String,
    service: String,
    hosting: String,
    backend: SecretBackend,
    /** A component's kind by its id, for the components the registry holds. */
    kindOf: String => Option[String],
    clock: Clock = Clock.systemUTC()
) extends SecretStore:

  def put(name: String, value: String): Unit =
    SecretRules.check(name, value)
    recorded(name, ReadRecord.Operation.Put)(underlying.put(name, value))(_ =>
      (ReadRecord.Outcome.Written, false)
    )

  def get(name: String): Option[String] =
    SecretRules.check(name)
    val read = recorded(name, ReadRecord.Operation.Get) {
      underlying match
        case detail: ReadDetail => detail.read(name)
        case plain              => ReadDetail.Read(plain.get(name), latestSkipped = false)
    } { read =>
      (if read.value.isDefined then ReadRecord.Outcome.Read else ReadRecord.Outcome.Absent) ->
        read.latestSkipped
    }
    read.value

  def delete(name: String): Unit =
    SecretRules.check(name)
    recorded(name, ReadRecord.Operation.Delete)(underlying.delete(name))(_ =>
      (ReadRecord.Outcome.Removed, false)
    )

  private def recorded[A](name: String, operation: String)(work: => A)(
      outcome: A => (String, Boolean)
  ): A =
    val asked = clock.instant()
    val result =
      try work
      catch
        case e: CommandError =>
          val failed =
            if e.code == ErrorCode.Unavailable then ReadRecord.Outcome.Unavailable
            else ReadRecord.Outcome.Refused
          // The backend's failure is what the caller should hear, so a record that cannot be written
          // as well is not allowed to replace it.
          try recorder.write(record(name, operation, failed, latestSkipped = false, asked))
          catch case _: CommandError => ()
          throw e
    val (said, skipped) = outcome(result)
    recorder.write(record(name, operation, said, skipped, asked))
    result

  private def record(
      name: String,
      operation: String,
      outcome: String,
      latestSkipped: Boolean,
      at: java.time.Instant
  ): ReadRecord =
    val trace     = Trace.currentContext
    val component = Trace.currentOrigin.map(_.component)
    ReadRecord(
      at = at,
      project = project,
      service = service,
      hosting = hosting,
      name = name,
      operation = operation,
      outcome = outcome,
      backend = backend.word,
      traceId = trace.map(_.traceIdHex),
      spanId = trace.map(_.spanIdHex),
      component = component,
      componentKind = component.map(c => kindOf(c).getOrElse("endpoint")),
      latestSkipped = latestSkipped
    )
