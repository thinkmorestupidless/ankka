package ankka

import (
	"runtime/debug"
	"strings"
)

// modulePath is this module's path, as a service's build records its dependency on it.
const modulePath = "github.com/thinkmorestupidless/ankka-go"

// Version is the SDK's version as the service's build recorded it, without the "v": the version of
// the ankka-go module the service depends on, or "0.0.0" when the dependency is replaced by a local
// directory, when the SDK is itself the main module, or when the build recorded nothing (V5).
func Version() string {
	info, ok := debug.ReadBuildInfo()
	if !ok {
		return "0.0.0"
	}
	return versionFrom(info)
}

func versionFrom(info *debug.BuildInfo) string {
	if info.Main.Path == modulePath {
		return "0.0.0"
	}
	for _, d := range info.Deps {
		if d.Path != modulePath {
			continue
		}
		if d.Replace != nil {
			return "0.0.0"
		}
		v := strings.TrimPrefix(d.Version, "v")
		if v == "" || v == "(devel)" {
			return "0.0.0"
		}
		return v
	}
	return "0.0.0"
}
