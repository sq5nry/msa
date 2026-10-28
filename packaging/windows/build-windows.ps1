<#
.SYNOPSIS
  Builds the portable Windows application: target\windows\MSA\ (MSA.exe, msa-probe.exe, a trimmed Java
  runtime, libusb-1.0.dll, msa-info) and target\windows\MSA-windows-<version>.zip.

.DESCRIPTION
  Run on Windows with a JDK 25 or newer installed (found via JAVA_HOME, PATH or the usual install folders,
  see Find-Jdk; an older JAVA_HOME is skipped). Maven is taken from PATH or
  MAVEN_HOME, or downloaded once (Apache Maven, SHA-512 checked) into .tools\ if there is none:
      powershell -ExecutionPolicy Bypass -File packaging\windows\build-windows.ps1
  libusb-1.0.dll (64-bit) is taken from -LibUsb, else the MSA_LIBUSB environment variable, else the
  repository root (where it is kept for development; git-ignored). Get it from
  https://github.com/libusb/libusb/releases (the .7z, file VS2022\MS64\dll\libusb-1.0.dll).

.PARAMETER LibUsb      Path of libusb-1.0.dll.
.PARAMETER SkipBuild   Use the jars already in msa-gui\target and msa-tools\target.
#>
param([string]$LibUsb = "", [switch]$SkipBuild)
$ErrorActionPreference = "Stop"

$root = (Resolve-Path "$PSScriptRoot\..\..").Path
Set-Location $root

<#
  A JDK 25 or newer (it must have jpackage): JAVA_HOME if it is one, else "java" on PATH, else the usual
  install folders (IntelliJ's %USERPROFILE%\.jdks, Eclipse Adoptium/Temurin, Oracle, Microsoft, Zulu,
  Liberica, Amazon Corretto), newest first. It becomes JAVA_HOME for this script, so Maven uses it too.
#>
function JdkMajor($dir) {
    $rel = Join-Path $dir "release"
    if (-not (Test-Path $rel)) { return 0 }
    $line = Select-String -Path $rel -Pattern '^JAVA_VERSION="?([0-9]+)' | Select-Object -First 1
    if (-not $line) { return 0 }
    return [int]$line.Matches[0].Groups[1].Value
}
function Find-Jdk {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) { $candidates += (Split-Path (Split-Path $java.Source)) }
    $roots = @("$env:USERPROFILE\.jdks", "$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java",
               "$env:ProgramFiles\Microsoft", "$env:ProgramFiles\Zulu", "$env:ProgramFiles\BellSoft",
               "$env:ProgramFiles\Amazon Corretto")
    foreach ($r in $roots) {
        if (Test-Path $r) { $candidates += (Get-ChildItem $r -Directory | Sort-Object Name -Descending | ForEach-Object FullName) }
    }
    $seen = @()
    foreach ($d in $candidates) {
        $v = JdkMajor $d
        $seen += "$d (" + $(if ($v) { "Java $v" } else { "not a JDK" }) + ")"
        if ($v -ge 25 -and (Test-Path "$d\bin\jpackage.exe")) { return $d }
    }
    throw ("No JDK 25 or newer found. Install one (e.g. Temurin 25 from https://adoptium.net) or set JAVA_HOME.`n" +
           "Looked at:`n  " + ($seen -join "`n  "))
}
$env:JAVA_HOME = Find-Jdk
$env:Path = "$env:JAVA_HOME\bin;$env:Path"      # Maven and jpackage both use this JDK
Write-Host "JDK: $env:JAVA_HOME (Java $(JdkMajor $env:JAVA_HOME))"
$jpackage = "$env:JAVA_HOME\bin\jpackage.exe"
$jdeps = "$env:JAVA_HOME\bin\jdeps.exe"

<#
  Maven: "mvn" on PATH, else MAVEN_HOME / M2_HOME, else Apache Maven $MavenVersion downloaded once from
  archive.apache.org into .tools\ (git-ignored), checked against the published SHA-512 and reused next time.
#>
$MavenVersion = "3.9.9"
function Find-Maven {
    $c = Get-Command mvn -ErrorAction SilentlyContinue
    if ($c) { return $c.Source }
    foreach ($h in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if ($h -and (Test-Path "$h\bin\mvn.cmd")) { return "$h\bin\mvn.cmd" }
    }
    $mavenHome = "$root\.tools\apache-maven-$MavenVersion"
    if (Test-Path "$mavenHome\bin\mvn.cmd") { return "$mavenHome\bin\mvn.cmd" }

    $url = "https://archive.apache.org/dist/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip"
    $zip = "$root\.tools\apache-maven-$MavenVersion-bin.zip"
    New-Item -ItemType Directory -Force "$root\.tools" | Out-Null
    Write-Host "mvn not found: downloading Apache Maven $MavenVersion ..."
    $ProgressPreference = "SilentlyContinue"   # Invoke-WebRequest is very slow with the progress bar
    # Windows PowerShell 5.1 on older Windows may not offer TLS 1.2 by default
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest $url -OutFile $zip -UseBasicParsing
    $sha = Invoke-WebRequest "$url.sha512" -UseBasicParsing
    $text = if ($sha.Content -is [byte[]]) { [Text.Encoding]::ASCII.GetString($sha.Content) } else { $sha.Content }
    $expected = ($text.Trim() -split '\s+')[0].ToLower()
    $actual = (Get-FileHash $zip -Algorithm SHA512).Hash.ToLower()
    if ($expected -ne $actual) {
        Remove-Item -Force $zip
        throw "Maven download failed the SHA-512 check (expected $expected, got $actual)"
    }
    Expand-Archive $zip -DestinationPath "$root\.tools" -Force
    Remove-Item -Force $zip
    if (-not (Test-Path "$mavenHome\bin\mvn.cmd")) { throw "Maven unpacked, but $mavenHome\bin\mvn.cmd is missing" }
    Write-Host "Maven $MavenVersion is in $mavenHome (used again next time)"
    return "$mavenHome\bin\mvn.cmd"
}

# ---- version: 0.2.0-SNAPSHOT -> 0.2.0 (jpackage wants numbers only)
[xml]$pom = Get-Content "$root\pom.xml"
$version = ($pom.project.version -replace '-.*$', '')

# ---- jars
if (-not $SkipBuild) {
    $mvn = Find-Maven
    Write-Host "Maven: $mvn"
    & $mvn -B -ntp -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }
}
$guiJar = "$root\msa-gui\target\msa-gui.jar"
$probeJar = "$root\msa-tools\target\msa-probe.jar"
foreach ($j in @($guiJar, $probeJar)) { if (-not (Test-Path $j)) { throw "$j missing: run without -SkipBuild" } }

# ---- libusb
if (-not $LibUsb) { $LibUsb = $env:MSA_LIBUSB }
if (-not $LibUsb) { $LibUsb = "$root\libusb-1.0.dll" }
if (-not (Test-Path $LibUsb)) {
    throw "libusb-1.0.dll not found ($LibUsb). Pass -LibUsb PATH or put the 64-bit dll in the repository root."
}

# ---- staging
$out = "$root\target\windows"
if (Test-Path $out) { Remove-Item -Recurse -Force $out }
$staging = "$out\input"
New-Item -ItemType Directory $staging | Out-Null
Copy-Item $guiJar, $probeJar, $LibUsb $staging

# ---- Java modules the jars need (jdeps), plus the ones JavaFX and libusb (FFM) use at run time
$mods = "$(& $jdeps --ignore-missing-deps --multi-release 25 --print-module-deps $guiJar $probeJar)".Trim()
if ($LASTEXITCODE -ne 0 -or -not $mods) { $mods = "java.base" }
$mods = "$mods,java.desktop,java.logging,java.xml,jdk.unsupported,jdk.charsets"
Write-Host "Java modules: $mods"

# ---- app image: MSA.exe (no console) + msa-probe.exe (console), runtime trimmed by jlink
#      $APPDIR is expanded by the launcher to the folder holding the jars and the dll.
& $jpackage --type app-image --name MSA --app-version $version --vendor "MSA Java port" `
    --description "Modular Spectrum Analyzer" `
    --input $staging --main-jar msa-gui.jar --main-class msa.gui.MsaGui `
    --add-modules $mods `
    --jlink-options "--strip-debug --no-man-pages --no-header-files --compress=zip-6" `
    --java-options "--enable-native-access=ALL-UNNAMED" `
    --java-options "--sun-misc-unsafe-memory-access=allow" `
    --java-options '-Dmsa.libusb=$APPDIR\libusb-1.0.dll' `
    --add-launcher "msa-probe=$PSScriptRoot\msa-probe.properties" `
    --dest $out
if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

# ---- calibration and settings next to MSA.exe (found by msa.config.MsaInfo), without local state files
$app = "$out\MSA"
Copy-Item -Recurse "$root\msa-info" "$app\msa-info"
Get-ChildItem "$app\msa-info" -Recurse -Include "java-session.properties", "*.bak", "*.tmp" | Remove-Item -Force
Copy-Item "$PSScriptRoot\README-Windows.txt" "$app\README.txt"
Remove-Item -Recurse -Force $staging

# ---- zip
$zip = "$out\MSA-windows-$version.zip"
Compress-Archive -Path $app -DestinationPath $zip
$size = [math]::Round((Get-Item $zip).Length / 1MB, 1)
Write-Host "Done: $app  and  $zip ($size MB)"
