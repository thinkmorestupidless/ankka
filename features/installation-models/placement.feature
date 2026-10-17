Feature: Placing and sizing the model server
  Hardware is the installation's business. The model server runs on a CPU by default, serving the
  small model a local platform serves when asked for one, and the installation's own settings place it on accelerator
  machines, size it, and say how many calls it runs at once. A call beyond that waits and is
  answered, or times out as any model call does.

  Scenario: the model server is placed and sized by the installation's own settings
    Given the platform as it is installed in a cluster
    And the installation's own settings for the model server name accelerator machines, an accelerator count, a memory size, how many calls run at once, and the size of its volume
    When what it installs is read
    Then the model server is placed on the accelerator machines named
    And the model server is given the accelerator count, the memory size and the volume size named
    And the model server runs at once as many calls as named

  Scenario: a call beyond what the model server runs at once waits and is answered
    Given an installation whose model server runs one call at once
    And two agents on the installation model "qwen3:0.6b"
    When each agent is sent a message at the same moment
    Then both messages are answered
    And neither caller is told the model provider is unavailable before the model timeout

  Scenario: a local platform asked for the model server serves one small model on a CPU
    Given a local platform, newly made and asked for the model server
    When the status of the installation is read
    Then the status of the installation names a model server with one served model
    And the model server runs on a CPU, with no accelerator

  Scenario: a local platform not asked for the model server has none
    Given a local platform, newly made
    When the status of the installation is read
    Then the status of the installation names no model server

  Scenario: a call that waits past the model timeout is timed out as any model call is
    Given an installation whose model server runs one call at once
    And a model timeout of "10 seconds"
    And an agent on the installation model "qwen3:0.6b" whose message the model server is answering slowly
    When a second agent on the same installation model is sent a message
    Then the second caller is told the model provider timed out after "10 seconds"
