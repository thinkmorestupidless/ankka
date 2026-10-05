Feature: Entities in every language
  A service's entities may be written in Scala, Python, TypeScript or Rust. Whatever the language,
  the developer's code decides what each command does and how each event changes the state, and the
  platform's own program records the events, keeps the snapshots and recovers the state. An event
  is recorded the same way in every language, so a service written in one language recovers what a
  service written in another recorded.

  Scenario Outline: a command runs its handler in the developer's code and records the events it names
    Given a service "shop" written in "<language>" with an event sourced entity "cart"
    When a caller sends the command "add-item" with the item "socks" to the cart "c1"
    Then the handler of "add-item" in the developer's code runs
    And the cart "c1" has recorded the event that "socks" was added
    And the caller is answered from the state after that event

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a refused command records nothing and its caller is told the refusal
    Given a service "shop" written in "<language>" whose entity "cart" refuses an item with no quantity
    When a caller sends the command "add-item" with no quantity to the cart "c1"
    Then the caller is refused, with the refusal the handler gave
    And the cart "c1" has recorded no event

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a handler that fails records nothing and the service goes on serving
    Given a service "shop" written in "<language>" whose entity "cart" has recorded the item "socks" for the cart "c1"
    And the handler of "add-item" fails for the item "boom"
    When a caller sends the command "add-item" with the item "boom" to the cart "c1"
    Then the caller is told that the command failed, not that it was refused
    And the cart "c1" has recorded no event for "boom"
    And the state of the cart "c1" still holds only "socks"
    And the next command to the cart "c1" and to every other cart is handled

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a restarted entity is recovered through the developer's code before its next command
    Given a service "shop" written in "<language>" whose cart "c1" has recorded 3 items
    And the service has restarted
    When a caller sends the command "get-cart" to the cart "c1"
    Then the developer's code is given the snapshot of "c1" and every event recorded after it
    And the caller is answered with the 3 items

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a snapshot is the developer's code's own, and recovery reads only the events after it
    Given a service "shop" written in "<language>" whose entity "cart" takes a snapshot every 2 events
    And the cart "c1" has recorded 3 events
    When the service restarts and the cart "c1" is recovered
    Then the platform holds a snapshot of "c1" that the developer's code produced after 2 events
    And the developer's code is given that snapshot and only the 1 event recorded after it

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a service written in one language recovers what a service written in another recorded
    Given a service "shop" written in "<written>" whose cart "c1" has recorded 3 items
    When a service "shop" written in "<recovered>" is started on the same database
    And a caller sends the command "get-cart" to the cart "c1"
    Then the caller is answered with the same 3 items

    Examples:
      | written    | recovered  |
      | Scala      | Python     |
      | Python     | Scala      |
      | Scala      | TypeScript |
      | TypeScript | Scala      |
      | Scala      | Rust       |
      | Rust       | Scala      |

  Scenario Outline: a query that would record an event is refused before the service is built
    Given a service written in "<language>"
    When the developer writes a query whose handler records an event
    Then the service does not build
    And the developer is told that a query cannot record an event

    Examples:
      | language   |
      | Scala      |
      | TypeScript |
      | Rust       |
