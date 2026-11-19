package com.thinkmorestupidless.ankka.javasdk.testkit

import com.thinkmorestupidless.ankka.core.graph.GraphJson as J
import com.thinkmorestupidless.ankka.testkit.LogCapturing

import java.nio.file.{Files, Path}
import scala.concurrent.duration.DurationInt

/**
 * The Java cart's OpenAPI document (feature 067, T033), written by its own `main`, kept beside it.
 */
class OpenApiJavaDocumentSuite extends munit.FunSuite with LogCapturing:

  override val munitTimeout = 3.minutes

  private lazy val written: Array[Byte] =
    WrittenDocument.of("shoppingcart.javasdk.Main").fold(fail(_), identity)

  test("the sample's kept openapi.json is the document its code gives") {
    val kept = Path.of("openapi.json")
    if sys.props.get("ankka.docs.update").contains("true") then Files.write(kept, written): Unit
    assert(Files.exists(kept), "samples/shopping-cart-java/openapi.json is not kept")
    assert(
      Files.readAllBytes(kept).sameElements(written),
      "samples/shopping-cart-java/openapi.json is stale: run " +
        "sbt -Dankka.docs.update=true 'shoppingCartJava/testOnly *OpenApiJavaDocumentSuite' and commit it"
    )
  }

  test("every route of the cart is complete, the receipt described as the Scala cart's is") {
    val document = WrittenDocument.parse(written)
    val carts    = WrittenDocument.operations(document, "/carts")
    val partial  = carts.collect {
      case (route, op) if op.field("x-ankka-incomplete").isDefined => route
    }
    assert(partial.isEmpty, s"incomplete: $partial")
    val receipt = carts("POST /carts/{cartId}/receipt")
    assertEquals(receipt.field("summary"), Some(J.Str("A cart's receipt")))
    assertEquals(
      WrittenDocument.at(receipt, "responses", "404", "description"),
      Some(J.Str("the cart is empty"))
    )
  }
