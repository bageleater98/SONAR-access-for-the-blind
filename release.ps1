# Publishes a new Sonar version: bumps the patch number, builds, commits, pushes, creates a GitHub release with the jar,
# and installs the jar into the Modrinth profile and the Desktop Sonar-mod.zip.
# Usage: .\release.ps1 "what changed"
param([Parameter(Mandatory = $true)][string]$Message)
$ErrorActionPreference = 'Continue' # git writes warnings to stderr; failures are caught through the exit code checks below
Set-Location $PSScriptRoot
$gh = 'C:\Program Files\GitHub CLI\gh.exe'
$repo = 'bageleater98/SONAR-access-for-the-blind'

$props = Get-Content gradle.properties -Raw
if ($props -notmatch '(?m)^version=(\d+)\.(\d+)\.(\d+)\s*$') { throw 'version= line not found in gradle.properties' }
$old = "$($Matches[1]).$($Matches[2]).$($Matches[3])"
$new = "$($Matches[1]).$($Matches[2]).$([int]$Matches[3] + 1)"
$props = $props -replace '(?m)^version=.*$', "version=$new"
[IO.File]::WriteAllText("$PWD\gradle.properties", $props, (New-Object Text.UTF8Encoding $false))

& .\gradlew.bat build -q
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$jar = "build\libs\sonar-$new.jar"
if (-not (Test-Path $jar)) { throw "$jar not found" }

git add -A
git commit -q -m "$Message (v$new)"
if ($LASTEXITCODE -ne 0) { throw 'commit failed' }
git push
if ($LASTEXITCODE -ne 0) { throw 'push failed' }
git tag "v$new"
git push origin "v$new"
if ($LASTEXITCODE -ne 0) { throw 'tag push failed' }
& $gh release create "v$new" $jar --title "Sonar v$new" --notes $Message -R $repo
if ($LASTEXITCODE -ne 0) { throw 'release failed' }

$mods = "$env:APPDATA\ModrinthApp\profiles\New instance (1)\mods"
Get-ChildItem $mods -Filter 'sonar-*.jar' | Remove-Item -Force
Copy-Item $jar $mods
$zipPath = "$env:USERPROFILE\Desktop\Sonar-mod.zip"
if (Test-Path $zipPath) {
	Add-Type -AssemblyName System.IO.Compression.FileSystem
	$z = [IO.Compression.ZipFile]::Open($zipPath, 'Update')
	@($z.Entries | Where-Object { $_.Name -like 'sonar-*.jar' }) | ForEach-Object { $_.Delete() }
	[IO.Compression.ZipFileExtensions]::CreateEntryFromFile($z, (Resolve-Path $jar).Path, "sonar-$new.jar") | Out-Null
	$z.Dispose()
}
"Released v$new (was v$old)"
