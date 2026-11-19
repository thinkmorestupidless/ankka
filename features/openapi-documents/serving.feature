Feature: A deployed service serves its OpenAPI document to the callers it names
  Serving the document tells a reader every route a service has, so a service serves it only when
  it states who may read it. A service that states nothing serves nothing, and says nothing of
  whether a document exists.

  Background:
    Given a deployed service "cart" with an HTTP endpoint for the path "/carts"

  Scenario: a caller the service names is given the document, the same as the one written from its code
    Given the service states an ACL for its OpenAPI document that admits tokens from the issuer "shop"
    When a person with a verified token from the issuer "shop" asks the service for its OpenAPI document
    Then the person is given the document
    And it is the same as the document a developer writes from that build

  Scenario: a caller the service does not name is refused as that ACL refuses
    Given the service states an ACL for its OpenAPI document that admits tokens from the issuer "shop"
    When a person with no token asks the service for its OpenAPI document
    Then the request is challenged

  Scenario: a service that states nothing about its document answers as for a path it does not have
    Given the service states no ACL for its OpenAPI document
    When a person asks the service for its OpenAPI document
    Then the request is answered as a request to a path the service has no route for

  Scenario: the served document is whole, whatever the reader may call
    Given the service states an ACL for its OpenAPI document that admits tokens from the issuer "shop"
    And the route "DELETE /carts/{cartId}" admits only the workloads it names
    When a person with a verified token from the issuer "shop" asks the service for its OpenAPI document
    Then the document holds the route "DELETE /carts/{cartId}"

  Scenario: a local service's document is given to its developer whether or not the service serves it
    Given a local service "cart" that states no ACL for its OpenAPI document
    When the developer asks the local platform for the service's OpenAPI document
    Then the developer is given the document
