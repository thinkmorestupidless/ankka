package testkit

import (
	"context"
	"errors"
	"fmt"
	"net"
	"os"
	"strconv"
	"sync"
	"testing"
	"time"

	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	ankka "github.com/thinkmorestupidless/ankka-go"
	"github.com/thinkmorestupidless/ankka-go/internal/kit"
)

// StartOption is an option of the integration kit.
type StartOption func(*startOptions)

type startOptions struct {
	image, postgresImage string
	env                  map[string]string
	ready                time.Duration
}

// Image is the sidecar image; SidecarImage() by default.
func Image(image string) StartOption { return func(o *startOptions) { o.image = image } }

// Postgres is the database image; PostgresImage by default.
func Postgres(image string) StartOption { return func(o *startOptions) { o.postgresImage = image } }

// Env adds variables to the sidecar's environment: ANKKA_MODEL_SCRIPT scripts its model, and
// ANKKA_SECRET_KEY "" starts a service with no secret key.
func Env(env map[string]string) StartOption {
	return func(o *startOptions) {
		for k, v := range env {
			o.env[k] = v
		}
	}
}

// ReadyWithin is how long the kit waits for a container to be ready; two minutes by default.
func ReadyWithin(d time.Duration) StartOption { return func(o *startOptions) { o.ready = d } }

// Kit is a service running against a real sidecar and a real Postgres, both in containers; the
// service itself runs in this process.
type Kit struct {
	opts    startOptions
	service *ankka.Service
	view    *kit.Service

	mu        sync.Mutex
	ddlDir    string
	network   *testcontainers.DockerNetwork
	database  *postgres.PostgresContainer
	sidecar   *testcontainers.DockerContainer
	besides   []*Beside
	serveStop context.CancelFunc
	served    chan error
	port      int
	http      *HTTP
	client    *ankka.Client
}

// Start serves service on all interfaces of this machine, starts Postgres from the schema the
// sidecar image carries, then the sidecar pointed at the service, and answers once the sidecar's
// routes are served. A start that fails stops everything it started.
func Start(ctx context.Context, service *ankka.Service, opts ...StartOption) (*Kit, error) {
	o := startOptions{postgresImage: PostgresImage, env: map[string]string{}, ready: 2 * time.Minute}
	for _, f := range opts {
		f(&o)
	}
	if o.image == "" {
		o.image = SidecarImage()
	}
	if service == nil {
		return nil, errors.New("testkit.Start is given no service")
	}
	v := view(service)
	k := &Kit{opts: o, service: service, view: v}
	if err := k.start(ctx); err != nil {
		_ = k.Stop(context.WithoutCancel(ctx))
		return nil, err
	}
	return k, nil
}

// StartT is Start for a test: it skips the test unless slow tests are asked for (Slow), fails it
// when the kit cannot start, and stops the kit when the test ends.
func StartT(t testing.TB, service *ankka.Service, opts ...StartOption) *Kit {
	t.Helper()
	Slow(t)
	k, err := Start(t.Context(), service, opts...)
	if err != nil {
		t.Fatalf("the integration kit did not start: %v", err)
	}
	t.Cleanup(func() {
		if err := k.Stop(context.Background()); err != nil {
			t.Errorf("the integration kit did not stop: %v", err)
		}
	})
	return k
}

func (k *Kit) start(ctx context.Context) error {
	dir, files, err := copyDDL(ctx, k.opts.image)
	if err != nil {
		return err
	}
	k.ddlDir = dir
	if k.network, err = startNetwork(ctx); err != nil {
		return err
	}
	if k.database, err = startPostgres(ctx, k.opts.postgresImage, k.network, files); err != nil {
		return err
	}
	// All interfaces: the sidecar is in a container and dials in through the host's gateway.
	lis, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		return fmt.Errorf("the service cannot listen: %w", err)
	}
	k.port = lis.Addr().(*net.TCPAddr).Port
	serveCtx, stop := context.WithCancel(context.WithoutCancel(ctx))
	k.serveStop, k.served = stop, make(chan error, 1)
	go func() {
		k.served <- k.service.Serve(serveCtx, lis, ankka.Host("0.0.0.0"), ankka.Port(k.port), ankka.GracePeriod(5*time.Second))
	}()
	return k.startSidecar(ctx)
}

func (k *Kit) startSidecar(ctx context.Context) error {
	c, err := startSidecar(ctx, k.opts.image, k.network, k.port, k.opts.env, k.opts.ready)
	if err != nil {
		return err
	}
	httpAddr, err := address(ctx, c, httpPort)
	if err != nil {
		_ = c.Terminate(context.WithoutCancel(ctx))
		return err
	}
	callback, err := address(ctx, c, callbackPort)
	if err != nil {
		_ = c.Terminate(context.WithoutCancel(ctx))
		return err
	}
	k.mu.Lock()
	k.sidecar, k.http = c, newHTTP(httpAddr)
	if k.client != nil {
		_ = k.client.Close()
	}
	k.client = k.view.ClientAt(callback).(*ankka.Client)
	k.mu.Unlock()
	k.view.Redirect(callback)
	return nil
}

// HTTP is the sidecar's HTTP port, where the service's routes are served.
func (k *Kit) HTTP() *HTTP {
	k.mu.Lock()
	defer k.mu.Unlock()
	return k.http
}

// Client is a client of the kit's sidecar, for calls a test makes outside a handler: use it with
// ankka.WithClient, or through Context.
func (k *Kit) Client() *ankka.Client {
	k.mu.Lock()
	defer k.mu.Unlock()
	return k.client
}

// Context is ctx carrying the kit's client, so ankka.Invoke and the other calls can be made from a
// test.
func (k *Kit) Context(ctx context.Context) context.Context { return ankka.WithClient(ctx, k.Client()) }

// DatabaseURL is the throwaway database's connection string, as the host reaches it.
func (k *Kit) DatabaseURL(ctx context.Context) (string, error) {
	if k.database == nil {
		return "", errors.New("the kit is not started")
	}
	return k.database.ConnectionString(ctx, "sslmode=disable")
}

// SidecarLogs is the sidecar's log so far.
func (k *Kit) SidecarLogs(ctx context.Context) string {
	k.mu.Lock()
	c := k.sidecar
	k.mu.Unlock()
	return logsOf(ctx, c)
}

// Restart replaces the sidecar with a new one on the same database and waits until it is ready:
// every instance is gone from memory, so the next read rebuilds from the journal. That is how a
// test proves durability rather than caching.
func (k *Kit) Restart(ctx context.Context) error {
	k.mu.Lock()
	old := k.sidecar
	k.sidecar = nil
	k.mu.Unlock()
	if old != nil {
		if err := old.Terminate(ctx); err != nil {
			return fmt.Errorf("the sidecar did not stop: %w", err)
		}
	}
	return k.startSidecar(ctx)
}

// Beside is another image started on the kit's network and database by StartBeside.
type Beside struct {
	container *testcontainers.DockerContainer
	http      *HTTP
}

// HTTP is the other service's HTTP port.
func (b *Beside) HTTP() *HTTP { return b.http }

// Logs is the other service's log so far.
func (b *Beside) Logs(ctx context.Context) string { return logsOf(ctx, b.container) }

// Stop stops the other service.
func (b *Beside) Stop(ctx context.Context) error { return b.container.Terminate(ctx) }

// StartBeside starts another image against the kit's database (the Scala shopping cart, say, to
// prove the journal is shared) and waits for its health route on port 9000.
func (k *Kit) StartBeside(ctx context.Context, image string, env map[string]string) (*Beside, error) {
	if k.network == nil {
		return nil, errors.New("the kit is not started")
	}
	all := databaseEnv()
	all["ANKKA_HTTP_PORT"] = strconv.Itoa(httpPort)
	all["ANKKA_SECRET_KEY"] = secretKey()
	for key, v := range env {
		all[key] = v
	}
	c, err := startContainer(ctx, image, k.network, all, k.opts.ready, false)
	if err != nil {
		return nil, err
	}
	httpAddr, err := address(ctx, c, httpPort)
	if err != nil {
		_ = c.Terminate(context.WithoutCancel(ctx))
		return nil, err
	}
	b := &Beside{container: c, http: newHTTP(httpAddr)}
	k.mu.Lock()
	k.besides = append(k.besides, b)
	k.mu.Unlock()
	return b, nil
}

// Stop stops the service, the sidecar, anything started beside it, the database and the network,
// whatever of them a start got as far as, and removes the schema it copied. It is safe to call
// twice.
func (k *Kit) Stop(ctx context.Context) error {
	k.mu.Lock()
	sidecar, besides, client := k.sidecar, k.besides, k.client
	k.sidecar, k.besides, k.client = nil, nil, nil
	k.mu.Unlock()
	var errs []error
	if client != nil {
		_ = client.Close()
	}
	if k.serveStop != nil {
		k.serveStop()
		<-k.served
		k.serveStop = nil
	}
	for _, b := range besides {
		errs = append(errs, b.container.Terminate(ctx))
	}
	if sidecar != nil {
		errs = append(errs, sidecar.Terminate(ctx))
	}
	if k.database != nil {
		errs = append(errs, k.database.Terminate(ctx))
		k.database = nil
	}
	if k.network != nil {
		errs = append(errs, k.network.Remove(ctx))
		k.network = nil
	}
	if k.ddlDir != "" {
		errs = append(errs, os.RemoveAll(k.ddlDir))
		k.ddlDir = ""
	}
	return errors.Join(errs...)
}
