Feature: The build
  A developer whose change is designed starts the build, and it takes the work list to a reviewed
  branch and a pull request ready for review, on the build model, reading nothing of the design
  but what the design wrote, and asking the developer only what the design left open.

  Scenario: a build started on a designed change ends with a reviewed branch and a pull request
    Given a change's directory that holds a work list with nothing done
    When the developer starts the build
    Then every work item is done and committed in order
    And the branch is reviewed
    And a pull request exists on its base

  Scenario: a build reads only what the design wrote
    Given a change's directory that holds a work list
    When the developer starts the build
    Then the build reads the change's directory and nothing the design read or said

  Scenario: a finished work item is committed and the next starts without a report
    Given the build is running
    When a work item is finished
    Then its commit lands
    And the next work item starts without the build stopping to report

  Scenario: a decision the spec and the plan left open is put to the developer and the build goes on from that item
    Given the build is running
    When a work item needs a decision the spec and the plan left open
    Then the build stops and puts the decision to the developer with options and their implications
    And the build goes on from that work item once answered

  Scenario: a work item whose check cannot be made to pass stops the build at that item
    Given the build is running
    When a work item's check fails and the build cannot make it pass
    Then the build stops at that work item with the failure
    And no pull request is raised

  Scenario: the review runs after the last work item and the pull request waits for it
    Given the build is running
    When the last work item is finished
    Then the review runs
    And the pull request is raised only after every finding is fixed, declined with a reason, or put to the developer

  Scenario: a blocking finding the build disagrees with stops the build before the pull request
    Given the build is running
    When the review reports a blocking finding the build disagrees with
    Then the build stops and shows the developer the finding and its reason
    And no pull request is raised

  Scenario: a change cut from an unmerged branch targets that branch and says which pull request it waits on
    Given the change's branch was cut from a branch whose pull request is not merged
    When the build raises the pull request
    Then the pull request's base is that branch
    And its first line says which pull request it waits on

  Scenario: a pull request is raised not yet marked ready
    Given the build is running
    When the build raises the pull request
    Then the pull request is not marked ready

  Scenario: a pull request whose checks are all green is marked ready and the developer told
    Given the build has raised the pull request
    When every check on it is green
    Then the build marks the pull request ready
    And the developer is told a review is waiting

  Scenario: a red check the branch causes is fixed before the pull request is marked ready
    Given the build has raised the pull request
    When a check on it is red and the default branch passes that check
    Then the build fixes the check on the branch
    And the pull request is not marked ready until the check is green

  Scenario: a red check the default branch also fails is shown as such and does not hold the pull request
    Given the build has raised the pull request
    When a check on it is red and a run of the default branch fails the same way
    Then the pull request says so, with the default branch's run as evidence
    And the check does not hold the pull request from being marked ready

  Scenario: a check the build cannot make green leaves the pull request not ready and the developer told
    Given the build has raised the pull request
    When a check stays red and the build cannot make it green
    Then the build stops and says which check and what it tried
    And the pull request is not marked ready

  Scenario: a build started on a partly done work list resumes at the first undone item
    Given a change's directory whose work list is partly done
    When the developer starts the build
    Then the build starts at the first undone work item
    And the done work items are left alone

  Scenario: a build started on a change whose work is done and reviewed resumes at the pull request
    Given a change's directory whose work list is done and whose review is recorded
    When the developer starts the build
    Then the build raises the pull request, or watches the checks of the one that exists

  Scenario: a build started without a work list says the design is not finished
    Given a change's directory with no work list
    When the developer starts the build
    Then the build stops and says the design is not finished
    And the build says how the design is started

  Scenario: a build started on a work list whose analysis did not finish says the design is not finished
    Given a change's directory with a work list and no record that the design finished
    When the developer starts the build
    Then the build stops and says the design is not finished
    And the build says how the "analyze" stage is resumed

  Scenario: a build started again after the review stopped on a finding settles it before the pull request
    Given a change's directory whose work list is done and whose review stopped on a finding
    When the developer starts the build
    Then the finding is settled from the review as it stands
    And no pull request is raised until every finding is settled

  Scenario: the build stops before any stage when review is not installed
    Given a repository with design and build installed and without "review"
    When the developer starts the build
    Then the build says "review" is required
    And no stage runs

  Scenario: the build neither merges, tags, publishes nor deploys
    Given the build is running
    When the pull request is marked ready
    Then the build ends without merging, tagging, publishing or deploying
