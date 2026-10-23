package ankka

import (
	"context"
	"net/http"
	"net/url"
	"time"
)

// What a handler can see about the request it runs for travels on its context.Context, read by the
// accessors below. Go has no thread-local: work a handler starts on another goroutine sees the
// request only when it is handed the context.

type contextKey int

const (
	requestKey contextKey = iota
	callerKey
	commandKey
	timerKey
	clientKey
	storageKey
	configKey
	metadataKey
	eventKey
	// withoutSecretsKey marks a context whose handler is not offered the secret store: an
	// entity's, a view's or a workflow command's.
	withoutSecretsKey
)

// withoutSecrets is ctx for a handler the secret store is not offered to, named by who.
func withoutSecrets(ctx context.Context, who string) context.Context {
	return context.WithValue(ctx, withoutSecretsKey, who)
}

// Request is what an endpoint handler sees of its HTTP request.
type Request struct {
	Method string
	// Path is the route's template, relative to the endpoint's prefix ("/{cartId}/items").
	Path string
	// Params are the path parameters, by the names the template gives them.
	Params map[string]string
	// Query is the query string in request order; a name may repeat.
	Query url.Values
	// Headers are the request's headers; Get matches names case-insensitively.
	Headers http.Header
	// ContentType is the body's media type, as the request said it.
	ContentType string
	// Body is the raw body; a route's typed body is decoded from it.
	Body []byte
	// Principal is the caller when the route's ACL authenticated one; nil otherwise.
	Principal *Principal
	// Caller is which workload sent the request.
	Caller Caller
	// Metadata carries the trace and span ids of the request's span.
	Metadata Metadata
}

// Param is the path parameter name, or "" when the template has none of that name.
func (r *Request) Param(name string) string { return r.Params[name] }

// Header is the first value of the header name.
func (r *Request) Header(name string) string { return r.Headers.Get(name) }

// Principal is the caller an authenticated route was invoked by.
type Principal struct {
	Subject       string
	Name          string
	Email         string
	EmailVerified bool
	Roles         []string
	// Claims are every other claim of the verified token, as text.
	Claims map[string]string
	// Issuer is the service's own name for the issuer that verified the token.
	Issuer string
}

// Claim is the claim name as text, or "".
func (p *Principal) Claim(name string) string {
	if p == nil {
		return ""
	}
	return p.Claims[name]
}

// HasRole reports whether the principal holds role.
func (p *Principal) HasRole(role string) bool {
	if p == nil {
		return false
	}
	for _, r := range p.Roles {
		if r == role {
			return true
		}
	}
	return false
}

// CallerKind is what sort of workload sent a request.
type CallerKind int

const (
	// CallerLocal is the local machine, outside a cluster, where there is no certificate.
	CallerLocal CallerKind = iota
	// CallerGateway is the internet, through the installation's gateway.
	CallerGateway
	// CallerService is a named service of this installation.
	CallerService
	// CallerMachine is a machine registered on an organization (protocol 1.15).
	CallerMachine
)

func (k CallerKind) String() string {
	return [...]string{"local", "gateway", "service", "machine"}[k]
}

// Caller is which workload sent a request, as the platform established it from the client
// certificate, never from anything the request says about itself.
type Caller struct {
	Kind CallerKind
	// Project and Name name a calling service.
	Project string
	Name    string
	// Organization names a calling machine's organization, Name the machine.
	Organization string
}

// CommandScope is what a stateful component's command sees of itself.
type CommandScope struct {
	ComponentID string
	EntityID    string
	// Sequence is the sequence number of the last event applied before the command; 0 for a fresh entity.
	Sequence int64
	Metadata Metadata
	// Installation is the installation handling the command, for a replicated entity (1.19); "" when none is named.
	Installation string
	// AtHome reports whether this installation is the replicated entity's home: false only for a
	// query a follower answers, true for an entity that is not replicated.
	AtHome bool
	// Attribution is the subject of the principal who made the original call (1.22), "" when none named one.
	Attribution string
	// ForwardedFrom is the installation the command was forwarded from to its home (1.22), "" when it was not.
	ForwardedFrom string
}

// EventScope is where an event being applied was written (1.19, 1.22), read inside an entity's apply
// function through EventFrom.
type EventScope struct {
	Installation  string
	Local         bool
	Sequence      int64
	Attribution   string
	ForwardedFrom string
}

// TimerScope is what a timed action sees of the timer that fired it.
type TimerScope struct {
	// Name is the timer's id.
	Name string
	// Due is the due time this run is for: the same on every retry of one due time, and for a
	// recurring timer a whole number of periods after the one before. Zero on a runtime before
	// protocol 1.12, which does not send it.
	Due time.Time
	// Attempts is how many times the timer has fired for this due time.
	Attempts int
}

// RequestFrom is the endpoint request ctx carries, or nil outside an endpoint handler.
func RequestFrom(ctx context.Context) *Request {
	r, _ := ctx.Value(requestKey).(*Request)
	return r
}

// CallerFrom is the caller of the request ctx carries; a request with nothing said is local.
func CallerFrom(ctx context.Context) Caller {
	if r := RequestFrom(ctx); r != nil {
		return r.Caller
	}
	c, _ := ctx.Value(callerKey).(Caller)
	return c
}

// CommandFrom is the command ctx carries, and whether it carries one.
func CommandFrom(ctx context.Context) (CommandScope, bool) {
	c, ok := ctx.Value(commandKey).(CommandScope)
	return c, ok
}

// EventFrom is where the event being applied was written, inside an entity's apply function.
func EventFrom(ctx context.Context) (EventScope, bool) {
	e, ok := ctx.Value(eventKey).(EventScope)
	return e, ok
}

// TimerFrom is the timer that fired the timed action ctx carries.
func TimerFrom(ctx context.Context) (TimerScope, bool) {
	t, ok := ctx.Value(timerKey).(TimerScope)
	return t, ok
}

// MetadataFrom is the metadata of the request or call ctx carries: trace ids, a source's id.
func MetadataFrom(ctx context.Context) Metadata {
	if r := RequestFrom(ctx); r != nil {
		return r.Metadata
	}
	if c, ok := CommandFrom(ctx); ok {
		return c.Metadata
	}
	m, _ := ctx.Value(metadataKey).(Metadata)
	return m
}

// ConfigFrom is the service's variables as the platform hands them, or nil outside a handler.
func ConfigFrom(ctx context.Context) map[string]string {
	c, _ := ctx.Value(configKey).(map[string]string)
	return c
}

// The setters the server and the test kits use.

func withRequest(ctx context.Context, r *Request) context.Context {
	return context.WithValue(ctx, requestKey, r)
}

func withCaller(ctx context.Context, c Caller) context.Context {
	return context.WithValue(ctx, callerKey, c)
}

func withCommand(ctx context.Context, c CommandScope) context.Context {
	return context.WithValue(ctx, commandKey, c)
}

func withEvent(ctx context.Context, e EventScope) context.Context {
	return context.WithValue(ctx, eventKey, e)
}

func withTimer(ctx context.Context, t TimerScope) context.Context {
	return context.WithValue(ctx, timerKey, t)
}

func withMetadata(ctx context.Context, md Metadata) context.Context {
	return context.WithValue(ctx, metadataKey, md)
}

func withConfig(ctx context.Context, c map[string]string) context.Context {
	return context.WithValue(ctx, configKey, c)
}
