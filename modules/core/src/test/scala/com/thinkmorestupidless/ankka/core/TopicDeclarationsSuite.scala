package com.thinkmorestupidless.ankka.core

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

/**
 * `topics.json` against the contract (feature 066, contracts/project-files.md): the example reads,
 * a field the platform does not know is refused by name, and each problem the file can have on its
 * own comes from a file that has only that defect, in the contract's words.
 */
class TopicDeclarationsSuite extends munit.FunSuite:

  private val Example = """{
    "topics": [
      {"name": "orders-placed", "partitions": 6,
       "contract": "order.v1", "schema": "contracts/order.v1.json"},
      {"name": "customers", "partitions": 1, "compacted": true, "retention": "everything"}
    ]
  }"""

  private def root(): Path =
    val dir  = Files.createTempDirectory("topics")
    val file = dir.resolve("contracts/order.v1.json")
    Files.createDirectories(file.getParent)
    Files.writeString(file, """{"type":"object"}""", UTF_8)
    dir

  private def declarations(text: String): TopicDeclarations =
    TopicDeclarations.parse(text).fold(p => fail(p.mkString("; ")), identity)

  private def one(topic: TopicDeclarations.Topic): Vector[String] =
    TopicDeclarations.problems(root(), TopicDeclarations(Vector(topic)))

  test("the contract's example reads, with its defaults") {
    val read = declarations(Example)
    assertEquals(
      read.topics,
      Vector(
        TopicDeclarations.Topic(
          "orders-placed",
          6,
          contract = Some("order.v1"),
          schema = Some("contracts/order.v1.json")
        ),
        TopicDeclarations.Topic("customers", 1, compacted = true, retention = Some("everything"))
      )
    )
    assertEquals(TopicDeclarations.problems(root(), read), Vector.empty)
  }

  test("a project with no topics.json has no declarations, and that is not a problem") {
    assertEquals(TopicDeclarations.read(Files.createTempDirectory("topics")), Right(None))
  }

  test("the file is read from the project's root") {
    val dir = root()
    Files.writeString(dir.resolve(TopicDeclarations.FileName), Example, UTF_8)
    assertEquals(
      TopicDeclarations.read(dir).map(_.map(_.topics.map(_.name))),
      Right(Some(Vector("orders-placed", "customers")))
    )
  }

  test("a field the platform does not know is refused by name") {
    assertEquals(
      TopicDeclarations.parse("""{"topics":[{"name":"a","partitions":1,"removes":"x"}]}"""),
      Left(
        Vector(
          "topics.json is not a topic declarations file: it names a field the platform does not know: \"removes\""
        )
      )
    )
  }

  test("a file that is not JSON is not a declarations file") {
    val read = TopicDeclarations.parse("topics: []")
    assert(
      read.left.exists(_.head.startsWith("topics.json is not a topic declarations file: ")),
      read
    )
  }

  test("a topic declared twice") {
    val twice = TopicDeclarations(
      Vector(TopicDeclarations.Topic("a", 1), TopicDeclarations.Topic("a", 2))
    )
    assertEquals(
      TopicDeclarations.problems(root(), twice),
      Vector("topics.json declares \"a\" twice")
    )
  }

  test("a name that is not a topic name") {
    assertEquals(
      one(TopicDeclarations.Topic("Orders Placed", 1)),
      Vector(s"the topic \"Orders Placed\" is not a valid topic name: ${Names.TopicNameRule}")
    )
  }

  test("partitions outside one to a thousand") {
    assertEquals(
      one(TopicDeclarations.Topic("a", 0)),
      Vector("the topic \"a\" declares 0 partitions; a topic has 1 to 1000")
    )
    assertEquals(
      one(TopicDeclarations.Topic("a", 1001)),
      Vector("the topic \"a\" declares 1001 partitions; a topic has 1 to 1000")
    )
  }

  test("a contract with no schema, and a schema with no contract") {
    assertEquals(
      one(TopicDeclarations.Topic("a", 1, contract = Some("order.v1"))),
      Vector("the topic \"a\" names a contract and no schema")
    )
    assertEquals(
      one(TopicDeclarations.Topic("a", 1, schema = Some("contracts/order.v1.json"))),
      Vector("the topic \"a\" names a schema and no contract")
    )
  }

  test("a schema that does not exist, and one that is not JSON") {
    assertEquals(
      one(
        TopicDeclarations
          .Topic("a", 1, contract = Some("order.v1"), schema = Some("contracts/none.json"))
      ),
      Vector("the topic \"a\" names the schema contracts/none.json, which does not exist")
    )
    val dir = root()
    Files.writeString(dir.resolve("contracts/bad.json"), "not json", UTF_8)
    assertEquals(
      TopicDeclarations.problems(
        dir,
        TopicDeclarations(
          Vector(
            TopicDeclarations
              .Topic("a", 1, contract = Some("order.v1"), schema = Some("contracts/bad.json"))
          )
        )
      ),
      Vector("the topic \"a\" names the schema contracts/bad.json, which is not a JSON document")
    )
  }

  test("a contract name that is not a contract name") {
    assertEquals(
      one(
        TopicDeclarations
          .Topic("a", 1, contract = Some("Order V1"), schema = Some("contracts/order.v1.json"))
      ),
      Vector(
        s"the topic \"a\" names the contract \"Order V1\", which is not a valid contract name: ${Contract.NameRule}"
      )
    )
  }
