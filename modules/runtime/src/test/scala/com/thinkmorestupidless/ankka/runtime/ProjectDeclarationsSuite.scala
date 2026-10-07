package com.thinkmorestupidless.ankka.runtime

import com.thinkmorestupidless.ankka.core.Contract

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

class ProjectDeclarationsSuite extends munit.FunSuite:

  private val file = """{"project": "shop",
    "topics": [
      {"name": "orders", "partitions": 3, "compacted": false, "contract": {"name": "order.v1", "fingerprint": "sha256:ab"}},
      {"name": "cart-deltas", "partitions": 1, "compacted": true}
    ],
    "brokers": [{"name": "legacy", "bootstrap": "kafka.legacy:9094", "shape": "sasl"}]}"""

  test("the operator's file is read") {
    val declared = ProjectDeclarations.parse(file.getBytes(UTF_8)).toOption.get
    assertEquals(declared.project, "shop")
    assertEquals(declared.topics("orders").contract, Some(Contract("order.v1", "sha256:ab")))
    assertEquals(declared.topics("cart-deltas").compacted, true)
    assertEquals(declared.topics("cart-deltas").contract, None)
    assertEquals(declared.brokers("legacy").bootstrap, "kafka.legacy:9094")
    assertEquals(declared.brokers("legacy").shape, "sasl")
  }

  test("no variable, or no file, declares nothing") {
    assertEquals(ProjectDeclarations.fromEnv(Map.empty), Right(None))
    assertEquals(
      ProjectDeclarations.fromEnv(Map(ProjectDeclarations.EnvVar -> "/nowhere/topics.json")),
      Right(None)
    )
  }

  test("the variable names a file") {
    val path = Files.createTempFile("topics", ".json")
    Files.writeString(path, file)
    val declared = ProjectDeclarations.fromEnv(Map(ProjectDeclarations.EnvVar -> path.toString))
    assertEquals(declared.map(_.map(_.topics.keySet)), Right(Some(Set("orders", "cart-deltas"))))
  }

  test("a file of another shape is a problem, not nothing") {
    assert(ProjectDeclarations.parse("""{"project": 1}""".getBytes(UTF_8)).isLeft)
    assert(
      ProjectDeclarations.parse("""{"project": "shop", "topics": {}}""".getBytes(UTF_8)).isLeft
    )
    assert(ProjectDeclarations.parse("not json".getBytes(UTF_8)).isLeft)
  }
