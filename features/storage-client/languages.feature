Feature: The storage client in every language
  A service keeps and reads objects through the storage client the same way whether it is written
  in Scala, Python, TypeScript or Rust. Each SDK's client speaks to the object store itself, from
  the same variables, so no object passes through the platform's own program. A module reaches the object store
  through the platform's own program and holds a whole object in its memory while it keeps or
  reads it.

  Scenario Outline: an object is kept and read through the storage client in every language
    Given a deployed service "listings" written in "<language>" with a bucket
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" with the content type "image/jpeg" through the storage client
    Then a handler of "listings" reads the object "listings/42/photos/front.jpg" back with the content type "image/jpeg"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the storage client lists, tells what an object is and deletes in every language
    Given a deployed service "listings" written in "<language>" with a bucket
    And a handler of "listings" has kept the objects "listings/42/photos/front.jpg" and "listings/42/photos/back.jpg"
    When a handler of "listings" lists the objects under the prefix "listings/42/", asks what "listings/42/photos/front.jpg" is and deletes it
    Then the handler is told both objects with their sizes, then the size and content type of "listings/42/photos/front.jpg", and a handler that reads it afterwards is told that there is no such object

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: no object passes through the platform's own program
    Given a deployed service "listings" written in "<language>" with a bucket
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then the platform's own program beside "listings" carried no part of the object

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario Outline: every SDK refuses the same object names for the same reasons
    Given a deployed service "listings" written in "<language>" with a bucket
    When a handler of "listings" keeps an object named "listings/../42/front.jpg"
    Then the handler is refused
    And the refusal names the rule for object names
    And the refusal is the one the fixture for object names says

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the storage client in every language keeps its objects in a directory on a developer's machine
    Given a service "listings" written in "<language>" running on a developer's machine with no variable of an object store
    When a handler of "listings" keeps the object "listings/42/photos/front.jpg" through the storage client
    Then the directory store of "listings" holds the object "listings/42/photos/front.jpg"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
