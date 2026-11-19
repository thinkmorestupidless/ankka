Feature: A route description says what a route's types do not
  A route whose body is text, whose answer is bytes it built itself, which reads query parameters
  in its handler or refuses with a status of its own carries a route description. The OpenAPI
  document then says as much of it as of a typed route. A description adds to what the route's
  declaration says and never contradicts it.

  Background:
    Given a service "players" with an HTTP endpoint for the path "/players"

  Scenario: a text body described with a shape has that shape in the document
    Given the route "PATCH /players/{playerId}" takes its body as text
    And its route description gives the body the shape of "EditProfile"
    When the developer writes the service's OpenAPI document
    Then the route's body has the shape of "EditProfile"

  Scenario: an answer of bytes described with a shape has that shape in the document
    Given the route "GET /players/{playerId}" answers bytes
    And its route description gives its answer the shape of "Profile"
    When the developer writes the service's OpenAPI document
    Then the route's answer has the shape of "Profile"

  Scenario: a described refusal stands beside the answer the route's type gives
    Given the route "GET /players/{playerId}" answers a "Profile"
    And its route description names a refusal "not found"
    When the developer writes the service's OpenAPI document
    Then the route has the answer of shape "Profile" and the refusal "not found"

  Scenario: a described refusal given no shape has the shape of the platform's own refusals
    Given the route description of "GET /players/{playerId}" names a refusal "not found" and gives it no shape
    When the developer writes the service's OpenAPI document
    Then the refusal "not found" has the shape of the platform's own refusals

  Scenario: an endpoint that writes refusals of its own gives their shape once for all its routes
    Given the endpoint states the shape "Refusal" for the refusals of all its routes
    And the route description of "GET /players/{playerId}" names a refusal "not found" and gives it no shape
    When the developer writes the service's OpenAPI document
    Then the refusal "not found" has the shape of "Refusal"

  Scenario: a described query parameter is in the document with its type and whether it is required
    Given the route description of "GET /players/{playerId}/messages" names the query parameters "page", an optional whole number, and "status", a required text
    When the developer writes the service's OpenAPI document
    Then the route has the query parameter "page", an optional whole number
    And the route has the query parameter "status", a required text

  Scenario: a shape described by a type is the named shape a typed route would have
    Given the route description of "GET /players/{playerId}" gives its answer the shape of the type "Profile"
    And the route "GET /players/{playerId}/summary" answers a "Profile"
    When the developer writes the service's OpenAPI document
    Then both routes refer to the one shape "Profile"

  Scenario: a shape described as JSON Schema is in the document as it was given
    Given the route description of "GET /players/{playerId}" gives its answer a shape written as JSON Schema
    When the developer writes the service's OpenAPI document
    Then the route's answer has that shape as it was given

  Scenario Outline: a description that contradicts the route fails the writing and names the route
    Given the route "POST /players/{playerId}/emails" whose body is an "AddEmail"
    And its route description <contradiction>
    When the developer writes the service's OpenAPI document
    Then the writing fails, naming the route and <named>
    And no document is written

    Examples:
      | contradiction                                      | named                       |
      | gives the body a different shape                   | the body's two shapes       |
      | names a query parameter "playerId", a path parameter's name | the parameter "playerId" |
      | names the query parameter "page" twice             | the query parameter "page"  |

  Scenario: describing a route changes nothing of how it answers
    Given the route "GET /players/{playerId}" has no route description
    When the developer gives it one
    Then every request to the route is answered as it was before

  Scenario: an endpoint's route tags and refusals are carried by each of its routes
    Given the endpoint states the route tag "player" and the refusal "wrong brand" for all its routes
    And its route "GET /players/{playerId}" states the route tag "profile"
    When the developer writes the service's OpenAPI document
    Then the route has the route tags "player" and "profile" and the refusal "wrong brand"

  Scenario: a document written for one route tag holds only the routes that carry it
    Given the route "GET /players/{playerId}" carries the route tag "player"
    And the route "POST /players/{playerId}/close" carries the route tag "operator"
    When the developer writes the service's OpenAPI document for the route tag "player"
    Then the document holds the route "GET /players/{playerId}"
    And the document holds neither the route "POST /players/{playerId}/close" nor a shape only it uses

  Scenario: a document written for one path prefix holds only the routes under it
    Given the service also has an HTTP endpoint for the path "/sessions"
    When the developer writes the service's OpenAPI document for the paths under "/players"
    Then the document holds no route under "/sessions"

  Scenario: a selection that matches no route fails the writing
    Given no route carries the route tag "affiliate"
    When the developer writes the service's OpenAPI document for the route tag "affiliate"
    Then the writing fails, saying that no route matches
    And no document is written
