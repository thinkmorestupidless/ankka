Feature: The documentation of blueprints
  A developer learns from one guide how to write a blueprint, what each pattern does, how runs and
  schedules behave, and what blueprints do not do.

  Scenario: the blueprints guide documents every pattern with samples from tested code
    Given the published documentation
    When a reader reads about blueprints
    Then the documentation describes the ask, work, for-each, gather, judge and critique steps
    And every blueprint shown there is taken from code a test runs
    And the documentation says what blueprints do not do
