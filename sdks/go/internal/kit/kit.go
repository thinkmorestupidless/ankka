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
	// KeyValue are the key value entities, by component id.
	KeyValue map[string]*KeyValue
	// Workflows are the workflows, by component id.
	Workflows map[string]*Workflow
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
	// Components are the declared components, by id, with their detail.
	Components map[string]*pb.Component
	// Input encodes a value as the input of a component's handler wire, by the handler's codec.
	Input func(componentID, wire string, value any) (*pb.Payload, error)
	// Reply decodes a reply of a component's handler wire, by the handler's codec.
	Reply func(componentID, wire string, p *pb.Payload) (any, error)
	// Views are the views and keyed views, by component id.
	Views map[string]*View
	// Consumers are the consumers and graph consumers, by component id.
	Consumers map[string]*Consumer
	// TimedActions answer TimedAction.Invoke, by component id.
	TimedActions map[string]server.TimedActionHandler
	// Agents answer the agent conversation, by component id.
	Agents map[string]server.AgentHandlers
	// Mcp run an MCP endpoint's tools, by endpoint id.
	Mcp map[string]server.McpHandler
	// WithClient is ctx carrying the client (an *ankka.Client) a handler's calls go through.
	WithClient func(ctx context.Context, client any) context.Context
}

// View is what a test kit needs of a view or a keyed view.
type View struct {
	ID    string
	Keyed bool
	// Sources are the component ids or topics the view reads.
	Sources []string
	Handle  server.ViewHandler
	// Change encodes a change from the source sourceID (any, for a plain view), by its codec.
	Change func(sourceID string, value any) (*pb.Payload, error)
	// Row decodes a row, by the view's row codec.
	Row func(p *pb.Payload) (any, error)
	// EncodeRow encodes a row, by the view's row codec.
	EncodeRow func(value any) (*pb.Payload, error)
	// WithRows is ctx carrying a keyed view's rows: get reads one, ask answers a declared query.
	WithRows func(ctx context.Context, get func(key string) (*pb.Payload, bool), ask func(name string, values map[string]string) ([]*pb.Payload, error)) context.Context
}

// Consumer is what a test kit needs of a consumer.
type Consumer struct {
	ID string
	// Graph is whether it publishes graph deltas.
	Graph  bool
	Handle server.ConsumerHandler
	// Message encodes a message, by the consumer's message codec.
	Message func(value any) (*pb.Payload, error)
	// Out decodes what the consumer produced, by its ProducesTo codec.
	Out func(p *pb.Payload) (any, error)
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

// KeyValue is what a test kit needs of a key value entity.
type KeyValue struct {
	ID string
	// New is a fresh instance of the entity, before its Init.
	New func(entityID string) server.KeyValueInstance
	// Input encodes a value as the input of the handler wire, by the handler's codec.
	Input func(wire string, value any) (*pb.Payload, error)
	// Reply decodes the reply of the handler wire, by the handler's codec.
	Reply func(wire string, p *pb.Payload) (any, error)
	// State decodes the state, by the entity's state codec.
	State func(p *pb.Payload) (any, error)
	// Empty is the empty state of the instance entityID, encoded by the state codec.
	Empty func(entityID string) (*pb.Payload, error)
	// Handlers are the entity's wire names, each with whether it is read-only.
	Handlers map[string]bool
}

// Workflow is what a test kit needs of a workflow.
type Workflow struct {
	ID string
	// New is a fresh instance of the workflow, before its Init.
	New func(workflowID string) server.WorkflowInstance
	// Input encodes a value as the input of the command wire, by the command's codec.
	Input func(wire string, value any) (*pb.Payload, error)
	// Reply decodes the reply of the command wire, by the command's codec.
	Reply func(wire string, p *pb.Payload) (any, error)
	// State decodes the state, by the workflow's state codec.
	State func(p *pb.Payload) (any, error)
	// Empty is the empty state of the workflow workflowID, encoded by the state codec.
	Empty func(workflowID string) (*pb.Payload, error)
	// Handlers are the workflow's command wire names, each with whether it is read-only.
	Handlers map[string]bool
}

// Of is the test kit's view of a service (an *ankka.Service); set by the root package.
var Of func(service any) (*Service, error)
