package com.thinkmorestupidless.ankka.core

import java.util.concurrent.{CountDownLatch, CyclicBarrier, TimeUnit}
import java.util.concurrent.atomic.AtomicReference

final class KeyringSuite extends munit.FunSuite:

  private def named(name: String): Keyring = new Keyring:
    val project: String                                                        = name
    def key(project: String, subject: DataSubject, create: Boolean): KeyAnswer = KeyAnswer.Destroyed
    override def toString: String                                              = name

  test("with nothing installed or bound, the current keyring is unavailable") {
    assertEquals(Keyring.current, Keyring.unavailable)
    val e = intercept[CommandError](Keyring.current.key("brand", DataSubject("a"), create = true))
    assertEquals(e.code, ErrorCode.Unavailable)
  }

  test("a keyring bound on a thread wins over the installed one, and is unbound after") {
    val installed = named("installed")
    Keyring.install(installed)
    try
      assertEquals(Keyring.current, installed)
      Keyring.binding(named("bound"))(assertEquals(Keyring.current.project, "bound"))
      assertEquals(Keyring.current, installed)
    finally Keyring.uninstall(installed)
    assertEquals(Keyring.current, Keyring.unavailable)
  }

  test("uninstalling a keyring that is no longer installed leaves the installed one") {
    val first  = named("first")
    val second = named("second")
    Keyring.install(first)
    Keyring.install(second)
    Keyring.uninstall(first)
    try assertEquals(Keyring.current, second)
    finally Keyring.uninstall(second)
  }

  test("two keyrings bound on two threads never cross") {
    val barrier = CyclicBarrier(2)
    val done    = CountDownLatch(2)
    val seen    = Vector.fill(2)(AtomicReference[Vector[String]](Vector.empty))
    (0 until 2).foreach { i =>
      Thread.ofVirtual().start { () =>
        try
          Keyring.binding(named(s"service-$i")) {
            (0 until 200).foreach { _ =>
              barrier.await(5, TimeUnit.SECONDS)
              seen(i).updateAndGet(_ :+ Keyring.current.project)
            }
          }
        finally done.countDown()
      }
    }
    assert(done.await(30, TimeUnit.SECONDS))
    assertEquals(seen(0).get.distinct, Vector("service-0"))
    assertEquals(seen(1).get.distinct, Vector("service-1"))
    assertEquals(seen(0).get.size, 200)
  }
