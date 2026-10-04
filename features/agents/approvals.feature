Feature: A tool that waits for a person's approval
  A developer declares that a tool requires approval. When the model calls it, the tool does not
  run: the agent records an approval request in the session and gives it to its caller instead of
  an answer. A person's decision, sent to the session, lets the agent go on: approved, the tool
  runs once and the model is told its result; refused, the model is told so and the tool never
  runs. The approval request is part of the session, so it is still there after the service
  restarts, and a session takes nothing new while one is awaiting a decision.

  Background:
    Given a service "support" with an agent "helper"
    And a tool "issue_refund" of "helper" that requires approval

  Scenario: a tool call that requires approval gives the caller an approval request instead of an answer
    When the model calls "issue_refund" with the arguments "amount 40" in the session "s-1"
    Then the caller of "helper" is given an approval request instead of an answer
    And the approval request names the tool "issue_refund" and carries the arguments "amount 40"
    And the tool "issue_refund" has not run

  Scenario: an approved tool call runs once and the model is told its result
    Given an approval request awaiting a decision in the session "s-1"
    When a person approves the approval request
    Then the tool "issue_refund" runs once with the arguments the model gave
    And the model is told the result of the tool call
    And the person is given the answer of the model

  Scenario: an approval request is still awaiting a decision after the service restarts
    Given an approval request awaiting a decision in the session "s-1"
    And "support" has since restarted
    When a person approves the approval request
    Then the tool "issue_refund" runs once with the arguments the model gave

  Scenario: a refused tool call never runs and the model is told the note
    Given an approval request awaiting a decision in the session "s-1"
    When a person refuses the approval request with the note "over the limit"
    Then the tool "issue_refund" has not run
    And the model is told that the tool call was refused, with the note "over the limit"
    And the person is given the answer of the model

  Scenario Outline: an approval request is decided once
    Given an approval request in the session "s-1" that a person has approved
    When a person <decides> the approval request again
    Then the decision is refused
    And the refusal says that the approval request is decided
    And the tool "issue_refund" has run once

    Examples:
      | decides  |
      | approves |
      | refuses  |

  Scenario: a decision for an approval request the session does not hold is refused
    Given an approval request awaiting a decision in the session "s-1"
    When a person approves an approval request "a-404" that the session "s-1" does not hold
    Then the decision is refused
    And the refusal names the approval request "a-404"
    And the tool "issue_refund" has not run

  Scenario: a decision sent to a session no agent has used is refused
    When a person approves an approval request "a-1" in a session "s-9" that no agent has used
    Then the decision is refused
    And the refusal names the session "s-9"

  Scenario: a session with an approval request awaiting a decision takes no new request
    Given an approval request awaiting a decision in the session "s-1"
    When a caller asks "helper" something new in the session "s-1"
    Then the caller is refused
    And the refusal says that an approval request is awaiting a decision
    And the model is not asked

  Scenario: a tool that requires no approval runs beside one that waits
    Given a tool "read_order" of "helper" that requires no approval
    When the model calls "read_order" and "issue_refund" together in the session "s-1"
    Then the tool "read_order" runs
    And the tool "issue_refund" has not run
    And the caller of "helper" is given an approval request that names the tool "issue_refund"

  Scenario: two tool calls made together are two approval requests, each decided alone
    Given the model has called "issue_refund" twice together in the session "s-1"
    And the session "s-1" holds two approval requests awaiting a decision
    When a person approves one approval request
    Then the other approval request is still awaiting a decision
    And the person is given the approval request that is still awaiting a decision
    And the model is not asked until both are decided

  Scenario: a session shows an approval request that is awaiting a decision
    Given an approval request awaiting a decision in the session "s-1"
    When the session "s-1" is read
    Then the session shows the tool call to "issue_refund" and its approval request awaiting a decision

  Scenario: a session with no approval request is read as it was recorded
    Given a session "s-0" recorded by a version of the platform that has no approval requests
    When the session "s-0" is read
    Then the session shows everything it was recorded with

  Scenario: a stream ends with the approval request as its last part
    Given a handler of "helper" that answers as a stream
    When the model calls "issue_refund" in the session "s-1"
    Then the last part of the stream is the approval request
    And the stream ends

  Scenario: a tool that requires no approval runs when the model calls it
    Given a tool "read_order" of "helper" that requires no approval
    When the model calls "read_order" in the session "s-1"
    Then the tool "read_order" runs
    And the caller of "helper" is given the answer of the model

  Scenario: compaction keeps an approval request that is awaiting a decision
    Given an approval request awaiting a decision in the session "s-1"
    When compaction runs on the session "s-1"
    Then the approval request is still awaiting a decision

  Scenario: an approval request with a time limit is refused when nobody decides it in time
    Given a tool "close_account" of "helper" that requires approval within "30" minutes
    And an approval request for the tool "close_account" awaiting a decision in the session "s-1"
    When "30" minutes pass with no decision
    Then the tool "close_account" has not run
    And the model is told that the tool call was refused, with a note that says the approval request expired
    And the session "s-1" shows the answer of the model
    And the session "s-1" shows that the platform decided the approval request

  Scenario: an approval request with no time limit waits however long nobody decides it
    Given an approval request awaiting a decision in the session "s-1"
    When "30" days pass with no decision
    Then the approval request is still awaiting a decision

  Scenario: a decision for an approval request that has expired is refused
    Given a tool "close_account" of "helper" that requires approval within "30" minutes
    And an approval request for the tool "close_account" in the session "s-1" that has expired
    When a person approves the approval request
    Then the decision is refused
    And the refusal says that the approval request is decided
    And the tool "close_account" has not run

  Scenario: a session shows who decided an approval request
    Given an approval request awaiting a decision in the session "s-1"
    When "dana" approves the approval request
    And the session "s-1" is read
    Then the session shows the approval request approved by "dana"

  Scenario: a decision that does not say who made it is refused
    Given an approval request awaiting a decision in the session "s-1"
    When a decision that approves the approval request and names nobody is sent
    Then the decision is refused
    And the refusal says that a decision names who made it
    And the approval request is still awaiting a decision

  Scenario: an approval request's time limit holds after the service restarts
    Given a tool "close_account" of "helper" that requires approval within "30" minutes
    And an approval request for the tool "close_account" awaiting a decision in the session "s-1"
    And "support" has since restarted
    When "30" minutes pass with no decision
    Then the tool "close_account" has not run
    And the session "s-1" shows that the platform decided the approval request once
