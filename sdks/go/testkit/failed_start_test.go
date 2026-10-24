package testkit

import (
	"context"
	"testing"
	"time"

	"github.com/moby/moby/client"
	"github.com/testcontainers/testcontainers-go"
	ankka "github.com/thinkmorestupidless/ankka-go"
)

// withRun names the kit's run, so a test can find what one start made after the start has failed.
func withRun(id string) StartOption { return func(o *startOptions) { o.run = id } }

// runContainers are the containers that still exist with the run's label. Counting testcontainers'
// session instead counted other packages' kits, since go test runs packages at once and they share
// the session.
func runContainers(t *testing.T, ctx context.Context, run string) []string {
	t.Helper()
	docker, err := testcontainers.NewDockerClientWithOpts(ctx)
	if err != nil {
		t.Fatal(err)
	}
	defer docker.Close()
	list, err := docker.ContainerList(ctx, client.ContainerListOptions{
		All:     true,
		Filters: client.Filters{}.Add("label", RunLabel+"="+run),
	})
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, c := range list.Items {
		out = append(out, c.Image+" "+c.ID)
	}
	return out
}

var probe = ankka.NewEndpoint("/probe", ankka.AllowAll(),
	ankka.Get("/", func(ctx context.Context, req *ankka.Request) (string, error) { return "here", nil }),
)

func TestAFailedStartLeavesNoContainer(t *testing.T) {
	Slow(t)
	ctx := t.Context()
	run := runID()
	// The database starts; the sidecar serves on a port the kit does not wait on, so it never
	// becomes ready.
	_, err := Start(ctx, ankka.NewService(probe), withRun(run),
		Env(map[string]string{"ANKKA_HTTP_PORT": "9999"}), ReadyWithin(20*time.Second))
	if err == nil {
		t.Fatal("a sidecar that never served its health route was taken as ready")
	}
	if left := runContainers(t, ctx, run); len(left) != 0 {
		t.Fatalf("a failed start left containers behind: %v", left)
	}
}

// The failed-start case finds nothing whether or not anything was left, unless the label is there:
// this is what shows it can fail.
func TestEveryContainerAKitStartsCarriesItsRun(t *testing.T) {
	Slow(t)
	ctx := t.Context()
	run := runID()
	kit, err := Start(ctx, ankka.NewService(probe), withRun(run))
	if err != nil {
		t.Fatal(err)
	}
	if got := runContainers(t, ctx, run); len(got) != 2 {
		t.Fatalf("a running kit's containers by its run, the database and the sidecar: %v", got)
	}
	if err := kit.Stop(context.WithoutCancel(ctx)); err != nil {
		t.Fatal(err)
	}
}
