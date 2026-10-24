package ankka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"reflect"
	"sync"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
)

// Client is the way back to the sidecar beside this process: calls to the service's own
// components, view queries, timers, secrets, client keys and calls to other services. A handler
// reaches it through its context (ClientFrom); every call carries the handler's metadata, so the
// runtime makes it as that handler, and the trace nests under it. One channel is opened, lazily.
type Client struct {
	address string
	decls   *declarations

	mu   sync.Mutex
	conn *grpc.ClientConn
	rpc  pb.ClientClient
}

// NewClient is a client of the sidecar at address ("127.0.0.1:9011"), for code outside a handler: a
// test, or a tool. The service's own client is handed to its handlers on their contexts.
func NewClient(address string) *Client { return &Client{address: address} }

// Close closes the client's channel, if it opened one; a later call opens another.
func (c *Client) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	conn := c.conn
	c.conn, c.rpc = nil, nil
	if conn != nil {
		return conn.Close()
	}
	return nil
}

// redirect points the client at another address: the integration test kit's sidecar, replaced by
// a restart, answers on a new port. The next call dials it.
func (c *Client) redirect(address string) {
	_ = c.Close()
	c.mu.Lock()
	c.address = address
	c.mu.Unlock()
}

func (c *Client) stub() (pb.ClientClient, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.rpc != nil {
		return c.rpc, nil
	}
	conn, err := grpc.NewClient(c.address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return nil, fmt.Errorf("the sidecar's client at %s cannot be reached: %w", c.address, err)
	}
	c.conn, c.rpc = conn, pb.NewClientClient(conn)
	return c.rpc, nil
}

// WithClient is ctx carrying c, so the calls below can be made from outside a handler.
func WithClient(ctx context.Context, c *Client) context.Context {
	return context.WithValue(ctx, clientKey, c)
}

// ClientFrom is the client ctx carries, or nil outside a handler.
func ClientFrom(ctx context.Context) *Client {
	c, _ := ctx.Value(clientKey).(*Client)
	return c
}

func clientOf(ctx context.Context) (*Client, pb.ClientClient, error) {
	c := ClientFrom(ctx)
	if c == nil {
		return nil, nil, errors.New("this context carries no client: call from a handler, or give it one with ankka.WithClient")
	}
	rpc, err := c.stub()
	return c, rpc, err
}

// tooOld turns UNIMPLEMENTED from a sidecar older than since into an error naming both versions,
// and a status into a refusal carrying its code where it has one.
func tooOld(what, since string, err error) error {
	if err == nil {
		return nil
	}
	st, ok := status.FromError(err)
	if !ok {
		return err
	}
	switch st.Code() {
	case codes.Unimplemented:
		return &ProtocolTooOldError{What: what, Needs: since, Message: st.Message()}
	case codes.Canceled:
		return context.Canceled
	case codes.DeadlineExceeded:
		return context.DeadlineExceeded
	}
	return err
}

// encodeInput encodes a call's input by the codec its handler declares in this service, or by the
// codec of its own type when the handler is not one this process declared.
func (c *Client) encodeInput(componentID, wire string, input any) (*pb.Payload, error) {
	if c.decls != nil {
		if enc, ok := c.decls.inputs[handlerKey(componentID, wire)]; ok {
			return enc(input)
		}
	}
	return encodeAny(input)
}

// encodeAny encodes v by the default codec of its dynamic type; nil is Unit.
func encodeAny(v any) (*pb.Payload, error) {
	if v == nil {
		v = Unit{}
	}
	rv := reflect.ValueOf(v)
	tc := codecForType(rv.Type(), "", true)
	if len(tc.problems) > 0 {
		return nil, fmt.Errorf("%s cannot be encoded: %v", rv.Type(), tc.problems)
	}
	data, err := tc.encode(rv)
	if err != nil {
		return nil, err
	}
	return &pb.Payload{ContentType: tc.contentType, Manifest: tc.manifest, Data: data}, nil
}

// kindOf is the kind of the component this service declares under id.
func (c *Client) kindOf(id string) (pb.Kind, error) {
	if c.decls != nil {
		for _, comp := range c.decls.components {
			if comp.GetId() == id {
				return comp.GetKind(), nil
			}
		}
		return 0, fmt.Errorf("this service declares no component %q", id)
	}
	return 0, fmt.Errorf("the client cannot tell what kind of component %q is; call through a service's own client", id)
}

func handlerKey(componentID, wire string) string { return componentID + "\x00" + wire }

// ApprovalAwaitedError is an agent's turn waiting for a person's decision (1.11).
type ApprovalAwaitedError struct{ Requests []ApprovalRequest }

func (e *ApprovalAwaitedError) Error() string {
	return fmt.Sprintf("the agent's turn waits for %d approval(s)", len(e.Requests))
}

// ApprovalRequest is a tool call waiting for a person's decision.
type ApprovalRequest struct {
	ID            string
	Tool          string
	ArgumentsJSON string
	RequestedAt   time.Time
	ExpiresAt     *time.Time
}

func approvalsOf(a *pb.ApprovalAwaited) *ApprovalAwaitedError {
	out := &ApprovalAwaitedError{}
	for _, r := range a.GetRequests() {
		req := ApprovalRequest{ID: r.GetId(), Tool: r.GetTool(), ArgumentsJSON: r.GetArgumentsJson(), RequestedAt: time.UnixMilli(r.GetRequestedAtMillis()).UTC()}
		if r.ExpiresAtMillis != nil {
			t := time.UnixMilli(r.GetExpiresAtMillis()).UTC()
			req.ExpiresAt = &t
		}
		out.Requests = append(out.Requests, req)
	}
	return out
}

// decodeReply decodes an invocation's answer as R.
func decodeReply[R any](reply *pb.InvokeReply) (R, error) {
	var zero R
	switch r := reply.GetResult().(type) {
	case *pb.InvokeReply_Reply:
		return valueOf(CodecFor[R](), r.Reply.GetPayload())
	case *pb.InvokeReply_Error:
		return zero, errorFromPb(r.Error)
	case *pb.InvokeReply_Approval:
		return zero, approvalsOf(r.Approval)
	}
	return zero, errors.New("the sidecar answered nothing")
}

// Invoke calls the handler wire of the entity, workflow or agent componentID (the instance entityID)
// with input, and answers its reply. A refusal is a *RefusalError with the callee's code.
func Invoke[R any](ctx context.Context, componentID, entityID, wire string, input any) (R, error) {
	var zero R
	reply, err := invoke(ctx, componentID, entityID, wire, input)
	if err != nil {
		return zero, err
	}
	return decodeReply[R](reply)
}

func (c *Client) invokeRequest(ctx context.Context, componentID, entityID, wire string, input any) (*pb.InvokeRequest, error) {
	kind, err := c.kindOf(componentID)
	if err != nil {
		return nil, err
	}
	payload, err := c.encodeInput(componentID, wire, input)
	if err != nil {
		return nil, fmt.Errorf("the input to %s %s cannot be encoded: %w", componentID, wire, err)
	}
	return &pb.InvokeRequest{Kind: kind, ComponentId: componentID, EntityId: entityID, Name: wire, Payload: payload, Metadata: metadataToPb(MetadataFrom(ctx))}, nil
}

// InvokeStream calls a streaming agent handler and yields its tokens as they come. The sequence
// ends after the last token, or with the error that ended it.
func InvokeStream(ctx context.Context, componentID, entityID, wire string, input any) iter.Seq2[string, error] {
	return func(yield func(string, error) bool) {
		c, rpc, err := clientOf(ctx)
		if err != nil {
			yield("", err)
			return
		}
		req, err := c.invokeRequest(ctx, componentID, entityID, wire, input)
		if err != nil {
			yield("", err)
			return
		}
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		stream, err := rpc.InvokeStream(ctx, req)
		if err != nil {
			yield("", tooOld("a streamed call", "1.0", err))
			return
		}
		for {
			tok, err := stream.Recv()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					yield("", tooOld("a streamed call", "1.0", err))
				}
				return
			}
			switch t := tok.GetToken().(type) {
			case *pb.StreamToken_Text:
				if !yield(t.Text, nil) {
					return
				}
			case *pb.StreamToken_Completed:
				return
			case *pb.StreamToken_Failed:
				yield("", errorFromPb(t.Failed))
				return
			case *pb.StreamToken_Approval:
				yield("", approvalsOf(t.Approval))
				return
			}
		}
	}
}

// SendAndAwait sends wire to the workflow workflowID (instance id) and waits up to within for the
// workflow to end, answering its final state (1.18). A workflow that failed is a *RefusalError with
// code WorkflowFailed and its step and reason in Details; one still running after within is a
// refusal with code Timeout, and the workflow runs on.
func SendAndAwait[R any](ctx context.Context, workflowID, id, wire string, input any, within time.Duration) (R, error) {
	var zero R
	reply, err := invoke(ctx, workflowID, id, wire, input)
	if err != nil {
		return zero, err
	}
	switch r := reply.GetResult().(type) {
	case *pb.InvokeReply_Error:
		return zero, errorFromPb(r.Error)
	case *pb.InvokeReply_Approval:
		return zero, approvalsOf(r.Approval)
	}
	return AwaitEnd[R](ctx, workflowID, id, within)
}

func invoke(ctx context.Context, componentID, entityID, wire string, input any) (*pb.InvokeReply, error) {
	c, rpc, err := clientOf(ctx)
	if err != nil {
		return nil, err
	}
	req, err := c.invokeRequest(ctx, componentID, entityID, wire, input)
	if err != nil {
		return nil, err
	}
	reply, err := rpc.Invoke(ctx, req)
	if err != nil {
		return nil, tooOld("a call", "1.0", err)
	}
	return reply, nil
}

// AwaitEnd waits up to within for the workflow workflowID (instance id) to end and answers its final
// state (1.18).
func AwaitEnd[R any](ctx context.Context, workflowID, id string, within time.Duration) (R, error) {
	var zero R
	if within <= 0 {
		return zero, errors.New("a wait for a workflow's end needs a time limit above zero")
	}
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return zero, err
	}
	reply, err := rpc.AwaitEnd(ctx, &pb.AwaitEndRequest{ComponentId: workflowID, EntityId: id, TimeoutMillis: within.Milliseconds(), Metadata: metadataToPb(MetadataFrom(ctx))})
	if err != nil {
		return zero, tooOld("waiting for a workflow's end", "1.18", err)
	}
	return decodeReply[R](reply)
}

// ViewQuery names what a view is asked: one of its declared queries with its values, or "all".
type ViewQuery struct {
	Name   string
	Values map[string]string
	// Limit is the most rows to read; 0 is the runtime's 1000. A stream ignores it unless set.
	Limit uint32
}

// All is the query of every row.
func All() ViewQuery { return ViewQuery{Name: "all"} }

// Named is the declared query name with its values.
func Named(name string, values map[string]string) ViewQuery {
	return ViewQuery{Name: name, Values: values}
}

func (q ViewQuery) request(ctx context.Context, viewID string) *pb.QueryRequest {
	r := &pb.QueryRequest{ViewId: viewID, Name: q.Name, Values: q.Values, Metadata: metadataToPb(MetadataFrom(ctx))}
	if q.Limit > 0 {
		r.Limit = &q.Limit
	}
	return r
}

// QueryView asks the view viewID query q and answers its rows.
func QueryView[Row any](ctx context.Context, viewID string, q ViewQuery) ([]Row, error) {
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return nil, err
	}
	reply, err := rpc.Query(ctx, q.request(ctx, viewID))
	if err != nil {
		return nil, tooOld("a view query", "1.0", err)
	}
	return rowsOf[Row](reply)
}

// ViewRow is the row of the view viewID under key, and whether there is one.
func ViewRow[Row any](ctx context.Context, viewID, key string) (Row, bool, error) {
	var zero Row
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return zero, false, err
	}
	reply, err := rpc.Query(ctx, &pb.QueryRequest{ViewId: viewID, Name: "get", Payload: &pb.Payload{ContentType: TextContent, Manifest: "string", Data: []byte(key)}, Metadata: metadataToPb(MetadataFrom(ctx))})
	if err != nil {
		return zero, false, tooOld("a view query", "1.0", err)
	}
	rows, err := rowsOf[Row](reply)
	if err != nil || len(rows) == 0 {
		return zero, false, err
	}
	return rows[0], true, nil
}

func rowsOf[Row any](reply *pb.QueryReply) ([]Row, error) {
	switch r := reply.GetResult().(type) {
	case *pb.QueryReply_Rows:
		var docs []json.RawMessage
		if err := json.Unmarshal(r.Rows.GetData(), &docs); err != nil {
			return nil, fmt.Errorf("the view answered something other than a JSON array: %w", err)
		}
		codec := CodecFor[Row]()
		out := make([]Row, 0, len(docs))
		for _, d := range docs {
			row, err := codec.Decode(d)
			if err != nil {
				return nil, err
			}
			out = append(out, row)
		}
		return out, nil
	case *pb.QueryReply_Error:
		return nil, errorFromPb(r.Error)
	}
	return nil, errors.New("the sidecar answered nothing")
}

// QueryStream asks the view viewID query q and yields its rows as the database gives them (1.17).
func QueryStream[Row any](ctx context.Context, viewID string, q ViewQuery) iter.Seq2[Row, error] {
	return func(yield func(Row, error) bool) {
		var zero Row
		_, rpc, err := clientOf(ctx)
		if err != nil {
			yield(zero, err)
			return
		}
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		stream, err := rpc.QueryStream(ctx, q.request(ctx, viewID))
		if err != nil {
			yield(zero, tooOld("a query stream", "1.17", err))
			return
		}
		codec := CodecFor[Row]()
		for {
			f, err := stream.Recv()
			if err != nil {
				if !errors.Is(err, io.EOF) {
					yield(zero, tooOld("a query stream", "1.17", err))
				}
				return
			}
			switch fr := f.GetFrame().(type) {
			case *pb.RowFrame_Row:
				row, err := valueOf(codec, fr.Row.GetPayload())
				if !yield(row, err) || err != nil {
					return
				}
			case *pb.RowFrame_Failed:
				yield(zero, errorFromPb(fr.Failed))
				return
			}
		}
	}
}

// WatchEvent is one element of a watch: a row now matching (Removed false), a row no longer
// matching (Removed true, Row zero), or CaughtUp once the rows there were when it began are given.
type WatchEvent[Row any] struct {
	Key      string
	Row      Row
	Removed  bool
	CaughtUp bool
}

// WatchEndedError is a watch that ended for Reason: rebuilt, instance-stopping, listener-lost or
// unread. Watch again for the rows now.
type WatchEndedError struct{ Reason string }

func (e *WatchEndedError) Error() string { return "the watch ended: " + e.Reason }

// WatchTarget is what a watch follows: a declared query declared watched, or one row by key.
type WatchTarget struct {
	query *ViewQuery
	key   string
}

// WatchQuery follows the declared query name with its values.
func WatchQuery(name string, values map[string]string) WatchTarget {
	return WatchTarget{query: &ViewQuery{Name: name, Values: values}}
}

// WatchKey follows the row under key.
func WatchKey(key string) WatchTarget { return WatchTarget{key: key} }

// Watch follows target in the view viewID (1.17): the rows now, CaughtUp, then each change, until
// ctx ends or the watch does (a *WatchEndedError).
func Watch[Row any](ctx context.Context, viewID string, target WatchTarget) iter.Seq2[WatchEvent[Row], error] {
	return func(yield func(WatchEvent[Row], error) bool) {
		_, rpc, err := clientOf(ctx)
		if err != nil {
			yield(WatchEvent[Row]{}, err)
			return
		}
		req := &pb.WatchRequest{ViewId: viewID, Metadata: metadataToPb(MetadataFrom(ctx))}
		if target.query != nil {
			req.Target = &pb.WatchRequest_Named{Named: &pb.Named{Name: target.query.Name, Values: target.query.Values}}
		} else {
			req.Target = &pb.WatchRequest_Key{Key: target.key}
		}
		ctx, cancel := context.WithCancel(ctx)
		defer cancel()
		stream, err := rpc.Watch(ctx, req)
		if err != nil {
			yield(WatchEvent[Row]{}, tooOld("a watch", "1.17", err))
			return
		}
		codec := CodecFor[Row]()
		for {
			f, err := stream.Recv()
			if err != nil {
				if !errors.Is(err, io.EOF) && ctx.Err() == nil {
					yield(WatchEvent[Row]{}, tooOld("a watch", "1.17", err))
				}
				return
			}
			var ev WatchEvent[Row]
			switch fr := f.GetFrame().(type) {
			case *pb.RowFrame_Row:
				row, err := valueOf(codec, fr.Row.GetPayload())
				if err != nil {
					yield(ev, err)
					return
				}
				ev = WatchEvent[Row]{Key: fr.Row.GetKey(), Row: row}
			case *pb.RowFrame_Removed:
				ev = WatchEvent[Row]{Key: fr.Removed.GetKey(), Removed: true}
			case *pb.RowFrame_CaughtUp:
				ev = WatchEvent[Row]{CaughtUp: true}
			case *pb.RowFrame_Ended:
				yield(ev, &WatchEndedError{Reason: fr.Ended.GetReason()})
				return
			case *pb.RowFrame_Failed:
				yield(ev, errorFromPb(fr.Failed))
				return
			default:
				continue
			}
			if !yield(ev, nil) {
				return
			}
		}
	}
}
