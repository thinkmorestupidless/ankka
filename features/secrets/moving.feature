Feature: Moving an installation to Secret Manager
  An installation on the Postgres backend moves to Secret Manager service by service, with no
  person seeing a value. A service started on the Secret Manager backend in the move phase "copy"
  copies each of its service secrets from its database into Secret Manager, decrypting with its
  own secret key, before it is ready; the cloud provider's sync copies each project secret's
  entries up. In the move phase "check" a service's copy check compares what the database and
  Secret Manager hold by digest, and only when every name is equal does the removal step, in the
  move phase "remove", remove the rows from the database.

  Background:
    Given an installation that was on the Postgres backend
    And a deployed service "payments" whose database holds the service secrets "acme" and "stripe", encrypted with its secret key

  Scenario: a service started on the Secret Manager backend in the move phase "copy" copies its service secrets before it is ready
    Given the installation is set to the Secret Manager backend in the move phase "copy"
    And Secret Manager already holds the service secret "stripe" of "payments"
    When "payments" starts
    Then Secret Manager holds the service secret "acme" of "payments" before "payments" is ready
    And the service secret "stripe" in Secret Manager is the one it already held

  Scenario: the copy check reports for each name whether the database and Secret Manager hold the same value
    Given "payments" has copied its service secrets into Secret Manager
    When "payments" starts in the move phase "check"
    Then the status of "payments" reports "acme" and "stripe" each as equal, by digest
    And the status holds no value

  Scenario: the removal step refuses while a copy check reports a difference
    Given the database and Secret Manager hold different values for the service secret "stripe" of "payments"
    When "payments" starts in the move phase "remove"
    Then the database of "payments" still holds the service secrets "acme" and "stripe"
    And the status of "payments" names the service secret "stripe" as different

  Scenario: the removal step removes the rows once every name is equal
    Given "payments" has copied its service secrets into Secret Manager
    When "payments" starts in the move phase "remove"
    Then the database of "payments" holds no service secret
    And the secret key of "payments" is still given to it and no longer read

  Scenario: a service that cannot reach Secret Manager during the move does not become ready and leaves its rows
    Given the installation is set to the Secret Manager backend in the move phase "copy"
    And Secret Manager cannot be reached at present
    When "payments" starts
    Then "payments" does not become ready
    And the status of "payments" says why
    And the database of "payments" still holds the service secrets "acme" and "stripe"

  Scenario: the cloud provider moves a project secret into Secret Manager with its values and its record unchanged
    Given a project "shop" whose entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1" in the cluster
    When the cloud provider first syncs the project secret "checkout" of "shop"
    Then Secret Manager holds the entry "STRIPE_KEY" of the project secret "checkout" of "shop" as "sk_live_1"
    And what the control plane recorded of "checkout" is unchanged

  Scenario: a moved service set back to the Postgres backend before the removal step reads its secrets from its database
    Given "payments" has copied its service secrets into Secret Manager, and the removal step has not run
    And a handler of "payments" has since kept "sk-new-1" as the service secret "newpay" in Secret Manager
    When the installation is set back to the Postgres backend and "payments" restarts
    Then a handler of "payments" that reads the service secret "acme" is told what the database held before
    And the status of "payments" names the service secret "newpay" as kept only in Secret Manager
