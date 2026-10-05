Feature: A module's commands, entities and views call no other service
  A call to another service is a wait of unknown length, and a module cannot be interrupted. A
  command would hold every other command to the same entity or workflow behind it for as long as
  the service called takes to answer, and whatever builds state from events must build the same
  state every time. The platform stops such a call itself, before anything is sent and whatever
  the module was built with.

  Background:
    Given a module "rewards"
    And a service "wallet" that "rewards" can reach

  Scenario Outline: a command in a module that calls another service fails before anything is sent
    Given <component> of "rewards" whose command "credit" calls "wallet"
    When the command "credit" is sent to it
    Then the command fails
    And the failure names the command "credit" and says that a command calls no other service
    And no call is sent to any service

    Examples:
      | component               |
      | an event sourced entity |
      | a key value entity      |
      | a workflow              |

  Scenario: an entity whose command failed by calling another service keeps its state
    Given an event sourced entity "account" of "rewards" whose command "credit" calls "wallet"
    And "account" holds the state its earlier commands left
    And the command "credit" sent to "account" has failed
    When another command is sent to "account"
    Then the command is handled
    And "account" holds the state its earlier commands left

  Scenario: an event sourced entity in a module that calls another service while reading its events fails
    Given an event sourced entity of "rewards" that calls "wallet" when it reads one of its events
    And the entity has recorded an event
    When a command is sent to the entity
    Then the command fails
    And the failure says that an entity reads its events without calling another service
    And no call is sent to any service

  Scenario: a view in a module that calls another service fails the event it was reading
    Given a view of "rewards" that calls "wallet" for every event it reads
    When the view reads an event
    Then the view fails
    And the failure says that a view calls no other service
    And no call is sent to any service

  Scenario: the platform stops a command's call whatever the module was built with
    Given a module "bare" built without an SDK
    And an event sourced entity of "bare" whose command "credit" calls "wallet"
    When the command "credit" is sent to it
    Then the command fails
    And no call is sent to any service
