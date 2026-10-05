Feature: Testing a service written in another language
  A developer tests their components with the test runner their language already has. The component
  test kit runs one component with no platform, no database and no network, and still carries every
  value through the component's own encoding. The test kit starts a database and the platform's own
  program, serves the developer's code to it, and can restart the platform's own program on the same
  database so that a test proves what was kept rather than what was remembered.

  Scenario Outline: the component test kit shows a command's effect as values
    Given an entity "cart" written in "<language>" and the component test kit
    When a test sends the command "add-item" with the item "socks" and then the command "get-cart"
    Then the test is shown the events recorded, the new state and the reply, as values
    And the command "get-cart" sees the state the command "add-item" left

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: a value the encoding cannot write fails in the component test kit
    Given an entity "cart" written in "<language>" whose state can hold a value its declared types cannot encode
    When a test drives "cart" with the component test kit until the state holds that value
    Then the test fails, naming the encoding

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the component test kit follows a workflow to where it waits for a command
    Given a workflow "checkout" written in "<language>" that waits for the command "approve" after its step "reserve"
    When a test runs "checkout" to its end with the component test kit
    Then the test is shown each step the workflow moved to, ending at the wait after "reserve"
    And the test can send "approve" and see the workflow go on to its end

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the test kit is ready before the test begins
    Given a service "shop" written in "<language>"
    When a test starts the test kit for "shop"
    Then a database with the platform's own schema is running
    And the platform's own program is running with the code of "shop"
    And the platform's own program is ready before the test begins

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |

  Scenario Outline: the test kit restarts the platform's own program on the same database
    Given a test kit running "shop" written in "<language>", whose cart "c1" has recorded 3 items
    When the test restarts the platform's own program
    Then the platform's own program runs again on the same database
    And the cart "c1" is recovered with its 3 items

    Examples:
      | language   |
      | Python     |
      | TypeScript |
      | Rust       |
