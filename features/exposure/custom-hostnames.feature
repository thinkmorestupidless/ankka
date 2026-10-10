Feature: A custom hostname for an exposed service
  An exposed service answers at the hostname the platform derived, and at every custom hostname a
  member adds to it: a name under a domain the owner of the domain brings. The project proves
  control of the name with a proof record before the claim is recorded, and then points the name at
  the installation. The platform obtains a certificate for the name and serves it as it serves the
  derived hostname; it reads the proof record once and otherwise manages no DNS, and a name nobody
  has pointed serves nothing. One custom hostname is held by one service of the installation.

  Background:
    Given an installation whose base domain is "example.test"
    And a deployed service "cart" of the project "checkout" that is exposed
    And the name "app.example.com" carries the proof record of the project "checkout"

  Scenario: a custom hostname is refused until the project proves control of the name
    Given the name "shop.example.com" carries no proof record
    When a member adds the custom hostname "shop.example.com" to "cart"
    Then the member is refused
    And the refusal says that "shop.example.com" does not carry the proof record of the project "checkout"
    And the refusal tells the member the proof record to create for "shop.example.com"
    And "cart" does not hold "shop.example.com"

  Scenario: a custom hostname whose name carries the project's proof is recorded
    Given the name "shop.example.com" carries the proof record of the project "checkout"
    When a member adds the custom hostname "shop.example.com" to "cart"
    Then the custom hostname "shop.example.com" is held by "cart"

  Scenario: a service answers at a custom hostname with a certificate for that hostname
    Given the name "app.example.com" resolves to the installation
    When a member adds the custom hostname "app.example.com" to "cart"
    Then within "300" seconds a request from the internet to "app.example.com", checking its certificate against the authority of the installation, is answered by "cart"
    And the certificate is for "app.example.com"

  Scenario: a service that gains a custom hostname still answers at the hostname the platform derived
    Given the name "app.example.com" resolves to the installation
    And a member has added the custom hostname "app.example.com" to "cart"
    When a person on the internet sends a request to "cart-checkout.example.test"
    Then the request is answered by "cart"

  Scenario: a member is told the record to create for a custom hostname
    When a member adds the custom hostname "app.example.com" to "cart"
    Then the member is told to create a record for "app.example.com" that points at "cart-checkout.example.test"
    And the member is told the same whenever the member reads the service "cart"
    And the member is told the proof record of the project "checkout" whenever the member reads the service "cart"

  Scenario: a custom hostname whose name does not resolve to the installation is held and serves nothing
    Given the authority for custom hostnames issues a certificate for a name only when the name resolves to the installation
    And the name "app.example.com" resolves to nothing
    When a member adds the custom hostname "app.example.com" to "cart"
    Then the custom hostname "app.example.com" is held by "cart"
    And the member is shown that "app.example.com" is waiting for its certificate
    And the member is shown the authority's reason, that it could not reach "app.example.com"
    And a request from the internet to "app.example.com" reaches no instance of "cart"

  Scenario: a member is shown every hostname of a service and where each stands
    Given the name "app.example.com" resolves to the installation
    And a member has added the custom hostname "app.example.com" to "cart"
    When a member reads the service "cart"
    Then the member is shown the hostname "cart-checkout.example.test"
    And the member is shown the custom hostname "app.example.com" and that it is serving

  Scenario: the console shows a service's custom hostnames beside the one the platform derived
    Given a member has added the custom hostname "app.example.com" to "cart"
    When the member reads "cart" in the console
    Then the console shows the hostname "cart-checkout.example.test"
    And the console shows the custom hostname "app.example.com" and where it stands

  Scenario: a gRPC call is answered at a custom hostname
    Given "cart" declares gRPC
    And the name "app.example.com" resolves to the installation
    And a member has added the custom hostname "app.example.com" to "cart"
    When a gRPC client on the internet calls a method of "cart" at "app.example.com"
    Then the call is answered by "cart"

  Scenario: a request sent to a custom hostname in the clear is redirected and served by no handler
    Given the name "app.example.com" resolves to the installation
    And a member has added the custom hostname "app.example.com" to "cart"
    When a person on the internet sends a request to "app.example.com" in the clear
    Then the person is redirected to the same address, not in the clear
    And no handler runs

  Scenario: a service that is not exposed cannot be given a custom hostname
    Given a deployed service "orders" of the project "checkout" that is not exposed
    When a member adds the custom hostname "orders.example.com" to "orders"
    Then the member is refused
    And the refusal says that "orders" is not exposed

  Scenario: a custom hostname another service holds is refused, naming the holder
    Given a member has added the custom hostname "app.example.com" to "cart"
    And a deployed service "portal" of the project "billing" that is exposed
    When a member adds the custom hostname "app.example.com" to "portal"
    Then the member is refused
    And the refusal says that "app.example.com" is held by "cart" of the project "checkout"

  Scenario Outline: a custom hostname under the base domain is refused
    When a member adds the custom hostname "<hostname>" to "cart"
    Then the member is refused
    And the refusal says that a custom hostname cannot be under the base domain

    Examples:
      | hostname                   |
      | shop.example.test          |
      | cart-checkout.example.test |
      | example.test               |

  Scenario Outline: a custom hostname that is not a name is refused, naming what is wrong
    When a member adds the custom hostname "<hostname>" to "cart"
    Then the member is refused
    And the refusal says <why>

    Examples:
      | hostname                     | why                                          |
      | *.example.com                | that a custom hostname cannot be a wildcard  |
      | https://app.example.com      | that a custom hostname is a name alone       |
      | app.example.com/shop         | that a custom hostname is a name alone       |
      | app.example.com:8443         | that a custom hostname is a name alone       |

  Scenario: a custom hostname is refused when the installation names no authority for custom hostnames
    Given an installation that names no authority for custom hostnames
    When a member adds the custom hostname "app.example.com" to "cart"
    Then the member is refused
    And the refusal says that the installation names no authority for custom hostnames

  Scenario: a sixth custom hostname is refused, naming the cap
    Given a member has added "5" custom hostnames to "cart", each carrying the proof record of the project "checkout"
    And the name "a6.example.com" carries the proof record of the project "checkout"
    When a member adds the custom hostname "a6.example.com" to "cart"
    Then the member is refused
    And the refusal says that a service holds at most "5" custom hostnames

  Scenario: a platform administrator takes a custom hostname away from a service
    Given a member has added the custom hostname "app.example.com" to "cart"
    When a platform administrator takes the custom hostname "app.example.com" away from "cart"
    Then "cart" no longer holds "app.example.com"
    And nothing answers at "app.example.com"
    And the history of "cart" says who took it away, and when

  Scenario: a custom hostname taken away from a service can be given to another
    Given a platform administrator has taken the custom hostname "app.example.com" away from "cart"
    And a deployed service "portal" of the project "billing" that is exposed
    And the name "app.example.com" carries the proof record of the project "billing"
    When a member adds the custom hostname "app.example.com" to "portal"
    Then the custom hostname "app.example.com" is held by "portal"

  Scenario: removing a custom hostname stops it answering and changes nothing else
    Given the name "app.example.com" resolves to the installation
    And a member has added the custom hostname "app.example.com" to "cart"
    And "app.example.com" is serving
    When a member removes the custom hostname "app.example.com" from "cart"
    Then within "30" seconds nothing answers at "app.example.com"
    And a request from the internet to "cart-checkout.example.test" is answered by "cart"
    And every instance of "cart" is ready
    And no instance of "cart" is restarted

  Scenario: unexposing a service stops every hostname it has
    Given the name "app.example.com" resolves to the installation
    And a member has added the custom hostname "app.example.com" to "cart"
    When a member unexposes "cart"
    Then within "30" seconds nothing answers at "app.example.com"
    And nothing answers at "cart-checkout.example.test"
    And "cart" still holds "app.example.com"

  Scenario: a service exposed again answers at every hostname it had
    Given the name "app.example.com" resolves to the installation
    And a member has added the custom hostname "app.example.com" to "cart"
    And a member has unexposed "cart"
    When a member exposes "cart"
    Then within "300" seconds a request from the internet to "app.example.com" is answered by "cart"
    And a request from the internet to "cart-checkout.example.test" is answered by "cart"

  Scenario: a deleted service leaves its custom hostnames free for another service
    Given a member has added the custom hostname "app.example.com" to "cart"
    And a deployed service "portal" of the project "billing" that is exposed
    And the name "app.example.com" carries the proof record of the project "billing"
    When a member deletes "cart"
    Then nothing answers at "app.example.com"
    And a member can add the custom hostname "app.example.com" to "portal"

  Scenario: a certificate the authority refuses is shown with the authority's reason
    Given the name "app.example.com" resolves to the installation
    And the authority for custom hostnames refuses to issue a certificate for "app.example.com"
    When a member adds the custom hostname "app.example.com" to "cart"
    Then the member is shown that "app.example.com" is waiting for its certificate
    And the member is shown the authority's reason
