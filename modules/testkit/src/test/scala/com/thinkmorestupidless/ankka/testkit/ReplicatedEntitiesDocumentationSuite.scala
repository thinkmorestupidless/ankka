package com.thinkmorestupidless.ankka.testkit

import java.nio.file.{Files, Path}

/**
 * `features/documentation/replicated-entities.feature`: what the published documentation must say
 * about replicated entities, held to the pages. One test per scenario, named after it, and one for
 * the reference pages that state the protocol, the configuration and the error code beside them.
 */
class ReplicatedEntitiesDocumentationSuite extends munit.FunSuite:

  private lazy val docs: Path =
    var dir = Path.of("").toAbsolutePath
    while !Files.isDirectory(dir.resolve("docs/reference")) do dir = dir.getParent
    dir.resolve("docs")

  private def page(path: String): String =
    Files.readString(docs.resolve(path)).replaceAll("\\s+", " ")

  private def says(path: String, statements: String*): Unit =
    val text = page(path)
    for statement <- statements do
      assert(text.contains(statement), s"$path does not say: $statement")

  private val fleet =
    "modules/testkit/src/test/scala/com/thinkmorestupidless/ankka/testkit/replication"

  test("the documentation describes the home, followers, the replication set and the handover") {
    says(
      "concepts/replicated-entities.md",
      "## The home",
      "## Followers",
      "## The replication set",
      "## The handover",
      "The home is the one installation that writes the entity",
      "A follower is an installation that holds a copy of the entity and does not write it",
      "The replication set is the list of installations that hold the entity",
      "A handover makes another installation in the replication set the home",
      "A read at a follower may be behind the home",
      "`ankka.replication.poll`"
    )
    says(
      "build/event-sourced-entities.md",
      "## Replication",
      "its home; the other installations in its replication set are followers",
      "`handOverTo(installation)`",
      "`replicateTo(installation)`",
      "`stopReplicatingTo(installation)`",
      "awaitHandover(",
      // Each sample from tested code.
      s"<!-- include: $fleet/FleetComponents.scala#replicated-companion -->",
      s"<!-- include: $fleet/FleetComponents.scala#replicated-effects -->",
      s"<!-- include: $fleet/FleetComponents.scala#replicated-apply -->",
      s"<!-- include: $fleet/ReplicationSteps.scala#follower-kit -->"
    )
    says(
      "build/consumers.md",
      "## Changes from a replicated entity",
      "each change is published once across installations",
      s"<!-- include: $fleet/FleetComponents.scala#mirrored-change -->"
    )
    says(
      "build/testing.md",
      "## Several installations",
      s"<!-- include: $fleet/ReplicationSmokeSuite.scala#several-installations -->"
    )
  }

  test(
    "the documentation says what replication across installations needs and what is not offered"
  ) {
    says(
      "reference/limitations.md",
      "**Replication between installations needs `ankka-replication`.**",
      "What one installation offers on its own",
      "**No forced takeover.**",
      "**No coordinated creation.**",
      "**No concurrent writers.**"
    )
    assert(
      !page("reference/limitations.md").contains("One region"),
      "limitations.md still says there is one region"
    )
    says(
      "concepts/replicated-entities.md",
      "The replication transport between installations on a network is `ankka-replication`",
      "There is no forced takeover"
    )
  }

  test("the documentation says which of Akka's replication modes ankka adopted") {
    says(
      "reference/akka-divergences.md",
      "## Replication",
      "ankka adopts Akka's replicated reads with a moving primary and replication filters",
      "Akka's replicated writes with merged state is not offered"
    )
    assert(
      !page("reference/akka-divergences.md").contains("Multi-region replication"),
      "akka-divergences.md still lists replication as something ankka does not have"
    )
  }

  test("the reference pages state the protocol, the configuration and the error code") {
    says(
      "reference/error-codes.md",
      "| `NotHome` | `NOT_HOME` | `421` | `OUT_OF_RANGE` |"
    )
    says("reference/sidecar-protocol.md", "`1.19` added replicated entities", "`at_home`")
    says(
      "reference/configuration.md",
      "### Replication",
      "`ANKKA_INSTALLATION`",
      "`ANKKA_INSTALLATION_<NAME>_DB_HOST`",
      "`ankka.replication.handover.hold-writes`"
    )
    says("reference/wasm-abi.md", "`mirrored`")
  }
