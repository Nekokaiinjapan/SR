# build_apk.ps1 - assemble a signed debug APK without Gradle.
#
# Gradle needs the AGP dependency graph (offline cache here is incomplete) and
# writes outside the sandbox; this app has zero libraries, so we can drive
# aapt2 / javac / d8 / zipalign / apksigner directly.

param(
    # x86_64 is only useful for the emulator; leaving it out halves the APK.
    [switch]$IncludeX86
)

$ErrorActionPreference = 'Stop'

$abis = @('arm64-v8a')
if ($IncludeX86) { $abis += 'x86_64' }

# d8.bat / apksigner.bat read JAVA_HOME; the machine-wide one points at a
# broken JDK, so pin it to Android Studio's JBR for this process.
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'

$root  = 'E:\XXX\android-sr'
$sdk   = 'C:\Users\15007\AppData\Local\Android\Sdk'
$bt    = Join-Path $sdk 'build-tools\33.0.2'
$jar   = Join-Path $sdk 'platforms\android-32\android.jar'
$jdk   = 'C:\Program Files\Android\Android Studio\jbr\bin'
$out   = Join-Path $root 'build-apk'
$app   = Join-Path $root 'app\src\main'

$exe = @{
    aapt2     = Join-Path $bt 'aapt2.exe'
    zipalign  = Join-Path $bt 'zipalign.exe'
    apksigner = Join-Path $bt 'apksigner.bat'
    javac     = Join-Path $jdk 'javac.exe'
    java      = Join-Path $jdk 'java.exe'
    keytool   = Join-Path $jdk 'keytool.exe'
    d8        = Join-Path $bt 'd8.bat'
}
foreach ($k in $exe.Keys) {
    if (-not (Test-Path $exe[$k])) { throw "missing tool: $k -> $($exe[$k])" }
}

if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out, "$out\gen", "$out\classes", "$out\dex" -Force | Out-Null

function Step($msg) { Write-Host "=== $msg ===" }

# --------------------------------------------------------------------------- #
Step '1/6 aapt2 compile'
& $exe.aapt2 compile --dir (Join-Path $app 'res') -o "$out\res.zip"
if ($LASTEXITCODE -ne 0) { throw 'aapt2 compile failed' }

Step '2/6 aapt2 link'
& $exe.aapt2 link -o "$out\base.apk" `
    -I $jar `
    --manifest (Join-Path $app 'AndroidManifest.xml') `
    -R "$out\res.zip" `
    --java "$out\gen" `
    --auto-add-overlay
# NOTE: no -A here. aapt2 on Windows writes asset entry names with backslashes,
# which AssetManager cannot resolve. Assets are added below with '/' separators.
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

# --------------------------------------------------------------------------- #
Step '3/6 javac'
$sources = @()
$sources += (Get-ChildItem "$out\gen" -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem (Join-Path $app 'java') -Recurse -Filter *.java | ForEach-Object { $_.FullName })

& $exe.javac -encoding UTF-8 -source 8 -target 8 -nowarn `
    -bootclasspath $jar -d "$out\classes" $sources 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# --------------------------------------------------------------------------- #
Step '4/6 d8'
$classes = Get-ChildItem "$out\classes" -Recurse -Filter *.class | ForEach-Object { $_.FullName }
# d8.bat uses -Djava.ext.dirs, which JDK 9+ rejects; drive the jar directly.
& $exe.java -cp (Join-Path $bt 'lib\d8.jar') com.android.tools.r8.D8 `
    --lib $jar --min-api 24 --release --output "$out\dex" $classes 2>&1 | Out-String | Write-Host
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }
if (-not (Test-Path "$out\dex\classes.dex")) { throw 'classes.dex was not produced' }

# --------------------------------------------------------------------------- #
Step '5/6 package + align + sign'
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

$unsigned = "$out\unsigned.apk"
Copy-Item "$out\base.apk" $unsigned

$zip = [System.IO.Compression.ZipFile]::Open($unsigned, 'Update')
try {
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $zip, "$out\dex\classes.dex", 'classes.dex',
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null

    $assetsDir = Join-Path $app 'assets'
    if (Test-Path $assetsDir) {
        Get-ChildItem $assetsDir -Recurse -File | ForEach-Object {
            $rel = $_.FullName.Substring($assetsDir.Length + 1).Replace('\', '/')
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $_.FullName, "assets/$rel",
                [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
            Write-Host "    + assets/$rel"
        }
    }

    foreach ($abi in $abis) {
        $dir = Join-Path $app "jniLibs\$abi"
        if (-not (Test-Path $dir)) { continue }
        Get-ChildItem $dir -Filter *.so -File | ForEach-Object {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip, $_.FullName, "lib/$abi/$($_.Name)",
                [System.IO.Compression.CompressionLevel]::NoCompression) | Out-Null
            Write-Host "    + lib/$abi/$($_.Name)"
        }
    }
} finally {
    $zip.Dispose()
}

$aligned = "$out\aligned.apk"
& $exe.zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

$ks = Join-Path $root 'debug.keystore'
if (-not (Test-Path $ks)) {
    & $exe.keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 `
        -dname "CN=Android Debug,O=Android,C=US" 2>&1 | Out-String | Write-Host
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

$final = Join-Path $root 'SR.apk'
if (Test-Path $final) { Remove-Item $final -Force }
$signer = Join-Path $bt 'lib\apksigner.jar'
& $exe.java -cp $signer com.android.apksigner.ApkSignerTool sign `
    --ks $ks --ks-pass pass:android --key-pass pass:android --out $final $aligned
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

Step '6/6 verify'
& $exe.java -cp $signer com.android.apksigner.ApkSignerTool verify --print-certs $final
$size = [math]::Round((Get-Item $final).Length / 1MB, 2)
Write-Host ''
Write-Host "APK: $final  ($size MB)"
