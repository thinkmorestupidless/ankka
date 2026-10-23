package cart_test

import (
	"archive/tar"
	"errors"
	"io"
	"os/exec"
	"path"
	"strings"
	"testing"

	"github.com/thinkmorestupidless/ankka-go/testkit"
)

const cartImage = "sample-shopping-cart-go:latest"

// A service's image holds its one executable and no language runtime: the image is built the one
// way it is built (scripts/image.sh), a container is created from it and never started, and its
// whole filesystem is read back and inspected.
func TestTheImageHoldsTheExecutableAndNoRuntime(t *testing.T) {
	testkit.Slow(t)
	build := exec.CommandContext(t.Context(), "scripts/image.sh", cartImage)
	if out, err := build.CombinedOutput(); err != nil {
		t.Fatalf("scripts/image.sh: %v\n%s", err, out)
	}

	created, err := exec.CommandContext(t.Context(), "docker", "create", cartImage).Output()
	if err != nil {
		t.Fatalf("docker create %s: %v", cartImage, err)
	}
	id := strings.TrimSpace(string(created))
	t.Cleanup(func() { _ = exec.Command("docker", "rm", "-f", id).Run() })

	export := exec.CommandContext(t.Context(), "docker", "export", id)
	stream, err := export.StdoutPipe()
	if err != nil {
		t.Fatal(err)
	}
	if err := export.Start(); err != nil {
		t.Fatal(err)
	}
	files := map[string]*tar.Header{}
	r := tar.NewReader(stream)
	for {
		h, err := r.Next()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			t.Fatalf("reading the container's filesystem: %v", err)
		}
		files["/"+strings.TrimPrefix(path.Clean(h.Name), "/")] = h
	}
	if err := export.Wait(); err != nil {
		t.Fatalf("docker export: %v", err)
	}
	if len(files) == 0 {
		t.Fatal("the container's filesystem read back empty")
	}

	cart, ok := files["/cart"]
	if !ok || cart.Typeflag != tar.TypeReg || cart.Mode&0o111 == 0 {
		t.Fatalf("/cart is not an executable file in the image: %+v", cart)
	}
	if h, ok := files["/etc/ssl/certs/ca-certificates.crt"]; !ok || h.Size == 0 {
		t.Error("the image holds no CA certificates, so the process could not reach a store or a model over TLS")
	}
	for _, shell := range []string{"/bin/sh", "/bin/bash", "/bin/ash", "/usr/bin/sh", "/bin/busybox"} {
		if _, ok := files[shell]; ok {
			t.Errorf("the image holds a shell: %s", shell)
		}
	}
	for name, h := range files {
		if h.Typeflag != tar.TypeReg && h.Typeflag != tar.TypeSymlink {
			continue
		}
		switch base := path.Base(name); {
		case base == "apk" || base == "apt" || base == "apt-get" || base == "dpkg" || base == "rpm" || base == "yum" || base == "microdnf":
			t.Errorf("the image holds a package manager: %s", name)
		case base == "go" || base == "gofmt" || strings.Contains(name, "/go/pkg/") || strings.HasSuffix(name, "/go/bin"):
			t.Errorf("the image holds the Go toolchain: %s", name)
		}
	}

	// The size `docker images` shows, unpacked; `image inspect` on a containerd store reports the
	// compressed layers instead.
	if size, err := exec.CommandContext(t.Context(), "docker", "images", cartImage, "--format", "{{.Size}}").Output(); err == nil {
		t.Logf("%s: %d files, %s", cartImage, len(files), strings.TrimSpace(string(size)))
	}
}
