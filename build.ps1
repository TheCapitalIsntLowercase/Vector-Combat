# Builds vectorcombat-<version>.jar without Gradle, compiling against the NeoForge 21.1.x libraries the Modrinth App
# already downloaded. No other mod is needed (optional mods' APIs come from src\stubs). Usage:  powershell -File build.ps1 [-Neo 21.1.252] [-Release]
param(
    [string]$Neo = "21.1.252",
    [string]$Libraries = "$env:APPDATA\ModrinthApp\meta\libraries",
    [string]$Jdk = "C:\Program Files\Eclipse Adoptium\jdk-22.0.2.9-hotspot",
    [switch]$Release,
    [string]$Stamp
)
$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$out = Join-Path $root "build"
$classes = Join-Path $out "classes"
$tests = Join-Path $out "test-classes"
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Force $classes, $tests | Out-Null

# Classpath = every library from the NeoForge version manifest + the patched client jar.
$manifest = Get-Content "$env:APPDATA\ModrinthApp\meta\versions\1.21.1-$Neo\1.21.1-$Neo.json" -Raw | ConvertFrom-Json
$cp = @("$Libraries\net\neoforged\neoforge\$Neo\neoforge-$Neo-client.jar", "$Libraries\net\minecraft\client\1.21.1\client-1.21.1-official.jar")
foreach ($lib in $manifest.libraries) {
    $path = $lib.downloads.artifact.path
    if (-not $path -or $lib.name -match 'installertools|AutoRenaming|neoform|natives') { continue }
    $file = Join-Path $Libraries ($path -replace '/', '\')
    if (Test-Path $file) { $cp += $file }
}
$cpArg = ($cp -join ";") -replace '\\', '/'

function Compile($srcDir, $dest, $classpath) {
    $sources = Get-ChildItem $srcDir -Recurse -Filter *.java | ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' }
    $argFile = Join-Path $out ("javac-" + (Split-Path $dest -Leaf) + ".args")
    @("-d", "`"$($dest -replace '\\','/')`"", "-cp", "`"$classpath`"", "--release", "21", "-proc:none", "-Xlint:deprecation", "-encoding", "UTF-8") + $sources | Set-Content $argFile -Encoding ascii
    & "$Jdk\bin\javac.exe" "@$argFile"
    if ($LASTEXITCODE -ne 0) { throw "javac failed for $srcDir" }
}

# Stand-ins for the optional mods' APIs that compat code calls (src\stubs): on the compile classpath only, never in the jar, so no other mod is needed to build.
$stubs = Join-Path $out "stub-classes"
Compile (Join-Path $root "src\stubs\java") $stubs $cpArg
$mainCp = ($stubs -replace '\\', '/') + ";" + $cpArg
Compile (Join-Path $root "src\main\java") $classes $mainCp
Compile (Join-Path $root "src\test\java") $tests (($classes -replace '\\', '/') + ";" + $mainCp)

$data = Join-Path $root "src\main\resources\data\vectorcombat\vectorcombat"
$testCp = "$classes;$tests;$($cp -join ';')"
foreach ($test in "CondTest", "RulesTest", "DirectionTest", "ArcTest", "AnimTest", "HitstopTest", "HitFxTest", "PoseTest", "HandsTest", "GuardTest", "WindUpTest", "MoveTrackerTest", "SweepTest") {
    & "$Jdk\bin\java.exe" -ea -cp $testCp "dev.vectorcombat.$test"
    if ($LASTEXITCODE -ne 0) { throw "$test failed" }
}
& "$Jdk\bin\java.exe" -ea -cp $testCp dev.vectorcombat.AttackLoadTest (Join-Path $data "attacks") (Join-Path $data "animations") (Join-Path $data "mob_attacks")
if ($LASTEXITCODE -ne 0) { throw "AttackLoadTest failed" }
& "$Jdk\bin\java.exe" -ea -cp $testCp dev.vectorcombat.WeaponDataTest (Join-Path $data "attacks")
if ($LASTEXITCODE -ne 0) { throw "WeaponDataTest failed" }
& "$Jdk\bin\java.exe" -ea -cp $testCp dev.vectorcombat.ClaimTest (Join-Path $data "attacks")
if ($LASTEXITCODE -ne 0) { throw "ClaimTest failed" }
& "$Jdk\bin\java.exe" -ea -cp $testCp dev.vectorcombat.DistTest $classes (Join-Path $root "src\main\resources\vectorcombat.mixins.json")
if ($LASTEXITCODE -ne 0) { throw "DistTest failed" }
& "$Jdk\bin\java.exe" -ea -cp $testCp dev.vectorcombat.ConfigTest (Join-Path $root "src\main\resources\assets\vectorcombat\lang\en_us.json")
if ($LASTEXITCODE -ne 0) { throw "ConfigTest failed" }

Copy-Item (Join-Path $root "src\main\resources\*") $classes -Recurse -Force
$jar = Join-Path $out "vectorcombat-0.2.0.jar"
if ($Stamp -and -not $Release) { # a test build's own version, written into the jar only
    $toml = Join-Path $classes "META-INF\neoforge.mods.toml"
    $lines = (Get-Content $toml) -replace '^version = ".*"', "version = `"$Stamp`""
    [IO.File]::WriteAllLines($toml, [string[]]$lines, (New-Object Text.UTF8Encoding($false)))
}
if ($Release) {
    # A release: a version with no -dev or -diag (so the developer tools stay off, see Dev.java), and no arena data in the jar.
    $version = (Select-String -Path (Join-Path $root "src\main\resources\META-INF\neoforge.mods.toml") -Pattern '^version = "(.+)"').Matches[0].Groups[1].Value
    if ($version -match '-(dev|diag)') { throw "a release build needs a release version in neoforge.mods.toml, not $version" }
    Remove-Item (Join-Path $classes "data\vectorcombat\vectorcombat\arena") -Recurse -Force -ErrorAction SilentlyContinue
    $jar = Join-Path $out "vectorcombat-$version.jar"
}
& "$Jdk\bin\jar.exe" --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw "jar failed" }
Write-Host "Built $jar"
