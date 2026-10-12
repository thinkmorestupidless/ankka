package com.thinkmorestupidless.ankka.controlplane

import com.thinkmorestupidless.ankka.controlplane.api.{ProjectId, ProjectSecrets}
import com.thinkmorestupidless.ankka.crd.{Buckets, Recovery}

/**
 * `controlplane-api` cannot see `crd`, so the rules it applies to a project id and a project
 * secret's name keep their own copies of what `crd` derives (feature 041). This suite, which sees
 * both, holds each copy to its derivation, as `ReservedProjectIdsSuite` holds the reserved ids.
 */
class BackupNamesAgreeSuite extends munit.FunSuite:

  test(
    "the longest project id the control plane accepts has a backup bucket, and no longer one does"
  ) {
    assertEquals(ProjectId.MaxLength, Buckets.BackedUpProjectMaxLength)
    val longest = "a" * ProjectId.MaxLength
    assert(ProjectId.isValid(longest))
    assertEquals(Buckets.backupProblems(longest), Vector.empty)
  }

  test("the control plane refuses every project id whose namespace is another's rehearsals") {
    assertEquals(ProjectId.RehearsalSuffix, Recovery.RehearsalSuffix)
    assert(!ProjectId.isValid("shop" + Recovery.RehearsalSuffix))
    assert(Recovery.projectProblems("shop" + Recovery.RehearsalSuffix).nonEmpty)
  }

  test("no project secret may be named as the backup credential's Secret is") {
    // By the platform's prefix, not a suffix of its own: `nightly-backups` is a member's to take.
    assert(Buckets.BackupSecret.startsWith(ProjectSecrets.ReservedPrefix))
    assert(ProjectSecrets.nameProblems(Buckets.BackupSecret).nonEmpty)
    assertEquals(ProjectSecrets.nameProblems("nightly-backups"), Vector.empty)
  }
