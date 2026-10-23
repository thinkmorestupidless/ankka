package ankka

import (
	"fmt"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// ACL is who may call an endpoint, a route, an MCP endpoint or a tool. There is no default: an
// endpoint is given one, and a nil ACL is a problem the service names at start.
type ACL struct {
	kind       aclKind
	callers    []CallerMatch
	issuers    []string
	anyOf      []*ACL
	clientKeys bool
}

type aclKind int

const (
	aclAllowAll aclKind = iota
	aclDenyAll
	aclAuthenticated
	aclCallers
	aclClientKeys
	aclAnyOf
)

// AllowAll admits every caller.
func AllowAll() *ACL { return &ACL{kind: aclAllowAll} }

// DenyAll admits no caller: a route nobody may reach until a route of its own says otherwise.
func DenyAll() *ACL { return &ACL{kind: aclDenyAll} }

// IssuerRef names an issuer by the service's own name for it, as ANKKA_AUTH_ISSUERS gives it.
type IssuerRef struct{ name string }

// Issuer is the issuer the service calls name.
func Issuer(name string) IssuerRef { return IssuerRef{name: name} }

// Authenticate admits a caller whose token the sidecar verified, issued by one of the issuers named
// (or by the endpoint's Issuers when none is named here); the principal reaches the handler.
func Authenticate(issuers ...IssuerRef) *ACL {
	a := &ACL{kind: aclAuthenticated}
	for _, i := range issuers {
		a.issuers = append(a.issuers, i.name)
	}
	return a
}

// CallerMatch is one kind of caller an AllowIf admits.
type CallerMatch struct {
	kind    string
	project string
	name    string
}

// Internet is a caller through the installation's gateway.
func Internet() CallerMatch { return CallerMatch{kind: "internet"} }

// ServiceNamed is the service name of this service's own project.
func ServiceNamed(name string) CallerMatch { return CallerMatch{kind: "service", name: name} }

// ServiceIn is the service name of project.
func ServiceIn(project, name string) CallerMatch {
	return CallerMatch{kind: "service", project: project, name: name}
}

// AnyInProject is any service of this service's project.
func AnyInProject() CallerMatch { return CallerMatch{kind: "any-in-project"} }

// Self is this service itself.
func Self() CallerMatch { return CallerMatch{kind: "self"} }

// Granted is whoever this service's project has granted the route to: a service of another project,
// or a registered machine (1.15). A route that does not name it can never be granted.
func Granted() CallerMatch { return CallerMatch{kind: "granted"} }

// AllowIf admits only the callers named, read from the certificate the platform issued. Outside a
// cluster every caller is local, and AllowIf admits it.
func AllowIf(first CallerMatch, rest ...CallerMatch) *ACL {
	return &ACL{kind: aclCallers, callers: append([]CallerMatch{first}, rest...)}
}

// AllowClientKeys admits a client key issued for the MCP endpoint (1.20); its subject and attributes are
// the principal. An MCP endpoint's ACL only.
func AllowClientKeys() *ACL { return &ACL{kind: aclClientKeys, clientKeys: true} }

// AnyOf admits a caller any of acls admits. An MCP endpoint's ACL only.
func AnyOf(first *ACL, rest ...*ACL) *ACL {
	return &ACL{kind: aclAnyOf, anyOf: append([]*ACL{first}, rest...)}
}

func (m CallerMatch) toPb() *pb.CallerMatcher {
	switch m.kind {
	case "internet":
		return &pb.CallerMatcher{Kind: &pb.CallerMatcher_Internet{Internet: &pb.Empty{}}}
	case "service":
		ns := &pb.NamedService{Name: m.name}
		if m.project != "" {
			ns.Project = &m.project
		}
		return &pb.CallerMatcher{Kind: &pb.CallerMatcher_Service{Service: ns}}
	case "any-in-project":
		return &pb.CallerMatcher{Kind: &pb.CallerMatcher_AnyInProject{AnyInProject: &pb.Empty{}}}
	case "self":
		return &pb.CallerMatcher{Kind: &pb.CallerMatcher_Self{Self: &pb.Empty{}}}
	case "granted":
		return &pb.CallerMatcher{Kind: &pb.CallerMatcher_Granted{Granted: &pb.Empty{}}}
	}
	return nil
}

// endpointACL is a for an HTTP endpoint or route: its protocol value and its callers.
func (a *ACL) endpointACL() (pb.Endpoint_Acl, []*pb.CallerMatcher) {
	switch a.kind {
	case aclDenyAll:
		return pb.Endpoint_DENY_ALL, nil
	case aclAuthenticated:
		return pb.Endpoint_AUTHENTICATED, nil
	case aclCallers:
		out := make([]*pb.CallerMatcher, 0, len(a.callers))
		for _, c := range a.callers {
			out = append(out, c.toPb())
		}
		return pb.Endpoint_CALLERS, out
	}
	return pb.Endpoint_ALLOW_ALL, nil
}

// grants reports whether a names Granted.
func (a *ACL) grants() bool {
	if a == nil {
		return false
	}
	for _, c := range a.callers {
		if c.kind == "granted" {
			return true
		}
	}
	for _, o := range a.anyOf {
		if o.grants() {
			return true
		}
	}
	return false
}

// httpProblems is what is wrong with a as an HTTP endpoint's or route's ACL.
func (a *ACL) httpProblems(fallbackIssuers []string) []string {
	var out []string
	switch a.kind {
	case aclClientKeys, aclAnyOf:
		out = append(out, "AllowClientKeys and AnyOf are an MCP endpoint's ACLs, not an HTTP endpoint's")
	case aclAuthenticated:
		if len(a.issuers) == 0 && len(fallbackIssuers) == 0 {
			out = append(out, "an authenticated route names its issuers: Authenticate(ankka.Issuer(...)), or ankka.Issuers(...) on the endpoint")
		}
	case aclCallers:
		for _, c := range a.callers {
			if c.kind == "service" && strings.TrimSpace(c.name) == "" {
				out = append(out, "a named service in AllowIf has a name")
			}
			if c.kind == "" {
				out = append(out, "AllowIf is given callers built by Internet, ServiceNamed, ServiceIn, AnyInProject, Self or Granted")
			}
		}
	}
	return out
}

// admits reports whether a principal is one of the issuers an authenticated ACL names, answering
// why not when it is not.
func (a *ACL) admitsIssuer(p *Principal, fallbackIssuers []string) (bool, string) {
	if a == nil || a.kind != aclAuthenticated {
		return true, ""
	}
	issuers := a.issuers
	if len(issuers) == 0 {
		issuers = fallbackIssuers
	}
	if p == nil {
		return false, "this route needs an authenticated caller"
	}
	for _, i := range issuers {
		if i == p.Issuer {
			return true, ""
		}
	}
	return false, fmt.Sprintf("a token from %q is not one this route accepts", p.Issuer)
}
