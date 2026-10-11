package com.thinkmorestupidless.ankka.crd

/**
 * A project's resource survives a round trip through fabric8's serialization, with the database's
 * settings, restores and rehearsals feature 041 adds and the status the operator writes back.
 */
class AnkkaProjectCodecSuite extends munit.FunSuite:

  private val serialization = AnkkaSerialization()

  private val verification = ServiceVerification(
    name = "wallet",
    present = true,
    journalRows = 1200L,
    stateRows = 3L,
    offsetRows = 40L,
    timerRows = 0L,
    highestSequence = 98765432101L,
    changedSecrets = List("stripe-key")
  )

  private val fullSpec = AnkkaProjectSpec(
    projectId = "shop",
    database = Some(
      ProjectDatabaseSpec(
        replicas = 2,
        synchronous = true,
        retentionDays = Some(45),
        rehearsalSchedule = Some("daily")
      )
    ),
    backups = Some(ProjectBackupsSpec(credentialGeneration = 3)),
    restores = List(
      RestoreEntry(
        name = "ankka-db-r202610081012",
        line = "ankka-db",
        targetTime = "2026-10-08T09:20:00Z",
        requestedAt = "2026-10-08T10:12:00Z"
      )
    ),
    rehearsals = List(
      RehearsalEntry(
        name = "ankka-db-x202610081100",
        line = "ankka-db",
        targetTime = "2026-10-08T10:00:00Z",
        requestedAt = "2026-10-08T11:00:00Z"
      )
    )
  )

  private val fullStatus = AnkkaProjectStatus(
    backups = Some(
      BackupsStatus(
        target = "object-store",
        lines = List(
          LineStatus(
            line = "ankka-db",
            cluster = "ankka-db",
            phase = "BackingUp",
            lastBaseBackup = Some("2026-10-08T00:00:12Z"),
            firstRestorable = Some("2026-09-08T00:00:12Z"),
            lastRestorable = Some("2026-10-08T10:11:50Z"),
            archiveLagSeconds = Some(12.5),
            failing = None,
            copiedAt = Some("2026-10-08T10:00:00Z")
          )
        ),
        detail = None
      )
    ),
    database = Some(
      ProjectDatabaseStatus(
        cluster = "ankka-db",
        instances = 3,
        readyInstances = 3,
        primary = Some("ankka-db-1"),
        synchronous = true,
        writesWaitingOn = None
      )
    ),
    clusters = List(
      ProjectClusterStatus(
        name = "ankka-db",
        line = "ankka-db",
        phase = "live",
        services = List("wallet"),
        since = Some("2026-09-01T00:00:00Z"),
        leftAt = None
      )
    ),
    restores = List(
      RestoreStatus(
        name = "ankka-db-r202610081012",
        line = "ankka-db",
        targetTime = "2026-10-08T09:20:00Z",
        phase = "Verified",
        reachedAt = Some("2026-10-08T09:19:58Z"),
        detail = None,
        services = List(verification)
      )
    ),
    rehearsals = List(
      RehearsalStatus(
        name = "ankka-db-x202610081100",
        targetTime = "2026-10-08T10:00:00Z",
        outcome = "Completed",
        startedAt = Some("2026-10-08T11:00:03Z"),
        elapsedSeconds = Some(1834L),
        detail = None,
        services = List(verification)
      )
    )
  )

  test("a project's spec with every field feature 041 adds round-trips unchanged") {
    val decoded = serialization.unmarshal(serialization.asJson(fullSpec), classOf[AnkkaProjectSpec])
    assertEquals(decoded, fullSpec)
  }

  test("a project's status with every block round-trips unchanged, numbers as their own types") {
    val decoded =
      serialization.unmarshal(serialization.asJson(fullStatus), classOf[AnkkaProjectStatus])
    assertEquals(decoded, fullStatus)
    // Erasure hides an Option's element type from Jackson: a boxed Integer where a Long was meant
    // compares equal and throws on first use (the trap in kubernetes.md).
    val seconds: Long = decoded.rehearsals.head.elapsedSeconds.get
    assertEquals(seconds, 1834L)
    val lag: Double = decoded.backups.get.lines.head.archiveLagSeconds.get
    assertEquals(lag, 12.5)
    val days: Int = serialization
      .unmarshal(serialization.asJson(fullSpec), classOf[AnkkaProjectSpec])
      .database
      .get
      .retentionDays
      .get
    assertEquals(days, 45)
  }

  test("a resource written before feature 041 decodes with the defaults") {
    val sparse  = """{"projectId":"shop","topics":[{"name":"orders","partitions":3}]}"""
    val decoded = serialization.unmarshal(sparse, classOf[AnkkaProjectSpec])
    assertEquals(decoded.database, None)
    assertEquals(decoded.backups, None)
    assertEquals(decoded.restores, Nil)
    assertEquals(decoded.rehearsals, Nil)
    val status = serialization.unmarshal("""{"topics":[]}""", classOf[AnkkaProjectStatus])
    assertEquals(status, AnkkaProjectStatus())
  }

  test("an empty block is omitted rather than written as null") {
    val json = serialization.asJson(AnkkaProjectSpec(projectId = "shop"))
    for field <- Vector("database", "backups", "null") do
      assert(!json.contains(field), s"'$field' should not appear in: $json")
    val status = serialization.asJson(AnkkaProjectStatus())
    for field <- Vector("backups", "database", "null") do
      assert(!status.contains(field), s"'$field' should not appear in: $status")
  }

  test("a service's database cluster is absent unless a switch named one") {
    val spec = AnkkaServiceSpec(projectId = "shop", serviceName = "wallet", image = "img:1")
    assertEquals(spec.databaseCluster, None)
    assert(!serialization.asJson(spec).contains("databaseCluster"))
    val switched = spec.copy(databaseCluster = Some("ankka-db-r202610081012"))
    assertEquals(
      serialization.unmarshal(serialization.asJson(switched), classOf[AnkkaServiceSpec]),
      switched
    )
  }
