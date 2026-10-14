# ---------------------------------------------------------------------------
# 启动后端（Windows PowerShell 5.1 与 PowerShell 7 均可）：
#   .\run.ps1                                            # 默认配置（MySQL）
#   .\run.ps1 "-Dspring-boot.run.profiles=local-file"     # 无 Docker：H2 文件库
#   .\run.ps1 "-Dspring-boot.run.profiles=standalone"     # 无 Docker：H2 内存库
#
# 它会做三件事：
#   1) 加载 backend/.env.local 里的 KEY=VALUE 到当前进程环境变量
#      （解决「在别的终端设置了环境变量、启动时读不到」的问题；空值不会覆盖已有变量）
#   2) 找不到 JAVA_HOME 时自动定位 C:\Program Files\Java\jdk-*
#   3) ffmpeg 不在 PATH 时，自动从 conda 环境（默认 zsl）的 Library\bin 里找
#
# 注意：本文件必须保存为「UTF-8 with BOM」。Windows PowerShell 5.1 在文件没有 BOM 时
# 会按本地代码页(GBK)解码，中文注释会变成乱码并导致语法错误。
# ---------------------------------------------------------------------------
$ErrorActionPreference = 'Stop'
$here = $PSScriptRoot

# ---- 1) .env.local ----
$envFile = Join-Path $here '.env.local'
if (Test-Path $envFile) {
    $loaded = 0
    foreach ($line in (Get-Content -LiteralPath $envFile -Encoding UTF8)) {
        $text = $line.Trim()
        if (-not $text -or $text.StartsWith('#') -or -not $text.Contains('=')) { continue }
        $index = $text.IndexOf('=')
        $key = $text.Substring(0, $index).Trim().Trim([char]0xFEFF)
        $value = $text.Substring($index + 1).Trim().Trim('"')
        # 空值跳过：否则 .env.local 里的空行会覆盖掉系统里已经生效的变量
        if (-not $value) { continue }
        [Environment]::SetEnvironmentVariable($key, $value, 'Process')
        $loaded++
    }
    Write-Host "[run.ps1] 已从 .env.local 加载 $loaded 个环境变量" -ForegroundColor DarkGray
}
else {
    Write-Host "[run.ps1] 提示：没有 .env.local，可复制 .env.example 后填入密钥（不填则用兜底摘要）" -ForegroundColor Yellow
}

# ---- 2) JAVA_HOME ----
if (-not $env:JAVA_HOME) {
    $javaRoot = 'C:\Program Files\Java'
    $jdk = $null
    if (Test-Path $javaRoot) {
        $jdk = Get-ChildItem -LiteralPath $javaRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'jdk*' } |
            Sort-Object Name -Descending |
            Select-Object -First 1
    }
    if ($jdk) {
        $env:JAVA_HOME = $jdk.FullName
        Write-Host "[run.ps1] JAVA_HOME=$env:JAVA_HOME" -ForegroundColor DarkGray
    }
    else {
        Write-Host "[run.ps1] 警告：未找到 JDK，请先设置 JAVA_HOME" -ForegroundColor Yellow
    }
}

# ---- 3) ffmpeg（画面理解需要）----
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    $envName = if ($env:QVIDEO_CONDA_ENV) { $env:QVIDEO_CONDA_ENV } else { 'zsl' }
    $candidates = @()
    try {
        $json = conda env list --json | ConvertFrom-Json
        $prefix = $json.envs | Where-Object { (Split-Path $_ -Leaf) -eq $envName } | Select-Object -First 1
        if ($prefix) { $candidates += (Join-Path $prefix 'Library\bin') }
    }
    catch {
        # conda 不可用时忽略，继续用下面的兜底路径
    }
    $candidates += @(
        "D:\anaconda\envs\$envName\Library\bin",
        "$env:USERPROFILE\anaconda3\envs\$envName\Library\bin",
        "$env:USERPROFILE\miniconda3\envs\$envName\Library\bin"
    )
    foreach ($bin in $candidates) {
        if ($bin -and (Test-Path (Join-Path $bin 'ffmpeg.exe'))) {
            $env:Path = "$bin;$env:Path"
            Write-Host "[run.ps1] 已把 conda($envName) 的 ffmpeg 加入 PATH: $bin" -ForegroundColor DarkGray
            break
        }
    }
}
if (-not (Get-Command ffmpeg -ErrorAction SilentlyContinue)) {
    Write-Host "[run.ps1] 提示：没找到 ffmpeg，画面理解会不可用。安装：conda install -n zsl -y ffmpeg" -ForegroundColor Yellow
}

Write-Host "[run.ps1] 启动后端..." -ForegroundColor Cyan
& (Join-Path $here 'mvnw.cmd') spring-boot:run @args
