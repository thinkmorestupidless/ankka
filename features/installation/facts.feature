Feature: What the installation reports about itself
  Any caller with a token reads what the installation is in one read: its version, its cloud,
  where its backups go, and the facts a product's requirements ask before a module is applied:
  its erasure, its broker and the longest retention a topic may be given. A fact the control
  plane cannot read is reported as not stated, never guessed.

  Scenario: what the installation reports includes its broker and its longest retention
    Given an installation whose broker has "3" nodes and gives a topic "3" copies with "2" in sync by default
    And whose longest retention is unbounded
    When a caller with a token reads the installation
    Then the installation reports its broker as "3" nodes, "3" copies and "2" in sync by default
    And its longest retention as unbounded, beside its backups

  Scenario: a fact the control plane cannot read is reported as not stated
    Given an installation whose number of broker nodes is not known to the control plane
    And whose erasure the platform does not yet have
    When a caller with a token reads the installation
    Then the installation reports its broker's nodes as not stated and its erasure as not stated
    And every other fact as it is
