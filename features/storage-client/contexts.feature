Feature: Where the storage client is offered
  The storage client is offered to an endpoint, a workflow step, a consumer, a timed action and
  an agent, beside the secret store, and not to an entity or a view: keeping or reading an object
  is a blocking call the single-writer path must not make. An entity keeps an object's name; the
  bucket keeps the object. A service that has no bucket is told so when it calls the client.

  Scenario Outline: the storage client is offered to an endpoint, a workflow step, a consumer, a timed action and an agent
    Given a deployed service "listings" with a bucket
    When <handler> of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then a handler of "listings" reads the object "listings/42/photos/front.jpg" back through the storage client

    Examples:
      | handler         |
      | an endpoint     |
      | a workflow step |
      | a consumer      |
      | a timed action  |
      | an agent        |

  Scenario Outline: an entity and a view have no storage client
    Given a deployed service "listings" with a bucket
    When <component> of "listings" asks for the storage client
    Then it is given none

    Examples:
      | component |
      | an entity |
      | a view    |

  Scenario: a workflow keeps an object in a step and not in a command
    Given a deployed service "listings" with a bucket
    When a command of a workflow of "listings" keeps the object "listings/42/photos/front.jpg"
    Then the command is refused
    And the refusal says that an object is kept in a step

  Scenario: a service that has no bucket is told so when it calls the storage client
    Given a deployed service "catalogue" with no bucket
    When a handler of "catalogue" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then what the handler did fails
    And the failure says that the service has no bucket and names "provisionObjectStorage"

  Scenario: a service that reaches its bucket as its workload identity is refused by the storage client with the reason
    Given a deployed service "listings" with a bucket in Google Cloud Storage, whose descriptor declines a storage credential
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then what the handler did fails
    And the failure says that the service has no storage credential to sign with and names "objectStorageCredential"

  Scenario: an entity keeps an object's name and the bucket keeps the object
    Given a deployed service "listings" with a bucket
    When an endpoint of "listings" keeps the object "listings/42/photos/front.jpg" and records its name on the entity "listing-42"
    Then the entity "listing-42" holds the name "listings/42/photos/front.jpg" and no part of the object
    And the bucket of "listings" holds the object "listings/42/photos/front.jpg"
