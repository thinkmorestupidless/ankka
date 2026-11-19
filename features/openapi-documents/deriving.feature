Feature: An OpenAPI document derived from a service's routes
  A developer writes a service's OpenAPI document from its code, without starting the service and
  without changing it. Every route of its HTTP endpoints is in the document, with what the route's
  own declaration says of its path, its body and its answer.

  Background:
    Given a service "cart" with an HTTP endpoint for the path "/carts"

  Scenario: the document holds every route of the service's HTTP endpoints
    Given the endpoint has the routes "POST /carts/{cartId}/items" and "GET /carts/{cartId}"
    When the developer writes the service's OpenAPI document
    Then the document is a valid OpenAPI document
    And the document holds the routes "POST /carts/{cartId}/items" and "GET /carts/{cartId}"

  Scenario: a path parameter is in the document under its name, with the type its route gives it
    Given the endpoint has the route "GET /carts/{cartId}/items/{line}" whose "line" is a whole number
    When the developer writes the service's OpenAPI document
    Then the route has the path parameters "cartId", a text, and "line", a whole number

  Scenario: a typed body has its type's shape, and a body the service accepts fits it
    Given the endpoint has the route "POST /carts/{cartId}/items" whose body is an "AddItem"
    When the developer writes the service's OpenAPI document
    Then the route's body has the shape of "AddItem"
    And a body the running service accepts for that route fits the shape

  Scenario: a typed answer has its type's shape, and the answer the service gives fits it
    Given the endpoint has the route "GET /carts/{cartId}" answering a "Cart"
    When the developer writes the service's OpenAPI document
    Then the route's answer has the shape of "Cart"
    And the answer the running service gives for that route fits the shape

  Scenario: a route that answers nothing has an answer with no body, and is complete
    Given the endpoint has the route "DELETE /carts/{cartId}" answering nothing
    When the developer writes the service's OpenAPI document
    Then the route's answer has no body
    And the route is not an incomplete route

  Scenario: a route answered as a stream of events is in the document as one
    Given the endpoint has the route "GET /carts/{cartId}/events" answered as a stream of events
    When the developer writes the service's OpenAPI document
    Then the route's answer is a stream of events

  Scenario: a type two routes use is one named shape that both refer to
    Given the endpoint has the routes "GET /carts/{cartId}" and "POST /carts/{cartId}/checkout" each answering a "Cart"
    When the developer writes the service's OpenAPI document
    Then the document holds the shape "Cart" once
    And both routes refer to it

  Scenario: writing the document twice from the same code gives the same document
    Given the developer has written the service's OpenAPI document
    When the developer writes it again from the same code
    Then the two documents are the same, character for character

  Scenario Outline: what has no place in an OpenAPI document is left out of it
    Given the service also has <thing>
    When the developer writes the service's OpenAPI document
    Then the document holds nothing of <thing>

    Examples:
      | thing                        |
      | a socket route               |
      | an MCP endpoint              |
      | a gRPC endpoint              |
      | the platform's own routes    |

  Scenario: the document is written with no database, no broker and no network
    Given the developer's machine has no database, no broker and no network
    When the developer writes the service's OpenAPI document
    Then the document is written

  Scenario Outline: a route says who may call it as far as its ACL tells
    Given the route "GET /carts/{cartId}" states an ACL that <acl>
    When the developer writes the service's OpenAPI document
    Then the route is marked as <marked>

    Examples:
      | acl                                  | marked                                                |
      | allows all                           | needing no credential                                 |
      | admits tokens from the issuer "shop" | needing a token, with no issuer named                 |
      | admits only the workloads it names   | not open, with no credential named                    |

  Scenario: an endpoint that cannot be built without the running service fails the writing and is named
    Given the service has an HTTP endpoint for the path "/reports" that cannot be built without the running service
    When the developer writes the service's OpenAPI document
    Then the writing fails, naming the endpoint that could not be built
    And no document is written
