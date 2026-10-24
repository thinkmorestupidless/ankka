package server

import (
	"context"
	"errors"
	"io"
	"sync"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

// HttpHost runs the process's routes. Handle answers a request route; an error is the request's
// failure, which the sidecar answers 500. Stream runs a streaming route, sending its frames; its
// error ends the stream failed. Socket runs a socket route for as long as the handler runs.
type HttpHost struct {
	Handle func(ctx context.Context, req *pb.HttpRequest) (*pb.HttpResponse, error)
	Stream func(ctx context.Context, req *pb.HttpRequest, send func(*pb.StreamFrame) error) error
	Socket func(ctx context.Context, open *pb.HttpRequest, conn *SocketConn) error
}

type httpServer struct {
	pb.UnimplementedHttpServer
	host HttpHost
	opts Options
}

// RegisterHttp serves the process's routes on s.
func RegisterHttp(s grpc.ServiceRegistrar, host HttpHost, opts Options) {
	pb.RegisterHttpServer(s, &httpServer{host: host, opts: opts})
}

func (s *httpServer) Handle(ctx context.Context, req *pb.HttpRequest) (*pb.HttpReply, error) {
	ctx = s.opts.scope(ctx)
	res, err := GuardValue(func() (*pb.HttpResponse, error) { return s.host.Handle(ctx, req) })
	if err != nil {
		s.opts.log().Error("ankka: a route failed", "endpoint", req.GetEndpointId(), "route", req.GetRouteId(), "error", err)
		return &pb.HttpReply{Message: &pb.HttpReply_Failure{Failure: &pb.Failure{Error: ErrorOf(err)}}}, nil
	}
	return &pb.HttpReply{Message: &pb.HttpReply_Response{Response: res}}, nil
}

// HandleStream sends the route's frames as it makes them, then completed, or failed with its error.
func (s *httpServer) HandleStream(req *pb.HttpRequest, stream grpc.ServerStreamingServer[pb.StreamFrame]) error {
	if s.host.Stream == nil {
		return status.Error(codes.NotFound, "this process declares no streaming route")
	}
	ctx := s.opts.scope(stream.Context())
	err := Guard(func() error { return s.host.Stream(ctx, req, stream.Send) })
	if err != nil {
		if ctx.Err() != nil {
			return nil // the caller went away; nobody reads a failure
		}
		s.opts.log().Warn("ankka: a stream failed", "endpoint", req.GetEndpointId(), "route", req.GetRouteId(), "error", err)
		return stream.Send(&pb.StreamFrame{Frame: &pb.StreamFrame_Failed{Failed: ErrorOf(err)}})
	}
	return stream.Send(&pb.StreamFrame{Frame: &pb.StreamFrame_Completed{Completed: &pb.Empty{}}})
}

// ErrSocketClosed is what sending on a closed socket answers.
var ErrSocketClosed = errors.New("the socket is closed")

// SocketConn is one open socket as the handler's side sees it: the frames the client sent, in
// order, and a way to send it frames. Frames ends when the socket closes.
type SocketConn struct {
	frames chan string
	mu     sync.Mutex
	send   func(*pb.SocketOut) error
	done   bool
	reason string
	ctx    context.Context
}

// Receive is the next frame the client sent; io.EOF once the socket is closed and every frame
// before the close has been received.
func (c *SocketConn) Receive() (string, error) {
	select {
	case f, ok := <-c.frames:
		if !ok {
			return "", io.EOF
		}
		return f, nil
	case <-c.ctx.Done():
		// The frames are closed before the context ends, so what is left is read first.
		select {
		case f, ok := <-c.frames:
			if ok {
				return f, nil
			}
			return "", io.EOF
		default:
			return "", c.ctx.Err()
		}
	}
}

// Send sends the client a text frame.
func (c *SocketConn) Send(text string) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.done {
		return ErrSocketClosed
	}
	return c.send(&pb.SocketOut{Message: &pb.SocketOut_Frame{Frame: &pb.SocketFrame{Kind: &pb.SocketFrame_Text{Text: text}}}})
}

// Reason is why the socket was closed: a close reason's word, or "client"; "" while it is open.
func (c *SocketConn) Reason() string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reason
}

func (c *SocketConn) finish(out *pb.SocketOut) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.done = true
	return c.send(out)
}

// HandleSocket runs the socket's handler from its open to its return, relaying frames both ways.
// The handler's context ends when the socket closes; the socket closes when the handler returns.
func (s *httpServer) HandleSocket(stream grpc.BidiStreamingServer[pb.SocketIn, pb.SocketOut]) error {
	first, err := stream.Recv()
	if err != nil {
		return err
	}
	open := first.GetOpen()
	if open == nil {
		return status.Error(codes.InvalidArgument, "a socket's stream begins with its open")
	}
	if s.host.Socket == nil {
		return status.Error(codes.NotFound, "this process declares no socket route")
	}
	ctx, cancel := context.WithCancel(s.opts.scope(stream.Context()))
	defer cancel()
	conn := &SocketConn{frames: make(chan string, 64), send: stream.Send, ctx: ctx}
	go func() {
		defer cancel()
		defer close(conn.frames)
		for {
			in, err := stream.Recv()
			if err != nil {
				conn.closeWith("going away")
				return
			}
			switch m := in.GetMessage().(type) {
			case *pb.SocketIn_Frame:
				select {
				case conn.frames <- m.Frame.GetText():
				case <-ctx.Done():
					return
				}
			case *pb.SocketIn_Closed:
				conn.closeWith(m.Closed.GetReason())
				return
			default:
				conn.closeWith("failed")
				return
			}
		}
	}()
	err = Guard(func() error { return s.host.Socket(ctx, open, conn) })
	if err != nil && !(errors.Is(err, context.Canceled) && conn.Reason() != "") {
		s.opts.log().Warn("ankka: a socket's handler failed", "endpoint", open.GetEndpointId(), "route", open.GetRouteId(), "error", err)
		return conn.finish(&pb.SocketOut{Message: &pb.SocketOut_Failed{Failed: ErrorOf(err)}})
	}
	return conn.finish(&pb.SocketOut{Message: &pb.SocketOut_Completed{Completed: &pb.Empty{}}})
}

func (c *SocketConn) closeWith(reason string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.reason == "" {
		c.reason = reason
	}
}
