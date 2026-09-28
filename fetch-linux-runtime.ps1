param(
    [string]$ProotReleaseApi = "https://api.github.com/repos/ahmed-alnassif/proot/releases/latest",
    [string]$ProotFallbackBase = "https://github.com/ahmed-alnassif/proot/releases/download/v26.08.25-7266fb3",
    [string]$Proxy = ""
)

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
$ProgressPreference = "SilentlyContinue"

$root = $PSScriptRoot
$assetsDir = Join-Path $root "app/src/main/assets/linux"
$jniArm = Join-Path $root "app/src/main/jniLibs/arm64-v8a"
$jniX86 = Join-Path $root "app/src/main/jniLibs/x86_64"
New-Item -ItemType Directory -Force -Path $assetsDir, $jniArm, $jniX86 | Out-Null

function Invoke-Download {
    param([string]$Url, [string]$OutFile, [string]$Label)
    Write-Host "[download] $Label"
    Write-Host "           $Url"
    $params = @{ Uri = $Url; OutFile = $OutFile; UseBasicParsing = $true; TimeoutSec = 300 }
    if ($Proxy -ne "") { $params.Proxy = $Proxy }
    Invoke-WebRequest @params
    $len = (Get-Item $OutFile).Length
    Write-Host "           -> $OutFile ($len bytes)"
}

function Install-ProotPackage {
    param([string]$ZipUrl, [string]$ZipName, [string]$DestJni, [string]$Label)
    $zip = Join-Path $env:TEMP $ZipName
    Invoke-Download -Url $ZipUrl -OutFile $zip -Label $Label
    $tmp = Join-Path $env:TEMP ("proot-" + [guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        Expand-Archive -Path $zip -DestinationPath $tmp -Force
        $proot = Join-Path $tmp "proot"
        if (-not (Test-Path $proot)) { throw "zip 中未找到 proot: $ZipName" }
        Copy-Item -Force $proot (Join-Path $DestJni "libproot_exec.so")
        $loader = Join-Path $tmp "loader"
        if (Test-Path $loader) { Copy-Item -Force $loader (Join-Path $DestJni "libproot_loader.so") }
        $loader32 = Join-Path $tmp "loader-m32"
        if (Test-Path $loader32) { Copy-Item -Force $loader32 (Join-Path $DestJni "libproot_loader_m32.so") }
        Write-Host "           installed PRoot package -> $DestJni"
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
        Remove-Item -Force $zip -ErrorAction SilentlyContinue
    }
}

function Resolve-ProotZipUrls {
    try {
        $release = Invoke-RestMethod -Uri $ProotReleaseApi -Headers @{ "User-Agent" = "AiChat-Linux-Fetch" } -TimeoutSec 30
        $arm = $release.assets | Where-Object { $_.name -eq "proot-aarch64.zip" } | Select-Object -First 1
        $x86 = $release.assets | Where-Object { $_.name -eq "proot-x86_64.zip" } | Select-Object -First 1
        if ($arm -and $x86) {
            Write-Host "[proot] release: $($release.tag_name)"
            return @{ Arm = $arm.browser_download_url; X86 = $x86.browser_download_url }
        }
    } catch {
        Write-Warning "GitHub release API 失败，使用 fallback: $($_.Exception.Message)"
    }
    return @{
        Arm = "$ProotFallbackBase/proot-aarch64.zip"
        X86 = "$ProotFallbackBase/proot-x86_64.zip"
    }
}

function Get-AlpineRootfs {
    param([string]$Arch, [string]$OutFile)
    $bases = @(
        "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/$Arch/",
        "https://mirrors.tuna.tsinghua.edu.cn/alpine/latest-stable/releases/$Arch/",
        "https://mirrors.aliyun.com/alpine/latest-stable/releases/$Arch/"
    )
    foreach ($base in $bases) {
        try {
            Write-Host "[alpine] 扫描 $base"
            $html = (Invoke-WebRequest -Uri $base -UseBasicParsing -TimeoutSec 60).Content
            $m = [regex]::Match($html, "alpine-minirootfs-[0-9]+\.[0-9]+\.[0-9]+-$Arch\.tar\.gz")
            if (-not $m.Success) { continue }
            $url = $base + $m.Value
            Invoke-Download -Url $url -OutFile $OutFile -Label "Alpine $Arch minirootfs"
            return
        } catch {
            Write-Warning "镜像失败: $base / $($_.Exception.Message)"
        }
    }
    throw "无法下载 Alpine rootfs（$Arch），请在能访问 dl-cdn.alpinelinux.org 的网络下重试。"
}

$prootUrls = Resolve-ProotZipUrls
Install-ProotPackage -ZipUrl $prootUrls.Arm -ZipName "proot-aarch64.zip" -DestJni $jniArm -Label "PRoot aarch64"
Install-ProotPackage -ZipUrl $prootUrls.X86 -ZipName "proot-x86_64.zip" -DestJni $jniX86 -Label "PRoot x86_64"

Get-AlpineRootfs -Arch "aarch64" -OutFile (Join-Path $assetsDir "alpine-aarch64.tar.gz")
Get-AlpineRootfs -Arch "x86_64" -OutFile (Join-Path $assetsDir "alpine-x86_64.tar.gz")

Write-Host ""
Write-Host "完成。接下来重新构建 APK："
Write-Host "  build-linux.bat              # 侧载，targetSdk=28"
Write-Host "  gradle :app:assembleRelease  # 普通 targetSdk=35"
Write-Host ""
Write-Host "产物："
Write-Host "  app/src/main/jniLibs/arm64-v8a/libproot_exec.so"
Write-Host "  app/src/main/jniLibs/arm64-v8a/libproot_loader.so"
Write-Host "  app/src/main/jniLibs/x86_64/libproot_exec.so"
Write-Host "  app/src/main/jniLibs/x86_64/libproot_loader.so"
Write-Host "  app/src/main/assets/linux/alpine-aarch64.tar.gz"
Write-Host "  app/src/main/assets/linux/alpine-x86_64.tar.gz"