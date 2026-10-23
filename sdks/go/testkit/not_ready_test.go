package testkit

import (
	"context"
	"io"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/moby/moby/client"
	"github.com/testcontainers/testcontainers-go"
	ankka "github.com/thinkmorestupidless/ankka-go"
)

// features/polyglot/starting.feature, "a service is not ready until its process has declared its
// components", the Go row. The platform's program starts against a port the Go process has not
// served yet: it is seen retrying discovery, and all that while it is not ready (its health route
// does not answer and its callback port is not listening, which is what the kit waits for). Once
// the process serves, it is ready, and a route of the process answers through it.
func TestAServiceIsNotReadyUntilItsProcessHasDeclaredItsComponents(t *testing.T) {
	Slow(t)
	ctx := t.Context()
	run := runID()
	image := SidecarImage()

	dir, files, err := copyDDL(ctx, image)
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	nw, err := startNetwork(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = nw.Remove(context.WithoutCancel(ctx)) }()
	db, err := startPostgres(ctx, PostgresImage, nw, files, run)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = db.Terminate(context.WithoutCancel(ctx)) }()

	// A free port the process will serve on later: until then every dial of it is refused. (A port
	// held and not served would leave discovery waiting on one call, saying nothing.)
	free, err := net.Listen("tcp", "0.0.0.0:0")
	if err != nil {
		t.Fatal(err)
	}
	port := free.Addr().(*net.TCPAddr).Port
	_ = free.Close()

	type started struct {
		c   *testcontainers.DockerContainer
		err error
		at  time.Time
	}
	ready := make(chan started, 1)
	go func() {
		c, err := startSidecar(ctx, image, nw, port, nil, 3*time.Minute, run)
		ready <- started{c, err, time.Now()}
	}()

	retrying := waitForLog(t, ctx, run, image, "discovery attempt", 2*time.Minute)
	// Retrying, and for five seconds more still not ready.
	select {
	case s := <-ready:
		t.Fatalf("the platform's program was ready with no process (err %v); its log:\n%s", s.err, retrying)
	case <-time.After(5 * time.Second):
	}

	lis, err := net.Listen("tcp", "0.0.0.0:"+strconv.Itoa(port))
	if err != nil {
		t.Fatalf("the process's port was taken while it was not served: %v", err)
	}
	served := time.Now()
	serveCtx, stop := context.WithCancel(context.WithoutCancel(ctx))
	done := make(chan error, 1)
	go func() {
		done <- ankka.NewService(probe).Serve(serveCtx, lis, ankka.Host("0.0.0.0"), ankka.Port(port), ankka.GracePeriod(5*time.Second))
	}()
	defer func() { stop(); <-done }()

	s := <-ready
	if s.err != nil {
		t.Fatalf("the platform's program did not become ready once the process served: %v", s.err)
	}
	defer func() { _ = s.c.Terminate(context.WithoutCancel(ctx)) }()
	if s.at.Before(served) {
		t.Fatalf("ready at %v, before the process served at %v", s.at, served)
	}
	addr, err := address(ctx, s.c, httpPort)
	if err != nil {
		t.Fatal(err)
	}
	r, err := newHTTP(addr).Get(ctx, "/probe/")
	if err != nil || r.Status != 200 || r.Text() != "here" {
		t.Fatalf("the process's route answered %+v, %v", r, err)
	}
}

// waitForLog waits until the run's container of image has logged text, and answers its log.
func waitForLog(t *testing.T, ctx context.Context, run, image, text string, within time.Duration) string {
	t.Helper()
	docker, err := testcontainers.NewDockerClientWithOpts(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	deadline := time.Now().Add(within)
	var last string
	for time.Now().Before(deadline) {
		list, err := docker.ContainerList(ctx, client.ContainerListOptions{
			Filters: client.Filters{}.Add("label", RunLabel+"="+run).Add("ancestor", image),
		})
		if err == nil && len(list.Items) == 1 {
			if logs, err := docker.ContainerLogs(ctx, list.Items[0].ID, client.ContainerLogsOptions{ShowStdout: true, ShowStderr: true}); err == nil {
				b, _ := io.ReadAll(logs)
				_ = logs.Close()
				if last = string(b); strings.Contains(last, text) {
					return last
				}
			}
		}
		time.Sleep(time.Second)
	}
	t.Fatalf("the platform's program never logged %q; its log:\n%s", text, last)
	return ""
}
