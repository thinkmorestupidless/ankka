package conformance

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"iter"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/examples/shopping-cart/cart"
)

func notFound(format string, args ...any) error {
	return &ankka.HttpProblem{Status: http.StatusNotFound, Message: fmt.Sprintf(format, args...)}
}

func badRequest(format string, args ...any) error {
	return &ankka.HttpProblem{Status: http.StatusBadRequest, Message: fmt.Sprintf(format, args...)}
}

// ── /joined: either side of the keyed view, and its rows ──

// Joined records on either side of the keyed view, and reads its rows.
var Joined = ankka.NewEndpoint("/joined", ankka.AllowAll(),
	// The left entity is the row's own key: one left per row.
	ankka.Post("/left/{key}/{holding}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (string, error) {
		return ankka.Invoke[string](ctx, "joined-left", req.Param("key"), "record", req.Param("key")+"|"+req.Param("holding"))
	}),
	ankka.Post("/right/{rightId}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (string, error) {
		return ankka.Invoke[string](ctx, "joined-right", req.Param("rightId"), "record", "")
	}),
	ankka.Get("/rows/{key}", func(ctx context.Context, req *ankka.Request) (JoinedRow, error) {
		row, found, err := ankka.ViewRow[JoinedRow](ctx, "joined-rows", req.Param("key"))
		if err == nil && !found {
			err = notFound("no row '%s'", req.Param("key"))
		}
		return row, err
	}),
)

// ── /vehicles: the replicated vehicle ──

func vehicleCall(wire string) func(context.Context, *ankka.Request, ankka.Unit) (string, error) {
	return func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (string, error) {
		return ankka.Invoke[string](ctx, "replicated-vehicle", req.Param("vehicleId"), wire, req.Param("target"))
	}
}

// Vehicles drives the replicated vehicle: a step, a read, and the three replication changes.
var Vehicles = ankka.NewEndpoint("/vehicles", ankka.AllowAll(),
	ankka.Post("/{vehicleId}/steps", func(ctx context.Context, req *ankka.Request, step string) (VehiclePlacement, error) {
		return ankka.Invoke[VehiclePlacement](ctx, "replicated-vehicle", req.Param("vehicleId"), "add-step", step)
	}),
	ankka.Get("/{vehicleId}", func(ctx context.Context, req *ankka.Request) (VehicleRecord, error) {
		return ankka.Invoke[VehicleRecord](ctx, "replicated-vehicle", req.Param("vehicleId"), "get", ankka.Unit{})
	}),
	ankka.Post("/{vehicleId}/hand-over/{target}", vehicleCall("hand-over")),
	ankka.Post("/{vehicleId}/replicate-to/{target}", vehicleCall("replicate-to")),
	ankka.Post("/{vehicleId}/stop-replicating-to/{target}", vehicleCall("stop-replicating-to")),
)

// ── /tree: a tree, walked, streamed and watched ──

// Tree places nodes of a tree and asks what is under one.
var Tree = ankka.NewEndpoint("/tree", ankka.AllowAll(),
	ankka.Post("/{nodeId}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (string, error) {
		return ankka.Invoke[string](ctx, "tree-node", req.Param("nodeId"), "place", "")
	}),
	ankka.Post("/{nodeId}/under/{parentId}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (string, error) {
		return ankka.Invoke[string](ctx, "tree-node", req.Param("nodeId"), "place", req.Param("parentId"))
	}),
	ankka.Get("/{nodeId}/below", func(ctx context.Context, req *ankka.Request) ([]string, error) {
		rows, err := ankka.QueryView[TreeRow](ctx, "tree-rows", ankka.Named("under", map[string]string{"row": req.Param("nodeId")}))
		keys := make([]string, 0, len(rows))
		for _, r := range rows {
			keys = append(keys, r.Key)
		}
		return keys, err
	}),
	// docs:start stream
	// Every row's key, as the database yields the rows: one event each, then the end.
	ankka.SSE("/streamed", func(ctx context.Context, _ *ankka.Request) iter.Seq2[string, error] {
		return func(yield func(string, error) bool) {
			for row, err := range ankka.QueryStream[TreeRow](ctx, "tree-rows", ankka.Named("all-rows", nil)) {
				if !yield(row.Key, err) || err != nil {
					return
				}
			}
		}
	}),
	// docs:end stream
	// docs:start watch
	// The rows now, caught-up, then each row as it is written, as named events.
	ankka.SSEEvents("/watched", func(ctx context.Context, _ *ankka.Request) iter.Seq2[ankka.SSEEvent, error] {
		return ankka.WatchEvents(ankka.Watch[TreeRow](ctx, "tree-rows", ankka.WatchQuery("all-rows", nil)))
	}),
	// docs:end watch
)

// ── /conformance: the protocol's edge cases, as every reference answers them ──

// Echo is what /conformance/echo answers: the query's repeated a, its b if any, and two headers.
type Echo struct {
	A       []string          `json:"a"`
	B       *string           `json:"b"`
	Headers map[string]string `json:"headers"`
}

// ServiceCallRecord is what a call to another service came to: outcome is response, failed,
// unresolvable, mismatch, unanswered or refused; answer is the X-Answer header the other set.
type ServiceCallRecord struct {
	Outcome     string `json:"outcome"`
	Status      int    `json:"status"`
	ContentType string `json:"contentType"`
	Body        string `json:"body"`
	Answer      string `json:"answer"`
	Message     string `json:"message"`
}

// StorageRecord is what a /conformance/storage call did: outcome ok, absent or refused, a
// refusal's code word and message, an object's content type, text and length, a listing's names,
// and an address.
type StorageRecord struct {
	Outcome     string   `json:"outcome"`
	Code        string   `json:"code"`
	Message     string   `json:"message"`
	ContentType string   `json:"contentType"`
	Text        string   `json:"text"`
	Length      int64    `json:"length"`
	Names       []string `json:"names"`
	URL         string   `json:"url"`
}

func storageRecord(outcome string) StorageRecord {
	return StorageRecord{Outcome: outcome, Names: []string{}}
}

// socketLog is what the socket routes' handlers noticed: closed:<room> when a /socket/{room}
// handler was told its socket closed, private:<subject> when the authenticated one ran.
var socketLog struct {
	sync.Mutex
	lines []string
}

func logSocket(line string) {
	socketLog.Lock()
	defer socketLog.Unlock()
	socketLog.lines = append(socketLog.lines, line)
}

func callerWord(c ankka.Caller) string {
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

// serviceCall makes the call the case asks for: service, method and path from the query, the body
// and every X-Conformance-* header sent on, and two headers no handler may send, the caller's and
// the host, added to show they never arrive.
func serviceCall(ctx context.Context, req *ankka.Request, body string) (ServiceCallRecord, error) {
	q := req.Query
	headers := http.Header{}
	for name, values := range req.Headers {
		if strings.HasPrefix(strings.ToLower(name), "x-conformance-") {
			headers[name] = values
		}
	}
	headers.Set("X-Ankka-Caller", "ankka://elsewhere/impostor")
	headers.Set("Host", "elsewhere")
	call := ankka.ServiceRequest{Method: q.Get("method"), Path: q.Get("path"), Headers: headers}
	typed := q.Get("mode") == "typed"
	if typed {
		call.Method = http.MethodGet
	} else if body != "" {
		call.Body, call.ContentType = []byte(body), req.ContentType
	}
	res, err := ankka.CallService(ctx, q.Get("service"), call)
	var failed *ankka.ServiceError
	switch {
	case errors.As(err, &failed):
		outcome := map[ankka.ServiceFailureReason]string{
			ankka.ServiceUnresolvable: "unresolvable", ankka.ServiceIdentityMismatch: "mismatch", ankka.ServiceUnanswered: "unanswered",
		}[failed.Reason]
		return ServiceCallRecord{Outcome: outcome, Message: failed.Error()}, nil
	case err != nil:
		if r, ok := ankka.AsRefusal(err); ok {
			return ServiceCallRecord{Outcome: "refused", Message: r.Message}, nil
		}
		return ServiceCallRecord{}, err
	}
	// The typed client answers a body for a success and fails on any other status.
	if typed {
		if res.Status < 200 || res.Status > 299 {
			return ServiceCallRecord{Outcome: "failed", Status: res.Status, Body: string(res.Body)}, nil
		}
		return ServiceCallRecord{Outcome: "response", Status: 200, Body: string(res.Body)}, nil
	}
	return ServiceCallRecord{
		Outcome: "response", Status: res.Status, ContentType: res.ContentType, Body: string(res.Body),
		Answer: res.Headers.Get("X-Answer"),
	}, nil
}

// storageCall runs f on the bucket the request names (absent: the unnamed bucket), and answers
// every refusal as a record rather than a status.
func storageCall(ctx context.Context, req *ankka.Request, f func(ankka.Bucket) (StorageRecord, error)) (StorageRecord, error) {
	refused := func(err error) (StorageRecord, error) {
		if r, ok := ankka.AsRefusal(err); ok {
			out := storageRecord("refused")
			out.Code, out.Message = r.Code.String(), r.Message
			return out, nil
		}
		return StorageRecord{}, err
	}
	store := ankka.StorageFrom(ctx)
	if store == nil {
		return refused(ankka.Refuse(ankka.Unavailable, "this service has no object storage"))
	}
	var bucket ankka.Bucket
	var err error
	if name := req.Query.Get("bucket"); req.Query.Has("bucket") {
		bucket, err = store.Bucket(name)
	} else {
		bucket, err = store.Default()
	}
	if err != nil {
		return refused(err)
	}
	out, err := f(bucket)
	if err != nil {
		return refused(err)
	}
	return out, nil
}

func storagePut(ctx context.Context, req *ankka.Request, body string) (StorageRecord, error) {
	return storageCall(ctx, req, func(b ankka.Bucket) (StorageRecord, error) {
		switch req.Query.Get("rules") {
		case "":
			contentType := req.ContentType
			if contentType == "" {
				contentType = "application/octet-stream"
			}
			if err := b.Put(ctx, req.Query.Get("name"), strings.NewReader(body), int64(len(body)), contentType); err != nil {
				return StorageRecord{}, err
			}
		case "prefix":
			for _, err := range b.List(ctx, body) {
				if err != nil {
					return StorageRecord{}, err
				}
				break
			}
		default:
			if _, err := b.Stat(ctx, body); err != nil {
				return StorageRecord{}, err
			}
		}
		return storageRecord("ok"), nil
	})
}

func storageGet(ctx context.Context, req *ankka.Request) (StorageRecord, error) {
	return storageCall(ctx, req, func(b ankka.Bucket) (StorageRecord, error) {
		name, out := req.Query.Get("name"), storageRecord("ok")
		switch op := req.Query.Get("op"); op {
		case "get":
			obj, err := b.Get(ctx, name)
			if err != nil || obj == nil {
				return storageRecord("absent"), err
			}
			data, err := io.ReadAll(obj.Body)
			if err != nil {
				return StorageRecord{}, err
			}
			out.ContentType, out.Text, out.Length = obj.ContentType, string(data), int64(len(data))
		case "stat":
			info, err := b.Stat(ctx, name)
			if err != nil || info == nil {
				return storageRecord("absent"), err
			}
			out.ContentType, out.Length = info.ContentType, info.Length
		case "list":
			for e, err := range b.List(ctx, req.Query.Get("prefix")) {
				if err != nil {
					return StorageRecord{}, err
				}
				out.Names = append(out.Names, e.Name)
			}
		case "url":
			url, err := b.PublicURL(name)
			if err != nil {
				return StorageRecord{}, err
			}
			out.URL = url
		case "signed-url":
			access := ankka.AccessRead
			if req.Query.Get("access") == "keep" {
				access = ankka.AccessKeep
			}
			url, err := b.SignedURL(name, access, 0)
			if err != nil {
				return StorageRecord{}, err
			}
			out.URL = url
		default:
			out = storageRecord("refused")
			out.Code, out.Message = ankka.BadRequest.String(), fmt.Sprintf("no storage op '%s'", op)
		}
		return out, nil
	})
}

// outcomeOf renders a turn as every reference does: {"answered": text}, or
// {"awaiting": [{"id", "tool", "arguments"}]} for the approval requests it waits on.
func outcomeOf(text string, err error) (string, error) {
	var waiting *ankka.ApprovalAwaitedError
	if errors.As(err, &waiting) {
		type request struct {
			ID        string          `json:"id"`
			Tool      string          `json:"tool"`
			Arguments json.RawMessage `json:"arguments"`
		}
		out := struct {
			Awaiting []request `json:"awaiting"`
		}{Awaiting: []request{}}
		for _, r := range waiting.Requests {
			args := r.ArgumentsJSON
			if args == "" {
				args = "{}"
			}
			out.Awaiting = append(out.Awaiting, request{r.ID, r.Tool, json.RawMessage(args)})
		}
		data, err := json.Marshal(out)
		return string(data), err
	}
	if err != nil {
		return "", err
	}
	data, err := json.Marshal(map[string]string{"answered": text})
	return string(data), err
}

// decisionOf reads {"approved": bool, "by": name, "note": text}, as the suite sends it.
func decisionOf(body string) (ankka.Decision, error) {
	var d struct {
		Approved bool   `json:"approved"`
		By       string `json:"by"`
		Note     string `json:"note"`
	}
	if err := json.Unmarshal([]byte(body), &d); err != nil {
		return ankka.Decision{}, badRequest("a decision cannot be read: %v", err)
	}
	return ankka.Decision{Approved: d.Approved, By: d.By, Note: d.Note}, nil
}

var tickTarget = ankka.TimerTarget{ComponentID: "reminder", Handler: "tick"}

// API is the routes the suite drives the conformance components through, the same in every
// language.
var API = ankka.NewEndpoint("/conformance", ankka.AllowAll(),
	// What the sidecar reported to this process, in order: a refusal at discovery among them.
	ankka.Get("/problems", func(context.Context, *ankka.Request) ([]string, error) {
		if s := reference.Load(); s != nil {
			return append([]string{}, s.Reported()...), nil
		}
		return []string{}, nil
	}),
	// docs:start service-call
	ankka.Post("/service-call", serviceCall),
	// docs:end service-call
	// The secret store. The name is a query parameter because it may hold a slash.
	ankka.Post("/secrets", func(ctx context.Context, req *ankka.Request, value string) (ankka.Done, error) {
		return ankka.Done{}, ankka.Secrets(ctx).Put(req.Query.Get("name"), value)
	}),
	ankka.Get("/secrets", func(ctx context.Context, req *ankka.Request) (string, error) {
		name := req.Query.Get("name")
		value, found, err := ankka.Secrets(ctx).Get(name)
		if err == nil && !found {
			err = notFound("no secret '%s'", name)
		}
		return value, err
	}),
	ankka.Delete("/secrets", func(ctx context.Context, req *ankka.Request) (ankka.Done, error) {
		return ankka.Done{}, ankka.Secrets(ctx).Delete(req.Query.Get("name"))
	}),
	// docs:start storage
	// The service's buckets. bucket names one (absent: the unnamed bucket) and name the object; op
	// is get, stat, list (under prefix), url or signed-url (access read or keep). A post keeps its
	// body under name; a post with rules set to name or prefix instead tries its body as an
	// object's name or a listing's prefix, and keeps nothing.
	ankka.Post("/storage", storagePut),
	ankka.Get("/storage", storageGet),
	ankka.Delete("/storage", func(ctx context.Context, req *ankka.Request) (StorageRecord, error) {
		return storageCall(ctx, req, func(b ankka.Bucket) (StorageRecord, error) {
			return storageRecord("ok"), b.Delete(ctx, req.Query.Get("name"))
		})
	}),
	// docs:end storage
	ankka.Get("/echo", func(_ context.Context, req *ankka.Request) (Echo, error) {
		out := Echo{A: append([]string{}, req.Query["a"]...), Headers: map[string]string{
			"x-one": req.Header("x-one"), "x-two": req.Header("x-two"),
		}}
		if req.Query.Has("b") {
			b := req.Query.Get("b")
			out.B = &b
		}
		return out, nil
	}),
	ankka.Get("/status/{code}", func(_ context.Context, req *ankka.Request) (string, error) {
		code, err := strconv.Atoi(req.Param("code"))
		if err != nil {
			return "", badRequest("%q is not a status", req.Param("code"))
		}
		return "", &ankka.HttpProblem{Status: code, Message: fmt.Sprintf("status %d as asked", code)}
	}),
	ankka.Get("/boom", func(context.Context, *ankka.Request) (string, error) {
		return "", errors.New("boom from the handler")
	}),
	// A route whose ACL differs from its endpoint's: /conformance admits everyone, this one nobody.
	ankka.Get("/closed", func(context.Context, *ankka.Request) (string, error) { return "never reached", nil }).ACL(ankka.DenyAll()),
	ankka.SSE("/stream/{session}", func(context.Context, *ankka.Request) iter.Seq2[string, error] {
		return func(yield func(string, error) bool) {
			for _, text := range []string{" leading space", "two\nlines", "plain"} {
				if !yield(text, nil) {
					return
				}
			}
		}
	}),
	// Sockets (protocol 1.9). Echoes each frame; "context" is answered with the room and the
	// opening request's tag, read after any number of frames.
	ankka.SocketRoute("/socket/{room}", func(_ context.Context, s *ankka.Socket) error {
		room := s.Request().Param("room")
		for {
			text, err := s.Receive()
			if err == io.EOF {
				logSocket("closed:" + room)
				return nil
			}
			if err != nil {
				return err
			}
			if text == "context" {
				text = room + " " + s.Request().Query.Get("tag")
			}
			if err := s.Send(text); err != nil {
				return err
			}
		}
	}),
	ankka.SocketRoute("/socket-once", func(_ context.Context, s *ankka.Socket) error {
		_, _ = s.Receive()
		return nil
	}),
	ankka.SocketRoute("/socket-fail", func(_ context.Context, s *ankka.Socket) error {
		_, _ = s.Receive()
		return errors.New("the socket handler broke")
	}),
	ankka.Get("/socket-log", func(context.Context, *ankka.Request) ([]string, error) {
		socketLog.Lock()
		defer socketLog.Unlock()
		return slices.Clone(append([]string{}, socketLog.lines...)), nil
	}),
	ankka.Post("/profile/{id}", func(ctx context.Context, req *ankka.Request, name string) (string, error) {
		return ankka.Invoke[string](ctx, "profile", req.Param("id"), "set", name)
	}),
	ankka.Get("/profile/{id}", func(ctx context.Context, req *ankka.Request) (string, error) {
		return ankka.Invoke[string](ctx, "profile", req.Param("id"), "get", ankka.Unit{})
	}),
	ankka.Delete("/profile/{id}", func(ctx context.Context, req *ankka.Request) (string, error) {
		return ankka.Invoke[string](ctx, "profile", req.Param("id"), "delete", ankka.Unit{})
	}),
	ankka.Post("/checkout/{id}", func(ctx context.Context, req *ankka.Request, mode string) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, "checkout", req.Param("id"), "start", mode)
	}),
	ankka.Get("/checkout/{id}", func(ctx context.Context, req *ankka.Request) (Checkout, error) {
		return ankka.Invoke[Checkout](ctx, "checkout", req.Param("id"), "status", ankka.Unit{})
	}),
	ankka.Get("/checkout/{id}/row", func(ctx context.Context, req *ankka.Request) (CheckoutRow, error) {
		row, found, err := ankka.ViewRow[CheckoutRow](ctx, "checkout-rows", req.Param("id"))
		if err == nil && !found {
			err = notFound("no row for '%s'", req.Param("id"))
		}
		return row, err
	}),
	ankka.Get("/checkout-rows/{standing}", func(ctx context.Context, req *ankka.Request) ([]string, error) {
		rows, err := ankka.QueryView[CheckoutRow](ctx, "checkout-rows", ankka.Named("by-standing", map[string]string{"standing": req.Param("standing")}))
		ids := make([]string, 0, len(rows))
		for _, r := range rows {
			ids = append(ids, r.ID)
		}
		return ids, err
	}),
	ankka.Get("/checkout/{id}/end", func(ctx context.Context, req *ankka.Request) (string, error) {
		end, err := ankka.Invoke[string](ctx, "profile", "end-"+req.Param("id"), "get", ankka.Unit{})
		if err == nil && end == "none" {
			err = notFound("'%s' has not ended", req.Param("id"))
		}
		return end, err
	}),
	// Starts a checkout and answers with how it ended, as one request; a failed one is 424.
	ankka.Post("/checkout-await/{id}", func(ctx context.Context, req *ankka.Request, mode string) (string, error) {
		end, err := ankka.SendAndAwait[Checkout](ctx, "checkout", req.Param("id"), "start", mode, 30*time.Second)
		if r, ok := ankka.AsRefusal(err); ok && r.Code == ankka.WorkflowFailed {
			return "", &ankka.HttpProblem{Status: 424, Message: r.Details["step"] + ": " + r.Details["reason"]}
		}
		return end.Status, err
	}),
	ankka.Post("/remind/{id}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		id := req.Param("id")
		return ankka.Done{}, ankka.Schedule(ctx, "remind-"+id, time.Second, ankka.TimerTarget{ComponentID: "reminder", Handler: "remind"}, id)
	}),
	// A recurring timer: due at once, then every second.
	ankka.Post("/recur/{id}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		id := req.Param("id")
		return ankka.Done{}, ankka.ScheduleRecurring(ctx, "recur-"+id, 0, time.Second, tickTarget, id)
	}),
	// The same timer set again, with a delay a replacement would be first due after.
	ankka.Post("/recur/{id}/again", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		id := req.Param("id")
		return ankka.Done{}, ankka.ScheduleRecurring(ctx, "recur-"+id, 60*time.Second, time.Second, tickTarget, id)
	}),
	ankka.Post("/recur/{id}/cancel", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		return ankka.Done{}, ankka.Cancel(ctx, "recur-"+req.Param("id"))
	}),
	// A period of zero is refused before anything is sent: a 400.
	ankka.Post("/recur-refused/{id}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		id := req.Param("id")
		return ankka.Done{}, ankka.ScheduleRecurring(ctx, "recur-"+id, 0, 0, tickTarget, id)
	}),
	ankka.Post("/ask/{session}", func(ctx context.Context, req *ankka.Request, question string) (string, error) {
		return ankka.Invoke[string](ctx, "assistant", req.Param("session"), "ask", question)
	}),
	ankka.Post("/ask-second/{session}", func(ctx context.Context, req *ankka.Request, question string) (string, error) {
		return ankka.Invoke[string](ctx, "assistant", req.Param("session"), "ask-second", question)
	}),
	// docs:start approver-routes
	// A turn that may wait: the model's answer, or the approval requests it waits on.
	ankka.Post("/approver/{session}", func(ctx context.Context, req *ankka.Request, question string) (string, error) {
		return outcomeOf(ankka.Invoke[string](ctx, "approver", req.Param("session"), "ask", question))
	}),
	// A person's decision, answered as the turn's caller would have been once the turn goes on.
	ankka.Post("/approver/{session}/decide/{id}", func(ctx context.Context, req *ankka.Request, body string) (string, error) {
		d, err := decisionOf(body)
		if err != nil {
			return "", err
		}
		return outcomeOf(ankka.DecideApproval[string](ctx, "approver", req.Param("session"), "ask", req.Param("id"), d))
	}),
	// docs:end approver-routes
	ankka.SSE("/stream-ask/{session}", func(ctx context.Context, req *ankka.Request) iter.Seq2[string, error] {
		return ankka.InvokeStream(ctx, "assistant", req.Param("session"), "stream", req.Query.Get("q"))
	}),
	ankka.Get("/{id}/count", func(ctx context.Context, req *ankka.Request) (int, error) {
		return ankka.Invoke[int](ctx, "conformance", req.Param("id"), "count", ankka.Unit{})
	}),
	// A handler that answers nothing: the call is not awaited, and the route says so with 204.
	ankka.Post("/{id}/no-reply", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		go func(ctx context.Context, id string) {
			_, _ = ankka.Invoke[ankka.Done](ctx, "conformance", id, "no-reply", "")
		}(context.WithoutCancel(ctx), req.Param("id"))
		return ankka.Done{}, nil
	}),
	// The generic forwarder: the body is the handler's input as text, the reply comes back as text.
	ankka.Post("/{id}/{handler}", func(ctx context.Context, req *ankka.Request, body string) (string, error) {
		handler := req.Param("handler")
		var input any = body
		if intInputs[handler] {
			n, err := strconv.Atoi(body)
			if err != nil {
				return "", ankka.Refusef(ankka.BadRequest, "%s takes a number, not %q", handler, body)
			}
			input = n
		}
		return ankka.Invoke[string](ctx, "conformance", req.Param("id"), handler, input)
	}),
)

// intInputs are the handlers whose input is a number: the forwarder reads their body as one.
var intInputs = map[string]bool{"record-many": true, "expire": true}

// ── /autonomous: tasks run, read and cancelled; instances driven and watched ──

func answerType(req *ankka.Request) error {
	if t := req.Param("taskType"); t != AnswerTask.Name() {
		return badRequest("no task type '%s'", t)
	}
	return nil
}

func jsonText(v any) (string, error) {
	data, err := json.Marshal(v)
	return string(data), err
}

// Autonomous drives the answerer.
var AutonomousAPI = ankka.NewEndpoint("/autonomous", ankka.AllowAll(),
	ankka.Post("/tasks/{taskType}", func(ctx context.Context, req *ankka.Request, instructions string) (string, error) {
		if err := answerType(req); err != nil {
			return "", err
		}
		id, err := ankka.RunSingleTask(ctx, Answerer.ID(), AnswerTask, instructions, ankka.TaskOptions{})
		if err != nil {
			return "", err
		}
		task, err := ankka.GetTask(ctx, AnswerTask, id)
		if err != nil {
			return "", err
		}
		instance := ""
		if task.Assignee != nil {
			instance = task.Assignee.InstanceID
		}
		return jsonText(map[string]string{"taskId": id, "instanceId": instance})
	}),
	ankka.Get("/tasks/{id}", func(ctx context.Context, req *ankka.Request) (string, error) {
		task, err := ankka.GetTask(ctx, AnswerTask, req.Param("id"))
		return string(task.Record), err
	}),
	ankka.Post("/tasks/{id}/cancel", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		return ankka.Done{}, ankka.CancelTask(ctx, req.Param("id"), "")
	}),
	// {"instructions": "...", "dependsOn": ["..."]}: creates without running.
	ankka.Post("/tasks/{taskType}/create", func(ctx context.Context, req *ankka.Request, body string) (string, error) {
		if err := answerType(req); err != nil {
			return "", err
		}
		var asked struct {
			Instructions string   `json:"instructions"`
			DependsOn    []string `json:"dependsOn"`
		}
		if err := json.Unmarshal([]byte(body), &asked); err != nil {
			return "", badRequest("%v", err)
		}
		id, err := ankka.CreateTask(ctx, AnswerTask, asked.Instructions, ankka.TaskOptions{DependsOn: asked.DependsOn})
		if err != nil {
			return "", err
		}
		return jsonText(map[string]string{"taskId": id})
	}),
	ankka.Post("/instances/{instance}/assign", func(ctx context.Context, req *ankka.Request, body string) (string, error) {
		var ids []string
		_ = json.Unmarshal([]byte(body), &ids)
		r, err := ankka.Instance(Answerer.ID(), req.Param("instance")).Assign(ctx, ids...)
		if err != nil {
			return "", err
		}
		return jsonText(map[string][]string{"accepted": append([]string{}, r.Accepted...)})
	}),
	ankka.Post("/instances/{instance}/{op}", func(ctx context.Context, req *ankka.Request, _ ankka.Unit) (ankka.Done, error) {
		calls := ankka.Instance(Answerer.ID(), req.Param("instance"))
		switch op := req.Param("op"); op {
		case "suspend":
			return ankka.Done{}, calls.Suspend(ctx)
		case "resume":
			return ankka.Done{}, calls.Resume(ctx)
		case "terminate":
			return ankka.Done{}, calls.Terminate(ctx)
		default:
			return ankka.Done{}, notFound("no operation '%s'", op)
		}
	}),
	// Each notification's JSON, as the runtime sent it.
	ankka.SSE("/instances/{instance}/notifications", func(ctx context.Context, req *ankka.Request) iter.Seq2[string, error] {
		return func(yield func(string, error) bool) {
			for n, err := range ankka.Instance(Answerer.ID(), req.Param("instance")).Notifications(ctx) {
				if err != nil {
					yield("", err)
					return
				}
				text, err := jsonText(n.Fields)
				if !yield(text, err) || err != nil {
					return
				}
			}
		}
	}),
	ankka.Post("/instances/{instance}/decide/{id}", func(ctx context.Context, req *ankka.Request, body string) (ankka.Done, error) {
		d, err := decisionOf(body)
		if err != nil {
			return ankka.Done{}, err
		}
		return ankka.Done{}, ankka.Instance(Answerer.ID(), req.Param("instance")).Decide(ctx, req.Param("id"), d)
	}),
	ankka.Get("/instances/{instance}/state", func(ctx context.Context, req *ankka.Request) (string, error) {
		st, err := ankka.Instance(Answerer.ID(), req.Param("instance")).State(ctx)
		if err != nil {
			return "", err
		}
		type awaiting struct {
			ID   string `json:"id"`
			Tool string `json:"tool"`
		}
		out := struct {
			Phase       string     `json:"phase"`
			Queued      []string   `json:"queued"`
			CurrentTask *string    `json:"currentTask,omitempty"`
			Awaiting    []awaiting `json:"awaiting"`
		}{Phase: string(st.Phase), Queued: append([]string{}, st.Queued...), Awaiting: []awaiting{}}
		if st.CurrentTask != "" {
			out.CurrentTask = &st.CurrentTask
		}
		for _, r := range st.Awaiting {
			out.Awaiting = append(out.Awaiting, awaiting{r.ID, r.Tool})
		}
		return jsonText(out)
	}),
)

// ── /callers: caller-naming ACLs ──

// Callers names callers through the local caller header, since every target runs outside a
// cluster, where this service's own identity is local/local.
var Callers = ankka.NewEndpoint("/callers", ankka.AllowIf(ankka.Internet(), ankka.ServiceNamed("orders")),
	ankka.Get("/whoami", func(ctx context.Context, _ *ankka.Request) (string, error) {
		return callerWord(ankka.CallerFrom(ctx)), nil
	}),
	ankka.Get("/self", func(context.Context, *ankka.Request) (string, error) { return "self", nil }).ACL(ankka.AllowIf(ankka.Self())),
	ankka.SSE("/events", func(context.Context, *ankka.Request) iter.Seq2[string, error] {
		return func(yield func(string, error) bool) { yield("tick", nil) }
	}).ACL(ankka.AllowIf(ankka.Self())),
	// Whoever the project granted this route to.
	ankka.Get("/granted", func(context.Context, *ankka.Request) (string, error) { return "granted", nil }).ACL(ankka.AllowIf(ankka.Granted())),
)

// ── /private: a verified token's principal ──

func sortedRoles(p *ankka.Principal) []string {
	roles := append([]string{}, p.Roles...)
	slices.Sort(roles)
	return roles
}

func orNull(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

// Private admits only a verified token from the suite's issuer, and says what the principal
// carried, so every language is held to the same answer.
var Private = ankka.NewEndpoint("/private", ankka.Authenticate(ankka.Issuer("test")),
	ankka.Get("/", func(context.Context, *ankka.Request) (string, error) { return "private", nil }),
	ankka.Get("/me", func(_ context.Context, req *ankka.Request) (string, error) {
		p := req.Principal
		tier, hasTier := p.Claims["tier"]
		out := struct {
			Subject string   `json:"subject"`
			Roles   []string `json:"roles"`
			Tier    *string  `json:"tier"`
			Issuer  *string  `json:"issuer"`
		}{Subject: p.Subject, Roles: sortedRoles(p), Issuer: orNull(p.Issuer)}
		if hasTier {
			out.Tier = &tier
		}
		return jsonText(out)
	}),
	// The principal and the caller, sent once when the socket opens.
	ankka.SocketRoute("/socket", func(_ context.Context, s *ankka.Socket) error {
		p := s.Request().Principal
		logSocket("private:" + p.Subject)
		text, err := jsonText(map[string]any{"subject": p.Subject, "roles": sortedRoles(p), "caller": callerWord(s.Request().Caller)})
		if err != nil {
			return err
		}
		if err := s.Send(text); err != nil {
			return err
		}
		for {
			if _, err := s.Receive(); err != nil {
				return nil
			}
		}
	}),
)

// ── /mcp and /mcp-keys: an MCP endpoint behind client keys or a token, and the keys ──

// CartArguments are get_cart's.
type CartArguments struct {
	CartID string `json:"cartId"`
}

// AddItemArguments are add_item's.
type AddItemArguments struct {
	CartID    string `json:"cartId"`
	ProductID string `json:"productId"`
	Quantity  int    `json:"quantity"`
}

// ToolCart is what get_cart answers: the cart, and who the tool was told it was for.
type ToolCart struct {
	Cart    cart.Cart         `json:"cart"`
	Subject *string           `json:"subject"`
	Claims  map[string]string `json:"claims"`
}

// docs:start mcp-endpoint

// CartMcp is the cart's MCP endpoint at /mcp. get_cart answers the principal beside the cart, so a
// case can see who a client key or a token made the caller, and that nothing in it reads as the key.
var CartMcp = ankka.NewMcpEndpoint("/mcp", ankka.AnyOf(ankka.AllowClientKeys(), ankka.Authenticate()),
	ankka.McpTool("get_cart", "Reads one cart", func(ctx context.Context, in CartArguments) (ToolCart, error) {
		c, err := ankka.Invoke[cart.Cart](ctx, cart.ComponentID, in.CartID, "get-cart", ankka.Unit{})
		out := ToolCart{Cart: c, Claims: map[string]string{}}
		if p := ankka.RequestFrom(ctx).Principal; p != nil {
			out.Subject = &p.Subject
			for k, v := range p.Claims {
				out.Claims[k] = v
			}
		}
		return out, err
	}),
	ankka.McpTool("add_item", "Adds an item to a cart", func(ctx context.Context, in AddItemArguments) (ankka.Done, error) {
		return ankka.Invoke[ankka.Done](ctx, cart.ComponentID, in.CartID, "add-item", cart.LineItem{ProductID: in.ProductID, Name: in.ProductID, Quantity: in.Quantity})
	}),
	ankka.McpCommandRef("checkout", "Checks a cart out", cart.ComponentID, "checkout", "cartId"),
)

// docs:end mcp-endpoint

// KeyRequest is what POST /mcp-keys is asked to issue: a key that never expires, for the cases.
type KeyRequest struct {
	Subject    string            `json:"subject"`
	Scope      []string          `json:"scope"`
	Attributes map[string]string `json:"attributes"`
}

// ListedKey is a key as the listing answers it: never the key.
type ListedKey struct {
	ID         string            `json:"id"`
	Subject    string            `json:"subject"`
	Attributes map[string]string `json:"attributes"`
	Label      *string           `json:"label"`
	Endpoint   string            `json:"endpoint"`
	Scope      []string          `json:"scope"`
}

// McpKeys is the reference's own routes over its client keys, as a product would write them.
var McpKeys = ankka.NewEndpoint("/mcp-keys", ankka.AllowAll(),
	ankka.Post("", func(ctx context.Context, _ *ankka.Request, asked KeyRequest) (map[string]string, error) {
		issued, err := ankka.ClientKeys(ctx).Issue(ankka.ClientKeyRequest{
			Subject: asked.Subject, Never: true, Scope: asked.Scope, Attributes: asked.Attributes,
		})
		return map[string]string{"id": issued.ID, "clientKey": issued.ClientKey}, err
	}),
	ankka.Get("", func(ctx context.Context, _ *ankka.Request) ([]ListedKey, error) {
		keys, err := ankka.ClientKeys(ctx).List()
		out := make([]ListedKey, 0, len(keys))
		for _, k := range keys {
			attrs := k.Attributes
			if attrs == nil {
				attrs = map[string]string{}
			}
			out = append(out, ListedKey{ID: k.ID, Subject: k.Subject, Attributes: attrs, Label: orNull(k.Label), Endpoint: k.Endpoint, Scope: append([]string{}, k.Scope...)})
		}
		return out, err
	}),
	ankka.Delete("/{id}", func(ctx context.Context, req *ankka.Request) (string, error) {
		return "revoked", ankka.ClientKeys(ctx).Revoke(req.Param("id"))
	}),
)
