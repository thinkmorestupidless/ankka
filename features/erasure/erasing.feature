Feature: An erasure makes a data subject's personal fields unreadable everywhere
  A member asks for an erasure of one data subject in one project. Applying it destroys the
  data subject's subject key, and from then on every service of the project reads every personal
  field of that data subject as erased, wherever it is held: an entity being recovered, a view's
  rows, a consumer's deliveries. Nothing is rewritten and nothing else changes: every field that
  is not personal reads as it was written. Each service's completion is recorded, and the member
  fetches an erasure certificate.

  Background:
    Given a project "brand" with the services "players", "wallet" and "engagement"
    And each of them has recorded personal fields of the data subject "player/8c1f" and of the data subject "player/9d2e"
    And each of them has recorded fields of "player/8c1f" that are not personal

  Scenario: an erasure request with no not-before date destroys the subject key and every service reads the data subject as erased
    When a member asks for an erasure request for "player/8c1f" in "brand" with no not-before date
    Then the keyring holds no subject key for "player/8c1f"
    And within 60 seconds every service of "brand" reads every personal field of "player/8c1f" as erased
    And every service of "brand" reads every personal field of "player/9d2e" as it was written

  Scenario: an entity of an erased data subject is recovered with its personal fields erased
    Given "player/8c1f" has been erased in "brand"
    When an entity of "wallet" holding events of "player/8c1f" is recovered
    Then the entity is recovered
    And its state holds erased in each personal field of "player/8c1f"
    And every field of its state that is not personal is as it was written

  Scenario: a view holding rows of an erased data subject is rebuilt with its personal fields erased
    Given "player/8c1f" has been erased in "brand"
    And a view of "players" holding rows with personal fields of "player/8c1f"
    When the view is declared at a higher version and rebuilt
    Then the rebuild finishes
    And its rows hold erased in each personal field of "player/8c1f"

  Scenario: a new personal field cannot be written for an erased data subject
    Given "player/8c1f" has been erased in "brand"
    When a command of "players" records an event with a personal field of "player/8c1f"
    Then the command is refused
    And the refusal names "player/8c1f" as erased
    And nothing is recorded

  Scenario: a member reads an applied erasure request with each service's completion
    Given "player/8c1f" has been erased in "brand"
    When a member reads the erasure request for "player/8c1f" in "brand"
    Then the erasure request is applied
    And it says when the subject key was destroyed
    And it names each service of "brand" with the time it completed
    And it says when the erasure became final

  Scenario: no table of any service of the project can give back a personal field of an erased data subject
    Given "player/8c1f" has been erased in "brand"
    When a member reads every table of every service of "brand"
    Then no table holds a value from which a personal field of "player/8c1f" can be recovered without the destroyed subject key

  Scenario: a member fetches the erasure certificate of an applied erasure request
    Given "player/8c1f" has been erased in "brand"
    When a member fetches the erasure certificate for "player/8c1f" in "brand"
    Then the erasure certificate names the erasure request, the data subject "player/8c1f" and who asked for it
    And it names each service of "brand" with the time it completed
    And it says when the erasure became final
    And it holds no personal field

  Scenario: a service's erasure handler runs on every application of an erasure
    Given "players" has an erasure handler
    When an erasure request for "player/8c1f" in "brand" is applied
    And it is applied again later
    Then the erasure handler of "players" ran for "player/8c1f" each time
    And the second run, with nothing of its own to do, completed at once
