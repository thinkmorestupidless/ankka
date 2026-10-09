package com.thinkmorestupidless.ankka.controlplane.api

import com.thinkmorestupidless.ankka.controlplane.api.Wire.given
import com.thinkmorestupidless.ankka.http.*

/**
 * The installation's status (`GET /platform`): where it keeps its secrets, its cloud, how long the
 * record of secret reads is kept, and whether Google Cloud's own access log is on. Any
 * authenticated caller may read it; nothing in it is a credential, and the encryption key's name is
 * never in it.
 */
final class PlatformEndpoint(val acl: Acl, status: () => PlatformStatus)
    extends HttpEndpoint("/platform"):

  get("/") { () =>
    principal: Unit
    status()
  }
