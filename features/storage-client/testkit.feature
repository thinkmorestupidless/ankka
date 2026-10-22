Feature: The storage client in a test
  The test kit starts a service with a store in memory behind the storage client, so a test of a
  handler that keeps or reads an object needs no object store and no Docker. The test reaches the
  same client the service does, to keep an object before the handler runs and to read what the
  handler kept. Objects survive a restart of the service within the test, and go when the test
  kit stops.

  Scenario: a test keeps an object and the service reads it through the storage client
    Given a service "listings" started by the test kit
    And the test has kept the object "listings/42/photos/front.jpg" in the bucket of "listings"
    When a handler of "listings" reads the object "listings/42/photos/front.jpg" through the storage client
    Then the handler is told the object "listings/42/photos/front.jpg"
    And no object store was started

  Scenario: a test reads what the service kept through the storage client
    Given a service "listings" started by the test kit
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" with the content type "image/jpeg" through the storage client
    Then the test reads the object "listings/42/photos/front.jpg" back with the content type "image/jpeg"

  Scenario: objects kept in a test survive a restart of the service
    Given a service "listings" started by the test kit
    And a handler of "listings" has kept the object "listings/42/photos/front.jpg" through the storage client
    When the test restarts "listings"
    Then a handler of "listings" reads the object "listings/42/photos/front.jpg" back through the storage client

  Scenario: objects kept in a test go when the test kit stops
    Given a service "listings" started by the test kit
    And a handler of "listings" has kept the object "listings/42/photos/front.jpg" through the storage client
    When the test kit stops
    And the test kit starts "listings" again
    Then a handler that reads the object "listings/42/photos/front.jpg" is told that there is no such object

  Scenario: two services started by the test kit do not see each other's objects
    Given a service "listings" and a service "catalogue" started by the test kit
    And a handler of "listings" has kept the object "listings/42/photos/front.jpg" through the storage client
    When a handler of "catalogue" reads the object "listings/42/photos/front.jpg" through the storage client
    Then the handler is told that there is no such object

  Scenario: a test reaches a service's public bucket through the storage client
    Given a service "listings" started by the test kit with a public bucket "photos"
    When a handler of "listings" keeps the object "listings/42/front.jpg" in the public bucket "photos" through the storage client
    Then the test reads the object "listings/42/front.jpg" back from the bucket "photos" of "listings"
    And the storage client answers a public address for "listings/42/front.jpg" in the bucket "photos"
