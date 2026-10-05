Feature: Signing in to the console
  A person signs in to the console through the installation's issuer, with whatever the issuer asks
  of them, and comes back signed in to what they asked for. Their browser holds a sign-in that
  nothing it runs can read, and no token ever reaches it. Signing out of the console signs them out
  of the issuer too.

  Scenario Outline: a person who has to sign in is brought back to what they asked for
    Given a person <who>
    When the person asks the console for the project "shop"
    Then the person is asked to sign in at the installation's issuer
    And once signed in, the person is shown the project "shop"

    Examples:
      | who                                                      |
      | who is not signed in to the console                      |
      | whose sign-in at the issuer has timed out                |
      | who has signed out of the issuer elsewhere               |
      | whose sign-in at the issuer an administrator has ended   |

  Scenario: a browser signed in to the console holds no token
    Given a person signed in to the console
    When everything the browser holds and everything the console sent it is read
    Then no token is found in any of it

  Scenario: a sign-in to the console cannot be read in the browser or carried by a request from outside the console
    Given a person signed in to the console in a cluster
    When what the browser holds of the sign-in is read
    Then it cannot be read by anything the browser runs
    And it is sent only over a connection nobody else can read
    And it is not sent with a request begun outside the console that changes anything

  Scenario: signing out of the console signs the person out of the issuer too
    Given a person signed in to the console
    When the person signs out
    Then the person is not signed in to the console
    And the person is no longer signed in at the installation's issuer
    And the person is asked to sign in when they next ask the console for anything

  Scenario Outline: a return from the issuer the console cannot trust signs nobody in
    Given a person returning to the console from signing in, <how>
    When the console receives the return
    Then the return is refused
    And the person is not signed in to the console

    Examples:
      | how                                       |
      | from a sign-in the console did not begin  |
      | from an issuer the console does not trust |

  Scenario: a person is never sent outside the console after signing in
    Given a request to the console that asks for the person to be sent outside the console after signing in
    When the person makes the request and signs in
    Then the person is shown the organizations they belong to
    And the person is not sent outside the console

  Scenario: a person signed in to the console is signed in on every instance of it
    Given the console running as 2 instances
    And a person signed in to the console
    When the person's requests reach each instance in turn
    Then every request is answered as that person

  Scenario: replacing an instance of the console signs nobody out and refuses no request
    Given the console running as 2 instances
    And a person signed in to the console, sending a request every second
    When one instance of the console is replaced
    Then the person is still signed in
    And every request the person sends is answered

  Scenario: a person stays signed in while the issuer cannot be reached
    Given a person signed in to the console whose sign-in needs renewing
    And the installation's issuer cannot be reached
    When the person asks the console for anything
    Then the person is told that the issuer cannot be reached and asked to try again
    And the person is still signed in
