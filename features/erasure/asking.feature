Feature: A service asks for an erasure, and the domain fans it out
  A member asks for an erasure by hand, and a service that its project grants the right to
  asks for one as code, and is named as having asked. A person known to two projects is two data
  subjects: the platform fans no erasure request out beyond its project. The domain asks for one in
  each project, in another project under that project's grant, and may give both one correlation
  id so that an auditor reading either project finds the other.

  Background:
    Given a project "brand" with the service "players"
    And a project "payments"

  Scenario: a service asks for an erasure request in its own project and is named as who asked for it
    Given "brand" grants "players" the right to ask for an erasure
    When "players" asks for an erasure request for "player/8c1f" in "brand"
    Then the erasure request is accepted
    And it names the service "players" as who asked for it

  Scenario: a service granted the right by another project asks for an erasure request there
    Given "payments" grants "players" of "brand" the right to ask for an erasure
    When "players" asks for an erasure request for "cardholder/77a0" in "payments"
    Then the erasure request is accepted
    And it names the service "players" of "brand" as who asked for it

  Scenario: a service without the right is refused and the refusal is recorded
    Given "payments" grants "players" of "brand" nothing
    When "players" asks for an erasure request for "cardholder/77a0" in "payments"
    Then "players" is refused
    And the refusal is recorded in the history of "payments"

  Scenario Outline: a member lists the erasure requests of two projects by their correlation id in either project
    Given "players" has asked for an erasure request for "player/8c1f" in "brand" with the correlation id "closure-4411"
    And "players" has asked for an erasure request for "cardholder/77a0" in "payments" with the correlation id "closure-4411"
    When a member lists the erasure requests of "<project>" with the correlation id "closure-4411"
    Then both erasure requests are listed, each with where it stands

    Examples:
      | project  |
      | brand    |
      | payments |
