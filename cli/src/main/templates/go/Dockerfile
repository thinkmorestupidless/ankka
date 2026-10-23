# Your process, nothing else: the platform runs the sidecar beside it in the same pod.
# Go builds one static executable, so the image that runs it holds no language runtime and no shell.
FROM golang:1.27-alpine AS build
ENV CGO_ENABLED=0
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN go build -trimpath -ldflags='-s -w' -o /service .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /service /service
# The port the sidecar dials, on loopback inside the pod.
ENV ANKKA_PROCESS_PORT=9010
USER nonroot
ENTRYPOINT ["/service"]
