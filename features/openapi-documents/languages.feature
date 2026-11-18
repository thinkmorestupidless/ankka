Feature: OpenAPI documents in Scala, Java and Kotlin
  The three languages share one way of declaring routes, so the same service written in each has
  the same OpenAPI document, and each describes a route in its own form. A service in another
  language has no document yet, and is told so.

  Scenario Outline: the same service has the same document in every language that has one
    Given the service "cart" is written in "<language>"
    When the developer writes the service's OpenAPI document
    Then the document holds the same routes, path parameters, answers and shapes as the service written in "scala"

    Examples:
      | language |
      | java     |
      | kotlin   |

  Scenario Outline: a route description says the same in every language that has one
    Given the service "cart" is written in "<language>"
    And the route "GET /carts/{cartId}" has a route description naming the refusal "not found" and the query parameter "currency"
    When the developer writes the service's OpenAPI document
    Then the route has the refusal "not found" and the query parameter "currency"

    Examples:
      | language |
      | scala    |
      | java     |
      | kotlin   |

  Scenario Outline: a service in a language with no document yet is told so
    Given the service "cart" is written in "<language>"
    When the developer writes the service's OpenAPI document
    Then the writing fails, saying that a service in "<language>" has no OpenAPI document yet
    And no document is written

    Examples:
      | language   |
      | python     |
      | typescript |
      | go         |
      | rust       |
