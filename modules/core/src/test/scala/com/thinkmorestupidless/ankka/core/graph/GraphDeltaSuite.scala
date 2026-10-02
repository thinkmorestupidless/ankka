package com.thinkmorestupidless.ankka.core.graph

/**
 * A graph delta as ankka writes it is a graph delta as ankka-flow's merge sink reads it.
 *
 * The rows are ankka-flow's own fixtures, read there by the sink's suite. Here each is built the
 * way an author's call builds it and must come out with the row's key and a value that reads back
 * as the row's delta.
 */
class GraphDeltaSuite extends munit.FunSuite:
  import GraphFixtures.*

  private val keys   = rows("keys.json")
  private val deltas = rows("deltas.json")

  private def kindOf(value: PropertyValue): String = value match
    case _: String     => "string"
    case _: Boolean    => "boolean"
    case _: Long       => "integer"
    case _: Int        => "integer"
    case _: Double     => "float"
    case items: Seq[?] => s"list:${kindOf(items.head.asInstanceOf[PropertyValue])}"

  test("the fixtures are the ones expected: every kind of delta, every kind of property") {
    assert(keys.sizeIs >= 8, keys.size)
    assert(deltas.sizeIs >= 12, deltas.size)
    val kinds =
      (keys ++ deltas).map(r => build(r.field("delta").get)).map(d => (d.kind, d.element)).toSet
    assertEquals(kinds.size, 4, kinds.toString)
    val read = deltas.flatMap(r => build(r.field("delta").get).properties.values.map(kindOf)).toSet
    assertEquals(
      read,
      Set("string", "integer", "float", "boolean").flatMap(k => Set(k, s"list:$k"))
    )
  }

  (keys.map("keys.json" -> _) ++ deltas.map("deltas.json" -> _)).zipWithIndex.foreach {
    case ((file, row), index) =>
      val key = text(row, "key")
      test(
        s"$file row $index ($key): built, it has the row's key and reads back as the row's delta"
      ) {
        val fixture = row.field("delta").get
        val built   = build(fixture)
        assertEquals(built.key, key)

        val written = GraphDelta.serializer.toBytes(built)
        // What was written reads back as what was built, and under the row's key.
        assertEquals(GraphDelta.read(Some(key), written), Right(built))
        // And as what the fixture holds, which is what the sink was shown to accept.
        assertEquals(GraphDelta.read(Some(key), bytes(fixture)), Right(built))

        // Each property is the kind the sink reads it as.
        row.field("reads") match
          case Some(GraphJson.Obj(reads)) =>
            assertEquals(
              built.properties.view.mapValues(kindOf).toMap,
              reads.collect { case (name, GraphJson.Str(kind)) => name -> kind }.toMap
            )
          case _ => ()
      }
  }

  test("a node and an edge with one id have different keys") {
    assertEquals(GraphDelta.nodeKey("same"), "node:same")
    assertEquals(GraphDelta.edgeKey("same"), "edge:same")
    assertEquals(GraphRules.node("same", Nil, Map.empty).key, "node:same")
    assertEquals(GraphRules.edge("same", "T", "a", "b", Map.empty).key, "edge:same")
    // A tombstone has the key of the element it marks.
    assertEquals(GraphRules.tombstoneNode("same").key, "node:same")
    assertEquals(GraphRules.tombstoneEdge("same", "T", "a", "b").key, "edge:same")
  }

  test("labels and properties are always written, empty when there are none") {
    val node = String(GraphDelta.serializer.toBytes(build(keysNode("bare"))), "UTF-8")
    assertEquals(node, """{"kind":"node","id":"bare","version":1,"labels":[],"properties":{}}""")
    val tombstone =
      GraphDelta.serializer.toBytes(GraphRules.tombstoneNode("gone").copy(version = 3))
    assertEquals(
      String(tombstone, "UTF-8"),
      """{"kind":"tombstone","element":"node","id":"gone","version":3}"""
    )
  }

  private def keysNode(id: String): GraphJson =
    GraphJson.Obj(Vector("kind" -> GraphJson.Str("node"), "id" -> GraphJson.Str(id)))

  test("a whole-number float is the integer the sink stores, and equal to it") {
    val float = GraphRules.node("n", Nil, Map("p" -> 2.0, "q" -> Seq(1.0, 2.0)))
    val whole = GraphRules.node("n", Nil, Map("p" -> 2, "q" -> Seq(1L, 2L)))
    assertEquals(float, whole)
    assertEquals(float.properties("p"), 2L: PropertyValue)
  }

  test("the reader refuses a record whose key is not the delta's element key") {
    val value = GraphDelta.serializer.toBytes(build(keysNode("cart:c1")))
    assertEquals(
      GraphDelta.read(Some("c1"), value),
      Left("key 'c1' is not this delta's element key 'node:cart:c1'")
    )
    assertEquals(
      GraphDelta.read(None, value),
      Left("no key; this delta's element key is 'node:cart:c1'")
    )
    assertEquals(GraphDelta.read(Some("edge:cart:c1"), value).isLeft, true)
    assert(GraphDelta.read(Some("node:cart:c1"), value).isRight)
  }

  test("the reader refuses what the contract's validation refuses, in its words") {
    def read(json: String) = GraphDelta.read(json.getBytes("UTF-8"))
    assertEquals(read("[]"), Left("not a JSON object"))
    assertEquals(read("not json"), Left("not a JSON object"))
    assertEquals(read("{}"), Left("kind missing"))
    assertEquals(read("""{"kind":"vertex","id":"n","version":1}"""), Left("unknown kind 'vertex'"))
    assertEquals(read("""{"kind":"node","id":"","version":1}"""), Left("id missing or empty"))
    assertEquals(
      read("""{"kind":"node","id":"n"}"""),
      Left("version is not a non-negative integer")
    )
    assertEquals(
      read("""{"kind":"node","id":"n","version":-1}"""),
      Left("version is not a non-negative integer")
    )
    assertEquals(
      read("""{"kind":"node","id":"n","version":1.5}"""),
      Left("version is not a non-negative integer")
    )
    assertEquals(
      read("""{"kind":"node","id":"n","version":1,"labels":["a b"]}"""),
      Left("labels must be an array of identifiers")
    )
    assertEquals(
      read("""{"kind":"edge","id":"e","version":1,"type":"T","from":"a"}"""),
      Left("edge needs type, from and to")
    )
    assertEquals(
      read("""{"kind":"tombstone","id":"e","version":1}"""),
      Left("tombstone needs element 'node' or 'edge'")
    )
    assertEquals(
      read("""{"kind":"tombstone","element":"edge","id":"e","version":1}"""),
      Left("tombstone of an edge needs type, from and to")
    )
    assertEquals(
      read("""{"kind":"node","id":"n","version":1,"properties":[]}"""),
      Left("properties must be an object")
    )
    assertEquals(
      read("""{"kind":"node","id":"n","version":1,"properties":{"p":{"a":1}}}"""),
      Left("property 'p' is not a scalar or array of scalars")
    )
    assertEquals(
      read("""{"kind":"node","id":"n","version":1,"properties":{"_version":1}}"""),
      Left("property '_version' is reserved")
    )
  }

  test(
    "the reader accepts a version of zero, which the contract allows and the builder never writes"
  ) {
    val read = GraphDelta.read("""{"kind":"node","id":"n","version":0}""".getBytes("UTF-8"))
    assertEquals(read.map(_.version), Right(0L))
  }

  test("the serializer reads what it writes, and names the fault when it cannot") {
    val delta = build(deltas.head.field("delta").get)
    assertEquals(GraphDelta.serializer.manifest, "ankka.graph-delta.v1")
    assertEquals(GraphDelta.serializer.fromBytes(GraphDelta.serializer.toBytes(delta)), delta)
    val failure =
      intercept[IllegalArgumentException](GraphDelta.serializer.fromBytes("{}".getBytes))
    assertEquals(failure.getMessage, "kind missing")
  }
