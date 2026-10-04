Feature: The platform's own settings
  A platform setting is a variable the platform's own program reads. Some the platform alone sets,
  and a descriptor that gives one is refused; the others a descriptor may give, and they reach the
  platform's program and never the developer's. Which variables are platform settings is said
  once, so a process and a module are kept from exactly the same ones.

  Scenario Outline: a platform setting a descriptor gives is kept from the process
    Given a descriptor for a service "payments" written in "Python" that gives the variable "<variable>"
    When a member applies the descriptor
    Then the process of "payments" is not given the variable "<variable>"

    Examples:
      | variable          |
      | ANTHROPIC_API_KEY |
      | ANKKA_MODEL_NAME  |
      | ANKKA_DB_HOST     |

  Scenario Outline: a module that asks for a platform setting is told that it is not set
    Given a service "payments" written in "Rust" whose environment has the variable "<variable>"
    When "payments" asks for the variable "<variable>"
    Then "payments" is told that the variable is not set

    Examples:
      | variable           |
      | ANTHROPIC_API_KEY  |
      | ANKKA_MODEL_NAME   |
      | ANKKA_DB_HOST      |
      | ANKKA_CLUSTER_MODE |
      | ANKKA_HTTP_PORT    |

  Scenario: every platform setting kept from a process is kept from a module
    Given the platform settings a process is not given
    When a module asks for each of them
    Then the module is told that each is not set

  Scenario: a variable newly made a platform setting is kept from a process and from a module alike
    Given a variable that has newly been made a platform setting
    When a descriptor gives it to a service with a process and to a module
    Then the process is not given the variable
    And the module is told that the variable is not set

  Scenario Outline: a descriptor may not give a variable the platform alone sets, however it gives it
    Given a descriptor for a service "payments" that gives the variable "ANKKA_HTTP_PORT" <how>
    When a member applies the descriptor
    Then the member is refused
    And the refusal names the variable "ANKKA_HTTP_PORT"

    Examples:
      | how                                     |
      | as a value written in the descriptor    |
      | taken from an entry of a project secret |
