# Multi-stage build: compile with the full Go toolchain, ship only the binary.

# ---- build stage ----
FROM golang:1.27 AS build
WORKDIR /src

# Copy go.mod/go.sum first so the dependency download is cached as its own layer and
# only re-runs when dependencies change, not on every source edit.
COPY go.mod go.sum ./
RUN go mod download

COPY . .
# CGO_ENABLED=0 produces a fully static binary, which the distroless "static" image
# (no libc) requires. -trimpath and -s -w make it smaller and reproducible.
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/coordinator ./cmd/coordinator

# ---- runtime stage ----
# distroless/static: no shell, no package manager — a tiny attack surface. The
# :nonroot variant runs as an unprivileged user (uid 65532).
FROM gcr.io/distroless/static:nonroot
COPY --from=build /out/coordinator /coordinator
USER nonroot:nonroot
EXPOSE 8080
ENTRYPOINT ["/coordinator"]
