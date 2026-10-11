package com.thinkmorestupidless.ankka.crd

import java.time.Instant

/**
 * The names backups and recovery derive (feature 041, research R4): the platform's buckets, the
 * credential's Secret, a restore's cluster, a rehearsal's cluster and namespace.
 */
class BackupNamesSuite extends munit.FunSuite:

  test("a project's backup bucket is the platform's, so no service's bucket can share its name") {
    assertEquals(Buckets.backup("shop"), "platform.backups.shop")
    // A service's bucket is <project>.<service>, and no project may be called `platform`.
    assert(Buckets.backup("shop").startsWith(s"${Buckets.PlatformProject}."))
  }

  test("the control plane's backup bucket is the platform's too") {
    assertEquals(Buckets.platformBackup("controlplane"), "platform.backups-controlplane")
  }

  test("a project id of 46 characters has a backup bucket, and one of 47 is refused naming 46") {
    assertEquals(Buckets.backupProblems("x" * Buckets.BackedUpProjectMaxLength), Vector.empty)
    val problems = Buckets.backupProblems("x" * (Buckets.BackedUpProjectMaxLength + 1))
    assertEquals(problems.size, 1)
    assert(problems.head.contains("46"), problems.head)
    assertEquals(Buckets.BackedUpProjectMaxLength, 46)
    assertEquals(Buckets.backup("x" * 46).length, Buckets.MaxName)
  }

  test("the backup credential's Secret ends in a suffix no project secret may take") {
    assertEquals(Buckets.BackupSecret, "ankka-db-backups")
    assert(Buckets.BackupSecret.endsWith(Buckets.BackupSecretSuffix))
  }

  test(
    "a restore's cluster and a rehearsal's are named for the minute they were asked for, in UTC"
  ) {
    val at = Instant.parse("2026-10-08T10:12:59Z")
    assertEquals(Recovery.restoreName(at), "ankka-db-r202610081012")
    assertEquals(Recovery.rehearsalName(at), "ankka-db-x202610081012")
    assert(Recovery.restoreName(at).startsWith(s"${Recovery.ProjectDatabase}-"))
  }

  test("a project's rehearsal namespace is its namespace and -rehearsal") {
    assertEquals(Recovery.rehearsalNamespace("ankka", "shop"), "ankka-shop-rehearsal")
  }

  test("a project id ending in -rehearsal is refused, since it would name another's rehearsals") {
    val problems = Recovery.projectProblems("shop-rehearsal")
    assertEquals(problems.size, 1)
    assert(problems.head.contains("-rehearsal"), problems.head)
    assertEquals(Recovery.projectProblems("rehearsals"), Vector.empty)
    assertEquals(Recovery.projectProblems("shop"), Vector.empty)
  }
