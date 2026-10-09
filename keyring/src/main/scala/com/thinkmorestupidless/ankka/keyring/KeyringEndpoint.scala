package com.thinkmorestupidless.ankka.keyring

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode}
import com.thinkmorestupidless.ankka.core.personal.KeyResult
import com.thinkmorestupidless.ankka.http.*
import com.thinkmorestupidless.ankka.runtime.erasure.{ChannelWire, KeyringApi}
import com.thinkmorestupidless.ankka.runtime.erasure.KeyringApi.given
import com.thinkmorestupidless.ankka.sdk.ComponentClient

import java.util.Base64
import java.util.UUID
import scala.util.control.NonFatal

/**
 * The keyring's routes (`contracts/keyring-api.md`): the channel every service instance holds, and
 * the erasure routes the control plane — and nothing else — calls. Nothing here answers a key to
 * the control plane's identity (FR-018).
 */
final class KeyringEndpoint(clients: EndpointClients, state: KeyringState) extends HttpEndpoint(""):
  import KeyringEndpoint.*

  /**
   * Every route checks its caller itself — the control plane for an erasure, a project's own
   * service for a channel — because the two differ; the network policy admits only ankka services
   * and the control plane to the keyring at all.
   */
  val acl: Acl = Acl.AllowAll

  private def client: ComponentClient = clients.componentClient

  /** The control plane, by its certificate; anyone locally, where there are no certificates. */
  private def requireControlPlane(): Unit = caller match
    case Caller.Service(Platform, ControlPlane) | Caller.Local => ()
    case other =>
      throw CommandError(
        s"only the control plane applies an erasure, not $other",
        ErrorCode.Forbidden
      )

  private def requireReady(): Unit =
    if !state.ready then
      throw CommandError("the keyring is replaying its erasure log", ErrorCode.Unavailable)

  /**
   * A machine outside the installation asks for one personal field's value (FR-028): it presents a
   * token its issuer signed, naming it `machine` (`<org>/<name>`, the claim spec 040 gives a
   * machine), and holds a grant of `decrypt` in the envelope's project. The key never leaves; the
   * value does, once, and every answer and refusal is counted on the subject's key. An erased
   * subject and a revoked grant are refused alike, from the next request on.
   */
  withAcl(state.machineAcl) {
    postBody[KeyringApi.DecryptRequest, KeyringApi.DecryptReply]("/decrypt") { request =>
      requireReady()
      val machine = principal.claims
        .get("machine")
        .filter(_.nonEmpty)
        .getOrElse(throw CommandError("the token names no machine", ErrorCode.Forbidden))
      val owner  = s"${request.project}/${request.subject}"
      val entity = client.forKeyValueEntity(EntityId(owner))
      def refuse(why: String): Nothing =
        entity.call(SubjectKeyEntity.refused).invoke(): Unit
        throw CommandError(why, ErrorCode.Forbidden)
      if !state.machines.allows(s"machine:$machine", request.project, "decrypt") then
        refuse(s"machine $machine holds no grant to decrypt in project ${request.project}")
      state.keys.subject(request.project, request.subject, create = false) match
        case KeyResult.Available(key) =>
          val stored = Base64.getDecoder.decode(request.data)
          val aad = com.thinkmorestupidless.ankka.core.personal.PersonalCipher
            .associated(request.subject, request.project)
          com.thinkmorestupidless.ankka.core.personal.PersonalCipher.decrypt(key, aad, stored) match
            case Some(plaintext) =>
              entity.call(SubjectKeyEntity.decrypted).invoke(): Unit
              KeyringApi.DecryptReply(String(plaintext, java.nio.charset.StandardCharsets.UTF_8))
            case None => throw CommandError("the envelope does not open", ErrorCode.BadRequest)
        case _ => refuse(s"data subject ${request.subject} is erased in project ${request.project}")
    }
  }

  get("/status") { () =>
    KeyringApi.KeyringStatus(
      state.ready,
      state.copies,
      state.replayed,
      state.behind,
      state.channels.size
    )
  }

  postBody[String, KeyringApi.ApplyErasure, KeyringApi.ErasureStatus](
    "/projects/{project}/erasures"
  ) { (project, request) =>
    requireControlPlane()
    requireReady()
    state.apply(project, request.erasureId, request.subject, request.sequence, reapply = false)
    status(request.erasureId)
  }

  post[String, String, KeyringApi.ErasureStatus]("/projects/{project}/erasures/{id}/reapply") {
    (project, id) =>
      requireControlPlane()
      requireReady()
      val record = client.forEventSourcedEntity(EntityId(id)).call(ErasureEntity.get).invoke()
      if record.project != project then
        throw CommandError(s"no erasure $id in $project", ErrorCode.NotFound)
      state.channels.broadcast(
        Broadcast(id, project, record.subject, record.sequence, reapply = true)
      )
      status(id)
  }

  get[String, String, KeyringApi.ErasureStatus]("/projects/{project}/erasures/{id}") {
    (project, id) =>
      requireControlPlane()
      val found = status(id)
      if found.project != project then
        throw CommandError(s"no erasure $id in $project", ErrorCode.NotFound)
      found

  }

  private def status(id: String): KeyringApi.ErasureStatus =
    val r = client.forEventSourcedEntity(EntityId(id)).call(ErasureEntity.get).invoke()
    KeyringApi.ErasureStatus(
      id,
      r.project,
      r.subject,
      r.sequence,
      r.keyDestroyedAt,
      r.everExisted,
      r.told.values.toVector.map(t =>
        KeyringApi.ChannelStatus(t.service, t.instance, t.acknowledged, t.closedUnacknowledged)
      ),
      r.completions.map(c =>
        KeyringApi.ServiceCompletion(
          c.service,
          c.instance,
          c.completedAt,
          c.viewsRedacted,
          c.rowsRedacted,
          c.sessionsMarked,
          c.instancesStopped,
          c.handlerOk,
          c.handlerDetail,
          c.objectsErased,
          c.objectsFinalAt
        )
      )
    )

  /**
   * One service instance's channel (`contracts/keyring-channel.md`). Admitted by the certificate: a
   * service of the project it names in `hello`. The control plane is refused one.
   */
  socket("/channel") { socket =>
    val callerProject: Option[String] = caller match
      case Caller.Service(Platform, _) => None
      case Caller.Service(project, _)  => Some(project)
      case Caller.Local                => Some("*")
      case _                           => None
    var channel: Option[OpenChannel] = None
    try
      var next = socket.receive()
      while next.isDefined do
        val frame = ChannelWire.readOut(next.get)
        frame match
          case ChannelWire.Out.Hello(project, service, instance, reads, appliedUpTo) =>
            if !callerProject.exists(p => p == "*" || p == project) then
              socket.send(ChannelWire.writeIn(ChannelWire.In.Close("not-admitted")))
              next = None
            else
              val readable = reads.toSet.filter(other => state.grants.allows(project, other))
              val open = OpenChannel(
                s"$project/$service/$instance/${UUID.randomUUID()}",
                project,
                service,
                instance,
                readable,
                socket
              )
              channel = Some(open)
              state.channels.register(open)
              val entries = client
                .forEventSourcedEntity(EntityId(project))
                .call(ProjectLogEntity.after)
                .invoke(appliedUpTo.getOrElse(0L))
                .entries
              open.send(
                ChannelWire.In.Log(
                  entries.map(e =>
                    ChannelWire.In.Entry(e.erasureId, e.sequence, e.subject, e.destroyedAt)
                  )
                )
              )
          case ChannelWire.Out.Fetch(id, project, subject, create) =>
            channel.foreach { open =>
              // The grants as they are now, not as they were at `hello`: a revoked grant refuses the
              // next fetch, and the service's cache expiry bounds what it still holds (FR-026).
              val admitted = project == open.project || state.grants.allows(open.project, project)
              val answer =
                if !admitted then
                  state.refused(project, subject)
                  ChannelWire.In.Refused(
                    id,
                    project,
                    subject,
                    s"project ${open.project} holds no grant that allows decryption of $project"
                  )
                else
                  if project != open.project then open.reading(project)
                  state.keys.subject(project, subject, create && project == open.project) match
                    case KeyResult.Available(key) =>
                      ChannelWire.In.Key(
                        id,
                        project,
                        subject,
                        Base64.getEncoder.encodeToString(key)
                      )
                    case KeyResult.Destroyed(eid) =>
                      ChannelWire.In.Erased(id, project, subject, eid)
                    case KeyResult.Unknown    => ChannelWire.In.Unknown(id, project, subject)
                    case KeyResult.Refused(r) => ChannelWire.In.Refused(id, project, subject, r)
              open.send(answer)
            }
          case ChannelWire.Out.LookupKey(id, project) =>
            channel.foreach { open =>
              open.send(
                if project == open.project then
                  ChannelWire.In.LookupKeyIs(
                    id,
                    project,
                    Base64.getEncoder.encodeToString(state.keys.lookupKey(project))
                  )
                else ChannelWire.In.Refused(id, project, "", "a lookup key is its project's own")
              )
            }
          case ChannelWire.Out.Ack(erasureId) =>
            channel.foreach(state.channels.acknowledged(_, erasureId))
          case c: ChannelWire.Out.Completed =>
            channel.foreach { open =>
              client
                .forEventSourcedEntity(EntityId(c.erasureId))
                .call(ErasureEntity.completed)
                .invoke(
                  CompletionRecord(
                    open.service,
                    open.instance,
                    System.currentTimeMillis(),
                    c.viewsRedacted,
                    c.rowsRedacted,
                    c.sessionsMarked,
                    c.instancesStopped,
                    c.handlerOk,
                    c.handlerDetail,
                    c.objectsErased,
                    c.objectsFinalAt
                  )
                ): Unit
            }
        if next.isDefined then next = socket.receive()
    catch
      case _: SocketClosed => ()
      case NonFatal(failure) =>
        try socket.send(ChannelWire.writeIn(ChannelWire.In.Close(s"failed: ${failure.getMessage}")))
        catch case NonFatal(_) => ()
    finally channel.foreach(state.channels.unregister)
  }

object KeyringEndpoint:
  val Platform: String     = "platform"
  val ControlPlane: String = "controlplane"
