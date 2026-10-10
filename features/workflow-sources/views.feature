Feature: A view reads a workflow
  A view may read a workflow as it reads an entity. Each change it is handed is a state the
  workflow recorded, with the workflow's standing once the whole effect that recorded it is
  applied: running, paused, completed or failed, the step it is on or waits after, and why it
  failed. A transition, pause, end or failure that records no state is no change. Changes arrive in
  the order recorded and are applied exactly once; a deletion runs the deletion handler. A view
  that reads a workflow declares a version and is rebuilt as a view that reads an entity is, and
  may not read a topic as well.

  Background:
    Given a service "shop" with a workflow "checkout" of the steps "reserve" and "charge"
    And a view "checkouts" that reads the workflow "checkout" and keeps a row for each workflow of it

  Scenario: a row holds the state a workflow ended with and the standing completed
    When the workflow "c1" of "checkout" runs from its start to its end
    Then "checkouts" holds a row "c1"
    And the row "c1" holds the state "c1" ended with
    And the row "c1" has the standing completed

  Scenario: a row of a workflow whose compensation recorded its failure has the standing failed and the reason
    Given the step "charge" of "checkout" fails after its retries and fails over to "refund", which records the failure in the state and fails the workflow
    When the workflow "c2" of "checkout" runs from its start
    Then the row "c2" has the standing failed
    And the row "c2" holds the reason

  Scenario: a workflow that fails without recording its state delivers no change
    Given the step "charge" of "checkout" fails after its retries and fails over to nothing
    And "checkouts" holds the row "c4" from the state "c4" recorded before "charge"
    When the workflow "c4" of "checkout" fails at "charge"
    Then "checkouts" is handed no change for the failure
    And the row "c4" is as it was

  Scenario: a row of a paused workflow names the step it waits after
    Given the step "reserve" of "checkout" records its state and pauses
    When the workflow "c3" of "checkout" runs from its start
    Then the row "c3" has the standing paused
    And the row "c3" names the step "reserve"

  Scenario: a row holds the last state recorded and the step the workflow moved to with it
    Given the step "reserve" of "checkout" records its state and moves to "charge"
    And the workflow "c1" of "checkout" has run its step "reserve"
    When a handler reads the row "c1"
    Then the row "c1" holds the state "reserve" recorded
    And the row "c1" has the standing running and names the step "charge"

  Scenario: a declared query lists the rows of one standing
    Given "checkouts" declares the query "by-standing" with a statement that reads its own table for the rows holding the value "standing"
    And the workflows "c1" and "c2" of "checkout" have ended as completed and the workflow "c3" as failed
    When a handler of "shop" asks "checkouts" the query "by-standing" with "failed" as "standing"
    Then the handler is answered with the row "c3" and no other

  Scenario: a view that reads a workflow at a higher version is rebuilt from every recorded state
    Given "checkouts" at version 1 has read every state recorded by "checkout"
    And the workflows "c1" and "c2" of "checkout" have ended
    When "shop" restarts with "checkouts" at version 2
    Then "checkouts" holds no row written at version 1
    And "checkouts" holds a row written at version 2 for "c1" and for "c2"

  Scenario: a restarted view reads no state of a workflow again
    Given "checkouts" has read every state recorded by the workflow "c1"
    When "shop" restarts
    Then "checkouts" reads no state of "c1" again

  Scenario: a state recorded before the platform stamped standings is delivered with the standing unknown
    Given the workflow "c8" of "checkout" recorded a state on a release before workflow sources
    When "shop" restarts with "checkouts" at version 2
    Then the row "c8" holds the state "c8" recorded
    And the row "c8" has the standing unknown

  Scenario: a view may not read a topic and a workflow together
    Given "shop" has a view "orders" that reads the topic "orders" and the workflow "checkout"
    When "shop" is started
    Then "shop" does not start
    And the developer is told that a topic and a workflow may not be sources of one view

  Scenario: a view of several sources reads a workflow beside an entity one change at a time
    Given "shop" has an event sourced entity "order"
    And a view "fulfilment" that reads the events of "order" and the workflow "checkout"
    When the workflow "c1" of "checkout" ends and the entity "o1" of "order" records an event at the same time
    Then "fulfilment" handles one of the two changes, with everything it reads and writes for it, before it handles the other
    And "fulfilment" holds the rows each change names

  Scenario: a deleted workflow runs the view's deletion handler
    Given "checkouts" holds the row "c1"
    When the workflow "c1" of "checkout" is deleted
    Then the deletion handler of "checkouts" runs for "c1"
    And "checkouts" holds no row "c1"
