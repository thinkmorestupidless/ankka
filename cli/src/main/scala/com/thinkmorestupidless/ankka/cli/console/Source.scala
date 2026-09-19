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
