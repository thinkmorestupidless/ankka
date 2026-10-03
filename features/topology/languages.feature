Feature: A service's topology in every language
  A service is written in Scala, Python, TypeScript or Rust. Whatever it is written in, its topology
  shows the same declared connections for the same components, and attributes its calls to the
  component that made them.

  Scenario Outline: a service declares the same connections in every language
    Given a service written in "<language>" with an event sourced entity "cart"
    And a consumer "checkout-recorder" that reads the events of "cart"
    When a developer reads the service's topology
    Then the topology shows "checkout-recorder" as a consumer
    And the topology shows a declared connection from "cart" to "checkout-recorder" as an event subscription

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a call is attributed to its caller in every language
    Given a service written in "<language>" with an event sourced entity "cart" and an event sourced entity "ledger"
    And a consumer "checkout-recorder" that reads the events of "cart" and calls "ledger"
    When "checkout-recorder" handles 1 event
    Then the topology shows an observed call from "checkout-recorder" to "ledger"
    And the topology shows no observed call from the unknown caller to "ledger"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
