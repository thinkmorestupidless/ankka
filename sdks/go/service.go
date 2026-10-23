package ankka

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"github.com/thinkmorestupidless/ankka-go/internal/server"
	"google.golang.org/grpc"
)

// Service is a set of declarations served to the sidecar beside this process.
type Service struct {
	decls *declarations
	log   *slog.Logger

	mu       sync.Mutex
	reported []string
	seen     map[string]bool
	sidecar  string // the protocol the sidecar stated at its last discovery
	client   *Client
}

// Client is the service's client of its sidecar, once Start or Serve has begun; nil before.
func (s *Service) Client() *Client {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.client
}

// serverOptions are what every servicer of this service is given.
func (s *Service) serverOptions() server.Options {
	return server.Options{Log: s.log, Scope: s.scope}
}

// scope is a conversation's or a request's context as a handler sees it: with the service's client
// and its configuration.
func (s *Service) scope(ctx context.Context) context.Context {
	if c := s.Client(); c != nil {
		ctx = WithClient(ctx, c)
	}
	return withConfig(ctx, environment())
}

// environment is the process's variables, as the platform handed them.
func environment() map[string]string {
	out := map[string]string{}
	for _, kv := range os.Environ() {
		if k, v, ok := strings.Cut(kv, "="); ok {
			out[k] = v
		}
	}
	return out
}

// NewService is a service of the components and endpoints given, in any order. Nothing is checked
// until Start, which refuses with every problem at once; Problems lists them before that.
func NewService(declarations ...Declaration) *Service {
	return &Service{decls: gather(declarations), log: slog.Default(), seen: map[string]bool{}}
}

// Problems is every problem with the service's declarations, together.
func (s *Service) Problems() []Problem { return append([]Problem(nil), s.decls.problems...) }

// Spec is what the service answers the sidecar's discovery with.
func (s *Service) Spec() *pb.Spec { return s.decls.spec() }

// Reported is every problem the sidecar has reported to this process, in order.
func (s *Service) Reported() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.reported...)
}

// SidecarProtocol is the protocol version the sidecar stated at its last discovery, or "".
func (s *Service) SidecarProtocol() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.sidecar
}

// StartOption is an option of Start and Serve.
type StartOption func(*startSettings)

type startSettings struct {
	host    string
	port    int
	sidecar string
	grace   time.Duration
	log     *slog.Logger
}

// Host is the address the process listens on: loopback (the default) or "0.0.0.0", which the
// integration test kit needs for a sidecar in a container. Any other is refused.
func Host(host string) StartOption { return func(s *startSettings) { s.host = host } }

// Port is the port the process listens on; by default ANKKA_PROCESS_PORT, else 9010. 0 picks one.
func Port(port int) StartOption { return func(s *startSettings) { s.port = port } }

// SidecarAddress is where the sidecar's Client service answers; by default ANKKA_SIDECAR_ADDRESS,
// else 127.0.0.1:9011.
func SidecarAddress(address string) StartOption {
	return func(s *startSettings) { s.sidecar = address }
}

// GracePeriod is how long handlers in flight may run after SIGTERM or SIGINT before the server
// stops them; 25 seconds by default, inside the platform's 30.
func GracePeriod(d time.Duration) StartOption { return func(s *startSettings) { s.grace = d } }

// Logger is where the service logs; slog's default otherwise.
func Logger(l *slog.Logger) StartOption { return func(s *startSettings) { s.log = l } }

func settingsOf(opts []StartOption) (startSettings, error) {
	st := startSettings{host: "127.0.0.1", port: 9010, sidecar: "127.0.0.1:9011", grace: 25 * time.Second, log: slog.Default()}
	if v := os.Getenv("ANKKA_PROCESS_PORT"); v != "" {
		p, err := strconv.Atoi(v)
		if err != nil || p < 0 || p > 65535 {
			return st, fmt.Errorf("ANKKA_PROCESS_PORT is %q, which is not a port", v)
		}
		st.port = p
	}
	if v := os.Getenv("ANKKA_SIDECAR_ADDRESS"); v != "" {
		st.sidecar = v
	}
	for _, o := range opts {
		o(&st)
	}
	switch st.host {
	case "127.0.0.1", "localhost", "::1", "0.0.0.0":
	default:
		return st, fmt.Errorf("the process listens on loopback or 0.0.0.0, never %q: the sidecar is the only thing that may reach it", st.host)
	}
	return st, nil
}

// Start serves the service until it is stopped: on SIGTERM or SIGINT, or when ctx ends, it stops
// accepting, lets handlers in flight finish within the grace period, then returns. It refuses
// before binding anything when the declarations have problems, naming every one.
func (s *Service) Start(ctx context.Context, opts ...StartOption) error {
	st, err := settingsOf(opts)
	if err != nil {
		return err
	}
	if len(s.decls.problems) > 0 {
		return &ProblemsError{Problems: s.Problems()}
	}
	lis, err := net.Listen("tcp", net.JoinHostPort(st.host, strconv.Itoa(st.port)))
	if err != nil {
		return fmt.Errorf("the process cannot listen: %w", err)
	}
	ctx, stop := signal.NotifyContext(ctx, syscall.SIGTERM, os.Interrupt)
	defer stop()
	return s.serve(ctx, lis, st)
}

// Serve serves the service on lis until ctx ends, then stops gracefully. The integration test kit
// uses it; a deployed service uses Start.
func (s *Service) Serve(ctx context.Context, lis net.Listener, opts ...StartOption) error {
	st, err := settingsOf(opts)
	if err != nil {
		return err
	}
	if len(s.decls.problems) > 0 {
		return &ProblemsError{Problems: s.Problems()}
	}
	return s.serve(ctx, lis, st)
}

func (s *Service) serve(ctx context.Context, lis net.Listener, st startSettings) error {
	s.log = st.log
	s.mu.Lock()
	if s.client == nil {
		s.client = &Client{address: st.sidecar, decls: s.decls}
	}
	s.mu.Unlock()
	g := s.grpcServer()
	done := make(chan error, 1)
	go func() { done <- g.Serve(lis) }()
	s.log.Info("ankka: serving", "address", lis.Addr().String(), "protocol", ProtocolVersion, "sdk", SDKName, "version", Version())
	select {
	case err := <-done:
		return err
	case <-ctx.Done():
	}
	stopped := make(chan struct{})
	go func() { g.GracefulStop(); close(stopped) }()
	select {
	case <-stopped:
	case <-time.After(st.grace):
		s.log.Warn("ankka: handlers still running after the grace period; stopping them", "grace", st.grace)
		g.Stop()
	}
	if err := <-done; err != nil && !errors.Is(err, grpc.ErrServerStopped) {
		return err
	}
	return nil
}

// grpcServer registers discovery and a servicer for every kind the declarations hold.
func (s *Service) grpcServer() *grpc.Server {
	g := server.New()
	server.RegisterDiscovery(g, server.Discovery{Discover: s.discover, Report: s.report})
	if len(s.decls.eventSourced) > 0 {
		server.RegisterEventSourced(g, s.eventSourcedHost, s.serverOptions())
	}
	if len(s.decls.endpoints) > 0 {
		server.RegisterHttp(g, server.HttpHost{Handle: s.decls.handleHTTP}, s.serverOptions())
	}
	return g
}

func (s *Service) eventSourcedHost(componentID, entityID string) (server.EventSourcedInstance, error) {
	f, ok := s.decls.eventSourced[componentID]
	if !ok {
		return nil, fmt.Errorf("this process hosts no event sourced entity %q", componentID)
	}
	return f(entityID), nil
}

func (s *Service) discover(_ context.Context, info *pb.SidecarInfo) (*pb.Spec, error) {
	s.log.Info("ankka: discovery", "sidecarProtocol", info.GetProtocolVersion(), "runtime", info.GetRuntimeVersion())
	if why := s.decls.refusal(info.GetProtocolVersion()); why != "" {
		s.report(why)
		return nil, errors.New(why)
	}
	s.mu.Lock()
	s.sidecar = info.GetProtocolVersion()
	s.mu.Unlock()
	return s.Spec(), nil
}

// report logs a problem the first time it is seen and keeps it.
func (s *Service) report(message string) {
	s.mu.Lock()
	first := !s.seen[message]
	s.seen[message] = true
	s.reported = append(s.reported, message)
	s.mu.Unlock()
	if first {
		s.log.Error("ankka: the sidecar refused", "problem", message)
	}
}
