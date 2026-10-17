package com.thinkmorestupidless.ankka.operator

/**
 * The model server's three settings: all of them, none of them, or a refusal naming what is
 * missing.
 */
class ModelServerSettingsSuite extends munit.FunSuite:

  private val all = Map(
    "ANKKA_MODELS_ADDRESS"    -> "https://models.models-system.svc:8443",
    "ANKKA_MODELS_STATUS_URL" -> "http://models.models-system.svc:8080/ankka/models",
    "ANKKA_MODELS_NAMESPACE"  -> "models-system"
  )

  private def lookup(values: Map[String, String]): (String, String) => Option[String] =
    (_, variable) => values.get(variable)

  test("all three are the installation's model server") {
    assertEquals(
      ModelServerSettings.read(lookup(all)),
      Some(
        ModelServerSettings(
          "https://models.models-system.svc:8443",
          "http://models.models-system.svc:8080/ankka/models",
          "models-system"
        )
      )
    )
  }

  test("none is an installation without one") {
    assertEquals(ModelServerSettings.read(lookup(Map.empty)), None)
  }

  test("each one missing is refused, naming it") {
    for missing <- all.keys do
      val error = intercept[IllegalStateException](ModelServerSettings.read(lookup(all - missing)))
      assertEquals(
        error.getMessage,
        s"the model server is configured by halves: $missing is not set; set all three or none"
      )
  }

  test("two missing are named together") {
    val error = intercept[IllegalStateException](
      ModelServerSettings.read(lookup(Map("ANKKA_MODELS_ADDRESS" -> "https://m:8443")))
    )
    assertEquals(
      error.getMessage,
      "the model server is configured by halves: ANKKA_MODELS_STATUS_URL, ANKKA_MODELS_NAMESPACE " +
        "are not set; set all three or none"
    )
  }
