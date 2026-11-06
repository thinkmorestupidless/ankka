package com.thinkmorestupidless.ankka.controlplane.erasure

import com.thinkmorestupidless.ankka.testkit.LogCapturing
import com.thinkmorestupidless.ankka.testpki.TestPki

import java.nio.file.Files

/**
 * Feature 042: the control plane's door to the keyring keeps one client while its certificate does.
 */
class KeyringAdminSuite extends munit.FunSuite with LogCapturing:

  test("the door makes one HTTP client for every call while the certificate stands") {
    val directory = Files.createTempDirectory("keyring-admin")
    TestPki
      .root("keyring-admin")
      .issue(uris = Seq("ankka://platform/controlplane"))
      .writeTo(directory): Unit
    val door  = KeyringAdmin.Door("https://127.0.0.1:1", directory)
    val first = door.http
    assert(first eq door.http, "a client was made for the second call")
    assert(first eq door.http, "a client was made for the third call")
  }
