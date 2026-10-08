Feature: Project secrets kept in Secret Manager
  On the Secret Manager backend a member sets and removes a project secret's entries as before;
  each value goes to Secret Manager, where the control plane can add a version and never read
  one, and the control plane records names only. The cloud provider keeps the project's secret in
  the cluster in step with Secret Manager, so a descriptor's variable is taken from an entry
  exactly as before and a starting instance is given it by the cluster; that read is the
  cluster's, and Secret Manager does not record it.

  Background:
    Given an installation on the Secret Manager backend
    And a project "shop"
    And a member of the organization "shop" is in

  Scenario: a member sets an entry and the value goes to Secret Manager, where the control plane cannot read it
    When the member sets the entry "STRIPE_KEY" of the project secret "checkout" to "sk_live_1"
    Then Secret Manager holds the entry "STRIPE_KEY" of the project secret "checkout" of "shop"
    And the control plane records the project secret "checkout" and the entry "STRIPE_KEY" and holds "sk_live_1" nowhere
    And Google Cloud refuses the control plane a read of the entry

  Scenario: a variable taken from an entry reaches a service through the project's secret in the cluster
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1" and synced
    And a descriptor for a service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "checkout"
    When the member applies the descriptor
    Then "cart" starts with the variable "STRIPE_KEY" set to "sk_live_1"
    And the variable was given by the cluster from the project's secret, as before

  Scenario: an entry set again is what an instance started afterwards is given, and a running instance keeps what it had
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    And a deployed service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "checkout"
    When the member sets the entry "STRIPE_KEY" of the project secret "checkout" to "sk_live_2"
    Then an instance of "cart" started afterwards has the variable "STRIPE_KEY" set to "sk_live_2"
    And an instance of "cart" already running has the variable "STRIPE_KEY" set to "sk_live_1"

  Scenario: a service whose variable is taken from a removed entry does not become ready
    Given the entry "STRIPE_KEY" of the project secret "checkout" was set to "sk_live_1" and has since been removed
    And a descriptor for a service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "checkout"
    When the member applies the descriptor
    Then "cart" does not become ready
    And what the platform shows of "cart" says why

  Scenario: the cloud provider keeps the project's secret in the cluster in step with Secret Manager
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    When the cloud provider has synced the entry
    Then the project's secret in the cluster holds "sk_live_1" as "STRIPE_KEY" within 1 minute
    And the access log holds the read of the version the cloud provider made
    And Secret Manager records no read by an instance that starts afterwards

  Scenario: a service of the project is not started until the cloud provider has synced an entry it takes a variable from
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1" and not yet synced
    And a descriptor for a service "cart" whose variable "STRIPE_KEY" is taken from the entry "STRIPE_KEY" of "checkout"
    When the member applies the descriptor
    Then no instance of "cart" starts until the cloud provider reports the entry synced
    And the status of "cart" says that it waits on the entry "STRIPE_KEY" of "checkout" being synced

  Scenario: a list of project secrets shows names and entries and never a value on the Secret Manager backend
    Given the entry "STRIPE_KEY" of the project secret "checkout" is set to "sk_live_1"
    When the member lists the project secrets of "shop"
    Then the list shows the project secret "checkout" with the entry "STRIPE_KEY"
    And the list shows no value
