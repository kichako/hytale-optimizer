# Builds build/HytaleOptimizer.jar from your own HytaleServer.jar.
# Needs a JDK 25+ (java, javac, jar) and git on the PATH.
param(
    [string]$ServerJar = "$env:APPDATA\Hytale\install\release\package\game\latest\Server\HytaleServer.jar"
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$VineflowerVersion = '1.11.1'
$VineflowerSha256 = 'a615d07ddbbcd489369674f40e42df639c32be95410890b38f173d5c1e2ea39c'

$root = $PSScriptRoot
$build = Join-Path $root 'build'
$work = Join-Path $build 'work'
$tools = Join-Path $root 'tools'
$targets = Join-Path $root 'patches\targets.properties'

function Get-Sha256([byte[]]$bytes) {
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try { return (($sha.ComputeHash($bytes) | ForEach-Object { $_.ToString('x2') }) -join '') } finally { $sha.Dispose() }
}

function Invoke-Native([string]$what, [scriptblock]$cmd) {
    & $cmd
    if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)" }
}

if (-not (Test-Path $ServerJar)) { throw "HytaleServer.jar not found: $ServerJar (pass -ServerJar <path>)" }
if (Test-Path $build) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force (Join-Path $work 'classes'), (Join-Path $work 'compiled'), (Join-Path $work 'plugin'), $tools | Out-Null

# 1. Vineflower
$vineflower = Join-Path $tools "vineflower-$VineflowerVersion.jar"
if (-not (Test-Path $vineflower)) {
    Write-Host "Downloading Vineflower $VineflowerVersion..."
    Invoke-WebRequest -UseBasicParsing "https://repo1.maven.org/maven2/org/vineflower/vineflower/$VineflowerVersion/vineflower-$VineflowerVersion.jar" -OutFile $vineflower
}
if ((Get-Sha256 ([System.IO.File]::ReadAllBytes($vineflower))) -ne $VineflowerSha256) {
    Remove-Item $vineflower
    throw 'Vineflower checksum mismatch, deleted the download. Try again.'
}

# 2. Check the server version and pull out only the classes we patch
$index = @{}
Get-Content $targets | Where-Object { $_ -match '=' } | ForEach-Object {
    $name, $rest = $_ -split '=', 2
    $index[$name] = ($rest -split ';')[0]
}
$zip = [System.IO.Compression.ZipFile]::OpenRead($ServerJar)
try {
    foreach ($internal in $index.Keys) {
        $entry = $zip.GetEntry("$internal.class")
        if ($null -eq $entry) { throw "$internal.class is missing from $ServerJar. This server version is not supported." }
        $ms = New-Object System.IO.MemoryStream
        $s = $entry.Open(); try { $s.CopyTo($ms) } finally { $s.Dispose() }
        $bytes = $ms.ToArray()
        if ((Get-Sha256 $bytes) -ne $index[$internal]) {
            throw "$internal differs from the version these patches were made for (HytaleServer 0.7.0, rev 648ebf37). This server version is not supported yet."
        }
        $dest = Join-Path $work ("classes\" + $internal.Replace('/', '\') + '.class')
        New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
        [System.IO.File]::WriteAllBytes($dest, $bytes)
    }
} finally { $zip.Dispose() }

# 3. Decompile, patch, compile
Write-Host 'Decompiling patch targets...'
Invoke-Native 'Vineflower' { java -jar $vineflower -dgs=1 -log=WARN "-e=$ServerJar" (Join-Path $work 'classes') (Join-Path $work 'src') | Out-Null }
Write-Host 'Applying patches...'
Push-Location (Join-Path $work 'src')
try { Invoke-Native 'git apply' { git apply -p1 --whitespace=nowarn (Get-ChildItem (Join-Path $root 'patches\*.patch') | ForEach-Object { $_.FullName }) } } finally { Pop-Location }

function Invoke-Javac([string]$srcDir, [string]$outDir) {
    $sources = @(Get-ChildItem -Recurse -Filter *.java $srcDir | ForEach-Object { $_.FullName })
    Invoke-Native 'javac' { javac --release 25 -nowarn -proc:none -encoding UTF-8 -cp $ServerJar -d $outDir @sources }
}
Write-Host 'Compiling...'
Invoke-Javac (Join-Path $work 'src') (Join-Path $work 'compiled')
Invoke-Javac (Join-Path $root 'plugin\src') (Join-Path $work 'plugin')

# 4. Package: replacement classes + the hashes they are pinned to
$compiled = Join-Path $work 'compiled'
Get-ChildItem -Recurse -Filter *.class $compiled | ForEach-Object {
    $internal = $_.FullName.Substring($compiled.Length + 1).Replace('\', '/') -replace '\.class$', ''
    if (-not $index.ContainsKey($internal)) { throw "Patched sources produced an unexpected class: $internal" }
    $dest = Join-Path $work ("plugin\hytaleopt\classes\" + $internal.Replace('/', '\') + '.class')
    New-Item -ItemType Directory -Force (Split-Path $dest) | Out-Null
    Copy-Item $_.FullName $dest
}
Copy-Item $targets (Join-Path $work 'plugin\hytaleopt\patches.properties')
Copy-Item -Recurse (Join-Path $root 'plugin\resources\*') (Join-Path $work 'plugin')

$jarOut = Join-Path $build 'HytaleOptimizer.jar'
Invoke-Native 'jar' { jar --create --file $jarOut -C (Join-Path $work 'plugin') . }
Write-Host ''
Write-Host "Done: $jarOut"
