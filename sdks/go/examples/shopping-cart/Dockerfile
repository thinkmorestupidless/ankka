# The developer's container: only their program. The sidecar is the platform's.
# Built by scripts/image.sh, whose build context holds the SDK at the root and this example under
# examples/shopping-cart, so the module's `replace => ../..` resolves inside the build. A service that
# depends on the released module needs no staging: the same two stages from its own directory.
FROM golang:1.27-alpine AS build
ENV GOWORK=off CGO_ENABLED=0
WORKDIR /src
COPY . .
WORKDIR /src/examples/shopping-cart
RUN go mod download && go build -trimpath -ldflags='-s -w' -o /cart ./cmd/cart

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /cart /cart
ENV ANKKA_PROCESS_PORT=9010
USER nonroot
ENTRYPOINT ["/cart"]
