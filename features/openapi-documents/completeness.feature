Feature: An incomplete route is listed, and a developer may require completeness
  A route that takes a body, or gives an answer with a body, of which the OpenAPI document cannot
  state the shape is an incomplete route. It is still in the document, marked. A developer who
  requires completeness is refused the document while any route is incomplete.

  Background:
    Given a service "players" with an HTTP endpoint for the path "/players"
    And the route "PATCH /players/{playerId}" takes its body as text and has no route description

  Scenario: an incomplete route is in the document, marked with what it lacks
    When the developer writes the service's OpenAPI document
    Then the document holds the route "PATCH /players/{playerId}"
    And the route is marked an incomplete route that lacks its body's shape

  Scenario: requiring completeness fails the writing and names every incomplete route
    Given the route "GET /players/{playerId}" answers bytes and has no route description
    When the developer writes the service's OpenAPI document, requiring completeness
    Then the writing fails, naming "PATCH /players/{playerId}" and "GET /players/{playerId}" and what each lacks
    And no document is written

  Scenario: requiring completeness of a service whose routes are all complete writes the document
    Given the route's description gives the body the shape of "EditProfile" and names its answer
    When the developer writes the service's OpenAPI document, requiring completeness
    Then the document is written

  Scenario: a body or an answer that is a stream of bytes does not make a route incomplete
    Given the route "GET /players/{playerId}/export" answers a stream of bytes
    When the developer writes the service's OpenAPI document
    Then the route "GET /players/{playerId}/export" is not an incomplete route

  Scenario: a kept document that no longer matches the code is found, and nothing is written
    Given the developer keeps the service's OpenAPI document with its code
    And a route has been added since the document was written
    When the developer checks the kept document against the code
    Then the check fails, showing the route the kept document lacks
    And the kept document is unchanged
