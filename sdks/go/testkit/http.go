package testkit

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
)

// HTTP is a small client of a sidecar's HTTP port, where the service's declared routes are served.
type HTTP struct {
	// BaseURL is the sidecar's address, "http://host:port".
	BaseURL string
	client  *http.Client
}

func newHTTP(address string) *HTTP {
	return &HTTP{BaseURL: "http://" + address, client: &http.Client{}}
}

// HTTPResponse is what a request answered.
type HTTPResponse struct {
	Status int
	Header http.Header
	Body   []byte
}

// Text is the body as text.
func (r *HTTPResponse) Text() string { return string(r.Body) }

// ContentType is the response's content type.
func (r *HTTPResponse) ContentType() string { return r.Header.Get("Content-Type") }

// Do makes one request. A body of []byte is sent as it is, a string as text, anything else as JSON;
// header adds to or replaces the content type that implies.
func (h *HTTP) Do(ctx context.Context, method, path string, body any, header http.Header) (*HTTPResponse, error) {
	var reader io.Reader
	contentType := ""
	switch b := body.(type) {
	case nil:
	case []byte:
		reader, contentType = bytes.NewReader(b), "application/octet-stream"
	case string:
		reader, contentType = strings.NewReader(b), "text/plain; charset=utf-8"
	default:
		data, err := json.Marshal(b)
		if err != nil {
			return nil, fmt.Errorf("the body cannot be written as JSON: %w", err)
		}
		reader, contentType = bytes.NewReader(data), "application/json"
	}
	req, err := http.NewRequestWithContext(ctx, method, h.BaseURL+path, reader)
	if err != nil {
		return nil, err
	}
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	for k, vs := range header {
		req.Header.Del(k)
		for _, v := range vs {
			req.Header.Add(k, v)
		}
	}
	res, err := h.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	data, err := io.ReadAll(res.Body)
	if err != nil {
		return nil, err
	}
	return &HTTPResponse{Status: res.StatusCode, Header: res.Header, Body: data}, nil
}

// Get requests path with GET.
func (h *HTTP) Get(ctx context.Context, path string) (*HTTPResponse, error) {
	return h.Do(ctx, http.MethodGet, path, nil, nil)
}

// Post requests path with POST and body.
func (h *HTTP) Post(ctx context.Context, path string, body any) (*HTTPResponse, error) {
	return h.Do(ctx, http.MethodPost, path, body, nil)
}

// Put requests path with PUT and body.
func (h *HTTP) Put(ctx context.Context, path string, body any) (*HTTPResponse, error) {
	return h.Do(ctx, http.MethodPut, path, body, nil)
}

// Delete requests path with DELETE.
func (h *HTTP) Delete(ctx context.Context, path string) (*HTTPResponse, error) {
	return h.Do(ctx, http.MethodDelete, path, nil, nil)
}

// GetJSON requests path with GET and reads a 200's body as JSON into a T; any other status is an
// error carrying the body.
func GetJSON[T any](ctx context.Context, h *HTTP, path string) (T, error) {
	var zero T
	r, err := h.Get(ctx, path)
	if err != nil {
		return zero, err
	}
	if r.Status != http.StatusOK {
		return zero, fmt.Errorf("GET %s answered %d: %s", path, r.Status, r.Text())
	}
	var out T
	if err := json.Unmarshal(r.Body, &out); err != nil {
		return zero, fmt.Errorf("GET %s answered what is not a %T: %w", path, zero, err)
	}
	return out, nil
}
