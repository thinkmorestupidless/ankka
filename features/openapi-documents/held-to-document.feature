Feature: A test holds a service to its OpenAPI document
  A route description nobody checks goes stale. A test may hold the service to its document: then
  an answer the document does not allow, or a query parameter a route never declared, fails the
  test and names the route. A test that does not ask is unaffected.

  Background:
    Given a service "players" with an HTTP endpoint for the path "/players"
    And the route "GET /players/{playerId}/messages" whose route description gives its answer the shape of "Messages", names the refusal "not found" and names the query parameter "page"

  Scenario: an answer the description does not name fails the test
    Given a test that holds the service to its document
    When the route answers with the refusal "conflict"
    Then the test fails, naming the route and the refusal "conflict"

  Scenario: an answer body its shape refuses fails the test
    Given a test that holds the service to its document
    When the route answers a body that lacks a part "Messages" requires
    Then the test fails, naming the route and the part that is missing

  Scenario: a query parameter the route did not declare fails the test when the handler reads it
    Given a test that holds the service to its document
    When the route's handler reads the query parameter "pageSize"
    Then the test fails, naming the route and the query parameter "pageSize"

  Scenario: a service that answers as its document says passes
    Given a test that holds the service to its document
    When every request of the test is answered as the document says
    Then the test passes

  Scenario: a test that does not hold the service to its document is unaffected
    Given a test that does not hold the service to its document
    When the route answers with the refusal "conflict"
    Then nothing about the document fails the test

  Scenario: an incomplete route is held only to what it states
    Given a test that holds the service to its document
    And the route "PATCH /players/{playerId}" is an incomplete route that lacks its body's shape
    When a request to it carries any body its handler accepts
    Then nothing about the document fails the test
