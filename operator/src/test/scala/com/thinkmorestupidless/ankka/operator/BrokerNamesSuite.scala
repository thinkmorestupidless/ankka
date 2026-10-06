package com.thinkmorestupidless.ankka.operator

class BrokerNamesSuite extends munit.FunSuite:

  test("a service's user is its project and its name") {
    assertEquals(BrokerNames.user("money", "wallet"), "money.wallet")
  }

  test("a declared topic is held under its project's name") {
    assertEquals(BrokerNames.topic("money", "transactions"), "money.transactions")
    assert(BrokerNames.topic("money", "transactions").startsWith(BrokerNames.topicPrefix("money")))
  }

  test("hyphenated projects and services cannot share a name or a prefix") {
    assertNotEquals(BrokerNames.user("a-b", "c"), BrokerNames.user("a", "b-c"))
    assertNotEquals(BrokerNames.topic("a-b", "c"), BrokerNames.topic("a", "b-c"))
    // One project's prefix never covers another project's topics.
    assert(!BrokerNames.topic("money-2", "t").startsWith(BrokerNames.topicPrefix("money")))
  }

  test("a service's groups are the qualified group ids the runtime builds") {
    assertEquals(BrokerNames.groupPrefix("money", "wallet"), "ankka.money.wallet.")
    assert("ankka.money.wallet.view.entries".startsWith(BrokerNames.groupPrefix("money", "wallet")))
    assert(
      !"ankka.money.wallet-2.view.entries".startsWith(BrokerNames.groupPrefix("money", "wallet"))
    )
  }
