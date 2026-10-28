Feature: The design model and the build model
  Each of the design and the build runs on a model its own definition names in one line, so which
  model decides and which model codes is a fact of the repository and not a habit of the developer.

  Scenario: the whole design runs on the design model, clarifications included
    Given a repository with design and build installed as shipped
    When the developer starts the design with a description
    Then every stage of the design runs on the design model
    And every clarification is asked and answered on the design model

  Scenario: the whole build runs on the build model
    Given a repository with design and build installed as shipped
    When the developer starts the build
    Then every stage of the build runs on the build model

  Scenario: as shipped, the design model thinks deeper than the build model
    Given a repository with design and build installed as shipped
    When a developer reads the two model lines
    Then the design model is the deeper-thinking model and the build model the cheaper

  Scenario: a model line edited in one definition changes that one's model and not the other's
    Given a developer has edited the model line of the build
    When the developer starts the build
    Then the build runs on the edited model
    And the design's model is unchanged

  Scenario: the model lines are the only record of the models, and the reason is beside them
    Given a repository with design and build installed
    When a developer looks for which models the repository chose
    Then each model line is in its own definition and nothing else in design and build names a model
    And the description of design and build says why the two differ and where the lines are

  Scenario: a coding agent started on another model runs the design on the design model and returns after
    Given a coding agent started on a model other than the design model
    When the developer starts the design with a description
    Then the design runs on the design model
    And the coding agent is back on its own model when the design ends
