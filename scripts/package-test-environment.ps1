# Legacy helper — prefer: ./gradlew build  (or deployPaperToLeaf)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\gradlew.bat deployPaperToLeaf copyReleaseJars --quiet
    if ($LASTEXITCODE -ne 0) { throw "gradlew deployPaperToLeaf failed: $LASTEXITCODE" }
} finally {
    Pop-Location
}
$dest = Join-Path (Split-Path -Parent $root) "test-server-leaf\plugins\MaxFastBuild.jar"
"Paper plugin -> $dest"
