Feature: Registering a blueprint
  A service holds blueprints: descriptions of workers and the steps between them, registered by a
  call and never deployed. A blueprint is never edited; a changed blueprint is a new blueprint
  version, and every earlier version is kept.

  Background:
    Given a service with the tools "search" and "keep entry" and the model "default"

  Scenario: a blueprint is registered and read back as it was registered
    When the service registers the blueprint "watch"
    Then the blueprint "watch" is held at blueprint version 1
    And a reader reads blueprint version 1 of "watch" as it was registered

  Scenario: a blueprint registered twice is one version
    Given the blueprint "watch" held at blueprint version 1
    When the service registers the same blueprint "watch" again
    Then the blueprint "watch" has one blueprint version
    And the service is given blueprint version 1

  Scenario: a changed blueprint is a new version, and the earlier version is kept
    Given the blueprint "watch" held at blueprint version 1
    When the service registers "watch" with different instructions for one worker
    Then the blueprint "watch" is held at blueprint version 2
    And blueprint version 1 of "watch" reads as it was registered

  Scenario: a blueprint version is never changed
    Given the blueprint "watch" held at blueprint version 1
    When a caller asks to change a worker's instructions in blueprint version 1
    Then the caller is refused
    And blueprint version 1 of "watch" reads as it was registered

  Scenario: a reader lists a blueprint's versions in order
    Given the blueprint "watch" held at blueprint versions 1, 2 and 3
    When a reader lists the blueprint versions of "watch"
    Then the reader is given blueprint versions 1, 2 and 3 in that order, each with when it was registered

  Scenario: a blueprint that comes with a service is registered when it starts
    Given a service whose code carries the blueprint "digest"
    When the service starts
    Then the blueprint "digest" is held at blueprint version 1

  Scenario: a blueprint that comes with a service and is unchanged adds no version when the service starts again
    Given a service whose code carries the blueprint "digest", held at blueprint version 1
    When the service restarts with the same blueprint
    Then the blueprint "digest" has one blueprint version
