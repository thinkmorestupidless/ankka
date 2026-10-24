package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.http
import com.thinkmorestupidless.ankka.javasdk.http.Acl

/**
 * A Scala ACL a suite built, handed to a Java fixture: the test issuer's, which `Acl.oidc()` would
 * read from the environment a test cannot set.
 */
object TestAcls:
  @volatile private var held: Option[http.Acl] = None

  def hold(acl: http.Acl): Unit = held = Some(acl)

  def tokens(): Acl = Acls.Held(held.getOrElse(throw IllegalStateException("no ACL is held")))
