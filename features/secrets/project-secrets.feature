Feature: Project secrets
  A member keeps a value a service needs when it starts, such as a credential for a service
  outside the platform, as an entry of a project secret, and a descriptor takes a variable from
  it. The platform keeps the values where a starting instance is given them and nowhere else: the
  control plane records that a project secret exists and which entries it has, and can never read
  a value back.

  Background:
    Given a project "shop"
    And a member of the organization "shop" is in

  Scenario: a member sets a project secret
    When the member sets the entry "STRIPE_KEY" of the project secret "checkout" to "sk_live_1"
    Then the project "shop" has the project secret "checkout" with the entry "STRIPE_KEY"

  Scenario: setting an entry keeps the other entries of the project secret
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    When the member sets the entry "WEBHOOK_KEY" of the project secret "checkout" to "whsec_1"
    Then the project "shop" has the project secret "checkout" with the entries "STRIPE_KEY" and "WEBHOOK_KEY"

  Scenario: a member removes an entry of a project secret
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    And the entry "WEBHOOK_KEY" of the project secret "checkout" is set to "whsec_1"
    When the member removes the entry "WEBHOOK_KEY" of the project secret "checkout"
    Then the project "shop" has the project secret "checkout" with only the entry "STRIPE_KEY"

  Scenario: a project secret whose last entry is removed is no longer listed
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    When the member removes the entry "STRIPE_KEY" of the project secret "checkout"
    Then a list of the project secrets of "shop" shows no project secret "checkout"

  Scenario: removing an entry that was never set changes nothing
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    When the member removes the entry "MISSING" of the project secret "checkout"
    Then the member is told that there is no such entry
    And the project "shop" has the project secret "checkout" with only the entry "STRIPE_KEY"

  Scenario: a project secret whose last entry was removed is listed again when an entry is set
    Given the entry "STRIPE_KEY" of the project secret "checkout" was set to "sk_live_1" and has since been removed
    When the member sets the entry "WEBHOOK_KEY" of the project secret "checkout" to "whsec_1"
    Then the project "shop" has the project secret "checkout" with only the entry "WEBHOOK_KEY"

  Scenario Outline: a project secret may not take a name the platform uses
    When the member sets the entry "STRIPE_KEY" of the project secret "<name>" to "sk_live_1"
    Then the member is refused
    And the refusal says that the name is one the platform uses
    And the project "shop" has no project secret "<name>"

    Examples:
      | name                  |
      | ankka-registry        |
      | payments-db           |
      | payments-secret-key   |
      | payments-cluster-tls  |
      | payments-service-tls  |
      | payments-database-tls |

  Scenario: a variable taken from a project secret reaches the service
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    And a descriptor for a service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "checkout"
    When the member applies the descriptor
    Then "cart" starts with the variable "STRIPE_KEY" set to "sk_live_1"

  Scenario: the control plane records that a project secret was set and never a value
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    And the entry "WEBHOOK_KEY" of the project secret "checkout" was set to "whsec_1" and has since been removed
    When what the control plane recorded is read
    Then it names the project "shop", the project secret "checkout" and the entry "STRIPE_KEY"
    And it names the entry "WEBHOOK_KEY" as removed
    And it holds "sk_live_1" and "whsec_1" nowhere

  Scenario: the control plane cannot read a project secret back
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    When the control plane tries to read the project secret "checkout"
    Then the control plane is refused

  Scenario: an entry set again is what an instance started afterwards is given
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    And a deployed service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "checkout"
    When the member sets the entry "STRIPE_KEY" of the project secret "checkout" to "sk_live_2"
    Then an instance of "cart" started afterwards has the variable "STRIPE_KEY" set to "sk_live_2"

  Scenario Outline: a person who is not a member is told there is no such project
    Given a person who is not a member of the organization "shop" is in
    When that person <asks>
    Then that person is told that there is no project "shop"

    Examples:
      | asks                                                                         |
      | sets the entry "STRIPE_KEY" of the project secret "checkout" to "sk_live_1" |
      | removes the entry "STRIPE_KEY" of the project secret "checkout"              |
      | lists the project secrets of "shop"                                          |

  Scenario: a machine holding a deploy token sets a project secret
    Given a deploy token of the organization "shop" is in
    When a machine holding the deploy token sets the entry "STRIPE_KEY" of the project secret "checkout" to "sk_live_1"
    Then the project "shop" has the project secret "checkout" with the entry "STRIPE_KEY"

  Scenario: a list of project secrets shows names and entries and never a value
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    When the member lists the project secrets of "shop"
    Then the list shows the project secret "checkout" with the entry "STRIPE_KEY"
    And the list shows no value

  Scenario: a service whose variable is taken from a project secret that does not exist does not become ready
    Given a descriptor for a service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "missing"
    When the member applies the descriptor
    Then "cart" does not become ready
    And the status of "cart" says why

  Scenario: a project secret the platform could not keep is not recorded
    Given the platform cannot keep a project secret at present
    When the member sets the entry "STRIPE_KEY" of the project secret "checkout" to "sk_live_1"
    Then the member is told that the project secret was not set, and to try again later
    And the control plane records nothing of it
