package ankka

import (
	"fmt"
	"reflect"
	"sort"
	"strconv"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// A Declaration is a component or an endpoint, built by its constructor and handed to NewService.
// Only this package's constructors make one.
type Declaration interface {
	declare(d *declarations)
}

// need is something a declaration requires of the sidecar: a protocol minor and why.
type need struct {
	minor  int
	reason string
}

// declarations are everything a service's declarations said, gathered once.
type declarations struct {
	components []*pb.Component
	endpoints  []*pb.Endpoint
	mcp        []*pb.McpEndpoint
	problems   []Problem
	needs      []need

	eventSourced map[string]func(entityID string) server.EventSourcedInstance
	keyValue     map[string]func(entityID string) server.KeyValueInstance
	workflows    map[string]func(workflowID string) server.WorkflowInstance
	views        map[string]server.ViewHandler
	consumers    map[string]server.ConsumerHandler
	timedActions map[string]server.TimedActionHandler
	agents       map[string]server.AgentHandlers

	// inputs encode a call's input by the codec its handler declares, keyed by handlerKey.
	inputs map[string]func(any) (*pb.Payload, error)

	// routes are each endpoint's routes by route id; endpointDecls the endpoints by id.
	routes        map[string]map[string]*Route
	endpointDecls map[string]*Endpoint

	// esKits are the event sourced entities as the test kits drive them, by id.
	esKits map[string]*kit.EventSourced
	kvKits map[string]*kit.KeyValue
	wfKits map[string]*kit.Workflow
}

func newDeclarations() *declarations {
	return &declarations{
		eventSourced:  map[string]func(string) server.EventSourcedInstance{},
		keyValue:      map[string]func(string) server.KeyValueInstance{},
		kvKits:        map[string]*kit.KeyValue{},
		workflows:     map[string]func(string) server.WorkflowInstance{},
		wfKits:        map[string]*kit.Workflow{},
		views:         map[string]server.ViewHandler{},
		consumers:     map[string]server.ConsumerHandler{},
		timedActions:  map[string]server.TimedActionHandler{},
		agents:        map[string]server.AgentHandlers{},
		inputs:        map[string]func(any) (*pb.Payload, error){},
		routes:        map[string]map[string]*Route{},
		endpointDecls: map[string]*Endpoint{},
		esKits:        map[string]*kit.EventSourced{},
	}
}

// input registers how a call to componentID's handler wire encodes its input.
func input[I any](d *declarations, componentID, wire string, c Codec[I]) {
	d.inputs[handlerKey(componentID, wire)] = func(v any) (*pb.Payload, error) {
		if v == nil {
			var zero I
			v = zero
		}
		typed, ok := v.(I)
		if !ok {
			return nil, fmt.Errorf("%s %s takes a %s, not a %T", componentID, wire, reflect.TypeFor[I](), v)
		}
		return payloadOf(c, typed)
	}
}

func (d *declarations) problem(where, format string, args ...any) {
	d.problems = append(d.problems, Problem{Where: where, Message: fmt.Sprintf(format, args...)})
}

func (d *declarations) require(minor int, format string, args ...any) {
	d.needs = append(d.needs, need{minor: minor, reason: fmt.Sprintf(format, args...)})
}

// gather declares every declaration and checks them against each other.
func gather(decls []Declaration) *declarations {
	d := newDeclarations()
	for i, decl := range decls {
		if decl == nil {
			d.problem("", "declaration %d is nil", i+1)
			continue
		}
		decl.declare(d)
	}
	ids := map[string]int{}
	for _, c := range d.components {
		ids[c.GetId()]++
	}
	for _, id := range sortedKeys(ids) {
		if ids[id] > 1 {
			d.problem(id, "%d components share the id %q; an id names one component", ids[id], id)
		}
	}
	prefixes := map[string][]string{}
	for _, e := range d.endpoints {
		prefixes[e.GetPrefix()] = append(prefixes[e.GetPrefix()], e.GetId())
	}
	for _, p := range sortedKeys(prefixes) {
		if len(prefixes[p]) > 1 {
			d.problem(p, "endpoints %s share the prefix %q; a prefix belongs to one endpoint", strings.Join(prefixes[p], ", "), p)
		}
	}
	paths := map[string]int{}
	for _, m := range d.mcp {
		paths[m.GetPath()]++
	}
	for _, p := range sortedKeys(paths) {
		if paths[p] > 1 {
			d.problem(p, "%d MCP endpoints share the path %q", paths[p], p)
		}
	}
	return d
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

// spec is the protocol's Spec for d.
func (d *declarations) spec() *pb.Spec {
	return &pb.Spec{
		ProtocolVersion: ProtocolVersion,
		Sdk:             &pb.SdkInfo{Name: SDKName, Version: Version()},
		Components:      d.components,
		Endpoints:       d.endpoints,
		McpEndpoints:    d.mcp,
	}
}

// protocolMinor is the minor of a "1.N" protocol version; ok is false when it is not one.
func protocolMinor(version string) (int, bool) {
	major, minor, found := strings.Cut(version, ".")
	if !found || major != "1" {
		return 0, false
	}
	n, err := strconv.Atoi(minor)
	if err != nil || n < 0 {
		return 0, false
	}
	return n, true
}

// refusal is why a sidecar speaking version cannot host d, naming the oldest version it lacks
// first, or "" when it can (FR-018).
func (d *declarations) refusal(version string) string {
	minor, ok := protocolMinor(version)
	if !ok {
		if len(d.needs) == 0 {
			return ""
		}
		minor = 0
	}
	var unmet []need
	for _, n := range d.needs {
		if n.minor > minor {
			unmet = append(unmet, n)
		}
	}
	if len(unmet) == 0 {
		return ""
	}
	sort.SliceStable(unmet, func(i, j int) bool { return unmet[i].minor < unmet[j].minor })
	oldest := unmet[0].minor
	var reasons []string
	for _, n := range unmet {
		if n.minor == oldest {
			reasons = append(reasons, n.reason)
		}
	}
	shown := version
	if shown == "" {
		shown = "unknown"
	}
	return fmt.Sprintf("the sidecar speaks protocol %s and this service needs 1.%d: %s; this SDK speaks %s, so run a sidecar speaking 1.%d or later",
		shown, oldest, strings.Join(reasons, "; "), ProtocolVersion, oldest)
}
