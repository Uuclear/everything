# 多架构镜像（linux/amd64、linux/arm64）：使用 BUILDPLATFORM 原生构建 +
# Go 交叉编译，避免 QEMU 模拟；CI 与 buildx 负责产出多架构 manifest。

# ---- 1) 构建网页端 ----
FROM --platform=$BUILDPLATFORM node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json* ./
RUN if [ -f package-lock.json ]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
COPY web/ ./
RUN npm run build

# ---- 2) 构建服务端（在构建机构架上原生运行 Go，交叉编译到目标架构）----
FROM --platform=$BUILDPLATFORM golang:1.23-alpine AS server
ARG TARGETARCH
ARG VERSION=dev
WORKDIR /src
COPY server/go.mod server/go.sum ./server/
RUN cd server && go mod download
COPY server/ ./server/
# 网页产物放进 embed 目录
COPY --from=web /src/server/web/dist ./server/web/dist
RUN cd server && \
    CGO_ENABLED=0 GOARCH=${TARGETARCH} go build \
      -trimpath -ldflags "-s -w -X github.com/everything-personal/eve/internal/api.Version=${VERSION}" \
      -o /out/eve ./cmd/eve

# ---- 3) 最小运行镜像 ----
FROM alpine:3.20
RUN apk add --no-cache ca-certificates tzdata && \
    adduser -D -u 10001 eve && \
    mkdir -p /data && chown eve:eve /data
COPY --from=server /out/eve /usr/local/bin/eve
USER eve
WORKDIR /data
VOLUME ["/data"]
EXPOSE 8787
ENV EVE_ADDR=":8787" EVE_DATA_DIR="/data"
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s \
    CMD wget -qO- http://127.0.0.1:8787/api/v1/health || exit 1
ENTRYPOINT ["eve"]
