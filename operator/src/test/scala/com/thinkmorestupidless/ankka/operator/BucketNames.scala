package com.thinkmorestupidless.ankka.operator

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.HexFormat

/**
 * How every cloud provider names a service's bucket in Google Cloud Storage (feature 039,
 * contracts/cloud-requests.md): `<prefix>-<project>-<service>-<digest>`.
 *
 * Test code, on purpose. No main source set of ankka derives a bucket's name in Google Cloud
 * Storage: the cloud provider makes it, reports it, and the operator copies what was reported
 * (FR-005). The scripted cloud provider names buckets by this, and the name scenario reads it, so
 * the rule the contract states is the one a test can fail against.
 *
 * The digest is the first eight hex characters of SHA-256 over `<project>.<service>` — 034's name,
 * unique per pair because neither part can hold a dot — so two pairs that join to the same
 * hyphenated name still differ. It is never shortened; when the whole would pass 63 characters the
 * service is shortened from the right, then the project, each keeping at least one character.
 */
object BucketNames:

  val MaxLength: Int = 63

  def digest(projectId: String, serviceName: String): String =
    HexFormat
      .of()
      .formatHex(
        MessageDigest.getInstance("SHA-256").digest(s"$projectId.$serviceName".getBytes(UTF_8))
      )
      .take(8)

  def name(prefix: String, projectId: String, serviceName: String): String =
    val d       = digest(projectId, serviceName)
    val fixed   = prefix.length + d.length + 3 // the three hyphens
    val room    = MaxLength - fixed
    val service = serviceName.take(math.max(1, room - math.min(projectId.length, room - 1)))
    val project = projectId.take(math.max(1, room - service.length))
    s"$prefix-$project-$service-$d"
