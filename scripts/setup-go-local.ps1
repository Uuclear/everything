# 本机无 winget/choco 时，下载可移植 Go 到仓库 .tools/go（已 gitignore）
# 用法: powershell -NoProfile -File scripts/setup-go-local.ps1
$ErrorActionPreference = 'Stop'
$ver = '1.23.4'
$root = Split-Path -Parent $PSScriptRoot
$dest = Join-Path $root '.tools\go'
if (Test-Path (Join-Path $dest 'bin\go.exe')) {
    Write-Host "Go already at $dest"
    exit 0
}
$zip = Join-Path $env:TEMP "go$ver.windows-amd64.zip"
Write-Host "Downloading Go $ver..."
Invoke-WebRequest -Uri "https://go.dev/dl/go$ver.windows-amd64.zip" -OutFile $zip -UseBasicParsing
New-Item -ItemType Directory -Force -Path (Split-Path $dest) | Out-Null
Expand-Archive -Path $zip -DestinationPath (Split-Path $dest) -Force
# 解压结果为 .tools/go/
Write-Host "Installed: $dest\bin\go.exe"
Write-Host 'Session PATH: $env:PATH = "' + $dest + '\bin;" + $env:PATH'
