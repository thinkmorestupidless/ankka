Feature: Workflow sources in every language
  A view or a consumer declares a workflow as its source where it would name an entity, whether
  the service is written in Scala, Python, TypeScript or Rust, and is handed the same change: the
  state as the workflow's codec reads it and the standing. A runtime that does not know a workflow
  source refuses the component when the service is discovered, naming the protocol version it
  needs, rather than starting a view that writes nothing.

  Scenario Outline: a view reads a workflow in every language
    Given a service "shop" written in "<language>" with a view "checkouts" that reads the workflow "checkout"
    When the workflow "c1" of "checkout" runs from its start to its end
    Then the row "c1" holds the state "c1" ended with
    And the row "c1" has the standing completed

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a consumer is handed a failed workflow's standing in every language
    Given a service "shop" written in "<language>" with a consumer "watchdog" that reads the workflow "checkout"
    And the step "charge" of "checkout" fails after its retries
    When the workflow "c2" of "checkout" runs from its start
    Then "watchdog" is handed a change whose standing is failed and names the step "charge"

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario: a runtime from before workflow sources refuses a program that declares one
    Given a service "shop" written in "Python" with a view "checkouts" that reads the workflow "checkout"
    When "shop" is started beside a runtime at a protocol version before workflow sources
    Then "shop" does not start
    And the developer is told which protocol version a workflow source needs

  Scenario Outline: the topology shows a view connected to the workflow it reads in every language
    Given a service "shop" written in "<language>" with a view "checkouts" that reads the workflow "checkout"
    When a developer reads the service's topology
    Then the topology shows a declared connection from "checkout" to "checkouts" as a workflow subscription

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the component test kit hands a view a workflow change in every language
    Given a view "checkouts" written in "<language>" that reads the workflow "checkout"
    When a test hands "checkouts" a change with a state and the standing failed for "c1"
    Then the test reads the row "c1" the view wrote for it

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
