Feature: Watching an agent instance
  Whoever watches an agent instance is given its notifications as they happen: its lifecycle, its
  tasks, its iterations and the warnings that a task is struggling. Watching shows what happens from
  then on; what happened before is read from the task's record.

  Background:
    Given an autonomous agent "answerer" that accepts the task type "answer" with a budget of 10 iterations
    And an agent instance of "answerer" with the id "desk-1"

  Scenario: a watcher is given every notification in order, each saying what it is about and when
    Given a watcher of "desk-1"
    When "desk-1" works a task whose first result is result-rejected and whose second completes it
    Then the watcher is given, in order: activated, task assigned, task started, iteration started, iteration completed, result rejected, iteration started, iteration completed, task completed, deactivated
    And each notification names "desk-1", the task where there is one, and when it happened

  Scenario: a watcher who starts watching part way through a task is given nothing from before
    Given "desk-1" is working a task and has completed 2 iterations
    When a watcher starts watching "desk-1"
    Then the watcher is given only notifications of what happens from then on

  Scenario: a task nearing its budget is warned of once
    Given "desk-1" is working a task and has completed 7 iterations
    When iterations 8, 9 and 10 start
    Then a watcher of "desk-1" is warned that the task is nearing its budget once, as iteration 8 starts

  Scenario: a task warned of its budget is warned again after it is result-rejected
    Given "desk-1" was warned that its task is nearing its budget
    And the task is then result-rejected
    When the next iteration starts
    Then a watcher of "desk-1" is warned again that the task is nearing its budget

  Scenario: notifications forwarded to a browser read back exactly as they were sent
    Given an endpoint that forwards the notifications of "desk-1" to a browser as a stream
    When a browser watches through the endpoint while "desk-1" works a task
    Then each notification reaches the browser as one part of the stream
    And each part reads back as the notification, whatever text the model wrote in it
