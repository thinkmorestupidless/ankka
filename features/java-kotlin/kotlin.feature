Feature: Kotlin on the Java SDK
  A Kotlin developer writes a service against the Java SDK, and sees it as Kotlin: a value the Java
  SDK may give as nothing is nullable, a value it takes may not be null, and a data class is a state
  or an event as a Java record is, under the same encoding.

  Scenario: a value the Java SDK may give as nothing is nullable in Kotlin
    Given a service written in "Kotlin"
    When the developer reads a value the Java SDK may give as nothing without saying what happens when it is null
    Then the service does not build
    And the developer is told that the value may be null

  Scenario: a null given where the Java SDK takes a value is refused before the service is built
    Given a service written in "Kotlin"
    When the developer gives null where the Java SDK takes a value
    Then the service does not build

  Scenario: a data class is a state or an event as a Java record is
    Given a service "shop" written in "Kotlin" whose entity "cart" declares its state and events as data classes
    And the cart "c1" has recorded 3 items
    When a service "shop" written in "Java" is started on the same database
    And a caller sends the command "get-cart" to the cart "c1"
    Then the caller is answered with the same 3 items

  Scenario Outline: a data class's default is taken for a field that is absent, and a field with no default that is absent fails recovery
    Given a service "shop" written in "Kotlin" whose state has the field "<field>" <default>
    And the cart "c1" recorded its state before the field "<field>" existed
    When the cart "c1" is recovered
    Then <outcome>

    Examples:
      | field    | default            | outcome                                                  |
      | discount | with a default of 0 | the cart "c1" is recovered with the field "discount" at 0 |
      | owner    | with no default    | the recovery fails, naming the field "owner"             |
