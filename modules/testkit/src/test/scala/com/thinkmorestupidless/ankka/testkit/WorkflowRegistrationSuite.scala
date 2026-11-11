package com.thinkmorestupidless.ankka.testkit

import com.thinkmorestupidless.ankka.runtime.Ankka

/**
 * A workflow is registered on the service like any other component, checked as a start checks it.
 */
class WorkflowRegistrationSuite extends munit.FunSuite with LogCapturing:

  test("the transfer and the wallets it moves money between are one valid service") {
    // docs:start register
    val service = Ankka.service
      .register(WalletEntity.descriptor)
      .register(TransferWorkflow.descriptor)
    // docs:end register
    assert(service.validate.isRight, service.validate.left.toString)
  }
