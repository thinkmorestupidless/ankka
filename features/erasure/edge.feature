Feature: A personal field at a service's edge is its value with its data subject beside it
  Inside the platform a personal field is held only as a personal envelope. At the edge of a
  service, where an endpoint answers a caller or reads what a caller sent, the field is its value
  with its data subject beside it, or the data subject alone once erased: an endpoint exposes what
  its handler returns, behind its ACL, as it does every other field.

  Background:
    Given a service "players" whose event "PlayerRegistered" has the field "email" marked as a personal field of the data subject "player/8c1f"
    And an endpoint of "players" that answers with the "PlayerRegistered" of an entity

  Scenario: an endpoint answers a personal field as its value with its data subject beside it
    Given an entity of "players" recorded a "PlayerRegistered" with the email "ada@example.com"
    When a caller the endpoint allows asks for the entity
    Then the answer holds the field "email" as the value "ada@example.com" with the data subject "player/8c1f" beside it
    And the answer holds no personal envelope

  Scenario: an endpoint answers a personal field of an erased data subject as the data subject alone
    Given an entity of "players" recorded a "PlayerRegistered" with the email "ada@example.com"
    And an erasure of "player/8c1f" in the project of "players" is applied
    When a caller the endpoint allows asks for the entity
    Then the answer holds the field "email" as the data subject "player/8c1f" alone

  Scenario: an endpoint reads a personal field a caller sent as a value with its data subject
    When a caller the endpoint allows sends a "PlayerRegistered" whose field "email" is the value "ada@example.com" with the data subject "player/8c1f" beside it
    Then the entity of "players" records the "PlayerRegistered" with the email "ada@example.com" as a personal field of "player/8c1f"
