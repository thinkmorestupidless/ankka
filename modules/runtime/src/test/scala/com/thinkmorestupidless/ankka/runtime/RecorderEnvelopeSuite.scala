package com.thinkmorestupidless.ankka.runtime

/** Telemetry never carries a personal envelope (feature 042, R24). */
final class RecorderEnvelopeSuite extends munit.FunSuite:

  private val envelope =
    """{"$personal":1,"project":"brand","subject":"player/8c1f","c":"AAECAwQFBgcICQoL"}"""

  test("an attribute holding a personal envelope is dropped, and the others kept") {
    val r    = Recorder(16)
    val span = r.begin(1L, 0L, componentRef = 1, handlerRef = 2)
    r.withAttribute(span, "client-key", "ck-1")
    r.withAttribute(span, "argument", envelope)
    r.withAttribute(span, "nested", s"""{"email":$envelope}""")
    r.complete(span, SpanOutcome.Ok)
    assertEquals(r.snapshot().head.attributes, Map("client-key" -> "ck-1"))
  }

  test("a span's attributes print a subject, never the value of a personal field") {
    import com.thinkmorestupidless.ankka.core.{DataSubject, Personal}
    val r    = Recorder(16)
    val span = r.begin(1L, 0L, componentRef = 1, handlerRef = 2)
    r.withAttribute(span, "email", Personal(DataSubject("player/8c1f"), "ada@example.com").toString)
    r.complete(span, SpanOutcome.Ok)
    assertEquals(r.snapshot().head.attributes, Map("email" -> "Personal(player/8c1f)"))
  }
