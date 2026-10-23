package ankka

import (
	"context"
	"errors"
	"io"
	"iter"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

var sEnded = make(chan string, 4)

var sEndpoint = NewEndpoint("/s", AllowAll(),
	SSE("/count/{n}", func(ctx context.Context, req *Request) iter.Seq2[string, error] {
		return func(yield func(string, error) bool) {
			for _, word := range []string{"one", "two", "three"} {
				if !yield(word, nil) {
					return
				}
				if req.Param("n") == "2" && word == "two" {
					yield("", Refuse(Conflict, "two is enough"))
					return
				}
			}
		}
	}),
	SocketRoute("/echo", func(ctx context.Context, s *Socket) error {
		for {
			text, err := s.Receive()
			if errors.Is(err, io.EOF) {
				sEnded <- "closed:" + s.CloseReason()
				return nil
			}
			if err != nil {
				return err
			}
			switch text {
			case "bye":
				return nil
			case "boom":
				return errors.New("the echo broke")
			case "panic":
				panic("the echo panicked")
			case "wait":
				<-ctx.Done()
				sEnded <- "context ended:" + s.CloseReason()
				return nil
			}
			if err := s.Send(s.Request().Path + ":" + text); err != nil {
				return err
			}
		}
	}),
)

func TestAnSSERouteStreamsItsEvents(t *testing.T) {
	svc := NewService(sEndpoint)
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	routes := svc.Spec().GetEndpoints()[0].GetRoutes()
	if !routes[0].GetStreaming() || routes[0].GetSocket() || !routes[1].GetSocket() || routes[1].GetMethod() != "GET" {
		t.Fatalf("routes %v", routes)
	}
	client := pb.NewHttpClient(loopback(t, svc))
	read := func(n string) []*pb.StreamFrame {
		t.Helper()
		stream, err := client.HandleStream(context.Background(), &pb.HttpRequest{EndpointId: "/s", RouteId: "GET /count/{n}", PathArgs: []string{n}})
		if err != nil {
			t.Fatal(err)
		}
		var out []*pb.StreamFrame
		for {
			f, err := stream.Recv()
			if err != nil {
				return out
			}
			out = append(out, f)
		}
	}
	all := read("3")
	if len(all) != 4 || all[0].GetText() != "one" || all[2].GetText() != "three" || all[3].GetCompleted() == nil {
		t.Errorf("a whole stream was %v", all)
	}
	cut := read("2")
	if len(cut) != 3 || cut[1].GetText() != "two" || cut[2].GetFailed().GetCode() != pb.ErrorCode_CONFLICT {
		t.Errorf("a failed stream was %v", cut)
	}
}

type sRow struct {
	N int `json:"n"`
}

func TestAnSSEEventsRouteSendsAWatchAsNamedEvents(t *testing.T) {
	watch := func(yield func(WatchEvent[sRow], error) bool) {
		_ = yield(WatchEvent[sRow]{Key: "a", Row: sRow{N: 1}}, nil) &&
			yield(WatchEvent[sRow]{CaughtUp: true}, nil) &&
			yield(WatchEvent[sRow]{Key: "a", Removed: true}, nil) &&
			yield(WatchEvent[sRow]{}, &WatchEndedError{Reason: "rebuilt"})
	}
	svc := NewService(NewEndpoint("/w", AllowAll(),
		SSEEvents("/rows", func(context.Context, *Request) iter.Seq2[SSEEvent, error] {
			return func(yield func(SSEEvent, error) bool) {
				if !yield(SSEEvent{Text: "before"}, nil) {
					return
				}
				for e, err := range WatchEvents(iter.Seq2[WatchEvent[sRow], error](watch)) {
					if !yield(e, err) {
						return
					}
				}
			}
		}),
		SSEEvents("/bad", func(context.Context, *Request) iter.Seq2[SSEEvent, error] {
			return func(yield func(SSEEvent, error) bool) { yield(SSEEvent{Name: "two\nlines"}, nil) }
		}),
	))
	if p := svc.Problems(); len(p) > 0 {
		t.Fatalf("problems: %v", p)
	}
	client := pb.NewHttpClient(loopback(t, svc))
	read := func(route string) []*pb.StreamFrame {
		t.Helper()
		stream, err := client.HandleStream(context.Background(), &pb.HttpRequest{EndpointId: "/w", RouteId: route})
		if err != nil {
			t.Fatal(err)
		}
		var out []*pb.StreamFrame
		for {
			f, err := stream.Recv()
			if err != nil {
				return out
			}
			out = append(out, f)
		}
	}
	got := read("GET /rows")
	want := []struct{ name, data string }{
		{"", "before"}, {"row", `{"key":"a","row":{"n":1}}`}, {"caught-up", `{}`}, {"removed", `{"key":"a"}`}, {"ended", `{"reason":"rebuilt"}`},
	}
	if len(got) != len(want)+1 || got[len(want)].GetCompleted() == nil {
		t.Fatalf("frames %v", got)
	}
	for i, w := range want {
		if w.name == "" {
			if got[i].GetText() != w.data {
				t.Errorf("frame %d is %v, want the text %q", i, got[i], w.data)
			}
			continue
		}
		if e := got[i].GetEvent(); e.GetName() != w.name || e.GetData() != w.data {
			t.Errorf("frame %d is %v, want %s %s", i, got[i], w.name, w.data)
		}
	}
	bad := read("GET /bad")
	if len(bad) != 1 || !strings.Contains(bad[0].GetFailed().GetMessage(), "one line") {
		t.Errorf("an event named over two lines was %v", bad)
	}
}

func socketOpen(t *testing.T, client pb.HttpClient) pb.Http_HandleSocketClient {
	t.Helper()
	stream, err := client.HandleSocket(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if err := stream.Send(&pb.SocketIn{Message: &pb.SocketIn_Open{Open: &pb.HttpRequest{EndpointId: "/s", RouteId: "GET /echo"}}}); err != nil {
		t.Fatal(err)
	}
	return stream
}

func frame(text string) *pb.SocketIn {
	return &pb.SocketIn{Message: &pb.SocketIn_Frame{Frame: &pb.SocketFrame{Kind: &pb.SocketFrame_Text{Text: text}}}}
}

func TestASocketRelaysFramesAndClosesWithItsHandler(t *testing.T) {
	client := pb.NewHttpClient(loopback(t, NewService(sEndpoint)))

	s := socketOpen(t, client)
	_ = s.Send(frame("hi"))
	_ = s.Send(frame("there"))
	_ = s.Send(frame("bye"))
	var got []string
	for {
		out, err := s.Recv()
		if err != nil {
			break
		}
		switch m := out.GetMessage().(type) {
		case *pb.SocketOut_Frame:
			got = append(got, m.Frame.GetText())
		case *pb.SocketOut_Completed:
			got = append(got, "completed")
		case *pb.SocketOut_Failed:
			got = append(got, "failed:"+m.Failed.GetMessage())
		}
	}
	if strings.Join(got, ",") != "/echo:hi,/echo:there,completed" {
		t.Errorf("the socket sent %v", got)
	}

	for text, want := range map[string]string{"boom": "the echo broke", "panic": "the echo panicked"} {
		s := socketOpen(t, client)
		_ = s.Send(frame(text))
		out, err := s.Recv()
		if err != nil || !strings.Contains(out.GetFailed().GetMessage(), want) {
			t.Errorf("%s ended %v, %v", text, out, err)
		}
	}

	// The client's close: the frames before it are read, then the handler sees the end.
	s = socketOpen(t, client)
	_ = s.Send(frame("last"))
	_ = s.Send(&pb.SocketIn{Message: &pb.SocketIn_Closed{Closed: &pb.SocketClosed{Reason: "client"}}})
	if out, _ := s.Recv(); out.GetFrame().GetText() != "/echo:last" {
		t.Errorf("the frame before the close was answered %v", out)
	}
	if out, _ := s.Recv(); out.GetCompleted() == nil {
		t.Errorf("a socket closed by its client ended %v", out)
	}
	if e := <-sEnded; e != "closed:client" {
		t.Errorf("the handler saw %q", e)
	}

	// A handler waiting on its context is released when the socket closes.
	s = socketOpen(t, client)
	_ = s.Send(frame("wait"))
	time.Sleep(50 * time.Millisecond)
	_ = s.Send(&pb.SocketIn{Message: &pb.SocketIn_Closed{Closed: &pb.SocketClosed{Reason: "going away"}}})
	select {
	case e := <-sEnded:
		if e != "context ended:going away" {
			t.Errorf("the handler saw %q", e)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("the handler's context did not end with the socket")
	}
}

func TestASocketRouteNeedsProtocol19(t *testing.T) {
	svc := NewService(sEndpoint)
	if why := svc.decls.refusal("1.8"); !strings.Contains(why, "needs 1.9") || !strings.Contains(why, "socket route") {
		t.Errorf("a 1.8 sidecar was refused with %q", why)
	}
	if why := svc.decls.refusal("1.9"); why != "" {
		t.Errorf("a 1.9 sidecar was refused with %q", why)
	}
}
