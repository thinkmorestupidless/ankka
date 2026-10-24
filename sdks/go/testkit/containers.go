package testkit

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"fmt"
	"io"
	"os"
	"strconv"
	"time"

	"github.com/moby/moby/api/types/container"
	"github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/modules/postgres"
	"github.com/testcontainers/testcontainers-go/network"
	"github.com/testcontainers/testcontainers-go/wait"
	ankka "github.com/thinkmorestupidless/ankka-go"
)

const (
	// PostgresImage is the database the kit starts, unless told otherwise.
	PostgresImage = "postgres:17-alpine"
	// PublishedSidecar is the repository a release publishes the sidecar to.
	PublishedSidecar = "ghcr.io/thinkmorestupidless/ankka-sidecar"

	httpPort     = 9000
	callbackPort = 9011
	databaseHost = "postgres"
)

// SidecarImage is the sidecar the kit starts when it is not told: ANKKA_SIDECAR_IMAGE when that is
// set; else, for a development build of the SDK (version 0.0.0, a checkout of the ankka
// repository), ankka-sidecar:latest, which sbt sidecar/Docker/publishLocal builds; else the sidecar
// published under the SDK's own version, which a release publishes beside it.
func SidecarImage() string { return sidecarImageFor(os.Getenv("ANKKA_SIDECAR_IMAGE"), ankka.Version()) }

func sidecarImageFor(explicit, version string) string {
	switch {
	case explicit != "":
		return explicit
	case version == "0.0.0":
		return "ankka-sidecar:latest"
	}
	return PublishedSidecar + ":" + version
}

// secretKey is a fresh service secret key, so the secret store works with no setup.
func secretKey() string {
	b := make([]byte, 32)
	_, _ = rand.Read(b)
	return base64.StdEncoding.EncodeToString(b)
}

// databaseEnv is how a container on the kit's network reaches its database.
func databaseEnv() map[string]string {
	return map[string]string{
		"ANKKA_DB_HOST":     databaseHost,
		"ANKKA_DB_PORT":     "5432",
		"ANKKA_DB_NAME":     "ankka",
		"ANKKA_DB_USER":     "ankka",
		"ANKKA_DB_PASSWORD": "ankka",
	}
}

// RunLabel is the label on every container a kit starts, its value the same for one kit and
// different for every other: what a kit started can be told from what other tests on the same
// engine started, which share testcontainers' session when go test runs packages at once.
const RunLabel = "com.thinkmorestupidless.ankka.testkit.run"

func runID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return fmt.Sprintf("%x", b)
}

func startNetwork(ctx context.Context) (*testcontainers.DockerNetwork, error) {
	nw, err := network.New(ctx)
	if err != nil {
		return nil, fmt.Errorf("the kit's network cannot be made: %w", err)
	}
	return nw, nil
}

// startPostgres starts the database on the kit's network, made from the schema files.
func startPostgres(ctx context.Context, image string, nw *testcontainers.DockerNetwork, ddl []string, run string) (*postgres.PostgresContainer, error) {
	c, err := postgres.Run(ctx, image,
		testcontainers.WithLabels(map[string]string{RunLabel: run}),
		postgres.WithDatabase("ankka"), postgres.WithUsername("ankka"), postgres.WithPassword("ankka"),
		postgres.WithOrderedInitScripts(ddl...),
		network.WithNetwork([]string{databaseHost}, nw),
		postgres.BasicWaitStrategies(),
	)
	if err != nil {
		if c != nil {
			_ = c.Terminate(context.WithoutCancel(ctx))
		}
		return nil, fmt.Errorf("the database did not start: %w", err)
	}
	return c, nil
}

// startSidecar starts the sidecar on the kit's network, pointed at the process on the host's port
// processPort, and waits until its routes are served and its callback port listens: the health
// route answers once HTTP is bound, which can be before the callback server listens.
func startSidecar(ctx context.Context, image string, nw *testcontainers.DockerNetwork, processPort int, env map[string]string, ready time.Duration, run string) (*testcontainers.DockerContainer, error) {
	all := databaseEnv()
	all["ANKKA_PROCESS_ADDRESS"] = "host.docker.internal:" + strconv.Itoa(processPort)
	all["ANKKA_SIDECAR_BIND"] = "0.0.0.0"
	all["ANKKA_HTTP_PORT"] = strconv.Itoa(httpPort)
	all["ANKKA_SECRET_KEY"] = secretKey()
	for k, v := range env {
		all[k] = v
	}
	return startContainer(ctx, image, nw, all, ready, true, run)
}

// startContainer runs image on the kit's network with env, exposing the HTTP port (and the callback
// port, for a sidecar), and waits for its health route.
func startContainer(ctx context.Context, image string, nw *testcontainers.DockerNetwork, env map[string]string, ready time.Duration, sidecar bool, run string) (*testcontainers.DockerContainer, error) {
	ports := []string{strconv.Itoa(httpPort) + "/tcp"}
	waits := []wait.Strategy{wait.ForHTTP("/_ankka/health").WithPort(ports[0]).WithStatusCodeMatcher(func(s int) bool { return s == 200 })}
	if sidecar {
		ports = append(ports, strconv.Itoa(callbackPort)+"/tcp")
		waits = append(waits, wait.ForListeningPort(ports[1]))
	}
	c, err := testcontainers.Run(ctx, image,
		testcontainers.WithEnv(env),
		testcontainers.WithLabels(map[string]string{RunLabel: run}),
		testcontainers.WithExposedPorts(ports...),
		network.WithNetwork(nil, nw),
		testcontainers.WithHostConfigModifier(func(h *container.HostConfig) {
			// Docker Desktop provides the name; a Linux engine needs it said.
			h.ExtraHosts = append(h.ExtraHosts, "host.docker.internal:host-gateway")
		}),
		testcontainers.WithWaitStrategyAndDeadline(ready, wait.ForAll(waits...)),
	)
	if err != nil {
		logs := logsOf(ctx, c)
		if c != nil {
			_ = c.Terminate(context.WithoutCancel(ctx))
		}
		if logs != "" {
			return nil, fmt.Errorf("%s did not become ready: %w\n--- its log ---\n%s", image, err, logs)
		}
		return nil, fmt.Errorf("%s did not become ready: %w", image, err)
	}
	return c, nil
}

// address is host:port of a container's port as the host reaches it.
func address(ctx context.Context, c *testcontainers.DockerContainer, port int) (string, error) {
	host, err := c.Host(ctx)
	if err != nil {
		return "", err
	}
	mapped, err := c.MappedPort(ctx, strconv.Itoa(port)+"/tcp")
	if err != nil {
		return "", err
	}
	return host + ":" + mapped.Port(), nil
}

// logsOf is a container's log so far, its last 8000 bytes, or "" when there is none to read.
func logsOf(ctx context.Context, c *testcontainers.DockerContainer) string {
	if c == nil {
		return ""
	}
	r, err := c.Logs(context.WithoutCancel(ctx))
	if err != nil {
		return ""
	}
	defer r.Close()
	b, _ := io.ReadAll(r)
	if len(b) > 8000 {
		b = b[len(b)-8000:]
	}
	return string(b)
}
