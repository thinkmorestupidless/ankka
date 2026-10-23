// Package kit is the seam between the SDK and its test kits. The root package fills Of when it is
// loaded; the test kits read a service's declarations through it and drive the same hosts the
// server drives, with no network. Nothing outside the module can import it.
package kit

import (
	"context"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
	"google.golang.org/grpc"
)

// Service is what a test kit needs of a service's declarations.
type Service struct {
	// Problems is every problem with the declarations, as the service's Start would refuse with.
	Problems []string
	// EventSourced are the event sourced entities, by component id.
	EventSourced map[string]*EventSourced
	// Kinds are the protocol kinds of the components, by id.
	Kinds map[string]pb.Kind
	// Route finds the route a request names: its endpoint id, route id and path arguments.
	Route func(method, path string) (endpointID, routeID string, args []string, ok bool)
	// Body encodes a value as a route's body, by the route's codec, with its content type.
	Body func(endpointID, routeID string, value any) ([]byte, string, error)
	// HTTP answers a request route, as Http.Handle does.
	HTTP func(ctx context.Context, req *pb.HttpRequest) (*pb.HttpResponse, error)
	// Client is the service's client over conn, a stand-in for the sidecar's Client service; the
	// value is an *ankka.Client.
	Client func(conn grpc.ClientConnInterface) any
	// ClientAt is a client of the sidecar at address that knows the service's declarations; the
	// value is an *ankka.Client.
	ClientAt func(address string) any
	// Redirect points the client of a service already serving at another sidecar address.
	Redirect func(address string)
}

// EventSourced is what a test kit needs of an event sourced entity.
type EventSourced struct {
	ID string
	// New is a fresh instance of the entity, before its Init.
	New func(entityID string) server.EventSourcedInstance
	// Input encodes a value as the input of the handler wire, by the handler's codec.
	Input func(wire string, value any) (*pb.Payload, error)
	// Reply decodes the reply of the handler wire, by the handler's codec.
	Reply func(wire string, p *pb.Payload) (any, error)
	// Event decodes one persisted event, by the entity's event codec.
	Event func(p *pb.Payload) (any, error)
	// State decodes a snapshot of the state, by the entity's state codec.
	State func(p *pb.Payload) (any, error)
	// Empty is the empty state of the instance entityID, encoded by the state codec.
	Empty func(entityID string) (*pb.Payload, error)
	// Handlers are the entity's wire names, each with whether it is read-only.
	Handlers map[string]bool
}

// Of is the test kit's view of a service (an *ankka.Service); set by the root package.
var Of func(service any) (*Service, error)
