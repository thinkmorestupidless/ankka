package com.thinkmorestupidless.ankka.sidecar.conformance

import java.nio.file.Files

/**
 * Which reference `ankka.conformance.reference` names, and that nothing else falls back to Scala.
 */
class EmbeddedReferenceSuite extends munit.FunSuite:

  test("no value, an empty one and scala all name the Scala reference") {
    Seq(None, Some(""), Some(" "), Some("scala")).foreach(value =>
      assert(EmbeddedReference.named(value) eq ConformanceReference.Embedded, value.toString)
    )
    assertEquals(ConformanceReference.Embedded.language, "Scala")
  }

  test("a value that names no reference is an error, naming the choices") {
    Seq("Scala", "kotlin", "python", "classpath:", "classpath:only-a-file").foreach { value =>
      val refused = intercept[IllegalArgumentException](EmbeddedReference.named(Some(value)))
      assert(refused.getMessage.contains(value), refused.getMessage)
    }
  }

  test("a classpath entry is refused for a missing file, a missing class or the wrong type") {
    val missing = intercept[IllegalArgumentException](
      EmbeddedReference.named(Some("classpath:/no/such/file.jar:x.Y"))
    )
    assert(missing.getMessage.contains("no file at"), missing.getMessage)
    val dir = Files.createTempDirectory("conformance-entry")
    val noClass = intercept[IllegalArgumentException](
      EmbeddedReference.named(Some(s"classpath:$dir:no.such.Entry"))
    )
    assert(noClass.getMessage.contains("no class no.such.Entry"), noClass.getMessage)
    val wrongType = intercept[IllegalArgumentException](
      EmbeddedReference.named(Some(s"classpath:$dir:java.lang.Object"))
    )
    assert(
      wrongType.getMessage.contains("does not implement ConformanceEntry"),
      wrongType.getMessage
    )
  }

  test("the relay answers the number after the one it was handed, on an instance of its own") {
    assertEquals(ConformanceReference.Embedded.relay(7), Some(8))
  }
