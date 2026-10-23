package testkit

import (
	"archive/tar"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"

	cerrdefs "github.com/containerd/errdefs"
	"github.com/moby/moby/client"
	"github.com/testcontainers/testcontainers-go"
)

// ddlPath is where the sidecar image keeps the platform's schema.
const ddlPath = "/opt/docker/ddl"

// copyDDL writes the schema the image carries into a new temporary directory and answers the files,
// in name order, without running the image: a container is created from it, the directory read out
// as a tar, and the container removed. The kit's database is made from these files, so a test can
// never pass against a schema the platform does not have.
func copyDDL(ctx context.Context, image string) (dir string, files []string, err error) {
	docker, err := testcontainers.NewDockerClientWithOpts(ctx)
	if err != nil {
		return "", nil, fmt.Errorf("docker is not reachable: %w", err)
	}
	defer docker.Close()
	if err := ensureImage(ctx, docker, image); err != nil {
		return "", nil, err
	}
	created, err := docker.ContainerCreate(ctx, client.ContainerCreateOptions{Image: image})
	if err != nil {
		return "", nil, fmt.Errorf("a container cannot be created from %s: %w", image, err)
	}
	defer func() {
		_, _ = docker.ContainerRemove(context.WithoutCancel(ctx), created.ID, client.ContainerRemoveOptions{Force: true})
	}()
	copied, err := docker.CopyFromContainer(ctx, created.ID, client.CopyFromContainerOptions{SourcePath: ddlPath + "/."})
	if err != nil {
		return "", nil, fmt.Errorf("%s holds no %s: %w", image, ddlPath, err)
	}
	defer copied.Content.Close()

	dir, err = os.MkdirTemp("", "ankka-ddl-")
	if err != nil {
		return "", nil, err
	}
	defer func() {
		if err != nil {
			_ = os.RemoveAll(dir)
		}
	}()
	// Readable by the container's own postgres user: the image's entrypoint lists the directory
	// before initdb, and a private one fails that on Linux (Docker Desktop hides it).
	if err := os.Chmod(dir, 0o755); err != nil {
		return "", nil, err
	}
	r := tar.NewReader(copied.Content)
	for {
		h, err := r.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return "", nil, fmt.Errorf("the schema copied out of %s cannot be read: %w", image, err)
		}
		if h.Typeflag != tar.TypeReg {
			continue
		}
		name := path.Base(h.Name)
		if !strings.HasSuffix(name, ".sql") && !strings.HasSuffix(name, ".sh") {
			continue
		}
		target := filepath.Join(dir, name)
		f, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
		if err != nil {
			return "", nil, err
		}
		_, err = io.Copy(f, r)
		if cerr := f.Close(); err == nil {
			err = cerr
		}
		if err != nil {
			return "", nil, err
		}
		if err := os.Chmod(target, 0o644); err != nil {
			return "", nil, err
		}
		files = append(files, target)
	}
	if len(files) == 0 {
		return "", nil, fmt.Errorf("%s holds no schema files under %s", image, ddlPath)
	}
	sort.Strings(files)
	return dir, files, nil
}

// ensureImage pulls image when the engine does not hold it: a container cannot be created from an
// image the engine has not got.
func ensureImage(ctx context.Context, docker *testcontainers.DockerClient, image string) error {
	if _, err := docker.ImageInspect(ctx, image); err == nil {
		return nil
	} else if !cerrdefs.IsNotFound(err) {
		return fmt.Errorf("the image %s cannot be inspected: %w", image, err)
	}
	pulled, err := docker.ImagePull(ctx, image, client.ImagePullOptions{})
	if err != nil {
		return fmt.Errorf("the image %s is not here and cannot be pulled: %w", image, err)
	}
	if err := pulled.Wait(ctx); err != nil {
		return fmt.Errorf("the image %s cannot be pulled: %w", image, err)
	}
	return nil
}
