package com.thinkmorestupidless.ankka.http

import com.thinkmorestupidless.ankka.core.{CommandError, EntityId, ErrorCode, Metadata}
import com.thinkmorestupidless.ankka.runtime.ViewClient
import com.thinkmorestupidless.ankka.runtime.remote.{Payload, PayloadKeys}
import com.thinkmorestupidless.ankka.sdk.{ComponentClient, SecretStore, ServiceClients, Workflow}
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source

import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.FiniteDuration

/**
 * What an endpoint is handed when the server builds it.
 *
 * A bundle rather than a bare `ComponentClient` because an endpoint routinely needs both halves of
 * the read/write split: commands go to an entity by id, listings come from a view queried by
 * attribute. Passing one object also means adding a client later is not a breaking change to every
 * endpoint's registration.
 *
 * An endpoint that needs only one takes only one — `MyEndpoint(_.componentClient)`.
 */
final class EndpointClients private[ankka] (
    val componentClient: ComponentClient,
    val viewClient: ViewClient,
    /**
     * Other services, called as this one (feature 014):
     * `clients.services("orders").get[Order](...)`. In a cluster the call carries this service's
     * certificate, so the callee's ACL knows who is calling; locally it reaches the named service
     * on this machine.
     */
    val services: ServiceClients = EndpointClients.noServices,
    /**
     * The service's secret store: `clients.secrets.put("provider/acme", credential)`. A value kept
     * here is encrypted in the service's own database and never reaches a journal or a view.
     */
    val secrets: SecretStore = SecretStore.unavailable,
    /**
     * How often a wait served as a stream says it goes on: `Heartbeat.interval` of the server's.
     */
    heartbeat: FiniteDuration = Heartbeat.WhenIdleIsInfinite
):

  /**
   * A wait for a workflow's end as server-sent events, for an `sseEvents` route to return as it is:
   * a `heartbeat` event while the wait goes on — often enough that the service never ends the
   * connection for being quiet — then exactly one `ended` (the state, as JSON), `failed` (the code,
   * the message, and a failed workflow's step, reason and whether it was deleted) or `timed-out`,
   * and the stream completes. The wait is made when the source is built, as the route's handler, so
   * it is that handler's call. A gateway in front still bounds the response.
   */
  def awaitEnd[W <: Workflow[S], S](
      workflowId: EntityId,
      companion: Workflow.Companion[W, S],
      timeout: FiniteDuration
  ): Source[SseEvent, NotUsed] =
    given ExecutionContext = ExecutionContext.parasitic
    val end =
      componentClient.transportRef
        .awaitEnd(companion.componentId, workflowId, timeout, Metadata.empty)
        .map((bytes, metadata) => SseEvent.ended(EndpointClients.asJson(bytes, metadata)))
        .recover {
          case timedOut: CommandError if timedOut.code == ErrorCode.Timeout =>
            SseEvent.timedOut(timeout)
          case refused: CommandError => SseEvent.failed(refused)
        }
    Source
      .tick(heartbeat, heartbeat, SseEvent.heartbeat)
      .mapMaterializedValue(_ => NotUsed)
      .merge(Source.future(end), eagerComplete = true)
      .takeWhile(event => !SseEvent.endsAWait(event), inclusive = true)

object EndpointClients:

  /** A state's bytes as JSON: JSON as it is, a text state as a JSON string. */
  private[http] def asJson(bytes: Array[Byte], metadata: Metadata): String =
    val text = String(bytes, UTF_8)
    if metadata.get(PayloadKeys.ContentType).forall(_ == Payload.Json) then text
    else JsonText.encode(text)

  private[ankka] val noServices: ServiceClients = ServiceClients.unavailable
