package samples

import (
	"context"
	"fmt"
	"iter"
	"net/http"
	"sort"
	"strconv"
	"strings"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// ── pages, redirects and cookies ──

const (
	accountPage = "<html><body>Your account</body></html>"
	stylesheet  = "body { font: 16px sans-serif }"
)

// SiteEndpoint serves a website rather than an API.
var SiteEndpoint = ankka.NewEndpoint("/site", ankka.AllowAll(),
	// docs:start pages
	// RawResponse is any body with the status, content type and headers the handler chooses.
	ankka.Get("/account", func(context.Context, *ankka.Request) (ankka.RawResponse, error) {
		return ankka.RawResponse{Status: 200, ContentType: "text/html; charset=utf-8",
			Headers: http.Header{"Set-Cookie": {"session=s-1; HttpOnly"}}, Body: []byte(accountPage)}, nil
	}),
	// 303 See Other, the status that is safe after a form post.
	ankka.Post("/logout", func(context.Context, *ankka.Request, ankka.Unit) (ankka.RawResponse, error) {
		return ankka.RawResponse{Status: 303, Headers: http.Header{"Location": {"/"}}}, nil
	}),
	ankka.Get("/style.css", func(context.Context, *ankka.Request) (ankka.RawResponse, error) {
		return ankka.RawResponse{Status: 200, ContentType: "text/css", Body: []byte(stylesheet)}, nil
	}),
	// docs:end pages
)

// ── query parameters and headers ──

// SearchResult is what the search answers: the parameters it read.
type SearchResult struct {
	Term    string   `json:"term"`
	Limit   int      `json:"limit"`
	Tags    []string `json:"tags"`
	Verbose bool     `json:"verbose"`
}

// QueryEndpoint reads what varies per call from the request.
var QueryEndpoint = ankka.NewEndpoint("/search", ankka.AllowAll(),
	// docs:start query
	// Required, optional-with-default, repeated, and flag parameters. Query is the request's
	// url.Values: a missing required parameter is the caller's mistake, and a 400 says which.
	ankka.Get("/", func(_ context.Context, req *ankka.Request) (SearchResult, error) {
		term := req.Query.Get("q")
		if term == "" {
			return SearchResult{}, &ankka.HttpProblem{Status: 400, Message: "missing query parameter q"}
		}
		limit := 20
		if raw := req.Query.Get("limit"); raw != "" {
			n, err := strconv.Atoi(raw)
			if err != nil {
				return SearchResult{}, &ankka.HttpProblem{Status: 400, Message: "limit is not a number"}
			}
			limit = n
		}
		verbose := req.Query.Has("verbose") && req.Query.Get("verbose") != "false"
		return SearchResult{Term: term, Limit: limit, Tags: req.Query["tag"], Verbose: verbose}, nil
	}),
	// docs:end query
	// docs:start header
	// Headers, matched by name case-insensitively.
	ankka.Get("/trace", func(_ context.Context, req *ankka.Request) (string, error) {
		if id := req.Header("X-Trace-Id"); id != "" {
			return id, nil
		}
		return "none", nil
	}),
	// docs:end header
	// docs:start sse-query
	// The request is the route's argument, so a stream reads its parameters as it is built, before
	// any element is pulled.
	ankka.SSE("/stream", func(_ context.Context, req *ankka.Request) iter.Seq2[string, error] {
		term, count := req.Query.Get("q"), 2
		if n, err := strconv.Atoi(req.Query.Get("count")); err == nil {
			count = n
		}
		return func(yield func(string, error) bool) {
			for n := 1; n <= count; n++ {
				if !yield(fmt.Sprintf("%s-%d", term, n), nil) {
					return
				}
			}
		}
	}),
	// docs:end sse-query
)

// ── a verified token's principal ──

// docs:start oidc-endpoint

// AccountEndpoint's users sign in with an identity provider the service lists: the sidecar verifies
// the token before the process is asked anything, and the handler reads the principal.
var AccountEndpoint = ankka.NewEndpoint("/account", ankka.Authenticate(ankka.Issuer("corp")),
	ankka.Get("/me", func(_ context.Context, req *ankka.Request) (string, error) {
		p := req.Principal
		roles := append([]string{}, p.Roles...)
		sort.Strings(roles)
		return fmt.Sprintf("%s from %s roles=%s tier=%s",
			p.Subject, p.Issuer, strings.Join(roles, ","), p.Claims["tier"]), nil
	}),
)

// docs:end oidc-endpoint

// ── naming who may call ──

// describeCaller is the caller as a word: a service by project and name, the gateway, or local.
func describeCaller(c ankka.Caller) string {
	switch c.Kind {
	case ankka.CallerService:
		return "service:" + c.Project + "/" + c.Name
	case ankka.CallerMachine:
		return "machine:" + c.Organization + "/" + c.Name
	case ankka.CallerGateway:
		return "gateway"
	}
	return "local"
}

// docs:start allow-callers

// CallersEndpoint admits only the internet and the orders service in this project; any other
// caller is refused 403. One route admits another instance of this very service, and nothing else.
var CallersEndpoint = ankka.NewEndpoint("/callers", ankka.AllowIf(ankka.Internet(), ankka.ServiceNamed("orders")),
	ankka.Get("/only-orders", func(_ context.Context, req *ankka.Request) (string, error) {
		return "admitted: " + describeCaller(req.Caller), nil
	}),
	ankka.Get("/only-self", func(context.Context, *ankka.Request) (string, error) {
		return "admitted: myself", nil
	}).ACL(ankka.AllowIf(ankka.Self())),
	// docs:end allow-callers
	// docs:start who-is-calling
	// The caller is always present, set before any ACL runs, and independent of the principal.
	ankka.Get("/whoami", func(_ context.Context, req *ankka.Request) (string, error) {
		return describeCaller(req.Caller), nil
	}),
	// docs:end who-is-calling
)

// ── a route another project may be granted ──

// DepositRequest is a deposit's body.
type DepositRequest struct {
	Amount int `json:"amount"`
}

// DepositMade is what a deposit answers: the balance, whether this key was applied now, and who
// the platform established the caller to be.
type DepositMade struct {
	Currency string `json:"currency"`
	Balance  int    `json:"balance"`
	Applied  bool   `json:"applied"`
	Caller   string `json:"caller"`
}

// docs:start granted-callers

// WalletEndpoint is a player's wallet, which another project may be granted. The endpoint's ACL
// admits granted callers: whether anyone outside the project may ever call these routes is decided
// here, in reviewed code; who may, today, is a grant the project's owners make and revoke as data.
var WalletEndpoint = ankka.NewEndpoint("/v1/wallets", ankka.AllowIf(ankka.Granted()),
	// A deposit, with the caller's idempotency key: a granted call is delivered at least once, and
	// the wallet applies each key once. The answer names the caller as the platform established it.
	ankka.Post("/{player}/{currency}/deposits",
		func(ctx context.Context, req *ankka.Request, body DepositRequest) (DepositMade, error) {
			key := req.Header("Idempotency-Key")
			if key == "" {
				return DepositMade{}, &ankka.HttpProblem{Status: 400,
					Message: "a deposit needs an Idempotency-Key header"}
			}
			deposit := Deposit{Key: key, Currency: req.Param("currency"), Amount: body.Amount}
			reply, err := ankka.Invoke[Deposited](ctx, "wallet", req.Param("player"), "deposit", deposit)
			if err != nil {
				return DepositMade{}, err
			}
			return DepositMade{Currency: reply.Currency, Balance: reply.Balance, Applied: reply.Applied,
				Caller: describeCaller(req.Caller)}, nil
		}),
)

// docs:end granted-callers

// ── a route with its own ACL ──

// docs:start route-acl

// MixedAclEndpoint is one endpoint, two audiences: reading a cart is public, purging one is not.
// A route's ACL replaces the endpoint's for that route, so neither audience needs an endpoint of
// its own at a second prefix.
var MixedAclEndpoint = ankka.NewEndpoint("/mixed", ankka.AllowAll(),
	ankka.Get("/{cartId}", func(_ context.Context, req *ankka.Request) (string, error) {
		return "cart:" + req.Param("cartId"), nil
	}),
	ankka.Delete("/{cartId}", func(_ context.Context, req *ankka.Request) (string, error) {
		return "purged:" + req.Param("cartId") + " by " + req.Principal.Subject, nil
	}).ACL(ankka.Authenticate(ankka.Issuer("support"))),
)

// docs:end route-acl

// docs:start route-acl-deny

// CartsEndpoint is open, but for one route nobody may reach until a rule of its own says otherwise.
var CartsEndpoint = ankka.NewEndpoint("/carts-admin", ankka.AllowAll(),
	ankka.Get("/{cartId}", func(_ context.Context, req *ankka.Request) (string, error) {
		return "cart:" + req.Param("cartId"), nil
	}),
	ankka.Delete("/{cartId}", func(_ context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Done{}, nil
	}).ACL(ankka.DenyAll()),
)

// docs:end route-acl-deny
