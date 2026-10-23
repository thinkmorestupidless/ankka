package ankka

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"net"
	"runtime/debug"
	"strings"
	"testing"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
)

// declared is a declaration that adds what a test says, standing in for a constructor.
type declared func(*declarations)

func (f declared) declare(d *declarations) { f(d) }

func component(kind pb.Kind, id string) declared {
	return func(d *declarations) { d.components = append(d.components, &pb.Component{Kind: kind, Id: id}) }
}

func endpoint(id, prefix string) declared {
	return func(d *declarations) { d.endpoints = append(d.endpoints, &pb.Endpoint{Id: id, Prefix: prefix}) }
}

func needing(minor int, reason string) declared {
	return func(d *declarations) { d.require(minor, "%s", reason) }
}

func TestStartRefusesWithEveryProblemAtOnce(t *testing.T) {
	svc := NewService(
		component(pb.Kind_EVENT_SOURCED_ENTITY, "cart"),
		component(pb.Kind_KEY_VALUE_ENTITY, "cart"),
		endpoint("a", "/carts"),
		endpoint("b", "/carts"),
		nil,
	)
	err := svc.Start(context.Background(), Port(0))
	var pe *ProblemsError
	if !errors.As(err, &pe) || len(pe.Problems) != 3 {
		t.Fatalf("got %v", err)
	}
	msg := err.Error()
	for _, want := range []string{`share the id "cart"`, `share the prefix "/carts"`, "declaration 5 is nil"} {
		if !strings.Contains(msg, want) {
			t.Errorf("the refusal does not say %q:\n%s", want, msg)
		}
	}
}

func TestTheProcessListensOnLoopbackOrTheAnyAddressOnly(t *testing.T) {
	svc := NewService()
	err := svc.Start(context.Background(), Host("192.168.1.10"), Port(0))
	if err == nil || !strings.Contains(err.Error(), "192.168.1.10") {
		t.Fatalf("got %v", err)
	}
	t.Setenv("ANKKA_PROCESS_PORT", "not-a-port")
	if err := svc.Start(context.Background()); err == nil || !strings.Contains(err.Error(), "ANKKA_PROCESS_PORT") {
		t.Fatalf("got %v", err)
	}
}

func TestTheSpecCarriesTheProtocolAndTheSDK(t *testing.T) {
	spec := NewService(component(pb.Kind_TIMED_ACTION, "reminder")).Spec()
	if spec.GetProtocolVersion() != "1.22" || spec.GetSdk().GetName() != "ankka-go" || spec.GetSdk().GetVersion() == "" || len(spec.GetComponents()) != 1 {
		t.Fatalf("got %v", spec)
	}
}

func TestAnOlderSidecarIsToldTheOldestThingItLacks(t *testing.T) {
	d := gather([]Declaration{needing(20, "an MCP endpoint (/mcp)"), needing(9, "a socket route (api.live)"), needing(15, "granted callers (api)"), needing(9, "another socket (api.feed)")})
	why := d.refusal("1.8")
	if !strings.Contains(why, "needs 1.9") || !strings.Contains(why, "api.live") || !strings.Contains(why, "api.feed") || strings.Contains(why, "/mcp") {
		t.Fatalf("got %q", why)
	}
	if why := d.refusal("1.16"); !strings.Contains(why, "needs 1.20") {
		t.Fatalf("got %q", why)
	}
	if why := d.refusal("1.22"); why != "" {
		t.Fatalf("a sidecar new enough was refused: %q", why)
	}
	if why := gather(nil).refusal(""); why != "" {
		t.Fatalf("a service needing nothing was refused: %q", why)
	}
	if why := d.refusal(""); !strings.Contains(why, "unknown") {
		t.Fatalf("got %q", why)
	}
}

func TestDiscoveryOverTheWire(t *testing.T) {
	var logs bytes.Buffer
	svc := NewService(component(pb.Kind_TIMED_ACTION, "reminder"), needing(9, "a socket route (api.live)"))
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	served := make(chan error, 1)
	go func() {
		served <- svc.Serve(ctx, lis, Logger(slog.New(slog.NewTextHandler(&logs, nil))), GracePeriod(time.Second))
	}()
	conn, err := grpc.NewClient(lis.Addr().String(), grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	d := pb.NewDiscoveryClient(conn)
	_, err = d.Discover(context.Background(), &pb.SidecarInfo{ProtocolVersion: "1.8", RuntimeVersion: "0.9.0"})
	if status.Code(err) != codes.FailedPrecondition || !strings.Contains(err.Error(), "needs 1.9") {
		t.Fatalf("a 1.8 sidecar: %v", err)
	}
	spec, err := d.Discover(context.Background(), &pb.SidecarInfo{ProtocolVersion: "1.22"})
	if err != nil || spec.GetProtocolVersion() != "1.22" || svc.SidecarProtocol() != "1.22" {
		t.Fatalf("got %v, %v", spec, err)
	}
	for range 2 {
		if _, err := d.ReportError(context.Background(), &pb.Problem{Message: "component reminder: no handlers"}); err != nil {
			t.Fatal(err)
		}
	}
	if n := strings.Count(logs.String(), "no handlers"); n != 1 {
		t.Fatalf("a problem reported twice was logged %d times:\n%s", n, logs.String())
	}
	if len(svc.Reported()) != 3 {
		t.Fatalf("reported %v", svc.Reported())
	}
	cancel()
	if err := <-served; err != nil {
		t.Fatalf("serve ended with %v", err)
	}
}

func TestTheVersionIsTheDependencysOrNone(t *testing.T) {
	dep := func(version string, replaced bool) *debug.BuildInfo {
		m := &debug.Module{Path: modulePath, Version: version}
		if replaced {
			m.Replace = &debug.Module{Path: "../.."}
		}
		return &debug.BuildInfo{Main: debug.Module{Path: "example.com/cart"}, Deps: []*debug.Module{m}}
	}
	cases := []struct {
		info *debug.BuildInfo
		want string
	}{
		{dep("v0.13.0", false), "0.13.0"},
		{dep("v0.13.0", true), "0.0.0"},
		{dep("(devel)", false), "0.0.0"},
		{&debug.BuildInfo{Main: debug.Module{Path: modulePath, Version: "v0.13.0"}}, "0.0.0"},
		{&debug.BuildInfo{Main: debug.Module{Path: "example.com/cart"}}, "0.0.0"},
	}
	for _, c := range cases {
		if got := versionFrom(c.info); got != c.want {
			t.Errorf("got %q, want %q", got, c.want)
		}
	}
}
