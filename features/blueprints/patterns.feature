Feature: Patterns: the ways a step uses workers
  A step is what it does once, how many times and over what, and until what. The common shapes have
  names: an ask step, a work step, a for-each step, a gather step, a judge step, a critique step and
  a call step. Every repetition in a run is inside a step and bounded by it.

  Background:
    Given a service with a scripted model and a scripted judgment provider

  Scenario: an ask step is one worker's answer
    Given an ask step with the worker "summariser"
    When a run reaches the step
    Then "summariser" answers the step's input once, running the tools its model asks for
    And the step's result is the answer

  Scenario: a work step iterates until its worker completes
    Given a work step with the worker "researcher" and a budget of "10"
    When a run reaches the step and the model completes it on its third iteration
    Then the step's result is the result the model completed it with
    And three iterations are recorded

  Scenario: a work step that spends its worker's budget fails
    Given a work step with the worker "researcher" and a budget of "3"
    When the model has not completed the step after three iterations
    Then the step is failed, saying the budget is spent

  Scenario: a for-each step gives each item to the worker and keeps the results in order
    Given a for-each step with the worker "reader" over a list of five items
    When a run reaches the step
    Then "reader" is given each of the five items
    And the step's result is the five results in the list's order

  Scenario: a for-each step over an empty list has an empty result
    Given a for-each step with the worker "reader" over an empty list
    When a run reaches the step
    Then the step's result is an empty list
    And the model is not called for the step
    And the next step is carried out

  Scenario: a for-each step's items run at once, up to the step's limit
    Given a for-each step with the worker "reader" over a list of twelve items and a limit of "4"
    When a run reaches the step
    Then no more than four items are worked at once
    And every item is worked

  Scenario Outline: one failed item fails a for-each step unless the step keeps going
    Given a for-each step with the worker "reader" over a list of five items, which <keeps going>
    When the worker gives up on the third item
    Then <outcome>

    Examples:
      | keeps going             | outcome                                                       |
      | does not keep going     | the step is failed, naming the third item                     |
      | keeps going             | the step's result holds four results and marks the third item failed |

  Scenario: a gather step gives every worker the same input and keeps every result
    Given a gather step with the workers "optimist", "sceptic" and "realist"
    When a run reaches the step
    Then each of the three workers is given the step's input
    And the step's result holds three results, each with the worker that gave it

  Scenario: a gather step chosen by an earlier step runs only the workers it names
    Given a gather step with the workers "weather", "activity" and "budget", chosen by the result of the step "select"
    And the step "select" has given the list "weather" and "budget"
    When a run reaches the gather step
    Then "weather" and "budget" are given the step's input
    And "activity" is not called
    And the step's result holds two results, each with the worker that gave it

  Scenario: a judge step's result is the judgment's answers with their probabilities
    Given a judge step asking the judgment questions "relevant" and "novelty"
    When a run reaches the step
    Then the step's result holds the answer to each question with the probabilities behind it

  Scenario: a critique step drafts again with the check's reasons until the draft passes
    Given a critique step with the drafting worker "writer", the critic "editor" and "3" rounds
    When "editor" returns the first draft with the reason "too long" and passes the second
    Then "writer" is given the reason "too long" before its second draft
    And the step's result is the second draft

  Scenario: a critique step's check may be a judgment
    Given a critique step with the drafting worker "writer", the judgment question "names a paper" as its verdict and "3" rounds
    When the judgment answers "no" for the first draft and "yes" for the second
    Then the step's result is the second draft

  Scenario: a draft that has not passed after the last round fails the step
    Given a critique step with the drafting worker "writer", the critic "editor" and "2" rounds
    When "editor" returns both drafts
    Then the step is failed, with the reasons "editor" gave for the last draft

  Scenario: a draft that has not passed after the last round is kept when the step says so
    Given a critique step with the drafting worker "writer", the critic "editor", "2" rounds and the last draft kept
    When "editor" returns both drafts
    Then the step's result is the second draft, marked as not passed, with the reasons "editor" gave

  Scenario: a call step runs a handler with what it reads and keeps what it returns
    Given a call step calling the handler "keep_paper" and reading the step "papers"
    When a run reaches the step
    Then the handler "keep_paper" is given the result of "papers" and told the run, the step and the version
    And the step's result is what the handler returned

  Scenario: a for-each step may drive work tasks
    Given a for-each step whose action is a work task for the worker "digger", over a list of three items
    When a run reaches the step
    Then "digger" works a task for each of the three items
    And the step's result is the three results in the list's order

  Scenario: each worker in a step has a session of its own
    Given a gather step with the workers "optimist" and "sceptic"
    When a run reaches the step
    Then "optimist" and "sceptic" each have a session of their own
    And neither is shown what the other said
