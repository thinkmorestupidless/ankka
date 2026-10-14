Feature: Credentials for a project on a remote cluster
  A project's registry credential and its project secrets are written on the cluster the project is
  placed on, by the operator there, by create and patch, as the control plane writes them at home.
  Each value crosses the tether once, downward; the control plane keeps none, and a remote cluster is
  sent nothing of a project placed elsewhere.

  Background:
    Given a platform administrator "root"
    And a remote cluster "pi" that is tethered
    And a remote cluster "garage" that is tethered
    And a project "shop" placed on "pi"

  Scenario: a project's registry credential is written on the cluster the project is placed on
    When a member registers a registry credential for the registry "ghcr.io" with the username "octocat" and the password "p4ss" for "shop"
    Then the operator on "pi" writes the registry credential of "shop" on "pi"
    And the operator on "pi" cannot read the registry credential back
    And no registry credential of "shop" is written on "garage" or on the home cluster

  Scenario: a project secret's entries are written on the cluster the project is placed on
    When a member sets the entry "apiKey" of the project secret "payments" of "shop"
    Then the operator on "pi" writes the entry "apiKey" of "payments" on "pi"
    And no project secret "payments" is written on "garage" or on the home cluster
    When the member removes the entry "apiKey" of "payments"
    Then the operator on "pi" removes the entry "apiKey" of "payments" on "pi"

  Scenario: a remote cluster is sent nothing of a project placed elsewhere
    Given a project "store" placed on "garage"
    When a member applies a descriptor for the service "cart" in "shop"
    And a member registers a registry credential for the registry "ghcr.io" for "shop"
    Then the operator on "garage" is sent nothing of "shop"
    And the operator on "pi" is sent nothing of "store"

  Scenario: the control plane keeps no value that crossed the tether
    When a member registers a registry credential for the registry "ghcr.io" with the password "p4ss" for "shop"
    And a member sets the entry "apiKey" of the project secret "payments" of "shop" to "k-123"
    Then the journal of the control plane holds no password and no entry value
    And what the control plane recorded of the tether holds no password and no entry value

  Scenario: setting a credential for a project on a remote cluster whose tether is down is refused and records nothing
    Given the tether of "pi" is down
    When a member registers a registry credential for the registry "ghcr.io" for "shop"
    Then the registration is refused with 503, saying that "pi" is untethered
    And no registry credential is registered for "shop"
    When a member sets the entry "apiKey" of the project secret "payments" of "shop"
    Then the setting is refused with 503, saying that "pi" is untethered
    And "shop" has no project secret "payments"
