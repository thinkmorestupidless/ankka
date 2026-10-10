Feature: A view reads a workflow
  A view may read a workflow as it reads an entity. Each change it is handed carries the
  workflow's state as it stood after what the workflow recorded and the workflow's standing:
  running, paused, completed or failed, the step it is on or waits after, and why it failed.
  Every record the workflow made is a change, in the order it was recorded, applied exactly once;
  a deletion runs the deletion handler. A view that reads a workflow declares a version and is
  rebuilt as a view that reads an entity is, and may not read a topic as well.

  Background:
    Given a service "shop" with a workflow "checkout" of the steps "reserve" and "charge"
    And a view "checkouts" that reads the workflow "checkout" and keeps a row for each workflow of it

  Scenario: a row holds the state a workflow ended with and the standing completed
    When the workflow "c1" of "checkout" runs from its start to its end
    Then "checkouts" holds a row "c1"
    And the row "c1" holds the state "c1" ended with
    And the row "c1" has the standing completed

  Scenario: a row of a failed workflow names the step it failed on and the reason
    Given the step "charge" of "checkout" fails after its retries
    When the workflow "c2" of "checkout" runs from its start
    Then the row "c2" has the standing failed
    And the row "c2" names the step "charge" and holds the reason

  Scenario: a row of a paused workflow names the step it waits after
    Given "checkout" pauses after its step "reserve"
    When the workflow "c3" of "checkout" runs from its start
    Then the row "c3" has the standing paused
    And the row "c3" names the step "reserve"

  Scenario: a row holds the state as of the last record and the step the workflow is on
    Given the workflow "c1" of "checkout" has run its step "reserve" and moved to "charge"
    When a handler reads the row "c1"
    Then the row "c1" holds the state as of the last thing "c1" recorded
    And the row "c1" has the standing running and names the step "charge"

  Scenario: a declared query lists the rows of one standing
    Given "checkouts" declares the query "by-standing" with a statement that reads its own table for the rows holding the value "standing"
    And the workflows "c1" and "c2" of "checkout" have ended as completed and the workflow "c3" as failed
    When a handler of "shop" asks "checkouts" the query "by-standing" with "failed" as "standing"
    Then the handler is answered with the row "c3" and no other

  Scenario: a view that reads a workflow at a higher version is rebuilt from every record
    Given "checkouts" at version 1 has read every record of "checkout"
    And the workflows "c1" and "c2" of "checkout" have ended
    When "shop" restarts with "checkouts" at version 2
    Then "checkouts" holds no row written at version 1
    And "checkouts" holds a row written at version 2 for "c1" and for "c2"

  Scenario: a restarted view reads no record of a workflow again
    Given "checkouts" has read every record of the workflow "c1"
    When "shop" restarts
    Then "checkouts" reads no record of "c1" again

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
