Feature: Timers when a service is upgraded
  A period is something a timer may have and need not. A timer set before a service was upgraded
  to a runtime version with recurring timers has none and fires once. A runtime version that has
  no recurring timers never fires or removes one: while a service is being upgraded, or after it
  is taken back to an earlier runtime version, a recurring timer is kept until an instance with
  recurring timers fires the timers. A recurring timer is kept, too, while the instance that
  fires the timers does not have its handler, as when a new version of a service adds one.

  Scenario: a timer set before a service was upgraded fires once
    Given a service "orders" made with a runtime version that has no recurring timers
    And a handler of "orders" has set the timer "nudge-c1"
    When "orders" is upgraded to a runtime version with recurring timers
    Then the timer "nudge-c1" fires once
    And "orders" then has no timer "nudge-c1"

  Scenario: a service made with an earlier runtime version starts with a database that holds a recurring timer
    Given a service "orders" whose database holds the recurring timer "sweep-carts"
    When "orders" starts from an image made with a runtime version that has no recurring timers
    Then "orders" is ready
    And a timer with no period that a handler of "orders" sets fires once
    And the timer "sweep-carts" does not fire
    And the database of "orders" still holds the recurring timer "sweep-carts"

  Scenario: an instance from before recurring timers does not fire or remove a recurring timer
    Given a service "catalog" with one instance made with a runtime version that has no recurring timers and one made with a runtime version that has them
    And the instance with no recurring timers is the one that fires the timers of "catalog"
    And a handler on the other instance has set the recurring timer "sweep-carts" with a period of "2 seconds"
    When "10 seconds" pass
    Then the timer "sweep-carts" has not fired
    And "catalog" has the recurring timer "sweep-carts"

  Scenario: a recurring timer kept through an upgrade fires once when an upgraded instance fires the timers
    Given a service "catalog" whose recurring timer "sweep-carts" has a period of "2 seconds"
    And several due times of "sweep-carts" have passed while an instance with no recurring timers fired the timers of "catalog"
    When an instance with recurring timers becomes the one that fires the timers of "catalog"
    Then the timer "sweep-carts" fires once
    And its next due time is the first still to come that is a whole number of periods after the due time it fired for

  Scenario: a recurring timer whose handler the instance that fires the timers does not have is kept
    Given a service "catalog" with two instances, of which only one has the handler "sweep" of the timed action "cleanup"
    And the instance without the handler "sweep" is the one that fires the timers of "catalog"
    And a handler on the other instance has set the recurring timer "sweep-carts" for "sweep" with a period of "2 seconds"
    When "5 seconds" pass
    Then the timer "sweep-carts" has not fired
    And "catalog" has the recurring timer "sweep-carts"

  Scenario: a recurring timer kept for its handler fires once when an instance that has the handler fires the timers
    Given a service "catalog" whose recurring timer "sweep-carts" for "sweep" has a period of "2 seconds"
    And several due times of "sweep-carts" have passed while an instance without the handler "sweep" fired the timers of "catalog"
    When an instance with the handler "sweep" becomes the one that fires the timers of "catalog"
    Then the timer "sweep-carts" fires once
    And its next due time is the first still to come that is a whole number of periods after the due time it fired for
