package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.javasdk.Ankka
import com.thinkmorestupidless.ankka.javasdk.codec.Serializer
import com.thinkmorestupidless.ankka.javasdk.fixtures.{Counter, ShoppingCart}
import com.thinkmorestupidless.ankka.javasdk.fixtures.broken.*
import com.thinkmorestupidless.ankka.javasdk.problems.{DeclarationProblem, ServiceProblems}

import java.nio.charset.StandardCharsets.UTF_8
import scala.jdk.CollectionConverters.*

/**
 * Every rule a class can break is found when the service starts, all of them at once, each naming
 * the class and the member; none waits for a request.
 */
class DeclarationSuite extends munit.FunSuite:

  private def problems(classes: Class[?]*): Vector[DeclarationProblem] =
    val e = intercept[ServiceProblems](
      JavaServiceBuilder.read(classes.toVector.map(_ -> None), Map.empty)
    )
    e.problems.asScala.toVector

  private def only(cls: Class[?]): DeclarationProblem =
    val found = problems(cls)
    assertEquals(found.size, 1, found.mkString("\n"))
    found.head

  test("a class with no component id") {
    val p = only(classOf[NoComponentId])
    assertEquals(p.component, "NoComponentId")
    assert(p.message.contains("@ComponentId"), p.message)
  }

  test("a handler taking two parameters") {
    val p = only(classOf[TwoParameters])
    assertEquals(p.member, "set")
    assert(p.message.contains("2 parameters"), p.message)
  }

  test("a query whose return type could persist") {
    val p = only(classOf[QueryThatPersists])
    assertEquals(p.member, "get")
    assert(p.message.contains("ReadOnlyEffect"), p.message)
  }

  test("two handlers with one wire name") {
    val p = only(classOf[DuplicateWireName])
    assert(p.message.contains("'add-item'"), p.message)
  }

  test("a method returning an effect with no annotation") {
    assertEquals(only(classOf[Unannotated]).member, "get")
  }

  test("a parameter type with no serializer, named with the type") {
    val p = only(classOf[NoSerializer])
    assertEquals(p.member, "set")
    assert(p.message.contains("Payload"), p.message)
  }

  test("a class with no constructor the platform can call") {
    assert(only(classOf[NoConstructor]).message.contains("constructor"))
  }

  test("a class that is no component") {
    assert(only(classOf[NotAComponent]).message.contains("not a component"))
  }

  test("every problem of every class at once, in registration order") {
    val found = problems(
      classOf[ShoppingCart],
      classOf[TwoParameters],
      classOf[Counter],
      classOf[NoComponentId]
    )
    assertEquals(found.map(_.component), Vector("TwoParameters", "NoComponentId"))
  }

  test("the service refuses to start, listing every problem") {
    val e = intercept[ServiceProblems](
      Ankka.service("shop").register(classOf[TwoParameters]).register(classOf[Unannotated]).start()
    )
    assertEquals(e.problems.size, 2)
    assert(e.getMessage.linesIterator.size == 3, e.getMessage)
  }

  test("a type the codec refuses is accepted with a serializer written by hand") {
    val payload = Serializer.of[NoSerializer.Payload](
      "payload",
      p => p.text.getBytes(UTF_8),
      b => { val p = NoSerializer.Payload(); p.text = String(b, UTF_8); p }
    )
    val read = JavaServiceBuilder.read(
      Vector(classOf[NoSerializer]      -> None),
      Map(classOf[NoSerializer.Payload] -> payload)
    )
    val handler = read.plans.head.handlers.head
    val input   = handler.input.get
    assertEquals(input.manifest, "payload")
    assertEquals(
      input.fromBytes("hello".getBytes(UTF_8)).asInstanceOf[NoSerializer.Payload].text,
      "hello"
    )
  }

  test("a component registered under another id keeps its handlers") {
    val read = JavaServiceBuilder.read(Vector(classOf[Counter] -> Some("tally")), Map.empty)
    assertEquals(read.plans.head.componentId, "tally")
    assertEquals(read.plans.head.handlers.map(_.wireName).sorted, Vector("get", "increase"))
  }
