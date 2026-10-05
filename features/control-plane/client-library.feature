Feature: The control plane's protocol, published for clients
  A program outside the platform that drives the control plane, such as a hosted product's
  provisioning, speaks the control plane's protocol through a library each release publishes: the
  same requests and answers, and the same rules for a descriptor, that the platform itself applies.
  It is a client's library, and brings nothing that runs a service with it.

  Scenario: each release publishes the control plane's protocol as a library
    Given a release of ankka
    When the libraries the release publishes are listed
    Then the protocol library is among them

  Scenario: a client built on the protocol library refuses a descriptor the platform would refuse, for the platform's reason
    Given a client built on the protocol library and nothing else of ankka's
    And a descriptor the platform refuses, for a reason it states
    When the client checks the descriptor
    Then the client refuses the descriptor
    And the client states the same reason the platform states

  Scenario: a client built on the protocol library reads every answer the control plane gives
    Given a client built on the protocol library and nothing else of ankka's
    When the client reads an answer of the control plane
    Then the client reads the answer whole

  Scenario: the protocol library brings no cluster and no database with it
    When the libraries the protocol library depends on are listed
    Then none of them forms a cluster, reaches a database or changes a cluster
