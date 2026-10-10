package com.thinkmorestupidless.ankka.crd

/**
 * A service's bucket, the Secret its credential is in, and where it is on the internet (feature
 * 034).
 */
class BucketsSuite extends munit.FunSuite:

  test("a bucket is named from its project and its service, joined by a dot") {
    assertEquals(Buckets.name("shop", "reports"), "shop.reports")
  }

  test("a dot cannot occur in a project or a service, so no two pairs share a bucket") {
    // The case a hyphen would get wrong: `a-b` in `c` and `a` in `b-c`.
    assertNotEquals(Buckets.name("c", "a-b"), Buckets.name("b-c", "a"))
  }

  test("the storage credential's Secret is the service's name and -storage") {
    assertEquals(Buckets.secret("reports"), "reports-storage")
    assert(Buckets.secret("reports").endsWith(Buckets.SecretSuffix))
  }

  test("a bucket name of 63 characters is accepted, and one of 64 is refused naming the limit") {
    val project = "p" * 30
    assertEquals(Buckets.problems(project, "s" * 32), Vector.empty)
    val problems = Buckets.problems(project, "s" * 33)
    assertEquals(problems.size, 1)
    assert(problems.head.contains("63"), problems.head)
    assert(problems.head.contains(Buckets.name(project, "s" * 33)), problems.head)
  }

  test("the store's address on the internet omits the port when it is 443") {
    assertEquals(Buckets.publicEndpoint("example.com", 443), "https://storage.example.com")
    assertEquals(
      Buckets.publicEndpoint("127.0.0.1.sslip.io", 8443),
      "https://storage.127.0.0.1.sslip.io:8443"
    )
  }

  test("a bucket's address on the internet is the store's, then the bucket") {
    assertEquals(
      Buckets.publicAddress("shop", "reports", "example.com", 443),
      "https://storage.example.com/shop.reports"
    )
  }

  test(
    "a credential in Google Cloud Storage has a Secret of its own, ending as every storage credential's does"
  ) {
    assertEquals(Buckets.cloudSecret("kyc"), "kyc-cloud-storage")
    assert(Buckets.cloudSecret("kyc").endsWith(Buckets.SecretSuffix))
    assertNotEquals(Buckets.cloudSecret("kyc"), Buckets.secret("kyc"))
  }

  test("a bucket in Google Cloud Storage is at Google's address, then the bucket's reported name") {
    assertEquals(
      Buckets.gcsPublicAddress("https://storage.googleapis.com", "ankka-casino-kyc-3f9a1c2e"),
      "https://storage.googleapis.com/ankka-casino-kyc-3f9a1c2e"
    )
    assertEquals(
      Buckets.gcsPublicAddress("https://storage.googleapis.com/", "b"),
      "https://storage.googleapis.com/b"
    )
  }

  test("the store's hostname is one label with no hyphen, so no service's hostname can be it") {
    assertEquals(Hostnames.StorageLabel, "storage")
    assert(!Hostnames.StorageLabel.contains("-"))
    assertEquals(Hostnames.storage("example.com"), "storage.example.com")
  }
