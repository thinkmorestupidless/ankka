package ankka

// What a Go service on a developer's machine serves at /_ankka/storage/<bucket>/<name> while its
// store is a directory: the signed URLs and public addresses that store makes, answered through the
// sidecar like any route, so a browser reads and keeps through them as it would through an object
// store's. The runtime's DirectoryStorageEndpoint and the other process SDKs say the same.
//
// The token is the whole of the authority, as a signature is on an object store: a GET reads with a
// token for read, or with none on a public bucket; a PUT keeps with a token for keep. Anything else
// is 403, and an object not there 404. The sidecar matches a route by whole segments and an object's
// name holds slashes, so one route is declared per depth, up to maxAddressSegments; a deeper name
// travels as one segment with its slashes encoded.

import (
	"bytes"
	"context"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/thinkmorestupidless/ankka-go/internal/pb"
	"google.golang.org/protobuf/proto"
)

const (
	// storageEndpointID is the storage endpoint's id, as every SDK names it.
	storageEndpointID = "ankka-storage"
	// storageSince is the protocol minor whose runtimes admit a process's endpoint at /_ankka/storage.
	storageSince = 21
	nowhere      = "no object at this address"
	unsigned     = "this address is not signed for that, or its signature has expired"
)

// decodedSegment is a segment as the sidecar forwards it, encoded; one that is not valid encoding is
// taken as it is.
func decodedSegment(s string) string {
	if d, err := url.PathUnescape(s); err == nil {
		return d
	}
	return s
}

// addressed is the bucket's segment and the object's name a request's path parameters spell.
func addressed(params map[string]string, depth int) (string, string) {
	parts := make([]string, depth)
	for i := range depth {
		parts[i] = decodedSegment(params["n"+strconv.Itoa(i+1)])
	}
	return decodedSegment(params["bucket"]), strings.Join(parts, "/")
}

// signedFor is whether the request's query signs access to name in segment's bucket.
func signedFor(s *DirectoryStorage, req *Request, segment, name, access string) bool {
	q := req.Query
	expires, err := strconv.ParseInt(q.Get("expires"), 10, 64)
	token := q.Get("token")
	return q.Get("access") == access && token != "" && err == nil && s.admits(segment, name, access, expires, token)
}

func problem(status int, message string) error { return &HttpProblem{Status: status, Message: message} }

// storageEndpoint is the endpoint that answers store's URLs.
func storageEndpoint(store *DirectoryStorage) *Endpoint {
	e := &Endpoint{prefix: storagePath, acl: AllowAll(), ident: storageEndpointID, reserved: true}
	for depth := 1; depth <= maxAddressSegments; depth++ {
		var t strings.Builder
		t.WriteString("/{bucket}")
		for i := 1; i <= depth; i++ {
			fmt.Fprintf(&t, "/{n%d}", i)
		}
		read := newRoute(http.MethodGet, t.String())
		read.run = func(ctx context.Context, req *Request) (*pb.HttpResponse, error) {
			segment, name := addressed(req.Params, depth)
			b := store.bySegment(segment)
			if b == nil {
				return nil, problem(http.StatusNotFound, nowhere)
			}
			open := b.exposure == ExposurePublic && b.name != ""
			if !open && !signedFor(store, req, segment, name, string(AccessRead)) {
				return nil, problem(http.StatusForbidden, unsigned)
			}
			o, err := b.Get(ctx, name)
			if err != nil {
				return nil, err
			}
			if o == nil {
				return nil, problem(http.StatusNotFound, "no object '"+name+"'")
			}
			data, err := o.Bytes()
			if err != nil {
				return nil, err
			}
			return &pb.HttpResponse{Status: http.StatusOK, ContentType: o.ContentType, Body: data}, nil
		}
		keep := newRoute(http.MethodPut, t.String())
		keep.hasBody = true
		keep.run = func(ctx context.Context, req *Request) (*pb.HttpResponse, error) {
			segment, name := addressed(req.Params, depth)
			b := store.bySegment(segment)
			if b == nil {
				return nil, problem(http.StatusNotFound, nowhere)
			}
			if !signedFor(store, req, segment, name, string(AccessKeep)) {
				return nil, problem(http.StatusForbidden, unsigned)
			}
			contentType := req.ContentType
			if contentType == "" {
				contentType = "application/octet-stream"
			}
			if err := b.Put(ctx, name, bytes.NewReader(req.Body), int64(len(req.Body)), contentType); err != nil {
				return nil, err
			}
			return textResponse(http.StatusOK, ""), nil
		}
		e.routes = append(e.routes, read, keep)
	}
	return e
}

// withoutStorageEndpoint is spec without the storage endpoint, for a sidecar too old to admit it.
func withoutStorageEndpoint(spec *pb.Spec) *pb.Spec {
	out := proto.Clone(spec).(*pb.Spec)
	out.Endpoints = nil
	for _, e := range spec.GetEndpoints() {
		if e.GetId() != storageEndpointID {
			out.Endpoints = append(out.Endpoints, e)
		}
	}
	return out
}
