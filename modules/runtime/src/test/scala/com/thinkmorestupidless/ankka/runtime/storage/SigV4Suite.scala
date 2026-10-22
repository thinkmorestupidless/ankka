package com.thinkmorestupidless.ankka.runtime.storage

import java.net.URI
import java.time.Instant

/**
 * The signer against the vectors AWS publishes. If one of these fails the signer is wrong, never
 * the vector: each signature below is copied from AWS's documentation.
 */
class SigV4Suite extends munit.FunSuite:

  // AWS's signature test suite, "get-vanilla": the generic case, service "service".
  test("a signed request matches AWS's get-vanilla vector") {
    val headers = SigV4.sign(
      SigV4.Request("GET", URI.create("https://example.amazonaws.com"), "/"),
      Map.empty,
      SigV4.EmptyPayload,
      SigV4.Credentials("AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"),
      "us-east-1",
      "service",
      Instant.parse("2015-08-30T12:36:00Z")
    )
    assertEquals(headers("x-amz-date"), "20150830T123600Z")
    assertEquals(
      headers("Authorization"),
      "AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, " +
        "SignedHeaders=host;x-amz-date, " +
        "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31"
    )
  }

  private val s3Credentials =
    SigV4.Credentials("AKIAIOSFODNN7EXAMPLE", "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY")
  private val s3Day = Instant.parse("2013-05-24T00:00:00Z")

  // S3's "GET Object" example in "Signature Calculations for the Authorization Header".
  test("a signed S3 get matches the S3 documentation's example") {
    val headers = SigV4.sign(
      SigV4.Request("GET", URI.create("https://examplebucket.s3.amazonaws.com"), "/test.txt"),
      Map("Range" -> "bytes=0-9", "x-amz-content-sha256" -> SigV4.EmptyPayload),
      SigV4.EmptyPayload,
      s3Credentials,
      "us-east-1",
      "s3",
      s3Day
    )
    assertEquals(
      headers("Authorization"),
      "AWS4-HMAC-SHA256 Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
        "SignedHeaders=host;range;x-amz-content-sha256;x-amz-date, " +
        "Signature=f0e8bdb87c964420e857bd35b5d6ed310bd44f0170aba48dd91039c6036bdb41"
    )
  }

  // S3's example in "Authenticating Requests: Using Query Parameters".
  test("a presigned S3 get matches the S3 documentation's example") {
    val uri = SigV4.presign(
      SigV4.Request("GET", URI.create("https://examplebucket.s3.amazonaws.com"), "/test.txt"),
      s3Credentials,
      "us-east-1",
      "s3",
      86400,
      s3Day
    )
    val query = uri.getRawQuery.split('&').map(_.split("=", 2)).map(a => a(0) -> a(1)).toMap
    assertEquals(
      query("X-Amz-Signature"),
      "aeeed9bbccd4d02ee5c0109b86d86835f995330da4c265957d157751f604d404"
    )
    assertEquals(
      query("X-Amz-Credential"),
      "AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request"
    )
    assertEquals(query("X-Amz-Expires"), "86400")
    assertEquals(uri.getHost, "examplebucket.s3.amazonaws.com")
    assertEquals(uri.getRawPath, "/test.txt")
  }

  test("a name's space and plus are encoded, its slashes kept in the path") {
    val request = SigV4.Request(
      "GET",
      URI.create("http://localhost:3900"),
      "/shop.listings/listings/42/front photo+1.jpg",
      Vector("prefix" -> "a/b c")
    )
    assertEquals(request.canonicalPath, "/shop.listings/listings/42/front%20photo%2B1.jpg")
    assertEquals(request.canonicalQuery, "prefix=a%2Fb%20c")
    assertEquals(request.host, "localhost:3900")
    assertEquals(
      request.uri.toString,
      "http://localhost:3900/shop.listings/listings/42/front%20photo%2B1.jpg?prefix=a%2Fb%20c"
    )
  }

  test("a name outside ASCII is encoded as its UTF-8 bytes") {
    assertEquals(SigV4.encode("café", encodeSlash = true), "caf%C3%A9")
  }

  test("credentials never print their secret") {
    assert(!s3Credentials.toString.contains("wJalr"), s3Credentials.toString)
  }
