Feature: Reading a workflow's standing in every language
  A handler in any language asks a workflow, through its client, where the platform says it has got
  to, and is told what a Scala handler is told: the standing, the step it is on or waits after, the
  retries of each step and, when it failed, why. The read runs no handler of the workflow's and
  records nothing. A runtime from before the read refuses it, and the handler is told which protocol
  version the read needs.

  Scenario Outline: a handler reads a running workflow's standing in every language
    Given a service "planner" written in "<language>" with a workflow "plan" whose step "consult" is running for "p1"
    When a handler of "planner" reads the standing of the workflow "p1" of "plan"
    Then the handler is told the standing running and the step "consult"
    And no handler of "plan" ran and the journal of "p1" holds no record it did not hold before

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
      | Java       |
      | Kotlin     |
      | Go         |

  Scenario Outline: a handler reads a failed workflow's standing with the reason and the retries in every language
    Given a service "planner" written in "<language>" with a workflow "plan" whose step "consult" is retried twice and then fails the workflow
    And "consult" failed every time it ran for "p2"
    When a handler of "planner" reads the standing of the workflow "p2" of "plan"
    Then the handler is told the standing failed, the reason, and that "consult" was retried 2 times

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |
      | Rust       |
      | Java       |
      | Kotlin     |
      | Go         |

  Scenario Outline: a handler reads the standing the platform records for a workflow in every language
    Given a service "planner" written in "<language>" with a workflow "plan"
    And the workflow "p3" of "plan" <situation>
    When a handler of "planner" reads the standing of the workflow "p3" of "plan"
    Then the handler is told the standing <standing>

    Examples:
      | language   | situation           | standing    |
      | Python     | paused              | paused      |
      | TypeScript | paused              | paused      |
      | Rust       | paused              | paused      |
      | Go         | paused              | paused      |
      | Python     | ended               | completed   |
      | TypeScript | ended               | completed   |
      | Rust       | ended               | completed   |
      | Go         | ended               | completed   |
      | Python     | was never started   | not started |
      | TypeScript | was never started   | not started |
      | Rust       | was never started   | not started |
      | Go         | was never started   | not started |

  Scenario Outline: the component test kit answers a workflow's standing it was told
    Given a component test of an endpoint written in "<language>" that reads the standing of a workflow of "plan"
    And the component test kit is told the standing of "p2" is failed with the reason "the provider refused"
    When the test calls the endpoint for "p2"
    Then the endpoint is told the standing failed and the reason "the provider refused"

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario Outline: the component test kit refuses a standing it was not told
    Given a component test of an endpoint written in "<language>" that reads the standing of a workflow of "plan"
    When the test calls the endpoint for "p9"
    Then the read is refused with an error that says the standing of "p9" was not stubbed

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario Outline: the test kit reads a workflow's standing from the running service
    Given a test of a service "planner" written in "<language>" with a workflow "plan"
    And the workflow "p4" of "plan" ended
    When the test reads the standing of "p4" through the test kit's client
    Then the test is told the standing completed

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
      | Go         |

  Scenario: a runtime from before the read refuses a workflow's standing, naming the protocol version
    Given a service "planner" written in "Python" whose handler reads the standing of a workflow
    And "planner" runs beside a runtime at a protocol version before the standing read
    When the handler reads the standing of a workflow
    Then the handler's read is refused
    And the handler is told which protocol version the standing read needs
