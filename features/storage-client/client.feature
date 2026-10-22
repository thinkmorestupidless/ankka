Feature: A service reaches its bucket through the storage client
  A service keeps and reads objects through the storage client the platform offers it, and not
  through a client of its own. The client keeps an object from a stream with its content type,
  reads one back as a stream, deletes one, lists the objects under a prefix and tells what an
  object is. An object's name may have slashes, and a listing by prefix reads like a directory,
  but there is none: the client offers no directory, no rename, no append and no partial write.
  The service's code is the same whichever object store stands behind the client.

  Background:
    Given a deployed service "listings" with a bucket

  Scenario: a service keeps an object through the storage client and reads it back
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then a handler of "listings" reads the object "listings/42/photos/front.jpg" back through the storage client

  Scenario: an object is read back with the content type it was kept with
    Given a handler of "listings" has kept the object "listings/42/photos/front.jpg" with the content type "image/jpeg"
    When a handler of "listings" reads the object "listings/42/photos/front.jpg"
    Then the handler is told the content type "image/jpeg"

  Scenario: a service lists the objects under a prefix
    Given a handler of "listings" has kept the objects "listings/42/photos/front.jpg", "listings/42/photos/back.jpg" and "listings/43/photos/front.jpg"
    When a handler of "listings" lists the objects under the prefix "listings/42/"
    Then the handler is told "listings/42/photos/front.jpg" and "listings/42/photos/back.jpg" and no other
    And each is told with its size and when it was kept

  Scenario: a service asks what an object is and is told its size, its content type and when it was kept
    Given a handler of "listings" has kept the object "listings/42/photos/front.jpg" with the content type "image/jpeg"
    When a handler of "listings" asks what the object "listings/42/photos/front.jpg" is
    Then the handler is told its size, the content type "image/jpeg" and when it was kept

  Scenario: a service deletes an object and it is no longer there
    Given a handler of "listings" has kept the object "listings/42/photos/front.jpg"
    When a handler of "listings" deletes the object "listings/42/photos/front.jpg"
    Then a handler that reads the object "listings/42/photos/front.jpg" is told that there is no such object
    And deleting the object "listings/42/photos/front.jpg" again does not fail

  Scenario: reading an object that is not there answers nothing rather than failing
    When a handler of "listings" reads the object "listings/99/photos/front.jpg"
    Then the handler is told that there is no such object
    And the read does not fail

  Scenario: an object larger than an instance's memory passes through the storage client
    Given an object larger than the memory of an instance of "listings"
    When a handler of "listings" keeps the object through the storage client and reads it back
    Then the object read back is the object kept
    And the instance holds no more of the object at once than a part of it

  Scenario Outline: a service's code against the storage client is the same on every object store
    Given the bucket of "listings" is in <store>
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then a handler of "listings" reads the object "listings/42/photos/front.jpg" back through the storage client
    And the code of "listings" names no object store

    Examples:
      | store                             |
      | Garage                            |
      | Google Cloud Storage              |
      | an object store of the service's own |

  Scenario Outline: an object's name that breaks the rule is refused before anything is sent
    When a handler of "listings" keeps an object named "<name>"
    Then the handler is refused
    And the refusal names the rule for object names
    And nothing was sent to the object store

    Examples:
      | name                      |
      | /listings/42/front.jpg    |
      | listings//42/front.jpg    |
      | listings/./42/front.jpg   |
      | listings/../42/front.jpg  |
      |                           |

  Scenario: an object's name longer than the limit is refused
    When a handler of "listings" keeps an object with a name longer than the limit
    Then the handler is refused
    And the refusal names the rule for object names

  Scenario: a storage client call is refused with a reason when the object store cannot be reached
    Given the object store of "listings" cannot be reached
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg"
    Then what the handler did fails
    And the failure says that the object store cannot be reached

  Scenario: a keep that fails part way leaves no partial object
    Given a handler of "listings" has kept the object "listings/42/photos/front.jpg"
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" from a stream that ends before its declared length
    Then what the handler did fails
    And a handler that reads the object "listings/42/photos/front.jpg" is told the object kept before
