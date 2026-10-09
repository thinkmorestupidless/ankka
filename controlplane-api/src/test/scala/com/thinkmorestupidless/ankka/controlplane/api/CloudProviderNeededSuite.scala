package com.thinkmorestupidless.ankka.controlplane.api

import com.github.plokhotnyuk.jsoniter_scala.core.{readFromString, writeToString}
import com.thinkmorestupidless.ankka.controlplane.api.Wire.given

/**
 * A choice that needs a cloud provider, refused on an installation with none (feature 044):
 * `features/cloud-provider/absent.feature`'s outline, one case per row, each in the row's own
 * words. The settings themselves are features 038, 039, 041 and 042's; each calls
 * `CloudProviderNeeded` from its own validation and re-points its row at that refusal when it adds
 * the setting.
 */
class CloudProviderNeededSuite extends munit.FunSuite:

  private val rows = Vector(
    "keep the project secrets of \"shop\" in the cloud account",
    "make the object store of \"shop\" the cloud account's",
    "keep the backups of \"shop\" in the cloud account",
    "wrap the keys of the keyring with a wrapping key"
  )

  rows.foreach { choice =>
    test(
      s"a setting that needs a cloud provider is refused when the installation has none: $choice"
    ) {
      assertEquals(
        CloudProviderNeeded.problem(choice, "gcp", None),
        Some(s"$choice needs the cloud provider gcp, and the installation has none")
      )
    }
  }

  test("an installation with the provider a choice needs refuses nothing") {
    rows.foreach(choice =>
      assertEquals(CloudProviderNeeded.problem(choice, "gcp", Some("gcp")), None)
    )
  }

  test("an installation with another provider is refused, naming both") {
    val refused = CloudProviderNeeded.problem(rows.head, "gcp", Some("other"))
    assert(refused.exists(r => r.contains("gcp") && r.contains("other")), refused.toString)
  }

  test("a keyring on an installation that names no wrapping key keeps its own secret") {
    // kinds.feature: with a provider and no key named, the member's choice is refused before any
    // request could be rendered, which is why the operator's renderer takes a key, never none.
    assertEquals(
      CloudProviderNeeded.wrappingKey(None),
      Some(
        "wrapping the keys of the keyring needs the installation to name a wrapping key, and it names none"
      )
    )
    assertEquals(CloudProviderNeeded.wrappingKey(Some("")), CloudProviderNeeded.wrappingKey(None))
    assertEquals(CloudProviderNeeded.wrappingKey(Some("keys/ankka")), None)
  }

  test("the installation's cloud round-trips, and an absent key is not on the wire") {
    val full = Installation(
      "0.7.0",
      Some(CloudInstallation("gcp", "acct", "europe-west2", Some("keys/ankka")))
    )
    assertEquals(readFromString[Installation](writeToString(full)), full)
    val member = full.copy(cloud = full.cloud.map(_.copy(kmsKey = None)))
    assert(!writeToString(member).contains("kmsKey"), writeToString(member))
    assert(!writeToString(Installation("0.7.0")).contains("cloud"))
  }
