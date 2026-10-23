package ankka

import (
	"context"
	"fmt"
	"iter"
	"net/http"

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
