package samples

import (
	"context"
	"encoding/json"
	"net/http"
	"testing"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/testkit"
)

func TestTheSiteAnswersPagesRedirectsAndCookies(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(SiteEndpoint)
	defer kit.Close()
	if r := kit.Get(ctx, "/site/account"); r.Status != 200 || r.Header.Get("Set-Cookie") != "session=s-1; HttpOnly" || r.Text() != accountPage {
		t.Fatalf("the account page answered %d %v %s", r.Status, r.Header, r.Text())
	}
	if r := kit.Post(ctx, "/site/logout", ankka.Unit{}); r.Status != 303 || r.Header.Get("Location") != "/" {
		t.Fatalf("logging out answered %d %v", r.Status, r.Header)
	}
	if r := kit.Get(ctx, "/site/style.css"); r.ContentType != "text/css" || r.Text() != stylesheet {
		t.Fatalf("the stylesheet answered %q %s", r.ContentType, r.Text())
	}
}

func TestTheQueryAndHeadersAreRead(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(QueryEndpoint)
	defer kit.Close()
	r := kit.Get(ctx, "/search/?q=pens&tag=a&tag=b&verbose")
	var got SearchResult
	if err := json.Unmarshal(r.Body, &got); err != nil || r.Status != 200 || len(got.Tags) != 2 {
		t.Fatalf("the search read %d %s (%v)", r.Status, r.Text(), err)
	}
	if got.Tags[0] != "a" || got.Tags[1] != "b" || got.Limit != 20 || !got.Verbose || got.Term != "pens" {
		t.Fatalf("the search read %+v", got)
	}
	if r := kit.Get(ctx, "/search/?limit=5"); r.Status != 400 {
		t.Fatalf("a missing q answered %d %s", r.Status, r.Text())
	}
	if r := kit.Get(ctx, "/search/?q=x&limit=abc"); r.Status != 400 {
		t.Fatalf("a bad limit answered %d %s", r.Status, r.Text())
	}
	if r := kit.Do(ctx, testkit.Request{Method: http.MethodGet, Path: "/search/trace", Header: http.Header{"X-Trace-Id": {"t-1"}}}); r.Text() != "t-1" {
		t.Fatalf("the trace header read as %q", r.Text())
	}
	if r := kit.Get(ctx, "/search/trace"); r.Text() != "none" {
		t.Fatalf("no trace header read as %q", r.Text())
	}
}

func TestThePrincipalAndTheCallerAreRead(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(AccountEndpoint, CallersEndpoint, MixedAclEndpoint, CartsEndpoint)
	defer kit.Close()
	me := kit.Do(ctx, testkit.Request{Method: http.MethodGet, Path: "/account/me", Principal: &ankka.Principal{
		Subject: "ada", Issuer: "corp", Roles: []string{"support", "admin"}, Claims: map[string]string{"tier": "gold"}}})
	if me.Text() != "ada from corp roles=admin,support tier=gold" {
		t.Fatalf("/me answered %d %q", me.Status, me.Text())
	}
	// Outside a cluster every caller is the local machine.
	if r := kit.Get(ctx, "/callers/whoami"); r.Text() != "local" {
		t.Fatalf("whoami answered %q", r.Text())
	}
	if r := kit.Get(ctx, "/callers/only-orders"); r.Text() != "admitted: local" {
		t.Fatalf("only-orders answered %q", r.Text())
	}
	if r := kit.Get(ctx, "/mixed/c1"); r.Text() != "cart:c1" {
		t.Fatalf("reading answered %q", r.Text())
	}
	// The route's own ACL: a token from the support issuer, which the kit's principal stands in for.
	purge := kit.Do(ctx, testkit.Request{Method: http.MethodDelete, Path: "/mixed/c1",
		Principal: &ankka.Principal{Subject: "support-7", Issuer: "support"}})
	if purge.Text() != "purged:c1 by support-7" {
		t.Fatalf("purging answered %q", purge.Text())
	}
	if r := kit.Get(ctx, "/carts-admin/c1"); r.Text() != "cart:c1" {
		t.Fatalf("reading a cart answered %q", r.Text())
	}
}

func TestADepositIsAppliedOncePerKey(t *testing.T) {
	ctx := context.Background()
	kit := testkit.Endpoint(WalletEndpoint, WalletEntity)
	defer kit.Close()
	deposit := func(key string) DepositMade {
		t.Helper()
		r := kit.Do(ctx, testkit.Request{Method: http.MethodPost, Path: "/v1/wallets/p1/EUR/deposits",
			Body: DepositRequest{Amount: 10}, Header: http.Header{"Idempotency-Key": {key}}})
		var made DepositMade
		if err := json.Unmarshal(r.Body, &made); err != nil || r.Status != 200 {
			t.Fatalf("a deposit answered %d %s (%v)", r.Status, r.Text(), err)
		}
		return made
	}
	if first := deposit("k-1"); first != (DepositMade{Currency: "EUR", Balance: 10, Applied: true, Caller: "local"}) {
		t.Fatalf("the first deposit answered %+v", first)
	}
	if again := deposit("k-1"); again.Balance != 10 || again.Applied {
		t.Fatalf("the same key again answered %+v", again)
	}
	if second := deposit("k-2"); second.Balance != 20 || !second.Applied {
		t.Fatalf("a second key answered %+v", second)
	}
	if r := kit.Post(ctx, "/v1/wallets/p1/EUR/deposits", DepositRequest{Amount: 10}); r.Status != 400 {
		t.Fatalf("a deposit with no key answered %d %s", r.Status, r.Text())
	}
}
