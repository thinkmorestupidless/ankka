Feature: What may change what a hostname reaches
  Only the operator makes a hostname reach a service, and only by exposing that service. A deployed
  service cannot change what any hostname reaches, and the operator cannot change how the gateway
  itself is reached.

  Scenario Outline: a deployed service cannot read or change what any hostname reaches
    Given a deployed service "cart"
    When "cart", with the credential it runs with, tries to <act> what the hostname of a service reaches
    Then the cluster refuses "cart"

    Examples:
      | act    |
      | read   |
      | change |

  Scenario: the operator cannot change how the gateway itself is reached
    Given the operator of an installation
    When the operator tries to change how the gateway is reached from the internet
    Then the cluster refuses the operator
