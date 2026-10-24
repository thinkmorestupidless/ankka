package ankka

import (
	"context"
	"fmt"
	"net/http"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
)

// MaxServiceBody is the largest body, either way, a call through the sidecar carries.
const MaxServiceBody = 4_000_000

// ServiceRequest is one request to another service, by its name: never an address.
type ServiceRequest struct {
	// Project names another project's service; empty is this service's own project.
	Project     string
	Method      string
	Path        string // starts with "/"; the query is part of it
	Headers     http.Header
	ContentType string
	Body        []byte // nil: no body
}

// ServiceResponse is what the other service answered, whatever its status.
type ServiceResponse struct {
	Status      int
	ContentType string
	Body        []byte
	Headers     http.Header
}

// ServiceFailureReason is why no answer came from a service.
type ServiceFailureReason int

const (
	// ServiceUnanswered: the connection was refused or broke, or no answer came in time. The
	// service may have received the request.
	ServiceUnanswered ServiceFailureReason = iota
	// ServiceUnresolvable: no such service was found; nothing was sent.
	ServiceUnresolvable
	// ServiceIdentityMismatch: what answered is not the service asked for; nothing was sent.
	ServiceIdentityMismatch
)

// ServiceError is a call to another service that ended without its answer.
type ServiceError struct {
	Service string
	Reason  ServiceFailureReason
	Detail  string
}

func (e *ServiceError) Error() string {
	switch e.Reason {
	case ServiceUnresolvable:
		return fmt.Sprintf("cannot reach %s: %s", e.Service, e.Detail)
	case ServiceIdentityMismatch:
		return fmt.Sprintf("the service reached as %s is not it: %s", e.Service, e.Detail)
	}
	return fmt.Sprintf("%s did not answer: %s", e.Service, e.Detail)
}

// CallService sends req to the service named service, made by the sidecar as this service (1.8): it
// resolves the service, presents this service's certificate and checks the other's. Any status is
// an answer; an error is a *ServiceError when no answer came, or a *RefusalError when the sidecar
// would not make the call.
func CallService(ctx context.Context, service string, req ServiceRequest) (*ServiceResponse, error) {
	if len(req.Body) > MaxServiceBody {
		return nil, Refusef(BadRequest, "a call's body is at most %d bytes through the sidecar; this one is %d", MaxServiceBody, len(req.Body))
	}
	_, rpc, err := clientOf(ctx)
	if err != nil {
		return nil, err
	}
	out := &pb.ServiceRequest{Service: service, Method: req.Method, Path: req.Path, Metadata: metadataToPb(MetadataFrom(ctx))}
	if req.Project != "" {
		out.Project = &req.Project
	}
	for _, name := range sortedKeys(req.Headers) {
		for _, v := range req.Headers[name] {
			out.Headers = append(out.Headers, &pb.HttpRequest_Pair{Name: name, Value: v})
		}
	}
	if req.ContentType != "" {
		out.ContentType = &req.ContentType
	}
	if req.Body != nil {
		out.Body = req.Body
	}
	target := service
	if req.Project != "" {
		target = req.Project + "/" + service
	}
	reply, err := rpc.Request(ctx, out)
	if err != nil {
		return nil, tooOld("calls to other services", "1.8", err)
	}
	switch r := reply.GetResult().(type) {
	case *pb.ServiceReply_Response:
		h := http.Header{}
		for _, p := range r.Response.GetHeaders() {
			h.Add(p.GetName(), p.GetValue())
		}
		return &ServiceResponse{Status: int(r.Response.GetStatus()), ContentType: r.Response.GetContentType(), Body: r.Response.GetBody(), Headers: h}, nil
	case *pb.ServiceReply_Failure:
		return nil, &ServiceError{Service: target, Reason: ServiceFailureReason(r.Failure.GetReason()), Detail: r.Failure.GetDetail()}
	case *pb.ServiceReply_Error:
		return nil, errorFromPb(r.Error)
	}
	return nil, &ServiceError{Service: target, Reason: ServiceUnanswered, Detail: "the sidecar answered nothing"}
}
