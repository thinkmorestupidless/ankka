Feature: A registered machine reading a project's topic from outside the installation
  An installation may expose its broker to registered machines, through the gateway, at hostnames
  of its base domain. A registered machine connects with an ordinary broker client, holding its
  client id and client secret and the token route, and the broker admits it by its machine token
  and lets it reach exactly what its grants in effect give it: the one topic, under a group of its
  own, within its byte rate. Nothing reaches the broker from outside without a machine token, and
  an installation that does not expose its broker is reached by no machine at all.

  Background:
    Given an installation with a broker
    And an organization "affiliates" whose owner is "bo"
    And "bo" has registered "network" as a machine of "affiliates"
    And the topic "affiliates.attribution" is declared on "spinvibe", a project of the organization "eitheror"
    And the registered machine "network" runs an ordinary broker client outside the installation, holding the hostname of the installation's broker, its client id and client secret, and the token route

  Scenario: a machine with an accepted consume grant reads a project's topic from outside the installation
    Given the installation exposes its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    And a service of "spinvibe" has published 100 messages with distinct subjects to the topic "affiliates.attribution"
    When the client of "network" reads "spinvibe.affiliates.attribution" from the earliest message under the group "ankka.machine.affiliates.network.attribution"
    Then it reads every one of the 100 messages
    And each message carries its attributes with it

  Scenario: a machine is refused a topic nobody granted it
    Given the installation exposes its broker to registered machines
    And the topic "casino.players" is declared on "spinvibe"
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    When the client of "network" reads "spinvibe.casino.players"
    Then the broker refuses it

  Scenario: a machine granted consume cannot publish to the topic
    Given the installation exposes its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    When the client of "network" publishes to "spinvibe.affiliates.attribution"
    Then the broker refuses it

  Scenario: a machine with an accepted produce grant publishes to a project's topic
    Given the installation exposes its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to produce to the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    And a deployed service "ledger" in "spinvibe" with a view "clicks" that reads the topic "affiliates.attribution"
    When the client of "network" publishes a message to "spinvibe.affiliates.attribution"
    Then the view "clicks" shows what was published

  Scenario: a machine's consumer group is under its own prefix and no other group is open to it
    Given the installation exposes its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    When what the installation's broker allows the credential of the registered machine "network" is listed
    Then the credential may read "spinvibe.affiliates.attribution"
    And the credential may read any group named under "ankka.machine.affiliates.network." and no other
    And the credential may do nothing else on the broker

  Scenario: a revoked grant is refused by the broker on the machine's next request
    Given the installation exposes its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    And the client of "network" has read "spinvibe.affiliates.attribution"
    When the owner revokes the grant
    Then within "120" seconds the broker refuses the next read by the client of "network"
    And no service of "spinvibe" is restarted

  Scenario: a deleted machine is disconnected by the time its token would have expired
    Given the installation exposes its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    And the client of "network" is reading "spinvibe.affiliates.attribution" with a machine token issued a moment ago
    When "bo" deletes the registered machine "network"
    Then the broker makes the client prove itself again before "15" minutes have passed
    And the client, given no new machine token by the token route, has its connection ended by the broker
    And the credential of "network" on the broker is kept, with no topic

  Scenario Outline: a connection to the external listener with no token, or a token from another issuer, is refused
    Given the installation exposes its broker to registered machines
    And an issuer "strangers" that signs tokens for the audience "shop"
    When a client outside the installation connects to the installation's broker at its hostname <with>
    Then the broker refuses the connection before anything is exchanged

    Examples:
      | with                          |
      | with no machine token         |
      | with a token from "strangers" |

  Scenario: a machine that exceeds its fetch quota is throttled, and services on the broker are not
    Given the installation exposes its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    And the byte rate of "network" is the installation's default
    And a deployed service "ledger" in "spinvibe" with a view "clicks" that reads the topic "affiliates.attribution"
    When the client of "network" reads "spinvibe.affiliates.attribution" as fast as it can
    Then the registered machine "network" is throttled to its byte rate
    And the view "clicks" reads the topic as fast as it did before

  Scenario: an installation that has not exposed its broker reports a machine's topic grant as not in effect
    Given the installation does not expose its broker to registered machines
    And an owner of "eitheror" has granted the registered machine "network" of "affiliates" to consume the topic "affiliates.attribution" of "spinvibe", and "bo" has accepted the grant
    When a member of "eitheror" reads the grants of "spinvibe"
    Then the grant is shown as not in effect, "broker not exposed"
    And the credential of "network" on the broker may read "spinvibe.affiliates.attribution"
    And the client of "network" reaches the installation's broker by no hostname
