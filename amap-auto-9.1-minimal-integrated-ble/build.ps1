param(
    [string] $InputApk = "..\amap-auto-cruise-wrapper-20260523.apk",
    [string] $OutputApk = ".\dist\AMapAuto_9.1.0.600087_minimal_integrated_ble.apk"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$javaHome = "D:\Soft\Android\Android Studio\jbr"
$env:JAVA_HOME = $javaHome
$sdk = "D:\Soft\Android\AndroidSDK"
$apktool = Join-Path $root "tools\apktool_2.9.3.jar"
$androidJar = Join-Path $sdk "platforms\android-34\android.jar"
$buildTools = Join-Path $sdk "build-tools\36.0.0"
$d8 = Join-Path $buildTools "d8.bat"
$zipalign = Join-Path $buildTools "zipalign.exe"
$apksigner = Join-Path $buildTools "apksigner.bat"
$keystore = Join-Path $env:USERPROFILE ".android\debug.keystore"
$input = [IO.Path]::GetFullPath((Join-Path $root $InputApk))
$output = [IO.Path]::GetFullPath((Join-Path $root $OutputApk))
$work = Join-Path $root "work\build"
$decoded = Join-Path $work "decoded-nores"
$classes = Join-Path $work "classes"
$dexOut = Join-Path $work "dex"
$runtimeApk = Join-Path $work "runtime.apk"
$runtimeDecoded = Join-Path $work "runtime-decoded"
$unsigned = Join-Path $work "unsigned.apk"
$aligned = Join-Path $work "aligned.apk"

if (Test-Path $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Force -Path $work, $classes, $dexOut, (Split-Path $output -Parent) | Out-Null

& (Join-Path $javaHome "bin\java.exe") -jar $apktool d -r -f $input -o $decoded
if ($LASTEXITCODE) { throw "apktool decode failed" }

# Keep the raw binary manifest/resources, but raise versionCode so this stable 9.1
# build can replace the previously installed 9.5 test package without clearing data.
$manifest = Join-Path $decoded "AndroidManifest.xml"
$manifestBytes = [IO.File]::ReadAllBytes($manifest)
$oldCode = [BitConverter]::GetBytes([int]25000001)
$newCode = [BitConverter]::GetBytes([int]29000001)
$matches = New-Object System.Collections.Generic.List[int]
for ($i = 0; $i -le $manifestBytes.Length - 4; $i++) {
    if ($manifestBytes[$i] -eq $oldCode[0] -and $manifestBytes[$i + 1] -eq $oldCode[1] -and
        $manifestBytes[$i + 2] -eq $oldCode[2] -and $manifestBytes[$i + 3] -eq $oldCode[3]) {
        $matches.Add($i)
    }
}
if ($matches.Count -ne 1) { throw "Expected one binary versionCode marker, found $($matches.Count)" }
[Array]::Copy($newCode, 0, $manifestBytes, $matches[0], 4)
[IO.File]::WriteAllBytes($manifest, $manifestBytes)

$sources = Get-ChildItem (Join-Path $root "src") -Recurse -Filter *.java | ForEach-Object FullName
& (Join-Path $javaHome "bin\javac.exe") -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classes $sources
if ($LASTEXITCODE) { throw "javac failed" }
Push-Location $classes
try { & (Join-Path $javaHome "bin\jar.exe") cf (Join-Path $work "runtime.jar") . } finally { Pop-Location }
& $d8 --lib $androidJar --min-api 21 --output $dexOut (Join-Path $work "runtime.jar")
if ($LASTEXITCODE) { throw "d8 failed" }

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::Open($runtimeApk, [System.IO.Compression.ZipArchiveMode]::Create)
try { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, (Join-Path $dexOut "classes.dex"), "classes.dex") | Out-Null } finally { $zip.Dispose() }
& (Join-Path $javaHome "bin\java.exe") -jar $apktool d -r -f $runtimeApk -o $runtimeDecoded
if ($LASTEXITCODE) { throw "runtime dex decode failed" }
Copy-Item (Join-Path $runtimeDecoded "smali\amap") (Join-Path $decoded "smali") -Recurse -Force
Copy-Item (Join-Path $runtimeDecoded "smali\com\autonavi\amapauto\DiagnoseActivity.smali") `
    (Join-Path $decoded "smali\com\autonavi\amapauto\DiagnoseActivity.smali") -Force

$hook = Join-Path $decoded "smali\ru\maximoff\signature\HookApplication.smali"
$text = [IO.File]::ReadAllText($hook, [Text.Encoding]::UTF8)
if ($text -notmatch 'Lamap/minimal/ble/BleRuntime;->init') {
    $method = @'

.method public onCreate()V
    .locals 0

    invoke-super {p0}, Lcom/autonavi/amapauto/app/MapApplicationProxy;->onCreate()V
    invoke-static {p0}, Lamap/minimal/ble/BleRuntime;->init(Landroid/app/Application;)V
    return-void
.end method
'@
    [IO.File]::WriteAllText($hook, $text.TrimEnd() + "`r`n" + $method + "`r`n", [Text.UTF8Encoding]::new($false))
}

& (Join-Path $javaHome "bin\java.exe") -jar $apktool b $decoded -o $unsigned
if ($LASTEXITCODE) { throw "apktool build failed" }
& $zipalign -p -f 4 $unsigned $aligned
if ($LASTEXITCODE) { throw "zipalign failed" }
& $apksigner sign --ks $keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $output $aligned
if ($LASTEXITCODE) { throw "sign failed" }
& $apksigner verify --verbose $output
if ($LASTEXITCODE) { throw "verify failed" }
Write-Host "Built: $output"
