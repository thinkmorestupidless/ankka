Feature: The installation's broker
  The platform provides one broker for an installation, as it provides each project a database.
  A local platform has one from the start, so a declared topic works on a developer's machine with
  nothing set up by hand. Only the services of the installation reach it.

  Scenario: a local platform has a broker from the start
    Given a local platform, newly made
    And a descriptor for the sample "shopping-cart" that declares a topic and gives no broker variable
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

  Scenario Outline: a service of an installation with no broker is deployed as it was before
    Given an installation with no broker
    And a descriptor for a service "wallet" that <declares>
    When a member applies the descriptor
    Then <outcome>

    Examples:
      | declares                         | outcome                                                               |
      | declares no topic                | the environment of "wallet" has no broker variable                    |
      | declares the topic "transactions" | the status says that the broker of "wallet" is "Failed", and that the installation has no broker |
