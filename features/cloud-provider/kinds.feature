Feature: Each need of the platform is one of six cloud requests
  Everything the platform asks of a cloud account is one of six cloud requests: an identity
  request, a secret access request, a secret sync request, a bucket request, a bucket credential
  request or a wrapping key request. Project secrets kept in the cloud account, backups kept in it
  and a keyring wrapped by it each ask in those words and no others, so a cloud provider is one
  program, and nothing a cloud request asks is in a cloud's own words.

  Background:
    Given an installation whose cloud provider is "gcp"

  Scenario: a service whose project secrets are kept in the cloud account asks for an identity and access to its secrets
    Given the installation keeps its project secrets in its cloud account
    And a descriptor for a service "reports" in the project "shop"
    When a member applies the descriptor
    Then the operator writes an identity request for "reports"
    And the operator writes a secret access request that grants the cloud identity of "reports" the secrets made for "reports" to own and the secrets of "shop" to read

  Scenario: a project whose project secrets are kept in the cloud account asks for them to be kept in step
    Given the installation keeps its project secrets in its cloud account
    And the project "shop" has the project secret "checkout" with the entries "STRIPE_KEY" and "WEBHOOK_KEY"
    When the project "shop" is deployed
    Then the operator writes a secret sync request for "shop" naming the project secret "checkout" and its entries
    And the cloud provider keeps what a starting instance of "shop" is given in step with the entries, within one minute of a change to any of them
    And the fulfilment says which change of the entries was last kept in step

  Scenario: a project whose backups are kept in the cloud account asks for a backup bucket and a credential for its database
    Given the installation keeps its backups in its cloud account
    And a project "shop" with a deployed service "cart"
    When the project "shop" is deployed
    Then the operator writes a bucket request for "shop" with the purpose "backup"
    And the operator writes a bucket credential request for that bucket whose cloud identity is the database of "shop" and whose secret is the one the database reads its backups' credential from
    And no service starts with a variable whose name starts with "ANKKA_S3_" naming the backup bucket
    And no route reaches the backup bucket
    And no descriptor can name the backup bucket

  Scenario: a keyring on an installation that names a wrapping key asks to wrap with it
    Given the installation names a wrapping key
    When the keyring is deployed
    Then the operator writes a wrapping key request for the cloud identity of the keyring
    And the fulfilment names the wrapping key the keyring wraps with

  Scenario: a keyring on an installation that names no wrapping key keeps its own secret
    Given the installation names no wrapping key
    When a member asks the control plane to wrap the keys of the keyring with a wrapping key
    Then the member is refused
    And the keyring wraps with the secret of its own

  Scenario Outline: no cloud request is in a cloud's own words
    Given a <request> the operator wrote
    When what it asks is read
    Then nothing it asks is in the words of any one cloud
    And the location it names is the one the installation named, in the installation's own words

    Examples:
      | request                   |
      | identity request          |
      | secret access request     |
      | secret sync request       |
      | bucket request            |
      | bucket credential request |
      | wrapping key request      |
