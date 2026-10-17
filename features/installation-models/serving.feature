Feature: Installation models
  An installation may run one model server per cluster, as it runs one broker: a platform
  administrator declares the models it serves, and a service names one as a model provider through
  the preset "installation", with a model and nothing else. The platform's own program talks to the
  model server inside the cluster, so nothing of a call leaves the installation. An installation
  without a model server is as it was.

  Scenario: an installation with the model server serves the models its administrator declared
    Given an installation whose model server declares the models "qwen3:0.6b" and "llama3.2:1b"
    When the status of the installation is read
    Then the status of the installation says that "qwen3:0.6b" and "llama3.2:1b" are served models

  Scenario: a service names an installation model with a preset and a model and no key
    Given an installation whose model server serves the model "qwen3:0.6b"
    And a descriptor for a service "support" written in "Python" that names a model provider "local" with the preset "installation" and the model "qwen3:0.6b"
    When a member applies the descriptor
    Then "support" is ready
    And the model provider "local" of "support" is the installation model "qwen3:0.6b"

  Scenario: an agent on an installation model answers with a tool call and nothing leaves the installation
    Given a deployed service "support" with an agent "helper" that names the model provider "local", an installation model
    And a tool "lookup" of "helper"
    When a caller sends a message to "helper" that makes the model call "lookup"
    Then the tool "lookup" runs
    And the caller is answered
    And every call the turn made was answered by the model server of the cluster, and none by anything outside the installation

  Scenario: the installation's status names the model server and the models it serves
    Given an installation whose model server declares the models "qwen3:0.6b" and "llama3.2:1b"
    When a developer reads the status of the installation
    Then the status of the installation names the model server of each cluster
    And the status of the installation says, for each declared model, whether it is a served model, still being fetched, or failed and why

  Scenario: an installation without the model server is unchanged
    Given an installation with no model server
    And a descriptor for a service "wallet"
    When a member applies the descriptor
    Then "wallet" is deployed as it was before the installation could run a model server
    And the status of the installation names no model server
