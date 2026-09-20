package com.thinkmorestupidless.ankka.cli.console

/**
 * Where the console's data comes from.
 *
 * This feature ships exactly one implementation — `LocalSource`, over services running on this
 * machine. The seam exists because a console over a *deployed* installation was deferred rather
 * than dismissed, and the expensive half of that feature is reassembling one trace from several
 * pods' separate windows, not plumbing. Keeping the UI and the aggregation API behind this means
 * that console is additive rather than a rewrite.
 *
 * Three rules keep the promise, and they cost this feature nothing:
 *
 *   - **A service has instances, plural.** Locally the list always holds exactly one. A shape with
 *     a single address is the expensive thing to change later; a list of one is free now.
 *   - **Nothing assumes co-location or cheap reads.** Locally a read is a loopback call; a later
 *     source fans out across pods.
 *   - **`partial` means "this window does not hold it all"**, never "spans aged out". Locally
 *     eviction is the only cause; deployed there is a second, and one flag with one meaning means
 *     no new case in the UI.
 */
trait Source:
  /** Every service this source can see, newest registration last. */
  def services(): Vector[ServiceSummary]

  /** Identity, instances and component inventory for one service. */
  def service(name: String): Option[String]

  /** The recent trace window for one service, as the contract's JSON. */
  def traces(name: String): Option[String]

  /** One trace in full, as the contract's JSON. */
  def trace(name: String, traceId: String): Option[String]

  /**
   * Sends a request to a service's own HTTP port, as an ordinary client.
   *
   * This is the invoke panel, and it is deliberately not a route into the observability endpoint.
   * The request goes to the address the service is actually serving on, so it matches routes the
   * same way, carries no privilege, and is refused by the endpoint's `acl` exactly as `curl` would
   * be. "The console cannot bypass an ACL" is therefore structural — there is no code path in which
   * it could — rather than a rule someone has to remember not to break.
   */
  def invoke(name: String, request: InvokeRequest): Option[InvokeResponse]

  /**
   * One agent session's stored memory and the tokens it has cost.
   *
   * By id, because sessions cannot be listed. A session is a sharded entity keyed by an id the
   * application chose, and there is no index of them — building one would mean the platform keeping
   * a registry of every conversation, which is a durable cost imposed on every service for the
   * benefit of a development tool. The id is the thing a developer already has.
   */
  def session(name: String, sessionId: String): Option[String]

  /**
   * Sends a request whose response arrives over time, handing each piece on as it lands.
   *
   * Separate from `invoke` because a stream has no end to wait for. An agent answering a question
   * may take a minute, and buffering it would show the developer nothing for the whole of the
   * interesting part — which is precisely the part they opened the console to watch.
   *
   * `onChunk` is called on the caller's thread as data arrives; it returns when the stream ends or
   * the service stops.
   */
  def invokeStream(name: String, request: InvokeRequest, onChunk: String => Unit): Boolean

  /**
   * Runs one of a component's query handlers against an entity id.
   *
   * Queries only, and the service enforces that rather than trusting the console to ask nicely: a
   * handler declared with `command` is refused there, not merely left off the menu here.
   *
   * The service's own status and body come back rather than an `Option[String]`, because the
   * refusal is the interesting case and it carries a reason — `'add-item' is a command, not a
   * query`. Collapsing a non-200 to "nothing" turned that into a bare 404 in the console while the
   * service was answering 405 and saying exactly why. `None` here means only what it says: this
   * source has no such service.
   */
  def query(
      name: String,
      component: String,
      entityId: String,
      method: String
  ): Option[QueryResponse]

/** A query handler's answer, forwarded with the status the service gave it. */
final case class QueryResponse(status: Int, body: String)

/** What the Services panel lists. `instances` is always 1 locally — and a column anyway. */
final case class ServiceSummary(
    name: String,
    instanceId: String,
    observabilityAddress: String,
    startedAt: String
)

/** What the invoke panel sends: a route, filled in. */
final case class InvokeRequest(
    method: String,
    path: String,
    headers: Vector[(String, String)],
    body: Option[String]
)

/** What came back, whole — status, headers and body, including a refusal. */
final case class InvokeResponse(
    status: Int,
    headers: Vector[(String, String)],
    body: String
)
