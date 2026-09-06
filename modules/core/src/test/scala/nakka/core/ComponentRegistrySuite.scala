package nakka.core

class ComponentRegistrySuite extends munit.FunSuite:

  private def descriptor(id: String, k: ComponentKind): ComponentDescriptor =
    new ComponentDescriptor:
      val componentId = ComponentId(id)
      val kind        = k

  test("components are looked up by kind and id together") {
    val entity = descriptor("cart", ComponentKind.EventSourcedEntity)
    val view   = descriptor("cart", ComponentKind.View)
    val registry = ComponentRegistry.fromOrThrow(Seq(entity, view))

    assertEquals(registry.get(ComponentKind.EventSourcedEntity, ComponentId("cart")), Some(entity))
    assertEquals(registry.get(ComponentKind.View, ComponentId("cart")), Some(view))
    assertEquals(registry.get(ComponentKind.Workflow, ComponentId("cart")), None)
  }

  test("an id may be reused across kinds — an entity and its view often share a name") {
    val result = ComponentRegistry.from(
      Seq(
        descriptor("cart", ComponentKind.EventSourcedEntity),
        descriptor("cart", ComponentKind.View),
        descriptor("cart", ComponentKind.Consumer)
      )
    )
    assert(result.isRight, s"expected success, got $result")
  }

  test("duplicate registration within a kind is rejected at build time") {
    val result = ComponentRegistry.from(
      Seq(
        descriptor("cart", ComponentKind.EventSourcedEntity),
        descriptor("cart", ComponentKind.EventSourcedEntity)
      )
    )
    val problems = result.left.getOrElse(Vector.empty)
    assertEquals(problems.size, 1)
    assert(problems.head.contains("cart"), problems.head)
    assert(problems.head.contains("registered 2 times"), problems.head)
  }

  test("every duplicate is reported at once, not just the first") {
    val result = ComponentRegistry.from(
      Seq(
        descriptor("cart", ComponentKind.EventSourcedEntity),
        descriptor("cart", ComponentKind.EventSourcedEntity),
        descriptor("wallet", ComponentKind.KeyValueEntity),
        descriptor("wallet", ComponentKind.KeyValueEntity)
      )
    )
    assertEquals(result.left.getOrElse(Vector.empty).size, 2)
  }

  test("fromOrThrow surfaces every problem in one message") {
    val failure = intercept[IllegalArgumentException] {
      ComponentRegistry.fromOrThrow(
        Seq(
          descriptor("a", ComponentKind.View),
          descriptor("a", ComponentKind.View),
          descriptor("b", ComponentKind.Agent),
          descriptor("b", ComponentKind.Agent)
        )
      )
    }
    assert(failure.getMessage.contains("'a'"), failure.getMessage)
    assert(failure.getMessage.contains("'b'"), failure.getMessage)
  }

  test("ofKind filters and the empty registry is empty") {
    val registry = ComponentRegistry.fromOrThrow(
      Seq(
        descriptor("a", ComponentKind.View),
        descriptor("b", ComponentKind.View),
        descriptor("c", ComponentKind.Agent)
      )
    )
    assertEquals(registry.ofKind(ComponentKind.View).map(_.componentId).sorted, Vector("a", "b"))
    assertEquals(registry.size, 3)
    assert(ComponentRegistry.empty.isEmpty)
  }

  test("sharded kinds are exactly the stateful, instance-addressed ones") {
    import ComponentKind.*
    assert(EventSourcedEntity.sharded && KeyValueEntity.sharded && Workflow.sharded && Agent.sharded)
    assert(!View.sharded && !Consumer.sharded && !TimedAction.sharded && !Endpoint.sharded)
  }
