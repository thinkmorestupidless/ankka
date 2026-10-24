package ankka

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"iter"
	"net/http"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
)

// Socket is an open socket of a socket route (1.9): Receive reads the frames the client sends, in
// order; Send reaches the client. The socket closes when the handler returns, and the handler's
// context ends when the client, or the platform, closes it.
type Socket struct {
	req     *Request
	receive func() (string, error)
	send    func(string) error
	reason  func() string
}

// Request is the request that opened the socket; it has no body.
func (s *Socket) Request() *Request { return s.req }

// Receive is the next text frame the client sent: io.EOF once the socket is closed and every frame
// before the close has been read.
func (s *Socket) Receive() (string, error) { return s.receive() }

// Send sends the client a text frame. After the socket closed it answers an error.
func (s *Socket) Send(text string) error { return s.send(text) }

// CloseReason is why the socket was closed ("client" for the client's own close, or a word: "going
// away", "too large", "unread", "not text", "failed"); "" while it is open.
func (s *Socket) CloseReason() string { return s.reason() }

// SocketRoute is a GET route at template answered by opening a socket (1.9): f runs for as long
// as the socket is open. Returning nil closes it "finished"; an error closes it "failed".
func SocketRoute(template string, f func(ctx context.Context, s *Socket) error) *Route {
	r := newRoute(http.MethodGet, template)
	r.kind = routeSocket
	r.socket = func(ctx context.Context, req *Request, s *Socket) error { return f(ctx, s) }
	return r
}

// SSE is a GET route streamed to the caller as server-sent events: each text f yields is one
// event, and an error ends the stream failed. The stream ends when the sequence does; the context
// ends when the caller goes away.
func SSE(template string, f func(ctx context.Context, req *Request) iter.Seq2[string, error]) *Route {
	r := newRoute(http.MethodGet, template)
	r.kind = routeStream
	r.stream = func(ctx context.Context, req *Request, send func(*pb.StreamFrame) error) error {
		for text, err := range f(ctx, req) {
			if err != nil {
				return err
			}
			if err := send(&pb.StreamFrame{Frame: &pb.StreamFrame_Text{Text: text}}); err != nil {
				return err
			}
		}
		return nil
	}
	return r
}

// SSEEvent is one frame of an SSEEvents route: with Name empty, Text sent as an SSE route sends it;
// otherwise an event under Name (one non-empty line) whose data is Data as JSON (1.11).
type SSEEvent struct {
	Name string
	Text string
	Data any
}

// SSEEvents is SSE whose frames may be named events beside text: an event a page listens for by
// name, such as WatchEvents' row, removed and caught-up.
func SSEEvents(template string, f func(ctx context.Context, req *Request) iter.Seq2[SSEEvent, error]) *Route {
	r := newRoute(http.MethodGet, template)
	r.kind = routeStream
	r.stream = func(ctx context.Context, req *Request, send func(*pb.StreamFrame) error) error {
		for e, err := range f(ctx, req) {
			if err != nil {
				return err
			}
			frame, err := frameOf(e)
			if err != nil {
				return err
			}
			if err := send(frame); err != nil {
				return err
			}
		}
		return nil
	}
	return r
}

func frameOf(e SSEEvent) (*pb.StreamFrame, error) {
	if e.Name == "" {
		return &pb.StreamFrame{Frame: &pb.StreamFrame_Text{Text: e.Text}}, nil
	}
	if strings.ContainsAny(e.Name, "\r\n") {
		return nil, fmt.Errorf("an event name must be one line, not %q", e.Name)
	}
	data, err := json.Marshal(e.Data)
	if err != nil {
		return nil, fmt.Errorf("the event %q cannot be written as JSON: %w", e.Name, err)
	}
	return &pb.StreamFrame{Frame: &pb.StreamFrame_Event{Event: &pb.SseEvent{Name: e.Name, Data: string(data)}}}, nil
}

// WatchEvents is a watch as server-sent events: each row as row carrying {key, row}, each removal
// as removed carrying {key}, the marker as caught-up, and a watch that ended with a reason as a last
// event ended carrying {reason}, so a page is told why.
func WatchEvents[Row any](watch iter.Seq2[WatchEvent[Row], error]) iter.Seq2[SSEEvent, error] {
	return func(yield func(SSEEvent, error) bool) {
		for e, err := range watch {
			var ended *WatchEndedError
			switch {
			case errors.As(err, &ended):
				yield(SSEEvent{Name: "ended", Data: map[string]string{"reason": ended.Reason}}, nil)
				return
			case err != nil:
				yield(SSEEvent{}, err)
				return
			}
			var out SSEEvent
			switch {
			case e.CaughtUp:
				out = SSEEvent{Name: "caught-up", Data: struct{}{}}
			case e.Removed:
				out = SSEEvent{Name: "removed", Data: map[string]string{"key": e.Key}}
			default:
				out = SSEEvent{Name: "row", Data: struct {
					Key string `json:"key"`
					Row Row    `json:"row"`
				}{e.Key, e.Row}}
			}
			if !yield(out, nil) {
				return
			}
		}
	}
}

// routeFor is the route a request names, admitted by its ACL, and the request its handler sees.
func (d *declarations) routeFor(in *pb.HttpRequest, kind routeKind) (*Route, *Request, error) {
	e, r := d.endpointDecls[in.GetEndpointId()], d.routes[in.GetEndpointId()][in.GetRouteId()]
	if e == nil || r == nil {
		return nil, nil, Refusef(NotFound, "no route %s %s", in.GetEndpointId(), in.GetRouteId())
	}
	if r.kind != kind {
		return nil, nil, fmt.Errorf("%s %s is not the kind of route this runtime asked for", e.prefix, r.describe)
	}
	req := requestOf(r, in)
	if ok, why := e.aclFor(r).admitsIssuer(req.Principal, e.issuers); !ok {
		return nil, nil, Refuse(Forbidden, why)
	}
	return r, req, nil
}

// handleStream runs one stream route, as Http.HandleStream and the endpoint test kit do.
func (d *declarations) handleStream(ctx context.Context, in *pb.HttpRequest, send func(*pb.StreamFrame) error) error {
	r, req, err := d.routeFor(in, routeStream)
	if err != nil {
		return err
	}
	return r.stream(withRequest(withMetadata(ctx, req.Metadata), req), req, send)
}

// handleSocket runs one socket route, as Http.HandleSocket does.
func (d *declarations) handleSocket(ctx context.Context, in *pb.HttpRequest, conn *server.SocketConn) error {
	r, req, err := d.routeFor(in, routeSocket)
	if err != nil {
		return err
	}
	s := &Socket{req: req, receive: conn.Receive, send: conn.Send, reason: conn.Reason}
	return r.socket(withRequest(withMetadata(ctx, req.Metadata), req), req, s)
}
