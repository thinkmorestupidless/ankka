package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.ServiceIdentity

/** A test kit plays the service it is told to be, and stays it across a restart. */
class IdentitySuite extends munit.FunSuite with LogCapturing:

  private var testKit: AnkkaTestKit = null

  override def beforeAll(): Unit =
    testKit = AnkkaTestKit.start(Seq.empty, serviceIdentity = ServiceIdentity.local("orders"))

  override def afterAll(): Unit = if testKit != null then testKit.stop()

  test("a test kit started as a named local service is that service") {
    assertEquals(testKit.service.identity, Right(ServiceIdentity.local("orders")))
  }

  test("and is still that service after a restart") {
    testKit.restartService()
    assertEquals(testKit.service.identity, Right(ServiceIdentity.local("orders")))
  }
