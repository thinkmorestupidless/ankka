package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/**
 * The rules of a grant, which the CLI applies before it sends one and the control plane applies
 * again: who may be named, what may be opened, and nothing that names more than one of either.
 */
class GrantRulesSuite extends munit.FunSuite:

  private val deposits =
    GrantTarget.route("wallet", "POST", "/v1/wallets/{player}/{currency}/deposits")

  test("a grantee is a service of a project or a machine of an organization, and nothing else") {
    assertEquals(
      Grantee.parse("service:payments/merchant"),
      Right(Grantee.Service("payments", "merchant"))
    )
    assertEquals(
      Grantee.parse("machine:affiliates/network"),
      Right(Grantee.Machine("affiliates", "network"))
    )
    for text <- Vector(
        "payments/merchant",
        "service:payments",
        "service:payments/merchant/extra",
        "service:/merchant",
        "machine:affiliates/",
        "user:ada"
      )
    do assert(Grantee.parse(text).isLeft, text)
  }

  test("there is no wildcard grantee: a pattern is not a name, and is refused as one") {
    for text <- Vector("service:*/merchant", "service:payments/*", "machine:affiliates/*") do
      val problems = Grantee.parse(text).fold(Vector(_), Grantee.problems)
      assert(problems.nonEmpty, text)
  }

  test("a grantee's text is how it is written everywhere, and reads back as itself") {
    val grantee = Grantee.Machine("affiliates", "network")
    assertEquals(grantee.text, "machine:affiliates/network")
    assertEquals(Grantee.parse(grantee.text), Right(grantee))
  }

  test("a grantee whose names are not names is refused, naming each") {
    assert(Grantee.problems(Grantee.Service("payments", "merchant")).isEmpty)
    assert(Grantee.problems(Grantee.Service("Payments", "merchant")).nonEmpty)
    assert(Grantee.problems(Grantee.Service("payments", "Merchant")).nonEmpty)
    assert(
      Grantee.problems(Grantee.Service("platform", "controlplane")).exists(_.contains("reserved"))
    )
    val dotted = Grantee.problems(Grantee.Machine("acme.inc", "network"))
    assert(dotted.exists(_.contains("cannot have machines")), dotted.toString)
    assert(Grantee.problems(Grantee.Machine("affiliates", "Network")).nonEmpty)
  }

  test("the words a person types make each kind of target") {
    assertEquals(
      GrantTarget.parse(
        Vector("route", "wallet", "post", "/v1/wallets/{player}/{currency}/deposits")
      ),
      Right(deposits)
    )
    assertEquals(
      GrantTarget.parse(Vector("method", "wallet", "WalletService/Deposit")),
      Right(GrantTarget.grpcMethod("wallet", "WalletService/Deposit"))
    )
    assertEquals(
      GrantTarget.parse(Vector("topic", "casino.players", "consume", "decrypt")),
      Right(GrantTarget.topic("casino.players", "consume", decrypt = true))
    )
    assertEquals(GrantTarget.parse(Vector("erasure")), Right(GrantTarget.erasure))
    assert(GrantTarget.parse(Vector("route", "wallet")).isLeft)
    assert(GrantTarget.parse(Vector("everything")).isLeft)
  }

  test("each kind of target is refused when a field it needs is missing or wrong") {
    assertEquals(GrantTarget.problems(deposits), Vector.empty)
    assert(
      GrantTarget
        .problems(deposits.copy(method = Some("FETCH")))
        .exists(_.contains("method is one of"))
    )
    assert(
      GrantTarget
        .problems(deposits.copy(path = Some("v1/wallets")))
        .exists(_.contains("beginning with '/'"))
    )
    assert(
      GrantTarget.problems(deposits.copy(service = None)).exists(_.contains("needs a service"))
    )
    assert(
      GrantTarget.problems(deposits.copy(service = Some("Wallet"))).exists(_.contains("invalid"))
    )
    assert(
      GrantTarget
        .problems(GrantTarget.grpcMethod("wallet", "Deposit"))
        .exists(_.contains("<Service>/<Method>"))
    )
    assertEquals(
      GrantTarget.problems(GrantTarget.grpcMethod("wallet", "ankka.WalletService/Deposit")),
      Vector.empty
    )
    assert(
      GrantTarget
        .problems(GrantTarget.topic("Casino", "consume"))
        .exists(_.contains("topic 'Casino'"))
    )
    assert(
      GrantTarget
        .problems(GrantTarget.topic("casino.players", "read"))
        .exists(_.contains("right is one of"))
    )
    assert(GrantTarget.problems(GrantTarget("everything")).exists(_.contains("is not one of")))
  }

  test("a target names one thing: no field of another kind is accepted beside its own") {
    assert(
      GrantTarget
        .problems(deposits.copy(topic = Some("casino.players")))
        .exists(_.contains("no topic"))
    )
    assert(
      GrantTarget
        .problems(GrantTarget.erasure.copy(service = Some("wallet")))
        .exists(_.contains("no service"))
    )
    assert(
      GrantTarget
        .problems(GrantTarget.topic("casino.players", "consume").copy(path = Some("/x")))
        .exists(_.contains("no path"))
    )
  }

  test("only a grant to consume a topic may allow decryption") {
    assertEquals(
      GrantTarget.problems(GrantTarget.topic("casino.players", "consume", decrypt = true)),
      Vector.empty
    )
    assert(
      GrantTarget
        .problems(GrantTarget.topic("payments.deposits", "produce", decrypt = true))
        .exists(_.contains("decryption"))
    )
    assert(GrantTarget.problems(deposits.copy(decrypt = true)).exists(_.contains("decryption")))
  }

  test("a request is refused for its grantee and its target together") {
    val problems = GrantRules.problems(GrantRequest("user:ada", GrantTarget.topic("x", "read")))
    assert(problems.exists(_.contains("grantee 'user:ada'")), problems.toString)
    assert(problems.exists(_.contains("right is one of")), problems.toString)
    assertEquals(
      GrantRules.problems(GrantRequest("service:payments/merchant", deposits)),
      Vector.empty
    )
  }

  test("a grant's state and change are single words on the wire") {
    assertEquals(writeToString(GrantState.Relinquished), "\"relinquished\"")
    assertEquals(readFromString[GrantState]("\"lapsed\""), GrantState.Lapsed)
    assertEquals(writeToString(GrantChange.Offered), "\"offered\"")
    assert(GrantState.Pending.live && GrantState.Accepted.live)
    assert(
      GrantState.values.filterNot(_.live).toSet == Set(
        GrantState.Declined,
        GrantState.Withdrawn,
        GrantState.Revoked,
        GrantState.Relinquished,
        GrantState.Lapsed
      )
    )
  }

  test("a grant's grantee and target read back from the wire as written") {
    val detail = GrantDetail(
      "abc",
      Grantee.Machine("affiliates", "network"),
      GrantTarget.topic("affiliates.attribution", "consume"),
      GrantState.Pending,
      "pending",
      GrantAct(Some("Ada"))
    )
    val json = writeToString(detail)
    assert(json.contains("\"grantee\":\"machine:affiliates/network\""), json)
    assert(json.contains("\"kind\":\"topic\""), json)
    assertEquals(readFromString[GrantDetail](json), detail)
  }

  test("a machine's names on the broker and at the token route are derived in one place") {
    assertEquals(Machines.clientId("affiliates", "network"), "machine:affiliates/network")
    assertEquals(Machines.brokerUser("affiliates", "network"), "machine.affiliates.network")
    assertEquals(Machines.groupPrefix("affiliates", "network"), "ankka.machine.affiliates.network.")
    assert(Machines.nameProblems("Network").nonEmpty)
    assert(Machines.nameProblems("").nonEmpty)
  }

  test(
    "a machine's byte rates are refused at nothing, above the ceiling, or a percentage out of range"
  ) {
    val ceiling = 32L * 1024 * 1024
    assertEquals(Machines.byteRateProblems(ByteRatesRequest(1024, 1024, 50), ceiling), Vector.empty)
    assert(Machines.byteRateProblems(ByteRatesRequest(0, 1024, 50), ceiling).nonEmpty)
    assert(
      Machines
        .byteRateProblems(ByteRatesRequest(1024, ceiling + 1, 50), ceiling)
        .exists(_.contains("ceiling"))
    )
    assert(Machines.byteRateProblems(ByteRatesRequest(1024, 1024, 101), ceiling).nonEmpty)
  }
