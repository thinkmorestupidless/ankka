Feature: The life of a served model
  The model server fetches each declared model when it starts, holds it ready, and keeps it so: a
  model added to the declaration is fetched and served without any service restarting, a served
  model is not let go of while no call comes, a model that cannot be fetched is reported while the
  others are served, and a model removed from the declaration is served no longer.

  Scenario: a model added to the declaration is fetched and served without restarting services
    Given an installation whose model server serves the model "qwen3:0.6b"
    And a deployed service "support" on the installation model "qwen3:0.6b"
    When a platform administrator adds the model "llama3.2:1b" to the model server's declaration
    Then "llama3.2:1b" becomes a served model
    And no instance of "support" restarted

  Scenario: a served model stays served while no call comes
    Given an installation whose model server serves the model "qwen3:0.6b"
    And no call has reached the model server for an hour
    When an agent on the installation model "qwen3:0.6b" is sent a message
    Then the message is answered within the same time as a message sent an hour before

  Scenario: a model that cannot be fetched is reported in the installation's status and the others are served
    Given an installation whose model server declares the models "qwen3:0.6b" and "no-such-model:1b"
    When the status of the installation is read
    Then the status of the installation says that "qwen3:0.6b" is a served model
    And the status of the installation says that "no-such-model:1b" failed, and why

  Scenario: a model removed from the declaration is no longer served and a service naming it fails to start naming the served models
    Given an installation whose model server serves the models "qwen3:0.6b" and "llama3.2:1b"
    When a platform administrator removes the model "llama3.2:1b" from the model server's declaration
    And a member applies a descriptor for a service "support" that names a model provider with the preset "installation" and the model "llama3.2:1b"
    Then "llama3.2:1b" is not a served model
    And the lifecycle of "support" is "Failed"
    And the detail of "support" says that the installation model "llama3.2:1b" is not served, and names "qwen3:0.6b"

  Scenario: a served model is fetched once and its digest recorded
    Given an installation whose model server declares the model "qwen3:0.6b"
    When "qwen3:0.6b" becomes a served model
    Then the status of the installation records the digest of "qwen3:0.6b" as fetched
    And "qwen3:0.6b" is not fetched again while it is a served model, whatever the tag "qwen3:0.6b" comes to name elsewhere
    And a platform administrator may ask for "qwen3:0.6b" to be fetched again, and the status then records the new digest

  Scenario: the model server restarts and serves its models again without fetching them
    Given an installation whose model server serves the model "qwen3:0.6b"
    When the model server restarts
    Then "qwen3:0.6b" is a served model again without being fetched again

  Scenario: a call in flight when the model server restarts is the model provider's failure
    Given a deployed service "support" with an agent "helper" on an installation model
    And a message to "helper" the model server is answering
    When the model server restarts
    Then the caller of "helper" is told the model provider is unavailable
    And "support" is unaffected
