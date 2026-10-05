Feature: An organization bounds what its members reach
  Everything in an organization — its projects, their services — is reached only by its members. To
  a person who is not a member, an organization and everything in it reads exactly as if it had
  never existed.

  Background:
    Given an organization "acme" with the project "shop", in which the service "cart" is deployed
    And "bob" is not a member of "acme"

  Scenario Outline: a person who is not a member is shown nothing of an organization in a list
    When "bob" lists <things>
    Then "bob" is shown nothing of "acme"

    Examples:
      | things            |
      | the organizations |
      | the projects      |
      | the services      |

  Scenario Outline: a person who is not a member is answered as if what they asked for had never existed
    When "bob" asks for <what>
    Then "bob" is given the same answer as for <never>

    Examples:
      | what                                      | never                                         |
      | the organization "acme"                   | an organization that never existed            |
      | the project "shop"                        | a project that never existed                  |
      | the service "cart" of the project "shop"  | a service of a project that never existed     |

  Scenario Outline: a person who is not a member changes nothing in an organization
    When "bob" <changes>
    Then "bob" is told that there is no <what>
    And <outcome>

    Examples:
      | changes                                                        | what                    | outcome                                 |
      | creates the project "store" in "acme"                          | organization "acme"     | "acme" has no project "store"           |
      | applies a descriptor for the service "orders" in "shop"        | project "shop"          | "shop" has no service "orders"          |

  Scenario: a member of two organizations is shown the projects of both and of no other
    Given "ada" is a member of "acme" and of the organization "globex", which has the project "lab"
    And an organization "initech" with the project "office", of which "ada" is not a member
    When "ada" lists the projects
    Then "ada" is shown the projects "shop" and "lab"
    And "ada" is shown nothing of "initech"

  Scenario: a member removed from an organization is refused on their very next request
    Given "carol" is a member of "acme"
    And an owner of "acme" removes "carol"
    When "carol" pauses the service "cart"
    Then "carol" is told that there is no project "shop"
    And the service "cart" is not paused
