package ankka

import (
	"fmt"
	"reflect"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/kit"
	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
	"google.golang.org/grpc"
)

func init() { kit.Of = kitOf }

func kitOf(service any) (*kit.Service, error) {
	s, ok := service.(*Service)
	if !ok || s == nil {
		return nil, fmt.Errorf("a test kit is given an *ankka.Service, not %T", service)
	}
	d := s.decls
	out := &kit.Service{
		EventSourced: map[string]*kit.EventSourced{},
		Kinds:        map[string]pb.Kind{},
		Route:        d.matchRoute,
		Body:         d.encodeBody,
		HTTP:         d.handleHTTP,
		Client: func(conn grpc.ClientConnInterface) any {
			c := &Client{address: "a test kit", decls: d, rpc: pb.NewClientClient(conn)}
			c.once.Do(func() {})
			return c
		},
	}
	for _, p := range d.problems {
		out.Problems = append(out.Problems, p.String())
	}
	for _, c := range d.components {
		out.Kinds[c.GetId()] = c.GetKind()
	}
	for id, e := range d.esKits {
		out.EventSourced[id] = e
	}
	return out, nil
}

// kitView is the entity as a test kit drives it.
func (e *EventSourcedEntity[S, E]) kitView() *kit.EventSourced {
	handlers := map[string]bool{}
	byWire := map[string]handlerPart{}
	for _, h := range e.handlers {
		handlers[h.wire()] = h.readOnly()
		byWire[h.wire()] = h
	}
	return &kit.EventSourced{
		ID:  e.id,
		New: func(entityID string) server.EventSourcedInstance { return e.instance(entityID) },
		Input: func(wire string, v any) (*pb.Payload, error) {
			h, ok := byWire[wire]
			if !ok {
				return nil, fmt.Errorf("%s has no handler %q", e.id, wire)
			}
			return h.encodeInput(v)
		},
		Reply: func(wire string, p *pb.Payload) (any, error) {
			h, ok := byWire[wire]
			if !ok {
				return nil, fmt.Errorf("%s has no handler %q", e.id, wire)
			}
			return h.decodeReply(p)
		},
		Event: func(p *pb.Payload) (any, error) { return valueOf(e.eventCodec, p) },
		State: func(p *pb.Payload) (any, error) { return valueOf(e.stateCodec, p) },
		Empty: func(entityID string) (*pb.Payload, error) {
			return payloadOf(e.stateCodec, e.empty(entityID))
		},
		Handlers: handlers,
	}
}

// encodeInput encodes v by the handler's input codec, refusing a value of another type.
func (h handlerBase[I, R]) encodeInput(v any) (*pb.Payload, error) {
	in, ok := v.(I)
	if !ok {
		return nil, fmt.Errorf("%s takes a %s, not a %T", h.wireName, reflect.TypeFor[I](), v)
	}
	return payloadOf(h.in, in)
}

// decodeReply decodes a reply by the handler's reply codec.
func (h handlerBase[I, R]) decodeReply(p *pb.Payload) (any, error) { return valueOf(h.out, p) }

// matchRoute finds the route a request names. A literal segment is preferred to a parameter, as
// the sidecar's router prefers it.
func (d *declarations) matchRoute(method, path string) (string, string, []string, bool) {
	path, _, _ = strings.Cut(path, "?")
	bestScore, found := -1, false
	var endpointID, routeID string
	var args []string
	for id, e := range d.endpointDecls {
		rest, ok := strings.CutPrefix(path, e.prefix)
		if !ok || (rest != "" && !strings.HasPrefix(rest, "/")) {
			continue
		}
		for _, r := range e.routes {
			if r.method != method {
				continue
			}
			a, score, ok := matchTemplate(r.template, rest)
			if ok && score > bestScore {
				bestScore, found, endpointID, routeID, args = score, true, id, r.routeID(), a
			}
		}
	}
	return endpointID, routeID, args, found
}

// matchTemplate matches a path below an endpoint's prefix against a route's template, answering
// the parameters in template order and how many segments matched literally.
func matchTemplate(template, path string) ([]string, int, bool) {
	ts, ps := segments(template), segments(path)
	if len(ts) != len(ps) {
		return nil, 0, false
	}
	var args []string
	literal := 0
	for i, t := range ts {
		if strings.HasPrefix(t, "{") && strings.HasSuffix(t, "}") {
			if ps[i] == "" {
				return nil, 0, false
			}
			args = append(args, ps[i])
			continue
		}
		if t != ps[i] {
			return nil, 0, false
		}
		literal++
	}
	return args, literal, true
}

func segments(p string) []string {
	p = strings.TrimPrefix(p, "/")
	if p == "" {
		return nil
	}
	return strings.Split(p, "/")
}

// encodeBody encodes value as the route's body: bytes and text as given, anything else by the
// route's body codec.
func (d *declarations) encodeBody(endpointID, routeID string, value any) ([]byte, string, error) {
	switch v := value.(type) {
	case nil:
		return nil, "", nil
	case []byte:
		return v, BinaryContent, nil
	}
	r := d.routes[endpointID][routeID]
	if r == nil || r.encodeBody == nil {
		return nil, "", fmt.Errorf("the route %s %s takes no body", endpointID, routeID)
	}
	return r.encodeBody(value)
}
