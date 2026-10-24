package com.thinkmorestupidless.ankka.javasdk.bridge

import com.thinkmorestupidless.ankka.core.effect.{EventSourcedEffect, KeyValueEffect}
import com.thinkmorestupidless.ankka.javasdk.Done
import com.thinkmorestupidless.ankka.javasdk.effect.{EventSourcedEffects, KeyValueEffects}
import com.thinkmorestupidless.ankka.javasdk.http.Problem

import java.nio.file.Files
import javax.tools.{DiagnosticCollector, JavaFileObject, ToolProvider}
import scala.jdk.CollectionConverters.*

/**
 * A Java effect is the Scala effect the same handler would return in Scala, reduced by the same
 * function; and the compiler refuses a persisting effect where a read-only one is declared.
 */
class EffectsSuite extends munit.FunSuite:

  private val es = Effects.eventSourced().asInstanceOf[EventSourcedEffects[String, String]]
  private val kv = Effects.keyValue().asInstanceOf[KeyValueEffects[Int]]

  private def reduce[R](effect: Any): EventSourcedEffect.Materialised[String, String, R] =
    EventSourcedEffect.materialise(
      Effects.unwrap(effect, "test").asInstanceOf[EventSourcedEffect[String, String, R]],
      "",
      (s, e) => s + e
    )

  test("persist then reply from the state after the events") {
    val m = reduce[Int](es.persist("a", "b").thenReply((s: String) => s.length))
    assertEquals(m.events, Vector("a", "b"))
    assertEquals(m.newState, "ab")
    assertEquals(m.replyOrThrow, Some(2))
  }

  test("thenDone replies Done, persistAll keeps order, a delete is retention") {
    assertEquals(reduce[Done](es.persist("x").thenDone()).replyOrThrow, Some(Done.DONE))
    assertEquals(
      reduce[String](es.persistAll(java.util.List.of("c", "d")).thenReplyState()).newState,
      "cd"
    )
    assert(reduce[Done](es.persist("x").thenDelete().thenDone()).retention.isDefined)
  }

  test("an error persists nothing, and a problem keeps its status") {
    val m = reduce[Done](es.error(Problem.notFound("no such cart")))
    assert(m.events.isEmpty)
    val error = m.reply.left.toOption.get
    assertEquals(Effects.toProblem(error).status, 404)
    assertEquals(error.message, "no such cart")
  }

  test("a key value update is the Scala update") {
    val m = KeyValueEffect.materialise(
      Effects
        .unwrap(kv.updateState(5).thenReplyState(), "test")
        .asInstanceOf[KeyValueEffect[Int, Int]],
      0
    )
    assertEquals(m.reply, Right(Some(5)))
    assert(m.changed)
  }

  test("something that is not an effect is named as such") {
    val e = intercept[IllegalStateException](Effects.unwrap("not an effect", "Cart.add"))
    assert(e.getMessage.contains("Cart.add"), e.getMessage)
  }

  test("the compiler refuses a persisting effect where a ReadOnlyEffect is declared") {
    val source =
      """package negative;
        |import com.thinkmorestupidless.ankka.javasdk.annotations.*;
        |import com.thinkmorestupidless.ankka.javasdk.component.KeyValueEntity;
        |import com.thinkmorestupidless.ankka.javasdk.effect.ReadOnlyEffect;
        |@ComponentId("n")
        |public class Negative extends KeyValueEntity<String> {
        |  @Query("get")
        |  public ReadOnlyEffect<String> get() {
        |    return effects().updateState("x").thenReplyState();
        |  }
        |}
        |""".stripMargin
    val (ok, messages) = compile(source)
    assert(!ok, "a persisting effect compiled as a ReadOnlyEffect")
    assert(messages.exists(_.contains("incompatible types")), messages.mkString("\n"))
    val (fine, problems) =
      compile(source.replace("""updateState("x").thenReplyState()""", """reply("x")"""))
    assert(fine, problems.mkString("\n"))
  }

  private def compile(source: String): (Boolean, List[String]) =
    val dir  = Files.createTempDirectory("negative")
    val file = dir.resolve("Negative.java")
    Files.writeString(file, source)
    val compiler    = ToolProvider.getSystemJavaCompiler
    val diagnostics = DiagnosticCollector[JavaFileObject]()
    val files       = compiler.getStandardFileManager(diagnostics, null, null)
    val units       = files.getJavaFileObjects(file.toFile)
    val options     = List("-d", dir.toString, "-classpath", System.getProperty("java.class.path"))
    val ok          = compiler.getTask(null, files, diagnostics, options.asJava, null, units).call()
    (ok, diagnostics.getDiagnostics.asScala.toList.map(_.getMessage(null)))
