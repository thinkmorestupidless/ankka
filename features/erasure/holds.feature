Feature: An erasure waits for a legal hold
  An erasure request may carry a not-before date and a reason, and is then held: nothing is
  destroyed until the date passes, and then the platform applies it without anyone acting. The
  domain chooses the date; the platform keeps it. A held erasure request can be withdrawn or
  replaced by whoever asked for it or by a member. Only an owner may override a hold, with a reason that is
  recorded; an applied erasure request cannot be withdrawn.

  Background:
    Given a project "brand" with the service "players"
    And "players" has recorded personal fields of the data subject "player/8c1f"

  Scenario: an erasure request with a not-before date is held, and nothing is destroyed before the date
    Given an erasure request for "player/8c1f" in "brand" with the not-before date "2031-10-08" and the reason "aml-retention"
    When the not-before date has not passed
    Then the keyring still holds the subject key of "player/8c1f"
    And the erasure request is held, with the not-before date "2031-10-08" and the reason "aml-retention"

  Scenario: a held erasure request is applied when its not-before date passes, without anyone acting
    Given a held erasure request for "player/8c1f" in "brand" with the not-before date "2031-10-08"
    When the not-before date passes
    Then within 15 minutes and 60 seconds the erasure request is applied without anyone acting
    And every service of "brand" reads every personal field of "player/8c1f" as erased

  Scenario Outline: a held erasure request is withdrawn before its not-before date
    Given a held erasure request for "player/8c1f" in "brand" with the not-before date "2031-10-08"
    When <who> withdraws it before the not-before date
    Then the erasure request is withdrawn
    And it records who withdrew it
    And the keyring still holds the subject key of "player/8c1f"

    Examples:
      | who                  |
      | whoever asked for it |
      | a member             |

  Scenario Outline: a held erasure request is replaced by a later one from whoever asked for it
    Given a held erasure request for "player/8c1f" in "brand" with the not-before date "2031-10-08"
    When whoever asked for it asks for an erasure request for "player/8c1f" in "brand" with the not-before date "<date>"
    Then the new erasure request replaces the held one
    And the history of the erasure request keeps both

    Examples:
      | date       |
      | 2032-10-08 |
      | 2030-10-08 |

  Scenario: an owner overrides a hold with a reason and the erasure request is applied at once
    Given a held erasure request for "player/8c1f" in "brand" with the not-before date "2031-10-08"
    When an owner overrides the hold with the reason "regulator order 2028/41"
    Then the erasure request is applied at once
    And it records the override, the owner and the reason "regulator order 2028/41"

  Scenario: a member who is not an owner cannot override a hold
    Given a held erasure request for "player/8c1f" in "brand" with the not-before date "2031-10-08"
    When a member who is not an owner overrides the hold with a reason
    Then the member is refused
    And the erasure request is still held

  Scenario Outline: an applied erasure request cannot be withdrawn
    Given "player/8c1f" has been erased in "brand"
    When <who> withdraws the erasure request for "player/8c1f" in "brand"
    Then <who> is refused
    And the erasure request is still applied

    Examples:
      | who                  |
      | whoever asked for it |
      | a member             |
      | an owner             |
