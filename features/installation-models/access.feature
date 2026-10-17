Feature: Only the platform's own program reaches the model server
  An installation model has no key. What keeps a developer's program from calling it behind the
  platform's back is the model server's door: it answers only a holder of a service certificate of
  the installation, which the platform's own program holds and the developer's program does not.
  So every call to an installation model is made by the platform, in a session, through guardrails,
  approvals and model usage, as a call to any model provider is.

  Scenario: a developer's program is refused by the model server
    Given a deployed service "support" written in "Python" on an installation with a model server
    When the process of "support" connects to the model server
    Then the process is refused before anything is sent

  Scenario: the platform's program of the same service is answered by the model server
    Given a deployed service "support" written in "Python" on an installation with a model server
    When the platform's own program of "support" connects to the model server
    Then the connection is accepted

  Scenario: a workload with no service certificate is refused by the model server
    Given an installation with a model server
    When a workload that holds no service certificate of the installation connects to the model server
    Then the workload is refused before anything is sent

  Scenario: every call to an installation model is a turn or an iteration in a session
    Given a deployed service "support" with an agent "helper" on an installation model
    When the model server has answered calls for "support"
    Then each call the model server answered is a turn of an agent or an iteration of an autonomous agent in a session of "support"
    And the model usage of each is recorded under the name of the model provider
