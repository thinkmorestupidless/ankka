# The Go example's image as a developer's is built: against the SDK released at a version, not the
# checkout's directory, so the build records that version and the deployed service can say it.
# SidecarClusterSuite stages the context: the SDK's sources at sdk/, the example at example/, and
# publish/, which puts the SDK into a file proxy at SDK_VERSION. The two stages are the example's
# own Dockerfile's.
FROM golang:1.27-alpine AS build
ARG SDK_VERSION
ENV GOWORK=off CGO_ENABLED=0
WORKDIR /src
COPY . .
RUN go run ./publish/main.go /src/sdk "v${SDK_VERSION}" /proxy
WORKDIR /src/example
ENV GOPROXY=file:///proxy,https://proxy.golang.org GONOSUMDB=github.com/thinkmorestupidless/ankka-go
RUN go mod edit -dropreplace=github.com/thinkmorestupidless/ankka-go \
      -require="github.com/thinkmorestupidless/ankka-go@v${SDK_VERSION}" \
 && go build -mod=mod -trimpath -ldflags='-s -w' -o /cart ./cmd/cart

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /cart /cart
ENV ANKKA_PROCESS_PORT=9010
USER nonroot
ENTRYPOINT ["/cart"]
