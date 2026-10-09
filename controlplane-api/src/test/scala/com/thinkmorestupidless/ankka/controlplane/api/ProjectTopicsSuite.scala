package com.thinkmorestupidless.ankka.controlplane.api

/**
 * The rules a topic's declaration is checked by, identically in the CLI and the control plane, one
 * case per refusal of `contracts/project-topics.md`, each message exact.
 */
class ProjectTopicsSuite extends munit.FunSuite:

  test("a name of the wrong shape is refused, naming the rule") {
    for name <- Seq("not a topic name", "Transactions", "-leading", "trailing-", "", "a" * 101) do
      assertEquals(
        ProjectTopics.problems(name, 12),
        Vector(
          s"topic '$name': a name is lower-case letters, digits, \"-\" and \".\", starting and " +
            "ending with a letter or digit, at most 100 characters"
        ),
        name
      )
  }

  test("a name the broker can hold is accepted") {
    for name <- Seq("transactions", "cart-graph", "a", "money.v2", "a" * 100) do
      assertEquals(ProjectTopics.problems(name, 12), Vector.empty, name)
  }

  test("partitions outside 1 to 1000 are refused, naming them") {
    for n <- Seq(0, -1, 1001) do
      assertEquals(
        ProjectTopics.problems("transactions", n),
        Vector(s"topic 'transactions': partitions $n is outside the range 1-1000")
      )
    for n <- Seq(1, 1000) do assertEquals(ProjectTopics.problems("transactions", n), Vector.empty)
  }

  test("every problem is reported at once") {
    assertEquals(ProjectTopics.problems("Bad", 0).size, 2)
  }

  test("fewer partitions than the project declares is worded as the contract says") {
    assertEquals(
      ProjectTopics.fewer("transactions", 12, 6),
      "topic 'transactions' has 12 partitions and cannot have fewer; 6 was asked"
    )
  }

  // features/topics/contracts.feature (feature 037)
  private def schema(text: String) =
    com.thinkmorestupidless.ankka.core.graph.GraphJson.parse(text.getBytes("UTF-8")).toOption.get

  test("a declaration with a contract and its schema is accepted") {
    val request = TopicDeclarationRequest(
      Some(3),
      contract = Some(ContractDeclaration("order.v1", schema("""{"type":"object"}""")))
    )
    assertEquals(ProjectTopics.problems("orders", request), Vector.empty)
    assertEquals(
      ProjectTopics.problems("orders", TopicDeclarationRequest(Some(3), compacted = true)),
      Vector.empty
    )
  }

  test("a contract name outside the rule is refused, naming it") {
    for name <- Seq("Order", ".v1", "a", "order v1") do
      val request =
        TopicDeclarationRequest(Some(3), contract = Some(ContractDeclaration(name, schema("{}"))))
      assertEquals(
        ProjectTopics.problems("orders", request),
        Vector(
          s"topic 'orders': contract name '$name' is not ${com.thinkmorestupidless.ankka.core.Contract.NameRule}"
        ),
        name
      )
  }

  test("a schema larger than 64 KiB is refused, naming its size") {
    val big = "{" + (1 to 2000).map(i => s""""f$i":"${"x" * 30}"""").mkString(",") + "}"
    val request =
      TopicDeclarationRequest(
        Some(3),
        contract = Some(ContractDeclaration("order.v1", schema(big)))
      )
    val problems = ProjectTopics.problems("orders", request)
    assertEquals(problems.size, 1)
    assert(problems.head.startsWith("topic 'orders': the schema of 'order.v1' is "), problems.head)
    assert(problems.head.endsWith(" bytes, more than 65536"), problems.head)
  }

  test("the topic's own problems come with the contract's") {
    val request =
      TopicDeclarationRequest(Some(0), contract = Some(ContractDeclaration("Bad", schema("{}"))))
    assertEquals(ProjectTopics.problems("orders", request).size, 2)
  }

  // ── Settings (feature 043): fill, merge, bounds, changes and removal ──────────

  private val d = TopicDefaults.Shipped
  private def parsed(request: TopicDeclarationRequest) =
    TopicSettingsRules.parse("transactions", request).fold(p => fail(p.mkString("; ")), identity)
  private def first(request: TopicDeclarationRequest) = TopicSettingsRules.fill(parsed(request), d)
  private def days(n: Long)                           = RetentionTime.Bounded(n * 86400000L)

  test(
    "a first declaration takes every setting it leaves out from the installation, and marks it"
  ) {
    val filled = first(TopicDeclarationRequest(Some(12), retention = Some("90d")))
    assertEquals(filled.settings.retention, days(90))
    assertEquals(filled.settings.retentionSize, RetentionSize.NoLimit)
    assertEquals(filled.settings.cleanup, CleanupPolicy.Delete)
    assertEquals(filled.settings.copies, Some(1))
    assertEquals(filled.settings.minInSync, Some(1))
    assertEquals(filled.defaulted, Setting.values.toSet - Setting.Retention)
  }

  test("compacted is the short form of cleanup compact, and disagreeing with cleanup is refused") {
    assertEquals(
      parsed(TopicDeclarationRequest(Some(3), compacted = true)).cleanup,
      Some(CleanupPolicy.Compact)
    )
    assertEquals(
      parsed(
        TopicDeclarationRequest(Some(3), compacted = true, cleanup = Some("compact,delete"))
      ).cleanup,
      Some(CleanupPolicy.CompactDelete)
    )
    assertEquals(
      TopicSettingsRules.parse(
        "deltas",
        TopicDeclarationRequest(Some(3), compacted = true, cleanup = Some("delete"))
      ),
      Left(Vector("topic 'deltas': compacted and cleanup disagree"))
    )
  }

  test("every value the parser does not read is refused at once, naming the field") {
    assertEquals(
      ProjectTopics.problems(
        "transactions",
        TopicDeclarationRequest(Some(3), retention = Some("90 days"), retentionSize = Some("50GB"))
      ),
      Vector(
        "topic 'transactions': retention \"90 days\" is not a duration: a whole number and ms, s, m, h or d, or \"everything\"",
        "topic 'transactions': retentionSize \"50GB\" is not a size: a whole number and B, KiB, MiB, GiB or TiB, or \"none\""
      )
    )
  }

  test("a redeclaration keeps every setting it leaves out, and its mark") {
    val declared = first(TopicDeclarationRequest(Some(12), retention = Some("90d")))
    val merged = TopicSettingsRules
      .merge(parsed(TopicDeclarationRequest(retentionSize = Some("10GiB"))), declared)
      .fold(fail(_), identity)
    assertEquals(merged.settings.retention, days(90))
    assertEquals(merged.settings.retentionSize, RetentionSize.Bounded(10L << 30))
    assert(!merged.defaulted(Setting.Retention))
    assert(!merged.defaulted(Setting.RetentionSize))
    assert(merged.defaulted(Setting.Cleanup))
  }

  test("copies and the minimum in-sync copies are fixed when a topic is declared") {
    val declared = first(TopicDeclarationRequest(Some(3), copies = Some(3), minInSync = Some(2)))
    val fixed    = Left("copies and minimum in-sync copies are fixed when a topic is declared")
    for (copies, minimum) <- Seq((Some(1), Some(1)), (Some(3), Some(3)), (Some(2), None)) do
      assertEquals(
        TopicSettingsRules
          .merge(parsed(TopicDeclarationRequest(copies = copies, minInSync = minimum)), declared),
        fixed
      )
    // Saying the same again is no change.
    assert(
      TopicSettingsRules
        .merge(parsed(TopicDeclarationRequest(copies = Some(3), minInSync = Some(2))), declared)
        .isRight
    )
    // A topic whose copies are the broker's cannot have them stated later.
    val legacy = TopicSettingsRules.legacy(compacted = false, d)
    assertEquals(
      TopicSettingsRules.merge(parsed(TopicDeclarationRequest(copies = Some(1))), legacy),
      fixed
    )
  }

  test("a value past a bound is refused naming the bound") {
    val bounds = TopicBounds(days(365), RetentionSize.Bounded(100L << 30), 3)
    assertEquals(
      TopicSettingsRules.bounded(
        "transactions",
        parsed(
          TopicDeclarationRequest(
            retention = Some("730d"),
            retentionSize = Some("1TiB"),
            copies = Some(5)
          )
        ),
        bounds
      ),
      Vector(
        "topic 'transactions': retention time 730d is longer than the installation's longest, 365d",
        "topic 'transactions': retention size 1TiB is larger than the installation's largest, 100GiB",
        "topic 'transactions': copies 5 is more than the installation's most, 3"
      )
    )
    val everything = parsed(TopicDeclarationRequest(retention = Some("everything")))
    assertEquals(
      TopicSettingsRules.bounded("t", everything, bounds),
      Vector("topic 't': retention time everything is longer than the installation's longest, 365d")
    )
    assertEquals(TopicSettingsRules.bounded("t", everything, TopicBounds.Shipped), Vector.empty)
  }

  test("a minimum in-sync copies outside 1 to the copies is refused") {
    val s = first(TopicDeclarationRequest(Some(3), copies = Some(3), minInSync = Some(4))).settings
    assertEquals(
      TopicSettingsRules.coherent("t", s),
      Vector("topic 't': minimum in-sync copies must be between 1 and the copies, 3")
    )
  }

  test("changes name each setting that differs, in words, and none when nothing does") {
    val before = first(TopicDeclarationRequest(Some(3), retention = Some("90d"))).settings
    assertEquals(
      TopicSettingsRules.changes(before, before.copy(retention = days(180))),
      Vector(SettingChange(Setting.Retention, "90d", "180d"))
    )
    assertEquals(TopicSettingsRules.changes(before, before), Vector.empty)
  }

  test(
    "a shorter retention, a smaller size, or compaction ended removes messages; the rest removes none"
  ) {
    val before = first(
      TopicDeclarationRequest(Some(3), retention = Some("90d"), retentionSize = Some("50GiB"))
    ).settings
    val tenGiB = RetentionSize.Bounded(10L << 30)
    assertEquals(
      TopicSettingsRules.removal(before, before.copy(retention = days(30))),
      Some("messages older than 30d")
    )
    assertEquals(
      TopicSettingsRules.removal(before, before.copy(retentionSize = tenGiB)),
      Some("messages beyond 10GiB on a partition")
    )
    assertEquals(
      TopicSettingsRules.removal(before, before.copy(retention = days(30), retentionSize = tenGiB)),
      Some("messages older than 30d; messages beyond 10GiB on a partition")
    )
    val compacted = before.copy(cleanup = CleanupPolicy.Compact)
    assertEquals(
      TopicSettingsRules.removal(compacted, compacted.copy(cleanup = CleanupPolicy.Delete)),
      Some("messages older than 90d, which compaction kept")
    )
    // Removing nothing: longer, larger, or compaction begun.
    assertEquals(
      TopicSettingsRules.removal(before, before.copy(retention = RetentionTime.Everything)),
      None
    )
    assertEquals(
      TopicSettingsRules.removal(before, before.copy(retentionSize = RetentionSize.NoLimit)),
      None
    )
    assertEquals(TopicSettingsRules.removal(before, compacted), None)
    // Compacted with everything kept, then deleting: nothing ages out.
    val keepsAll = compacted.copy(retention = RetentionTime.Everything)
    assertEquals(
      TopicSettingsRules.removal(keepsAll, keepsAll.copy(cleanup = CleanupPolicy.Delete)),
      None
    )
  }

  test(
    "a topic declared before settings is filled with the defaults, compacted if it was, copies unstated"
  ) {
    val legacy = TopicSettingsRules.legacy(compacted = true, d)
    assertEquals(legacy.settings.cleanup, CleanupPolicy.Compact)
    assertEquals(legacy.settings.copies, None)
    assertEquals(legacy.settings.minInSync, None)
    assertEquals(legacy.defaulted, Setting.values.toSet - Setting.Copies - Setting.MinInSync)
  }
