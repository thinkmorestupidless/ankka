package com.thinkmorestupidless.ankka.cli

import com.thinkmorestupidless.ankka.cli.local.LocalFile
import com.thinkmorestupidless.ankka.cli.local.LocalFile.Token

import java.nio.file.Files

/**
 * `local.json` against contracts/project-files.md (feature 066): its shape, its strictness, its
 * tokens.
 */
class LocalFileSuite extends munit.FunSuite:

  private val Example = """{
    "values": {
      "*":      {"LOG_LEVEL": "debug"},
      "orders": {"PAYMENT_KEY": "test-key",
                 "ANKKA_AUTH_STAFF_JWKS_URL": "{{companion.identity.url}}/realms/shop/protocol/openid-connect/certs"}
    },
    "run": {
      "billing": ["./gradlew", ":billing:run"]
    },
    "companions": [
      {
        "name": "identity",
        "image": "quay.io/keycloak/keycloak:26.0",
        "command": ["start-dev", "--import-realm"],
        "env": {"KC_DB": "postgres", "KC_DB_URL": "{{database.jdbc}}",
                "KC_DB_USERNAME": "{{database.user}}", "KC_DB_PASSWORD": "{{database.password}}"},
        "ports": [{"port": 8080, "host": 8081}],
        "files": [{"from": "local/realm.json", "to": "/opt/keycloak/data/import/realm.json"}],
        "ready": {"http": {"port": 8080, "path": "/realms/shop"}},
        "database": true,
        "neededBy": ["orders"]
      }
    ]
  }"""

  private def example: LocalFile =
    LocalFile.parse(Example).fold(p => fail(p.mkString("; ")), identity)

  test("the contract's example reads") {
    val file = example
    assertEquals(file.run, Map("billing" -> Vector("./gradlew", ":billing:run")))
    val identity = file.companions.head
    assertEquals(identity.name, "identity")
    assertEquals(identity.ports, Vector(LocalFile.Port(8080, Some(8081))))
    assertEquals(identity.ready.flatMap(_.http), Some(LocalFile.HttpReady(8080, "/realms/shop")))
    assert(identity.database)
    assert(identity.needed("orders"))
    assert(!identity.needed("billing"))
  }

  test("a service's own values win over those for every service") {
    val file = LocalFile(
      values = Map("*" -> Map("LOG_LEVEL" -> "debug", "A" -> "all"), "orders" -> Map("A" -> "own"))
    )
    assertEquals(file.valuesFor("orders"), Map("LOG_LEVEL" -> "debug", "A" -> "own"))
    assertEquals(file.valuesFor("billing"), Map("LOG_LEVEL" -> "debug", "A" -> "all"))
  }

  test("a companion that names no services holds back every service") {
    assert(LocalFile.Companion("mail", "mailpit").needed("anyone"))
  }

  test("a project with no local.json has an empty one") {
    assertEquals(LocalFile.read(Files.createTempDirectory("local")), Right(LocalFile()))
  }

  test("a field the platform does not know is refused by name") {
    assertEquals(
      LocalFile.parse("""{"values": {}, "secrets": {}}"""),
      Left(
        Vector(
          "local.json is not a local values file: it names a field the platform does not know: \"secrets\""
        )
      )
    )
    assertEquals(
      LocalFile
        .parse("""{"companions": [{"name": "a", "image": "b", "healthcheck": "x"}]}""")
        .left
        .map(_.head),
      Left(
        "local.json is not a local values file: it names a field the platform does not know: \"healthcheck\""
      )
    )
  }

  test("each token is found, and what is no token is said") {
    assertEquals(
      LocalFile.tokens(
        "{{companion.identity.url}}/x {{companion.mail}} {{database.jdbc}} {{other.thing}}"
      ),
      (
        known = Vector(
          Token.CompanionUrl("identity"),
          Token.CompanionAddress("mail"),
          Token.Database("jdbc")
        ),
        unknown = Vector("other.thing")
      )
    )
  }

  test("tokens are written, and one with no answer is left as it is") {
    val written = LocalFile.fill(
      "{{companion.identity.url}}/realms {{companion.mail}} {{database.user}}",
      {
        case Token.CompanionUrl("identity") => Some("http://host.docker.internal:8081")
        case Token.Database("user")         => Some("ankka")
        case _                              => None
      }
    )
    assertEquals(written, "http://host.docker.internal:8081/realms {{companion.mail}} ankka")
  }

  test("a written value that holds a dollar sign or a backslash is written as it is") {
    assertEquals(LocalFile.fill("{{database.password}}", _ => Some("a$1\\b")), "a$1\\b")
  }
