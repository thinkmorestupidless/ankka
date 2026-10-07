Feature: A service's history
  The control plane keeps a history of what members did to a service: what was done, at which
  generation, by whom and when. For a generation that was applied the history also shows the image
  and a digest of the descriptor, so that a member can tell two generations apart, and a member can
  read the descriptor of a generation before rolling back to it.

  Background:
    Given a project "shop"
    And a member of the organization "shop" is in
    And a service "cart" applied at generation 1 with the image "cart:1"

  Scenario: the history shows the image and a digest at every generation that was applied
    Given "cart" applied at generation 2 with the image "cart:2"
    And "cart" applied at generation 3 with the image "cart:3"
    When the member reads the history of "cart"
    Then the history shows the image "cart:1" at generation 1, "cart:2" at generation 2 and "cart:3" at generation 3
    And the history shows a digest at each of those generations

  Scenario: two generations with the same image and a different environment have different digests
    Given "cart" applied at generation 2 with the image "cart:1" and the variable "MODE" set to "test"
    And "cart" applied at generation 3 with the image "cart:1" and the variable "MODE" set to "live"
    When the member reads the history of "cart"
    Then the digest at generation 2 and the digest at generation 3 differ

  Scenario: two generations applied with the same descriptor have the same digest
    Given "cart" applied at generation 2 with the same descriptor as generation 1
    When the member reads the history of "cart"
    Then the digest at generation 1 and the digest at generation 2 are the same

  Scenario: a roll back shows the image and the digest of the generation it was rolled back to
    Given "cart" applied at generation 2 with the image "cart:2"
    And the member has rolled "cart" back
    When the member reads the history of "cart"
    Then the history shows the image "cart:1" at generation 3
    And the digest at generation 1 and the digest at generation 3 are the same

  Scenario: a member reads the descriptor that was applied at a generation
    Given "cart" applied at generation 2 with the image "cart:2"
    When the member reads the descriptor of "cart" at generation 1
    Then the member is shown the descriptor that was applied at generation 1
    And the platform accepts that descriptor as it is shown

  Scenario: history an older platform kept is still read, and shows no image and no digest
    Given the history of "cart" was kept by a platform too old to record an image or a digest
    When the member reads the history of "cart" on the upgraded platform
    Then the history shows generation 1 with no image and no digest
    And the status of "cart" is what it was before the platform was upgraded

  Scenario Outline: what applied no descriptor shows no image and no digest in the history
    Given a member <did> "cart"
    When the member reads the history of "cart"
    Then the history shows that "cart" was <done> with no image and no digest

    Examples:
      | did       | done      |
      | paused    | paused    |
      | resumed   | resumed   |
      | restarted | restarted |
      | exposed   | exposed   |

  Scenario: the console shows the image of each generation that was applied
    Given "cart" applied at generation 2 with the image "cart:2"
    When the member reads the history of "cart" in the console
    Then the console shows the image "cart:1" at generation 1 and "cart:2" at generation 2

  Scenario: the descriptor of a generation that is no longer kept cannot be read
    Given a service "orders" applied 60 times
    When the member reads the descriptor of "orders" at generation 3
    Then the member is told that the descriptor of generation 3 is no longer kept

  Scenario: the descriptor of a generation the service never had cannot be read
    When the member reads the descriptor of "cart" at generation 9
    Then the member is told that "cart" has no generation 9

  Scenario: the descriptor of a generation that recorded none cannot be read
    Given a member restarted "cart" at generation 2
    When the member reads the descriptor of "cart" at generation 2
    Then the member is told that generation 2 was a restart and ran the descriptor of generation 1

  Scenario Outline: a person who is not a member cannot read the history of a service
    Given a person who is not a member of the organization "shop" is in
    When that person <asks>
    Then that person is told that there is no project "shop"

    Examples:
      | asks                                           |
      | reads the history of "cart"                    |
      | reads the descriptor of "cart" at generation 1 |
