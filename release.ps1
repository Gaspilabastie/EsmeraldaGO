<#
  Publica una versión nueva de EsmeraldaGO en GitHub Releases (la app la encuentra con "Buscar actualización").

  Uso:
    .\release.ps1 -Notes "Qué cambió"                 # sube el último número (0.2.0 -> 0.2.1)
    .\release.ps1 -Notes "Qué cambió" -Version 0.3.0  # versión explícita
    .\release.ps1 -Notes "..." -SkipTests             # sin correr los tests

  Pasos: sube versionCode/versionName en version.properties, corre los tests, compila el APK de release,
  hace commit + tag vX.Y.Z, pushea y crea la release con el APK adjunto.
#>
param(
    [Parameter(Mandatory = $true)][string]$Notes,
    [string]$Version,
    [switch]$SkipTests
)
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$env:JAVA_HOME = "$env:LOCALAPPDATA\DevTools\jdk17"
$env:PATH = "$env:LOCALAPPDATA\DevTools\git\cmd;$env:PATH"  # gh también necesita git
$git = "$env:LOCALAPPDATA\DevTools\git\cmd\git.exe"
$gh = "$env:LOCALAPPDATA\DevTools\gh\bin\gh.exe"
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Invoke-Checked([string]$what, [scriptblock]$cmd) {
    # PowerShell 5.1 convierte cualquier línea de stderr (avisos de git/gradle) en error: se juzga por el código de salida.
    $ErrorActionPreference = 'Continue'
    & $cmd 2>&1 | ForEach-Object { "$_" }
    if ($LASTEXITCODE -ne 0) { throw "Falló: $what (código $LASTEXITCODE)" }
}

# --- Versión ---
$propsPath = Join-Path $PSScriptRoot 'version.properties'
$original = [IO.File]::ReadAllText($propsPath)
$code = [int]([regex]::Match($original, 'versionCode=(\d+)').Groups[1].Value) + 1
$current = [regex]::Match($original, 'versionName=([\d.]+)').Groups[1].Value
if (-not $Version) {
    $parts = $current.Split('.') | ForEach-Object { [int]$_ }
    $parts[-1]++
    $Version = $parts -join '.'
}
$tag = "v$Version"
if (& $git tag --list $tag) { throw "Ya existe el tag $tag" }

$updated = $original -replace 'versionCode=\d+', "versionCode=$code" -replace 'versionName=[\d.]+', "versionName=$Version"
[IO.File]::WriteAllText($propsPath, $updated, $utf8)
Write-Host "Versión $current -> $Version (code $code)"

try {
    # --- Tests + build ---
    $tasks = @(':app:assembleRelease')
    if (-not $SkipTests) { $tasks = @(':core:test', ':app:testDebugUnitTest') + $tasks }
    Invoke-Checked 'gradle' { & .\gradlew.bat @tasks --console=plain }
} catch {
    [IO.File]::WriteAllText($propsPath, $original, $utf8)
    throw
}

$apk = Join-Path $PSScriptRoot "app\build\outputs\apk\release\EsmeraldaGO-$Version.apk"
Copy-Item 'app\build\outputs\apk\release\app-release.apk' $apk -Force
Copy-Item $apk (Join-Path $PSScriptRoot 'EsmeraldaGO.apk') -Force

# --- Commit, tag, push, release ---
$scratch = Join-Path $env:TEMP 'esmeraldago-release'
New-Item -ItemType Directory -Force $scratch | Out-Null
$msgFile = Join-Path $scratch 'commit.txt'
$notesFile = Join-Path $scratch 'notes.txt'
[IO.File]::WriteAllText($msgFile, "$tag`n`n$Notes`n", $utf8)
[IO.File]::WriteAllText($notesFile, $Notes, $utf8)

Invoke-Checked 'git add' { & $git add -A }
Invoke-Checked 'git commit' { & $git commit -q -F $msgFile }
Invoke-Checked 'git tag' { & $git tag $tag }
Invoke-Checked 'git push' { & $git push -q origin HEAD $tag }
Invoke-Checked 'gh release' { & $gh release create $tag $apk --title "EsmeraldaGO $Version" --notes-file $notesFile }

Write-Host "Listo: $tag publicado. En el teléfono: Opciones -> Buscar actualización."
