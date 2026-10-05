Feature: Applying a descriptor
  A member applies a descriptor and the platform makes the service what it says: its instances run
  the image, with the environment and the size the descriptor asks for, in the service's project.
  Nothing else needs doing after the apply.

  Scenario: an applied descriptor becomes a service that is ready
    Given a project "shop" with no service "cart"
    When a member applies a descriptor for the service "cart" in "shop"
    Then "cart" is ready with 1 instance
    And the status of "cart" is "Ready"

  Scenario: a descriptor applied with a new image replaces the instances with ones running it
    Given a deployed service "cart" that is ready with the image "cart:1" at generation 3
    When a member applies the descriptor of "cart" with the image "cart:2"
    Then the status of "cart" is "UpdateInProgress" at generation 4
    And "cart" becomes ready with the image "cart:2"

  Scenario Outline: a value taken from a project secret is shown nowhere the platform shows a service
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    And a deployed service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "checkout"
    When a member reads <what>
    Then "sk_live_1" is shown nowhere in it

    Examples:
      | what                                    |
      | the status of "cart"                    |
      | the services of the project "shop"      |
      | the logs of "cart"                      |
      | what the control plane recorded of "cart" |

  Scenario: services of the same name in two projects run apart
    Given a project "shop" and a project "warehouse"
    When a member applies a descriptor for the service "cart" in each of them
    Then each project has a service "cart" of its own that is ready
    And a request to "cart" of "shop" is answered by an instance of "cart" of "shop"
