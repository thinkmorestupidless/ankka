Feature: Hostnames on a local platform
  A developer who installs a local platform as the documentation says reaches the control plane and
  every exposed service at their hostnames from their own machine, without changing anything on it.

  Scenario: the control plane of a local platform answers at its hostname from the developer's machine
    Given a developer's machine with what the documentation says a local platform needs
    When the developer installs a local platform as the documentation says
    Then the control plane answers at its hostname from the developer's machine

  Scenario: an exposed sample on a local platform answers at its hostname with nothing on the machine changed
    Given a local platform
    And the sample "shopping-cart" deployed and exposed on it
    When the developer adds the item "socks" to the cart "c1" through the hostname of "shopping-cart"
    Then reading the cart "c1" through the hostname of "shopping-cart" shows the item "socks"
    And the developer changed nothing on the machine to reach it

  Scenario: a cluster that cannot take requests at the machine's ports is refused, and the developer is told to create it again
    Given a cluster on the developer's machine that does not take requests at the machine's ports
    When the developer installs the platform on it
    Then the installation is refused
    And the developer is told to create the cluster again
