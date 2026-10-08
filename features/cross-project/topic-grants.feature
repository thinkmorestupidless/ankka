Feature: Granting a topic to a service of another project
  A project opens one of its declared topics to one service of another project with a grant to
  consume it, to produce to it, or both. The broker enforces it: the grantee's credential gains
  the one topic, by its whole name and never a prefix, and nothing else. A service reading another
  project's topic reads it under a group of its own project, so how far it has read stays its own.

  Background:
    Given an installation with a broker
    And the projects "spinvibe" and "affiliates-hub" of the organization "eitheror"
    And the topic "casino.players" is declared on "spinvibe"
    And a deployed service "attribution" in "affiliates-hub"

  Scenario: a service is refused another project's topic nobody granted it
    When something holding the credential of "attribution" reads "spinvibe.casino.players" on the installation's broker
    Then the broker refuses it

  Scenario: a service reads another project's topic its project granted it to consume
    Given an owner of "eitheror" has granted the service "attribution" of "affiliates-hub" to consume the topic "casino.players" of "spinvibe"
    And a view "players" of "attribution" that reads the topic "casino.players" of "spinvibe"
    When a service of "spinvibe" publishes 100 messages with distinct subjects to the topic "casino.players"
    Then the view "players" holds 100 rows

  Scenario: a service publishes to another project's topic its project granted it to produce
    Given a deployed service "notifier" in the project "payments" of "eitheror"
    And the topic "payments.deposits" is declared on "spinvibe"
    And an owner of "eitheror" has granted the service "notifier" of "payments" to produce to the topic "payments.deposits" of "spinvibe"
    And a consumer "deposits" of "notifier" that publishes to the topic "payments.deposits" of "spinvibe"
    When "deposits" handles an event
    Then what "deposits" published is read from "spinvibe.payments.deposits" on the installation's broker

  Scenario: a grant to consume does not let a service publish
    Given an owner of "eitheror" has granted the service "attribution" of "affiliates-hub" to consume the topic "casino.players" of "spinvibe"
    When something holding the credential of "attribution" publishes to "spinvibe.casino.players" on the installation's broker
    Then the broker refuses it

  Scenario: a consumer of another project's topic keeps its group in its own project
    Given an owner of "eitheror" has granted the service "attribution" of "affiliates-hub" to consume the topic "casino.players" of "spinvibe"
    And a view "players" of "attribution" that reads the topic "casino.players" of "spinvibe"
    When the view subscribes
    Then its group is "ankka.affiliates-hub.attribution.view.players"
    And the credential of "attribution" may read no group of "spinvibe"

  Scenario: a revoked topic grant is refused by the broker without a redeploy
    Given an owner of "eitheror" has granted the service "attribution" of "affiliates-hub" to consume the topic "casino.players" of "spinvibe"
    And a view "players" of "attribution" that has read the topic "casino.players" of "spinvibe"
    When the owner revokes the grant
    Then within "120" seconds the broker refuses the next read of "spinvibe.casino.players" by the credential of "attribution"
    And no instance of "attribution" is restarted
    And no service of "spinvibe" is restarted

  Scenario: a topic the project has not declared cannot be granted
    When an owner of "eitheror" grants the service "attribution" of "affiliates-hub" to consume the topic "casino.sessions" of "spinvibe"
    Then the owner is refused
    And the refusal says that "spinvibe" has not declared the topic "casino.sessions"
