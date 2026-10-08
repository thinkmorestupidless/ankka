Feature: Another project, and a machine outside the installation, read an erased data subject as erased
  A personal field published to a topic is encrypted under the producing project's subject key,
  and so it stays in whatever another project keeps of it. A consumer in another project reads the
  value only under a grant that allows decryption, by asking the keyring for the subject key; an
  erasure in the producing project reaches it with no erasure request of its own, and a revoked
  grant ends its reads. No subject key leaves the installation: a machine outside it reads every
  personal field as erased unless its grant allows decryption, and then asks the keyring to
  decrypt each field for it.

  Background:
    Given a project "brand" with the service "players", which publishes "PlayerRegistered" to the topic "players"
    And the event "PlayerRegistered" has the field "name" marked as a personal field of the data subject "player/8c1f"
    And a project "payments" with a consumer "cardholders" that reads the topic "players" of "brand"

  Scenario: a consumer in another project with a grant that allows decryption reads the value
    Given "payments" has a grant on the topic "players" of "brand" that allows decryption
    When "cardholders" reads a "PlayerRegistered" of "player/8c1f" with the name "Ada Byron"
    Then the handler of "cardholders" is handed the name "Ada Byron"
    And the keyring gave "payments" the subject key of "player/8c1f" because it holds the grant

  Scenario: a consumer in another project with a grant that does not allow decryption reads the field as erased
    Given "payments" has a grant on the topic "players" of "brand" that does not allow decryption
    When "cardholders" reads a "PlayerRegistered" of "player/8c1f" with the name "Ada Byron"
    Then the handler of "cardholders" reads the field "name" as erased
    And the keyring records that it refused "payments" the subject key of "player/8c1f"

  Scenario: an erasure in the producing project reaches what another project kept of the data subject
    Given "payments" has a grant on the topic "players" of "brand" that allows decryption
    And "cardholders" has kept the name "Ada Byron" of "player/8c1f" in a row of a view of "payments"
    When "player/8c1f" is erased in "brand"
    Then the view of "payments" reads the field "name" of that row as erased
    And no erasure request was asked for in "payments"

  Scenario: a revoked grant ends another project's reads of the producing project's data subjects
    Given "payments" has a grant on the topic "players" of "brand" that allows decryption
    And "brand" has since revoked the grant
    When "cardholders" next needs the subject key of "player/8c1f"
    Then the keyring refuses it
    And "cardholders" reads the field "name" as erased
    And no personal field of a data subject of "brand" is read as its value in "payments" more than 5 minutes after the grant was revoked

  Scenario: a machine outside the installation with a grant that does not allow decryption reads every personal field as erased
    Given a machine outside the installation with a grant on the topic "players" of "brand" that does not allow decryption
    When the machine reads a "PlayerRegistered" of "player/8c1f"
    Then the machine reads the field "name" as erased

  Scenario: a machine outside the installation with a grant that allows decryption asks the keyring to decrypt each field
    Given a machine outside the installation with a grant on the topic "players" of "brand" that allows decryption
    When the machine reads a "PlayerRegistered" of "player/8c1f" and asks the keyring to decrypt the field "name"
    Then the machine is told the name "Ada Byron"
    And no subject key leaves the installation
    And the keyring records the decryption against the machine and the grant

  Scenario Outline: the keyring refuses to decrypt for a machine outside the installation once its grant is revoked or the data subject is erased
    Given a machine outside the installation with a grant on the topic "players" of "brand" that allows decryption
    And <since>
    When the machine asks the keyring to decrypt the field "name" of a "PlayerRegistered" of "player/8c1f"
    Then the machine is refused

    Examples:
      | since                                   |
      | "brand" has since revoked the grant     |
      | "player/8c1f" has since been erased in "brand" |
