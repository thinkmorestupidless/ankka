Feature: Deploying a module
  A member deploys a service written in Rust from an image whose only job is to hand over its module.
  The service runs as one program, the platform's own, with the module handed over once when each
  instance starts. What goes wrong handing it over or loading it is said in the service's status.

  Scenario: a module is deployed as one program, the platform's own
    Given an image "shop" whose only job is to hand over the module of the service "shop"
    When a member applies a descriptor for the service "shop" with the image "shop" as a module
    Then each instance of "shop" runs one program, the platform's own, with the module loaded
    And "shop" is ready

  Scenario Outline: a descriptor for a module is refused for what does not apply to it
    Given a descriptor for the service "shop" as a module that <declares>
    When a member applies the descriptor
    Then the member is refused
    And the refusal names <named>

    Examples:
      | declares                     | named                |
      | says the service serves no HTTP | what it said      |
      | declares no protocol version | the protocol version |

  Scenario Outline: an image that does not hand over its module is reported in the status
    Given an image "broken" that <fails>
    When a member applies a descriptor for the service "shop" with the image "broken" as a module
    Then the status of "shop" says that its module was not handed over, and why

    Examples:
      | fails                          |
      | hands over no module           |
      | stops with an error handing it over |

  Scenario: a module that fails as it declares its components is reported failed until a fixed image is applied
    Given a service "shop" whose module fails as it declares its components
    When a member applies its descriptor
    Then the status of "shop" is "Failed", with the reason the platform's own program gave
    And "shop" is ready once a member applies its descriptor with a fixed image
