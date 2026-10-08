Feature: The weekly research digest
  The research digest holds two blueprints. The watch runs daily and keeps one entry per paper it finds
  in its sources; the digest runs weekly and writes a script from the entries found in its period,
  every statement naming a paper. They compose through the service's own records: neither starts
  the other.

  Background:
    Given the research digest with scripted sources, a scripted model and a scripted judgment provider

  Scenario: the watch keeps one entry per paper however many sources find it
    Given three sources that return forty papers, ten of them found by two sources
    When a run of the watch is completed
    Then thirty entries are kept, one per paper by its identifier
    And each entry names every source that found it

  Scenario: the digest reads the papers found in its period and no others
    Given entries found before, during and after the period of a run of the digest
    When the run of the digest is completed
    Then the digest read only the entries found during its period

  Scenario: every statement in the script names a paper the digest read
    Given a completed run of the digest that read thirty entries
    When a reader reads the script
    Then every statement in the script names a paper
    And every paper named is one of the thirty

  Scenario: a paper found after its digest's period is in the next digest
    Given a paper published on "2026-10-14" and first found on "2026-10-20"
    When the digests for the weeks ending "2026-10-18" and "2026-10-25" are completed
    Then the paper is read by the digest for the week ending "2026-10-25" and by no other

  Scenario: a week with no papers has a digest that says so
    Given no entries found during the period of a run of the digest
    When the run of the digest is completed
    Then the script says that no papers were found in the period

  Scenario: a source that cannot be reached is named in the watch's run and the others are kept
    Given three sources, one of which cannot be reached
    When a run of the watch is completed
    Then the entries from the two other sources are kept
    And the run names the source that could not be reached
