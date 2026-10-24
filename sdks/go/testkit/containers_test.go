package testkit

import "testing"

func TestTheSidecarImageIsChosenByTheRule(t *testing.T) {
	for _, c := range []struct{ explicit, version, want string }{
		{"europe-west2-docker.pkg.dev/ankka-ops/ghcr/ankka-sidecar:0.13.0", "0.14.0", "europe-west2-docker.pkg.dev/ankka-ops/ghcr/ankka-sidecar:0.13.0"},
		{"", "0.0.0", "ankka-sidecar:latest"},
		{"", "0.14.0", "ghcr.io/thinkmorestupidless/ankka-sidecar:0.14.0"},
	} {
		if got := sidecarImageFor(c.explicit, c.version); got != c.want {
			t.Errorf("explicit %q, version %q: chose %q, want %q", c.explicit, c.version, got, c.want)
		}
	}
	// This module is the main module under its own tests, so it is a development build.
	t.Setenv("ANKKA_SIDECAR_IMAGE", "")
	if got := SidecarImage(); got != "ankka-sidecar:latest" {
		t.Errorf("a development build chose %q", got)
	}
}
