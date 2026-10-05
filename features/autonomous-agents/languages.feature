Feature: Autonomous agents in a service written in another language
  An autonomous agent in a service hosted as a process is declared by its definition once. The
  platform keeps its tasks and its agent instances and runs its iterations; the process is asked only
  for what is the developer's own code.

  Scenario: the platform runs the task of a process's autonomous agent and asks the process only for its own code
    Given a service written in "Python" with an autonomous agent "answerer" that accepts the task type "answer"
    When a caller runs a task of the type "answer" on "answerer"
    Then the task, its iterations and its result are kept by the platform
    And the process is asked only to run a tool, to check a guardrail and to check a result

  Scenario: the process checks a result against the rules it wrote
    Given a service written in "Python" with an autonomous agent "answerer" that accepts the task type "answer"
    And the task type "answer" has a rule, written in "Python", that a result must cite a source
    When the model completes a task of the type "answer" with a result that cites no source
    Then the process is asked to check the result
    And the task is result-rejected with the reason the process gave

  Scenario Outline: an endpoint of a process watches an agent instance's notifications
    Given a service written in "<language>" with an autonomous agent "answerer" that accepts the task type "answer"
    And an endpoint of the service watching an agent instance of "answerer"
    When the agent instance works a task
    Then the endpoint is given the same notifications an endpoint of a service written in "Scala" is given

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario: a test of a process's autonomous agent scripts its model as a test of an embedded one does
    Given a service written in "Python" with an autonomous agent "answerer"
    When a developer writes a test with the "Python" test kit and a scripted model
    Then the test can complete a task with a scripted result, wait for a task to end, condition the script on what the model was asked, and fail when the script runs out
