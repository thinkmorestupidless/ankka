Feature: Exposing a service
  A deployed service is private until a member exposes it. Exposed, it answers at its hostname from
  the internet, through the gateway, with a certificate the platform issued for that hostname;
  unexposed again, only its hostname stops answering and the service goes on as it was.

  Scenario: a service that is not exposed answers nothing at the hostname it would have
    Given a deployed service "cart" that is not exposed
    When a person on the internet sends a request to the hostname "cart" would have
    Then the request reaches no instance of "cart"

  Scenario: an exposed service answers at its hostname with a certificate the platform issued
    Given a deployed service "cart" that is not exposed
    When a member exposes "cart"
    Then the member is shown the hostname of "cart"
    And within "60" seconds a request from the internet to the hostname, checking its certificate against the authority of the installation, is answered by "cart"

  Scenario: a request sent to a hostname in the clear is redirected and served by no handler
    Given a deployed service "cart" that is exposed
    When a person on the internet sends a request to the hostname of "cart" in the clear
    Then the person is redirected to the same address, not in the clear
    And no handler runs

  Scenario: what is written through a hostname is read back through it
    Given a deployed service "cart" that is exposed
    And a person has added the item "socks" to the cart "c1" through the hostname of "cart"
    When the person reads the cart "c1" through the hostname of "cart"
    Then the person is shown the item "socks"

  Scenario Outline: no request through a hostname reaches an instance that is not ready
    Given a deployed service "cart" that is exposed and has "3" instances
    When an instance of "cart" is <why>
    Then no request to the hostname of "cart" reaches that instance

    Examples:
      | why                         |
      | starting                    |
      | stopping to be replaced     |

  Scenario: unexposing a service stops its hostname answering and changes nothing else
    Given a deployed service "cart" that is exposed
    When a member unexposes "cart"
    Then within "30" seconds nothing answers at the hostname "cart" had
    And every instance of "cart" is ready
    And no instance of "cart" is restarted
    And the other services of the installation still reach "cart"

  Scenario: a deleted service leaves nothing answering at its hostname
    Given a deployed service "cart" that is exposed
    When a member deletes "cart"
    Then nothing answers at the hostname "cart" had

  Scenario: a paused service answers nothing at its hostname
    Given a deployed service "cart" that is exposed
    When a member pauses "cart"
    Then a request to the hostname of "cart" reaches no instance of "cart"

  Scenario: a resumed service answers at its hostname without being exposed again
    Given a deployed service "cart" that is exposed and paused
    When a member resumes "cart"
    Then a request to the hostname of "cart" is answered by "cart" once an instance is ready
    And "cart" is still exposed
