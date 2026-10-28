Feature: The design
  A developer describes a change and the design takes it from that description to a spec, its
  scenarios in the living features, a plan and a work list, asking the developer only what the
  developer must decide, and never leaving the design model while it asks.

  Scenario: a design started with a description ends with a spec, a plan and a work list
    Given a repository with design and build installed
    And a developer on the change's branch
    When the developer starts the design with a description
    Then the change's directory holds a spec, a plan and a work list
    And the living features hold the scenarios the spec names
    And the design ends by saying it is ready and printing how the build is started

  Scenario: a design started without a description asks for one and does nothing else
    Given a repository with design and build installed
    And a developer on the change's branch
    When the developer starts the design with no description
    Then the design asks for a description
    And nothing is written until one is given

  Scenario: a clarification the spec raises is settled by the developer before the clarify stage runs
    Given the design is running
    When the spec it wrote raises a clarification
    Then the developer is asked to choose between options whose implications are stated
    And the spec carries the answer before the "clarify" stage runs

  Scenario: the clarify stage's clarifications are put to the developer one at a time
    Given the design is running
    When the "clarify" stage has clarifications
    Then each is put to the developer one at a time
    And each answer is written into the spec before the next is put

  Scenario: the checker reports nothing before the plan stage runs
    Given the design is running
    When the checker reports a finding before the "plan" stage
    Then the finding is settled as a clarification is
    And the "plan" stage does not run until the checker reports nothing

  Scenario: a remediation that changes no decision and no scenario is applied without asking
    Given the design is running
    When the "analyze" stage proposes a remediation that changes neither a decision the developer made nor a scenario
    Then the remediation is applied
    And the "analyze" stage runs again without the developer being asked

  Scenario: a remediation that would change a decision or a scenario is put to the developer first
    Given the design is running
    When the "analyze" stage proposes a remediation that would change a decision the developer made or a scenario
    Then the developer is asked before anything changes

  Scenario: a remediation that changes a scenario goes through the living features and the checker
    Given the design is running
    When the developer accepts a remediation that changes a scenario
    Then the scenario is changed in its living feature
    And the checker reports nothing before the "analyze" stage runs again

  Scenario: a remediation the developer declined is not proposed again
    Given the design is running
    And the developer declined a remediation
    When the "analyze" stage runs again
    Then the declined remediation is neither applied nor put to the developer again

  Scenario: the design stops analysing when no finding above low remains
    Given the design is running
    When the "analyze" stage reports no finding above "low"
    Then the design says it is ready

  Scenario: the design stops analysing after three rounds and shows what remains
    Given the design is running
    When the "analyze" stage still reports a finding above "low" after "3" rounds
    Then the design stops and shows the developer the findings that remain

  Scenario: a design resumed from a stage leaves the earlier stages' work as it was
    Given a change's directory that holds a spec and a plan
    When the developer resumes the design from the "tasks" stage
    Then the "tasks" and "analyze" stages run
    And the spec and the plan are as they were

  Scenario: a design resumed from a stage whose inputs do not exist says which stage must run first
    Given a change's directory that holds a spec and no plan
    When the developer resumes the design from the "tasks" stage
    Then the design says the "plan" stage must run first
    And nothing is written

  Scenario: the design refuses to write a spec on the default branch
    Given a repository with design and build installed
    And a developer on the default branch
    When the developer starts the design with a description
    Then the design refuses and names the branch to make
    And nothing is written

  Scenario: the design stops before any stage when bdd is not installed
    Given a repository with design and build installed and without "bdd"
    When the developer starts the design with a description
    Then the design says "bdd" is required
    And no stage runs

  Scenario: the design stops before any stage when the developer cannot be asked anything
    Given a repository with design and build installed
    And a developer on the change's branch
    And a session in which the developer cannot be asked anything
    When the developer starts the design with a description
    Then the design says it needs a session in which the developer can be asked
    And no stage runs

  Scenario: a clarification is put to the developer without the design ending
    Given the design is running
    When the developer is asked anything
    Then the design is still running when the answer is given
    And the design is still on the design model
