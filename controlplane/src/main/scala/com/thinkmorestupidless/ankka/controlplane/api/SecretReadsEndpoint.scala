package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.secrets.ReadRecordStore
import com.thinkmorestupidless.ankka.core.{CommandError, Done, ErrorCode}
import com.thinkmorestupidless.ankka.core.secrets.ReadRecord
import com.thinkmorestupidless.ankka.http.*

/**
 * Where a service writes the record of each read, keep and removal of a secret, before it uses the
 * value (`POST /secret-reads`).
 *
 * Called by services, never by a person: the ACL admits any service by the certificate the platform
 * issued it and never the internet, and the handler holds the caller to its own reads — a record
 * naming another service, or another project, is refused, so no service can write a record that
 * blames another or bury its own among someone else's. Answered only once the record is committed;
 * a service reads anything else as "not acknowledged" and refuses the read.
 */
final class SecretReadsEndpoint(records: Option[ReadRecordStore])
    extends HttpEndpoint("/secret-reads"):

  val acl: Acl = Acl.allowCallers(Callers.anyService)

  postBody("/") { (record: ReadRecord) =>
    caller match
      case Caller.Service(project, service)
          if project != record.project || service != record.service =>
        throw CommandError(
          s"a service may record only its own reads: $project/$service sent a record for " +
            s"${record.project}/${record.service}",
          ErrorCode.Forbidden
        )
      case _ => ()
    SecretReadsEndpoint.problems(record) match
      case Vector() => ()
      case found    => throw CommandError(found.mkString("; "), ErrorCode.BadRequest)
    records
      .getOrElse(
        throw CommandError("this control plane keeps no record of reads", ErrorCode.Unavailable)
      )
      .insert(record)
    Done: Done
  }

object SecretReadsEndpoint:

  private val Operations =
    Set(ReadRecord.Operation.Get, ReadRecord.Operation.Put, ReadRecord.Operation.Delete)

  /** What makes a record unkeepable: a word the platform does not write, or an empty name. */
  def problems(record: ReadRecord): Vector[String] =
    Vector(
      Option.when(record.name.isEmpty)("a record names the secret it is of"),
      Option.when(record.project.isEmpty || record.service.isEmpty)(
        "a record names the project and the service"
      ),
      Option.when(!Operations.contains(record.operation))(
        s"'${record.operation}' is not an operation on a secret"
      ),
      Option.when(!ReadRecord.Outcome.All.contains(record.outcome))(
        s"'${record.outcome}' is not an outcome of one"
      )
    ).flatten
