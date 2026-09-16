#!/usr/bin/env bash
# ============================================================
# scripts/ci-local.sh — 本地全门禁复跑（替代 GitHub Actions）
# ============================================================
# 2026-09-16 起启用：用户决策关闭 GitHub CI；所有门禁走本地执行。
# 使用方法：bash scripts/ci-local.sh [target]
#   target ∈ all | server | web | android | clean
# 默认 all。
# ============================================================

set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

TARGET="${1:-all}"

red()   { printf '\033[31m%s\033[0m\n' "$*"; }
green() { printf '\033[32m%s\033[0m\n' "$*"; }
blue()  { printf '\033[34m%s\033[0m\n' "$*"; }

bar() {
  echo ""
  blue "===================================================="
  blue "  $*"
  blue "===================================================="
  echo ""
}

gate_server() {
  bar "[1/3] Go server test + vet + cross-compile"
  if ! command -v go >/dev/null 2>&1; then
    red "  ✗ go not found in PATH — server 门禁跳过"
    red "  ✗ 请安装 Go 1.23+ 后重跑；CI 决策由用户承担"
    return 0
  fi
  cd "$ROOT/server"
  echo "  → go test -race -count=1 ./..."
  CGO_ENABLED=0 go test -race -count=1 ./...
  echo "  → go vet ./..."
  go vet ./...
  echo "  → cross-compile (linux/amd64 linux/arm64 darwin/arm64 windows/amd64)"
  for target in linux/amd64 linux/arm64 darwin/arm64 windows/amd64; do
    GOOS="${target%/*}" GOARCH="${target#*/}" CGO_ENABLED=0 go build ./cmd/eve
  done
  green "  ✓ Go gate passed"
  cd "$ROOT"
}

gate_web() {
  bar "[2/3] Web vitest + vue-tsc + build"
  cd "$ROOT/web"
  if [ ! -d node_modules ]; then
    echo "  → npm ci"
    npm ci --no-audit --no-fund
  fi
  echo "  → npx vitest run"
  npx vitest run
  echo "  → npx vue-tsc --noEmit -p tsconfig.json"
  npx vue-tsc --noEmit -p tsconfig.json
  echo "  → npm run build"
  npm run build
  green "  ✓ Web gate passed"
  cd "$ROOT"
}

gate_android() {
  bar "[3/3] Android testDebugUnitTest + assembleDebug"
  cd "$ROOT/android"
  echo "  → ./gradlew.bat :app:testDebugUnitTest --rerun-tasks"
  ./gradlew.bat :app:testDebugUnitTest --rerun-tasks
  echo "  → ./gradlew.bat :app:assembleDebug"
  ./gradlew.bat :app:assembleDebug
  green "  ✓ Android gate passed"
  cd "$ROOT"
}

clean_all() {
  bar "clean — 清理所有构建产物"
  rm -rf "$ROOT/web/dist" "$ROOT/web/.vitest-cache"
  rm -rf "$ROOT/android/app/build" "$ROOT/android/.kotlin"
  rm -rf "$ROOT/server/coverage.out"
  green "  ✓ cleaned"
}

case "$TARGET" in
  all)
    gate_server
    gate_web
    gate_android
    bar "✅ all gates passed (local)"
    ;;
  server)   gate_server ;;
  web)      gate_web ;;
  android)  gate_android ;;
  clean)    clean_all ;;
  *)
    red "unknown target: $TARGET"
    echo "usage: bash scripts/ci-local.sh [all|server|web|android|clean]"
    exit 1
    ;;
esac