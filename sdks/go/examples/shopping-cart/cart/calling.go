package cart

import (
	"context"
	"errors"
	"fmt"

	ankka "github.com/thinkmorestupidless/ankka-go"
)

// Calling calls another service of this project, as this service: the answer is what that service
// answered, and the service it called saw this one as the caller. The Python sample's /calling, so
// the platform's cluster suite drives both the same way. It admits only this service's own
// instances.
var Calling = ankka.NewEndpoint("/calling", ankka.AllowIf(ankka.Self()),
	ankka.Get("/call/{service}", func(ctx context.Context, req *ankka.Request) (string, error) {
		path := req.Query.Get("path")
		if path == "" {
			path = "/callers/whoami"
		}
		res, err := ankka.CallService(ctx, req.Param("service"), ankka.ServiceRequest{Method: "GET", Path: path})
		var failed *ankka.ServiceError
		if errors.As(err, &failed) && failed.Reason == ankka.ServiceUnresolvable {
			return "", &ankka.HttpProblem{Status: 503, Message: failed.Error()}
		}
		if err != nil {
			return "", err
		}
		if res.Status < 200 || res.Status > 299 {
			return "", &ankka.HttpProblem{Status: res.Status, Message: fmt.Sprintf("%s answered %d: %s", req.Param("service"), res.Status, res.Body)}
		}
		return string(res.Body), nil
	}),
)
