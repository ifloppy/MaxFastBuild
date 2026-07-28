$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$server = Join-Path (Split-Path -Parent $root) "test-server-leaf"
$release = Join-Path $root "release"

if (-not (Test-Path -LiteralPath $server)) { throw "Leaf test server directory is missing: $server" }
if (-not (Test-Path -LiteralPath $release)) { New-Item -ItemType Directory -Path $release | Out-Null }

$paper = Get-ChildItem -LiteralPath (Join-Path $root "maxfastbuild-paper\build\libs") -Filter "*.jar" |
    Where-Object { $_.Name -notmatch "sources" } | Select-Object -First 1
$fabric = Get-ChildItem -LiteralPath (Join-Path $root "maxfastbuild-fabric\build\libs") -Filter "*.jar" |
    Where-Object { $_.Name -notmatch "sources|dev" } | Select-Object -First 1

if (-not $paper) { throw "Paper plugin JAR was not built" }
if (-not $fabric) { throw "Fabric client JAR was not built" }

Copy-Item -LiteralPath $paper.FullName -Destination (Join-Path $server "plugins\MaxFastBuild.jar") -Force
Copy-Item -LiteralPath $fabric.FullName -Destination (Join-Path $release "MaxFastBuild-Fabric-26.2.jar") -Force

"Paper plugin: $($paper.FullName)"
"Client mod: $(Join-Path $release 'MaxFastBuild-Fabric-26.2.jar')"
