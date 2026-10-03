package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.testpki.TestPki
import com.typesafe.config.{Config, ConfigFactory}

import java.nio.file.Files
import scala.jdk.CollectionConverters.*

/**
 * Where a service learns who it is, which names every consumer group it reads a topic under.
 *
 * A deployed service's identity is its certificate's, read with the same parsing the TLS paths use,
 * from a certificate shaped as the operator asks cert-manager for one.
 */
class ServiceIdentitySuite extends munit.FunSuite:

  private val root = TestPki.root("service-identity-suite")

  private def local(entries: (String, String)*): Config =
    ConfigFactory.parseMap(
      (Map("ankka.cluster.formation" -> "join-self-or-seeds") ++ entries).asJava
    )

  private def deployedWith(directory: String): Config =
    ConfigFactory.parseMap(
      Map(
        "ankka.cluster.formation"     -> "bootstrap",
        "ankka.tls.service-directory" -> directory,
        // A deployed service ignores this, whoever wrote it.
        "ankka.service.name" -> "someone-else"
      ).asJava
    )

  private def certificateDirectory(uris: String*): String =
    root.issue(uris = uris).writeTo(Files.createTempDirectory("service-identity")).toString

  test("three shapes: deployed, named local, unnamed") {
    val deployed = ServiceIdentity.deployed("shop", "orders")
    assertEquals((deployed.project, deployed.service), (Some("shop"), Some("orders")))
    val named = ServiceIdentity.local("orders")
    assertEquals((named.project, named.service), (None, Some("orders")))
    assertEquals(
      (ServiceIdentity.unnamed.project, ServiceIdentity.unnamed.service),
      (None, None)
    )
  }

  test("a deployed identity cannot be built in the project reserved for local runs") {
    intercept[IllegalArgumentException](ServiceIdentity.deployed("local", "orders")): Unit
  }

  test("a local run reads ankka.service.name, and empty is none stated") {
    assertEquals(
      ServiceIdentity.resolve(local("ankka.service.name" -> "orders")),
      Right(ServiceIdentity.local("orders"))
    )
    assertEquals(
      ServiceIdentity.resolve(local("ankka.service.name" -> "")),
      Right(ServiceIdentity.unnamed)
    )
    assertEquals(ServiceIdentity.resolve(local()), Right(ServiceIdentity.unnamed))
  }

  test("a local name is held to a deployed name's rule, and a bad one is named") {
    for bad <- Seq("or.ders", "Orders", "o" * 64, "-orders") do
      val answer = ServiceIdentity.resolve(local("ankka.service.name" -> bad))
      assert(
        answer.left.exists(m => m.contains(s"'$bad'") && m.contains("ankka.service.name")),
        s"$bad: $answer"
      )
      intercept[IllegalArgumentException](ServiceIdentity.local(bad)): Unit
  }

  test("a deployed service reads its certificate's identity and ignores ankka.service.name") {
    val directory = certificateDirectory("ankka://shop/orders")
    assertEquals(
      ServiceIdentity.resolve(deployedWith(directory)),
      Right(ServiceIdentity.deployed("shop", "orders"))
    )
  }

  test(
    "a deployed service whose certificate carries no identity is a Left naming the certificate"
  ) {
    val directory = certificateDirectory()
    val answer    = ServiceIdentity.resolve(deployedWith(directory))
    assert(answer.left.exists(_.contains(s"$directory/tls.crt")), answer.toString)
  }

  test("a deployed service with no certificate, or no directory, is a Left naming which") {
    val missing = Files.createTempDirectory("service-identity-empty").toString
    val noFile  = ServiceIdentity.resolve(deployedWith(missing))
    assert(noFile.left.exists(_.contains(s"no certificate at $missing/tls.crt")), noFile.toString)

    val noDirectory = ServiceIdentity.resolve(deployedWith(""))
    assert(noDirectory.left.exists(_.contains("ankka.tls.service-directory")), noDirectory.toString)
  }

  test("a certificate naming the project reserved for local runs is a Left") {
    val directory = certificateDirectory("ankka://local/orders")
    val answer    = ServiceIdentity.resolve(deployedWith(directory))
    assert(answer.left.exists(_.contains("reserved for services run locally")), answer.toString)
  }
