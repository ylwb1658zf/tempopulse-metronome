param(
    [string]$Sdk = "",
    [switch]$Clean
)

$ErrorActionPreference = "Continue"
$root = Split-Path -Parent $PSScriptRoot
if ((Split-Path -Leaf $PSScriptRoot) -eq "tools") { $root = Split-Path -Parent $PSScriptRoot }
if (-not $Sdk) {
    if ($env:ANDROID_SDK_ROOT) { $Sdk = $env:ANDROID_SDK_ROOT }
    elseif ($env:ANDROID_HOME) { $Sdk = $env:ANDROID_HOME }
    else { $Sdk = Join-Path (Split-Path -Parent $root) "tuner-work\android-sdk" }
}
$Sdk = (Resolve-Path $Sdk).Path
$androidJar = Join-Path $Sdk "platforms\android-35\android.jar"
if (!(Test-Path $androidJar)) { throw "android.jar not found: $androidJar" }
$buildToolsDir = Get-ChildItem (Join-Path $Sdk "build-tools") -Directory |
    Sort-Object Name -Descending | Select-Object -First 1
if (-not $buildToolsDir) { throw "Android build-tools not found under $Sdk" }
$buildTools = $buildToolsDir.FullName
$aapt2 = Join-Path $buildTools "aapt2.exe"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$lambdaStubs = Join-Path $buildTools "core-lambda-stubs.jar"
foreach ($tool in @($aapt2, $d8, $zipalign, $apksigner, $lambdaStubs)) {
    if (!(Test-Path $tool)) { throw "Missing build tool: $tool" }
}

$app = Join-Path $root "app\src\main"
$build = Join-Path $root "build"
$gen = Join-Path $build "gen"
$classes = Join-Path $build "classes"
$dex = Join-Path $build "dex"
$resZip = Join-Path $build "resources.zip"
$baseApk = Join-Path $build "base.apk"
$unsigned = Join-Path $build "TempoPulse-unsigned.apk"
$aligned = Join-Path $build "TempoPulse-aligned.apk"
$keystore = Join-Path $root "keystore\tempopulse.jks"
$outApk = Join-Path $root "TempoPulse-1.0.0.apk"

if ($Clean -and (Test-Path $build)) { Remove-Item -Recurse -Force $build }
New-Item -ItemType Directory -Force -Path $build, $gen, $classes, $dex | Out-Null

Write-Host "==> Compiling resources"
& $aapt2 compile --dir (Join-Path $app "res") -o $resZip
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> Linking resources"
& $aapt2 link -o $baseApk -I $androidJar --manifest (Join-Path $app "AndroidManifest.xml") `
    -R $resZip --java $gen --auto-add-overlay -A (Join-Path $app "assets") `
    --min-sdk-version 23 --target-sdk-version 35 --version-code 1 --version-name "1.0.0"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> Compiling Java"
$javaFiles = Get-ChildItem (Join-Path $app "java") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
$rFile = Join-Path $gen "com\tempopulse\metronome\R.java"
if (Test-Path $rFile) { $javaFiles += $rFile } else { throw "R.java not generated" }
$argFile = Join-Path $build "javac.args"
$javaFiles | ForEach-Object { '"' + ($_ -replace '\\', '/') + '"' } | Set-Content -Encoding ASCII $argFile
& javac -encoding UTF-8 -source 8 -target 8 -bootclasspath "$androidJar;$lambdaStubs" `
    -d $classes "@$argFile" -Xlint:-options
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "==> Dexing"
$classFiles = Get-ChildItem $classes -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --lib $androidJar --min-api 23 --output $dex $classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
$dexFile = Join-Path $dex "classes.dex"
if (!(Test-Path $dexFile)) { throw "classes.dex missing" }

Write-Host "==> Packaging"
$py = @"
import shutil, zipfile, os
base = r'$baseApk'
unsigned = r'$unsigned'
dex = r'$dexFile'
shutil.copyfile(base, unsigned)
with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as z:
    info = zipfile.ZipInfo('classes.dex')
    info.compress_type = zipfile.ZIP_STORED
    with open(dex, 'rb') as f:
        z.writestr(info, f.read())
print('packaged', os.path.getsize(unsigned), 'bytes')
"@
$pyFile = Join-Path $build "package.py"
Set-Content -Encoding UTF8 $pyFile $py
python $pyFile
if ($LASTEXITCODE -ne 0) { throw "packaging failed" }

Write-Host "==> Aligning"
& $zipalign -f 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

if (!(Test-Path $keystore)) {
    Write-Host "==> Creating local signing key"
    New-Item -ItemType Directory -Force -Path (Split-Path $keystore) | Out-Null
    & keytool -genkeypair -v -keystore $keystore -alias tempopulse -keyalg RSA `
        -keysize 2048 -validity 10000 -storepass tempopulse -keypass tempopulse `
        -dname "CN=TempoPulse, OU=Mobile, O=TempoPulse, L=Shanghai, C=CN"
    if ($LASTEXITCODE -ne 0) { throw "keytool failed" }
}

Write-Host "==> Signing"
& $apksigner sign --ks $keystore --ks-key-alias tempopulse `
    --ks-pass pass:tempopulse --key-pass pass:tempopulse `
    --out $outApk $aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
& $apksigner verify --print-certs $outApk
if ($LASTEXITCODE -ne 0) { throw "apk verification failed" }

Write-Host ""
Write-Host "APK: $outApk"
Write-Host ("Size: {0:N0} bytes" -f (Get-Item $outApk).Length)
