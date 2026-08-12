param(
    [string]$JdkHome = $env:JAVA_HOME,
    [switch]$SkipTests
)

$ErrorActionPreference = "Stop"
$moduleRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$reactorRoot = Split-Path -Parent $moduleRoot
$targetRoot = Join-Path $moduleRoot "target"
$inputRoot = Join-Path $targetRoot "jpackage-input"
$distRoot = Join-Path $targetRoot "dist"
$appName = "JT Terminal Simulator"
$appImage = Join-Path $distRoot $appName
$archivePath = Join-Path $distRoot "jt-terminal-simulator-windows-x64.zip"

function Clear-GeneratedReadOnlyAttributes([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }
    $targetPrefix = [System.IO.Path]::GetFullPath($targetRoot).TrimEnd('\') + '\'
    $fullPath = [System.IO.Path]::GetFullPath($Path)
    if (-not $fullPath.StartsWith($targetPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to change attributes outside simulator target: $fullPath"
    }
    $items = @((Get-Item -LiteralPath $fullPath -Force)) +
        @(Get-ChildItem -LiteralPath $fullPath -Recurse -Force)
    foreach ($item in $items) {
        if (($item.Attributes -band [System.IO.FileAttributes]::ReadOnly) -ne 0) {
            $item.Attributes = $item.Attributes -band (-bnot [System.IO.FileAttributes]::ReadOnly)
        }
    }
}

if ([System.Environment]::OSVersion.Platform -ne [System.PlatformID]::Win32NT) {
    throw "The portable simulator can only be packaged on Windows"
}
if (-not [System.Environment]::Is64BitOperatingSystem) {
    throw "Windows x64 is required"
}

if ([string]::IsNullOrWhiteSpace($JdkHome)) {
    throw "JdkHome or JAVA_HOME must point to JDK 25"
}

$javaExecutable = Join-Path $JdkHome "bin\java.exe"
$jpackageExecutable = Join-Path $JdkHome "bin\jpackage.exe"
if (-not (Test-Path -LiteralPath $javaExecutable) -or -not (Test-Path -LiteralPath $jpackageExecutable)) {
    throw "JDK 25 java.exe and jpackage.exe were not found under $JdkHome"
}

$previousErrorActionPreference = $ErrorActionPreference
try {
    $ErrorActionPreference = "Continue"
    $javaVersion = @(& $javaExecutable -version 2>&1)
    $javaVersionExitCode = $LASTEXITCODE
    $javaSettingsOutput = @(& $javaExecutable -XshowSettings:properties -version 2>&1)
    $javaSettingsExitCode = $LASTEXITCODE
} finally {
    $ErrorActionPreference = $previousErrorActionPreference
}
if ($javaVersionExitCode -ne 0 -or $javaSettingsExitCode -ne 0) {
    throw "Unable to inspect the configured JDK"
}
if (($javaVersion | Out-String) -notmatch 'version "25') {
    throw "JDK 25 is required; detected: $($javaVersion | Select-Object -First 1)"
}
$javaSettings = $javaSettingsOutput | Out-String
if ($javaSettings -notmatch '(?m)^\s*os\.arch\s*=\s*(amd64|x86_64)\s*$') {
    throw "An x64 JDK is required"
}

$env:JAVA_HOME = (Resolve-Path -LiteralPath $JdkHome).Path
$env:Path = "$env:JAVA_HOME\bin;$env:Path"

Clear-GeneratedReadOnlyAttributes $appImage

Push-Location $reactorRoot
try {
    $mavenArguments = @("-B", "-ntp", "-pl", "jt-terminal-simulator", "-am")
    if ($SkipTests) {
        $mavenArguments += "-DskipTests"
    }
    $mavenArguments += @("clean", "package")
    & mvn @mavenArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}

foreach ($path in @($inputRoot, $appImage)) {
    if (Test-Path -LiteralPath $path) {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}
New-Item -ItemType Directory -Path $inputRoot -Force | Out-Null
New-Item -ItemType Directory -Path $distRoot -Force | Out-Null

$mainJars = @(Get-ChildItem -LiteralPath $targetRoot -Filter "jt-terminal-simulator-*.jar" |
    Where-Object { $_.Name -notmatch 'sources|javadoc' } |
    Sort-Object Name)
if ($mainJars.Count -ne 1) {
    throw "Expected exactly one simulator JAR under $targetRoot; found $($mainJars.Count)"
}
$mainJar = $mainJars[0]
Copy-Item -LiteralPath $mainJar.FullName -Destination $inputRoot

$dependencyRoot = Join-Path $targetRoot "app\lib"
if (-not (Test-Path -LiteralPath $dependencyRoot -PathType Container)) {
    throw "Runtime dependency directory is missing: $dependencyRoot"
}
$dependencyJars = @(Get-ChildItem -LiteralPath $dependencyRoot -Filter "*.jar")
if ($dependencyJars.Count -eq 0) {
    throw "No runtime dependency JARs were copied to $dependencyRoot"
}
$dependencyJars | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination $inputRoot
}

& $jpackageExecutable `
    --type app-image `
    --dest $distRoot `
    --input $inputRoot `
    --name $appName `
    --main-jar $mainJar.Name `
    --main-class io.github.jtplatform.simulator.SimulatorLauncher `
    --app-version 0.1.0 `
    --vendor "JT Platform" `
    --description "JT/T 808 and JT/T 1078 camera terminal simulator" `
    --java-options "-Dfile.encoding=UTF-8"
if ($LASTEXITCODE -ne 0) {
    throw "jpackage failed with exit code $LASTEXITCODE"
}
Clear-GeneratedReadOnlyAttributes $appImage

$launcherPath = Join-Path $appImage "$appName.exe"
if (-not (Test-Path -LiteralPath $launcherPath)) {
    throw "Packaged launcher is missing"
}
if (-not (Test-Path -LiteralPath (Join-Path $appImage "runtime\bin\java.dll"))) {
    throw "Packaged Java runtime is missing"
}
$forbiddenMediaTools = @("ffmpeg.exe", "ffprobe.exe", "ffmpeg", "ffprobe")
$bundledMediaTool = Get-ChildItem -LiteralPath $appImage -Recurse -File | Where-Object {
    $forbiddenMediaTools -contains $_.Name.ToLowerInvariant()
} | Select-Object -First 1
if ($null -ne $bundledMediaTool) {
    throw "FFmpeg/ffprobe must not be bundled in the simulator package: $($bundledMediaTool.FullName)"
}

$smokeProcess = Start-Process -FilePath $launcherPath -ArgumentList "--smoke-test" `
    -PassThru -WindowStyle Hidden
try {
    if (-not $smokeProcess.WaitForExit(15000)) {
        $smokeProcess.Kill()
        $smokeProcess.WaitForExit()
        throw "Packaged launcher smoke test timed out after 15 seconds"
    }
    $smokeProcess.Refresh()
    if ($smokeProcess.ExitCode -ne 0) {
        throw "Packaged launcher smoke test failed with exit code $($smokeProcess.ExitCode)"
    }
} finally {
    if (-not $smokeProcess.HasExited) {
        $smokeProcess.Kill()
        $smokeProcess.WaitForExit()
    }
    $smokeProcess.Dispose()
}

if (Test-Path -LiteralPath $archivePath) {
    Remove-Item -LiteralPath $archivePath -Force
}
Compress-Archive -LiteralPath $appImage -DestinationPath $archivePath -CompressionLevel Optimal
if (-not (Test-Path -LiteralPath $archivePath -PathType Leaf) -or
        (Get-Item -LiteralPath $archivePath).Length -eq 0) {
    throw "Portable ZIP was not created"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($archivePath)
try {
    $entryNames = @($archive.Entries | ForEach-Object { $_.FullName.Replace('\', '/') })
    $launcherEntry = $entryNames | Where-Object {
        $_ -eq "$appName.exe" -or $_.EndsWith("/$appName.exe", [System.StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -First 1
    $runtimeEntry = $entryNames | Where-Object {
        $_.EndsWith("runtime/bin/java.dll", [System.StringComparison]::OrdinalIgnoreCase)
    } | Select-Object -First 1
    $mediaToolEntry = $entryNames | Where-Object {
        $entryName = [System.IO.Path]::GetFileName($_)
        $forbiddenMediaTools -contains $entryName.ToLowerInvariant()
    } | Select-Object -First 1
    if ($null -eq $launcherEntry) {
        throw "Packaged ZIP launcher is missing"
    }
    if ($null -eq $runtimeEntry) {
        throw "Packaged ZIP runtime is missing"
    }
    if ($null -ne $mediaToolEntry) {
        throw "FFmpeg/ffprobe must not be bundled in the simulator ZIP: $mediaToolEntry"
    }
} finally {
    $archive.Dispose()
}

$archiveItem = Get-Item -LiteralPath $archivePath
Write-Output "$($archiveItem.FullName) ($($archiveItem.Length) bytes)"
