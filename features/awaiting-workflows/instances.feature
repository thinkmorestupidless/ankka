Feature: A wait survives the workflow's instance moving
  The platform holds a wait in memory and the caller asks again within its own time, so a
  workflow whose instance stops or whose place in the cluster moves loses no caller for longer
  than one ask. Nothing about a wait is recorded.

  Background:
    Given a service "pricing" written in "Scala" running as 3 instances
    And a workflow "quote" of the steps "rates", "margin" and "offer"

  Scenario: a caller is answered after the instance running the workflow stops during a step
    Given a caller on one instance waiting for the end of the workflow "w1" of "quote" running on another instance
    When the instance running "w1" stops during the step "margin"
    Then "w1" goes on on another instance to its end
    And the caller is answered with the state "w1" ended with

  Scenario: no caller is lost when an instance stops
    Given a caller waiting for the end of the workflow "w1" of "quote"
    When the instance running "w1" stops and "w1" ends elsewhere
    Then the caller is answered
    And the service's log has no line saying a waiting caller was lost
