# ============================================================
# scripts/ci-local.ps1 — 本地全门禁复跑（替代 GitHub Actions）
# ============================================================
# 2026-09-16 起启用：用户决策关闭 GitHub CI；所有门禁走本地执行。
# 使用方法：
#   powershell -NoProfile -File scripts/ci-local.ps1 [-Target all|server|web|android|clean]
# 默认 all。
# ============================================================

param(
    [ValidateSet('all', 'server', 'web', 'android', 'clean')]
    [string]$Target = 'all'
)

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Bar([string]$msg) {
    Write-Host ''
    Write-Host "====================================================" -ForegroundColor Cyan
    Write-Host "  $msg" -ForegroundColor Cyan
    Write-Host "====================================================" -ForegroundColor Cyan
    Write-Host ''
}

function Gate-Server {
    Bar "[1/3] Go server test + vet + cross-compile"
    $go = Get-Command go -ErrorAction SilentlyContinue
    if (-not $go) {
        Write-Host "  X go not found in PATH — server gate skipped" -ForegroundColor Red
        Write-Host "  X install Go 1.23+ and rerun; CI policy is user's call" -ForegroundColor Red
        return
    }
    Set-Location "$root/server"
    $env:CGO_ENABLED = '0'
    Write-Host "  -> go test -race -count=1 ./..."
    & go test -race -count=1 ./...
    Write-Host "  -> go vet ./..."
    & go vet ./...
    Write-Host "  -> cross-compile (linux/amd64 linux/arm64 darwin/arm64 windows/amd64)"
    $targets = @('linux/amd64', 'linux/arm64', 'darwin/arm64', 'windows/amd64')
    foreach ($t in $targets) {
        $parts = $t -split '/'
        $env:GOOS = $parts[0]
        $env:GOARCH = $parts[1]
        $env:CGO_ENABLED = '0'
        & go build ./cmd/eve
    }
    Write-Host "  V Go gate passed" -ForegroundColor Green
    Set-Location $root
}

function Gate-Web {
    Bar "[2/3] Web vitest + vue-tsc + build"
    Set-Location "$root/web"
    if (-not (Test-Path node_modules)) {
        Write-Host "  -> npm ci"
        & npm ci --no-audit --no-fund
    }
    Write-Host "  -> npx vitest run"
    & npx vitest run
    Write-Host "  -> npx vue-tsc --noEmit -p tsconfig.json"
    & npx vue-tsc --noEmit -p tsconfig.json
    Write-Host "  -> npm run build"
    & npm run build
    Write-Host "  V Web gate passed" -ForegroundColor Green
    Set-Location $root
}

function Gate-Android {
    Bar "[3/3] Android testDebugUnitTest + assembleDebug"
    Set-Location "$root/android"
    Write-Host "  -> gradlew.bat :app:testDebugUnitTest --rerun-tasks"
    & .\gradlew.bat :app:testDebugUnitTest --rerun-tasks
    Write-Host "  -> gradlew.bat :app:assembleDebug"
    & .\gradlew.bat :app:assembleDebug
    Write-Host "  V Android gate passed" -ForegroundColor Green
    Set-Location $root
}

function Clean-All {
    Bar "clean — remove all build artifacts"
    if (Test-Path "$root/web/dist") { Remove-Item -Recurse -Force "$root/web/dist" }
    if (Test-Path "$root/web/.vitest-cache") { Remove-Item -Recurse -Force "$root/web/.vitest-cache" }
    if (Test-Path "$root/android/app/build") { Remove-Item -Recurse -Force "$root/android/app/build" }
    if (Test-Path "$root/android/.kotlin") { Remove-Item -Recurse -Force "$root/android/.kotlin" }
    if (Test-Path "$root/server/coverage.out") { Remove-Item -Force "$root/server/coverage.out" }
    Write-Host "  V cleaned" -ForegroundColor Green
}

switch ($Target) {
    'all' {
        Gate-Server
        Gate-Web
        Gate-Android
        Bar "all gates passed (local)"
    }
    'server'   { Gate-Server }
    'web'      { Gate-Web }
    'android'  { Gate-Android }
    'clean'    { Clean-All }
    default {
        Write-Host "unknown target: $Target" -ForegroundColor Red
        Write-Host "usage: powershell -File scripts/ci-local.ps1 [-Target all|server|web|android|clean]"
        exit 1
    }
}