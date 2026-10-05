Feature: The GitHub action
  A job on GitHub uses the GitHub action to install the command line at a version it names, point it at a
  control plane and authenticate it with a deploy token, so that every later command in the job runs
  as the deploy token's member with no sign-in. The GitHub action wraps no command: any command the
  command line offers runs as it does for a person.

  Background:
    Given a deploy token of the organization "acme"

  Scenario: a job that uses the GitHub action has the command line at the version it named
    Given a job that uses the GitHub action at the version "0.12.0"
    When a later command in the job asks the command line for its version
    Then the command line answers "0.12.0"

  Scenario: a command in a job that used the GitHub action runs authenticated against the control plane it named
    Given a job that used the GitHub action with a control plane and the deploy token
    When a later command in the job lists the services of a project of "acme"
    Then the control plane admits it as a member of "acme"
    And nobody signed in

  Scenario: a job given an installation's authority reaches that installation's control plane
    Given an installation whose certificates an authority of its own issued
    And a job that used the GitHub action with the control plane of that installation, the deploy token and that authority
    When a later command in the job lists the services of a project of "acme"
    Then the command succeeds

  Scenario Outline: the GitHub action fails at once without a deploy token the control plane admits
    Given a job that uses the GitHub action with <token>
    When the job runs
    Then the GitHub action fails
    And the failure names <what>
    And no later command in the job runs

    Examples:
      | token                                    | what                                     |
      | no deploy token                          | the deploy token as what is missing      |
      | a deploy token the control plane refuses | why the control plane refused it         |

  Scenario: the GitHub action fails plainly when the version it names cannot be fetched
    Given a job that uses the GitHub action at the version "9.9.9", which was never released
    When the job runs
    Then the GitHub action fails
    And the failure says that the command line at the version "9.9.9" could not be fetched
