Feature: A route's own ACL
  An HTTP endpoint states an ACL, and any of its routes may state one of its own, which replaces the
  endpoint's for that route alone. A route that states none answers to its endpoint's. So one
  endpoint can serve two audiences without being split in two or checking inside its handlers.

  Background:
    Given a service "cart" with an HTTP endpoint for the path "/carts"

  Scenario: an authenticated route of an endpoint that allows all challenges a request with no token, and its other routes serve it
    Given the endpoint's ACL allows all
    And its route "DELETE /carts/{cartId}" is an authenticated route
    When a person sends a request with no token to "DELETE /carts/c1"
    Then the request is challenged
    And the same request to "GET /carts/c1" is served

  Scenario: a route's ACL replaces its endpoint's rather than adding to it
    Given the endpoint's ACL denies all
    And its route "GET /carts/{cartId}" states an ACL that allows all
    When a person sends a request to "GET /carts/c1"
    Then the request is served

  Scenario: a closed endpoint refuses a path it has no route for, and says nothing of which paths exist
    Given the endpoint's ACL denies all
    When a person sends a request to "GET /carts/c1/unknown"
    Then the request is refused
    And the request is not told that there is no such route

  Scenario: a handler is told the principal its route's ACL established
    Given the endpoint's ACL allows all
    And its route "DELETE /carts/{cartId}" is an authenticated route
    When a person sends a request with a verified token whose subject is "ada" to "DELETE /carts/c1"
    Then the handler is told a principal whose subject is "ada"

  Scenario Outline: a route that states no ACL answers to its endpoint's, in every language
    Given the service "cart" is written in "<language>"
    And the endpoint's ACL denies all
    And its route "GET /carts/{cartId}" states no ACL
    When a person sends a request to "GET /carts/c1"
    Then the request is refused

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a route's own ACL applies to that route alone, in every language
    Given the service "cart" is written in "<language>"
    And the endpoint's ACL allows all
    And its route "DELETE /carts/{cartId}" states an ACL that denies all
    When a person sends a request to "DELETE /carts/c1"
    Then the request is refused
    And the same request to "GET /carts/c1" is served

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
