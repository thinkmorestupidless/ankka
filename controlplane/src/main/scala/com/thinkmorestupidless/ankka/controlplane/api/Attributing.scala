package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.auth.Principals
import com.thinkmorestupidless.ankka.controlplane.domain.{Actor, Attribution}
import com.thinkmorestupidless.ankka.core.Metadata
import com.thinkmorestupidless.ankka.http.HttpEndpoint

import java.time.Clock

/**
 * Stamps every command an endpoint issues with who asked and when (feature 008, FR-023).
 *
 * The principal is the one the endpoint's `Acl.Authenticate` put on the request; the time comes
 * from an injectable clock so a test can assert on it. `administrative` is false here — the
 * authorization step that discovers a caller needed `platform-admin` is what sets it.
 */
private[api] trait Attributing extends HttpEndpoint:

  protected def clock: Clock

  /** The current caller's attribution, as command metadata. */
  protected def by: Metadata = attribution(administrative = false).metadata

  protected def attribution(administrative: Boolean): Attribution =
    Attribution(
      Actor(principal.subject, Some(Principals.display(principal)), administrative),
      clock.instant()
    )
