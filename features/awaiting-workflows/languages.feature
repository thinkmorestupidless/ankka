Feature: Waiting for a workflow in every language
  A handler in Python or TypeScript sends a command to a workflow and waits for its end through
  its client, as a Scala one does. A runtime from before waiting refuses a program that waits,
  naming the protocol version it needs.

  Scenario Outline: a handler sends a command and is answered with the state in every language
    Given a service "pricing" written in "<language>" with a workflow "quote" of the steps "rates" and "offer"
    When a handler of "pricing" sends the command "start" to the workflow "q1" of "quote" and waits for its end within "30 seconds"
    Then the handler is answered with the state "q1" ended with

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario Outline: a handler that waits for a workflow that fails is answered with the failure in every language
    Given a service "pricing" written in "<language>" with a workflow "quote" whose step "offer" fails after its retries
    When a handler of "pricing" sends the command "start" to the workflow "q2" of "quote" and waits for its end within "30 seconds"
    Then the handler is answered with a failure that names the step "offer" and the reason

    Examples:
      | language   |
      | Scala      |
      | Python     |
      | TypeScript |

  Scenario: a runtime from before waiting refuses a program that waits
    Given a service "pricing" written in "Python" whose handler waits for a workflow's end
    When "pricing" is started beside a runtime at a protocol version before waiting
    Then "pricing" does not start
    And the developer is told which protocol version waiting needs
