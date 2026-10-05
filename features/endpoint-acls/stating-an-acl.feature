Feature: An endpoint states its ACL
  Every endpoint says who may call it. An endpoint that says nothing is a decision nobody made, so it
  is refused when it is registered, before the service serves anything, rather than opened to the
  internet.

  Scenario Outline: an endpoint that states no ACL is refused at registration
    Given a service "cart" written in "<language>" with an HTTP endpoint "CartEndpoint" that states no ACL
    When "cart" starts
    Then "cart" does not start
    And the reason names "CartEndpoint" and says that an ACL must be stated

    Examples:
      | language   |
      | Python     |
      | TypeScript |

  Scenario Outline: an endpoint that states it allows all serves everyone
    Given a service "cart" written in "<language>" with an HTTP endpoint whose ACL allows all
    When a person sends a request to one of its routes
    Then the request is served

    Examples:
      | language   |
      | Python     |
      | TypeScript |
