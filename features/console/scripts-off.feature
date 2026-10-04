Feature: Every operation works with scripts off
  A member's browser may run no scripts. Every operation the console offers completes without
  them, and an overlay is only an enhancement of a page that exists without it: further operations
  open as a disclosure, a page's sections are pages of their own, and a choice is the browser's
  own. With scripts on, the same operation completes in place.

  Background:
    Given a service "cart" deployed in the project "checkout"
    And a member of "checkout" whose browser runs no scripts

  Scenario: an operation completes with scripts off
    When the member pauses "cart"
    Then "cart" is paused
    And the member is shown the page of "cart" saying so

  Scenario: a page's sections are reached as pages of their own with scripts off
    When the member reads the logs of "cart"
    Then the member is shown the logs of "cart" as a page of its own
    And the sections overview, topology, logs and history are each offered as a page of its own

  Scenario: further operations open as a disclosure with scripts off
    When the member opens the further operations of the project "checkout"
    Then they open as a disclosure
    And renaming and deleting the project are offered in it

  Scenario: a choice is made with scripts off
    When the member invites "ann@example.test" to the organization of "checkout" as an owner
    Then "ann@example.test" is invited as an owner
    And the choice of owner was the browser's own

  Scenario: with scripts on the same operation completes in place
    Given the member's browser runs scripts
    When the member pauses "cart"
    Then "cart" is paused
    And the page of "cart" says so without being reloaded
