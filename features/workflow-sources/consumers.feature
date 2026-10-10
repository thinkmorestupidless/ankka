Feature: A consumer reads a workflow
  A consumer may read a workflow as it reads an entity. It is handed each change the workflow
  recorded, at least once and in order, carrying the state and the standing, with the workflow's
  id as its subject and the record's sequence number. A consumer that reacts to a workflow's end
  reads the standing and ignores the changes before it; nothing in the workflow knows the
  consumer exists.

  Background:
    Given a service "money" with a workflow "transfer" of the steps "withdraw" and "deposit"
    And an event sourced entity "ledger"

  Scenario: a consumer publishes once when a workflow ends as completed
    Given a consumer "settlement" that reads the workflow "transfer" and publishes to the topic "transfers-settled" for each change whose standing is completed
    When the workflow "t1" of "transfer" runs from its start to its end
    Then one message about "t1" is published to "transfers-settled"
    And no message is published for the changes of "t1" before its end

  Scenario: a consumer calls an entity once when a workflow fails
    Given a consumer "settlement" that reads the workflow "transfer" and calls "ledger" for each change whose standing is failed
    And the step "deposit" of "transfer" fails after its retries
    When the workflow "t2" of "transfer" runs from its start
    Then "ledger" is called once about "t2"

  Scenario: a consumer is handed one change for each record, in order, with its sequence number
    Given a consumer "settlement" that reads the workflow "transfer"
    When the workflow "t1" of "transfer" records a state and a transition in one step
    Then "settlement" is handed a change for the state and then a change for the transition
    And each change carries the sequence number of the record it was handed for
    And each change carries "t1" as its subject

  Scenario: a deleted workflow runs the consumer's deletion handler
    Given a consumer "settlement" that reads the workflow "transfer" and has read every change of "t1"
    When the workflow "t1" of "transfer" is deleted
    Then the deletion handler of "settlement" runs for "t1"

  Scenario: a change of a workflow timed out has the standing failed and names the timeout
    Given a consumer "settlement" that reads the workflow "transfer"
    And "transfer" declares a timeout of "2 seconds" for the whole workflow
    When the workflow "t3" of "transfer" runs a step that takes longer than "2 seconds"
    Then "settlement" is handed a change whose standing is failed
    And the reason of that change names the timeout
