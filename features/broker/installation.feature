Feature: The installation's broker
  The platform provides one broker for an installation, as it provides each project a database.
  A local platform has one from the start, so a declared topic works on a developer's machine with
  nothing set up by hand. Only the services of the installation reach it, until the installation
  exposes it to registered machines (features/cross-project/machine-topics.feature).

  Scenario: a local platform has a broker from the start
    Given a local platform, newly made
    And a project with a declared topic
    And a descriptor for the sample "shopping-cart" in that project that gives no broker variable
    When a member applies the descriptor
    Then the sample is ready
    And what its consumer publishes is read from the installation's broker

  Scenario: only the services of the installation reach its broker
    Given an installation with a broker
    When a workload that is not of the installation connects to the broker
    Then the workload is refused

  Scenario: an installation in a cluster has the broker a local platform has
    Given the platform as it is installed in a cluster
    When what it installs is read
    Then it installs the same broker a local platform has
    And the size of the broker is left for whoever installs it to state

  Scenario: the installation's broker belongs to the platform and to no project of a member
    Given an installation with a broker
    When the certificate of the broker is read
    Then the certificate names the platform and no project a member can have

  Scenario: a service of an installation with no broker is deployed as it was before
    Given an installation with no broker
    And a descriptor for a service "wallet"
    When a member applies the descriptor
    Then the environment of "wallet" has no broker variable

  Scenario: a topic declared on an installation with no broker says why it is not made
    Given an installation with no broker
    And a project "money"
    When a member declares the topic "transactions" on "money"
    Then the topic "transactions" is "Failed", and the installation has no broker
