Feature: The research digest sample
  The research digest sample shows blueprints at work: a scheduled watch and a scheduled digest that
  compose through the sample's own records. What the sample does is said by its own features and
  glossary, beside its code.

  Scenario: the research digest sample's features pass offline
    Given the research digest sample with a scripted model, a scripted judgment provider and scripted sources
    When the sample's features are run
    Then every one of them passes, with no model key and no network
