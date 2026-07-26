param(
    [string]$ServerAddr,
    [string]$Secret,
    [ValidateSet("amd64", "arm64")]
    [string]$Architecture = "",
    [string]$Release = "2.13.3",
    [switch]$Uninstall
)

$ErrorActionPreference = "Stop"
$ServiceName = "FluxConnector"
$InstallDir = Join-Path $env:ProgramData "FluxConnector"
$BinaryPath = Join-Path $InstallDir "gost.exe"
$AgentConfigPath = Join-Path $InstallDir "config.json"
$GostConfigPath = Join-Path $InstallDir "gost.json"

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw "请使用管理员身份运行 PowerShell"
    }
}

function Resolve-Architecture {
    if ($Architecture) { return $Architecture }
    $detected = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString().ToLowerInvariant()
    switch ($detected) {
        "x64" { return "amd64" }
        "arm64" { return "arm64" }
        default { throw "不支持的 Windows 架构: $detected" }
    }
}

function Stop-And-Remove-Service {
    $service = Get-Service -Name $ServiceName -ErrorAction SilentlyContinue
    if (-not $service) { return }
    if ($service.Status -ne "Stopped") {
        Stop-Service -Name $ServiceName -Force
        $service.WaitForStatus("Stopped", [TimeSpan]::FromSeconds(20))
    }
    & sc.exe delete $ServiceName | Out-Null
    Start-Sleep -Seconds 1
}

Assert-Administrator

if ($Uninstall) {
    Stop-And-Remove-Service
    if (Test-Path $InstallDir) { Remove-Item $InstallDir -Recurse -Force }
    Write-Host "Flux Connector 已卸载"
    exit 0
}

if ([string]::IsNullOrWhiteSpace($ServerAddr) -or [string]::IsNullOrWhiteSpace($Secret)) {
    throw "安装时必须提供 -ServerAddr 和 -Secret"
}

$arch = Resolve-Architecture
$downloadUrl = "https://github.com/NorwayXZ/flux-panel/releases/download/$Release/gost-windows-$arch.exe"
$downloadPath = Join-Path $env:TEMP "flux-connector-$arch.exe"
$backupPath = "$BinaryPath.previous"

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Write-Host "下载 Flux Connector: Windows $arch"
Invoke-WebRequest -UseBasicParsing -Uri $downloadUrl -OutFile $downloadPath
if (-not (Test-Path $downloadPath) -or (Get-Item $downloadPath).Length -eq 0) {
    throw "Connector 下载失败"
}

New-Item -ItemType Directory -Path $InstallDir -Force | Out-Null
Stop-And-Remove-Service
if (Test-Path $BinaryPath) { Copy-Item $BinaryPath $backupPath -Force }
Move-Item $downloadPath $BinaryPath -Force

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
$agentConfig = @{ addr = $ServerAddr; secret = $Secret; role = "connector" } | ConvertTo-Json
[IO.File]::WriteAllText($AgentConfigPath, $agentConfig, $utf8NoBom)
if (-not (Test-Path $GostConfigPath)) {
    [IO.File]::WriteAllText($GostConfigPath, "{}", $utf8NoBom)
}

$serviceCommand = "`"$BinaryPath`" -agent-config `"$AgentConfigPath`" -C `"$GostConfigPath`""
New-Service -Name $ServiceName -BinaryPathName $serviceCommand -DisplayName "Flux Panel Connector" -StartupType Automatic | Out-Null

try {
    Start-Service -Name $ServiceName
    (Get-Service -Name $ServiceName).WaitForStatus("Running", [TimeSpan]::FromSeconds(20))
    if (Test-Path $backupPath) { Remove-Item $backupPath -Force }
    Write-Host "Flux Connector 安装完成，服务已启动"
    Write-Host "配置目录: $InstallDir"
} catch {
    Stop-And-Remove-Service
    if (Test-Path $backupPath) {
        Move-Item $backupPath $BinaryPath -Force
        New-Service -Name $ServiceName -BinaryPathName $serviceCommand -DisplayName "Flux Panel Connector" -StartupType Automatic | Out-Null
        Start-Service -Name $ServiceName
    }
    throw "新版本启动失败，已尝试恢复旧版本: $($_.Exception.Message)"
}
