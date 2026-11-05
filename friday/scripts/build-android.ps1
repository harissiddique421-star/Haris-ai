param(
  [string]$SdkRoot = $env:ANDROID_HOME,
  [string]$JavaRoot = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'ensure-build-icons.ps1')
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $SdkRoot) { throw 'Pass -SdkRoot pointing to an Android SDK with platform 35 and build-tools 35.0.0.' }
if (-not $JavaRoot) { $JavaRoot = Split-Path (Split-Path (Get-Command javac.exe).Source) }
$toolsRoot = Join-Path $SdkRoot 'build-tools\35.0.0'
$androidJar = Join-Path $SdkRoot 'platforms\android-35\android.jar'
$javaBin = Join-Path $JavaRoot 'bin'
foreach ($required in @($androidJar, (Join-Path $toolsRoot 'aapt2.exe'), (Join-Path $javaBin 'javac.exe'))) { if (-not (Test-Path -LiteralPath $required)) { throw "Missing build prerequisite: $required" } }
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME = $JavaRoot
& npm.cmd exec -- vite build --config vite.mobile.config.ts
if ($LASTEXITCODE -ne 0) { throw 'Mobile asset build failed.' }
$assetRoot = Join-Path $projectRoot 'android\app\src\main\assets'
New-Item -ItemType Directory -Path (Join-Path $assetRoot 'characters') -Force | Out-Null
# This is a PRIVATE local test build. The model licence does not permit public redistribution.
Copy-Item -LiteralPath (Join-Path $projectRoot 'assets\characters\evelyn') -Destination (Join-Path $assetRoot 'characters\evelyn') -Recurse
& node.exe --import tsx scripts/export-mobile-personality.ts
if ($LASTEXITCODE -ne 0) { throw 'Shared personality export failed.' }
$buildRoot = Join-Path $projectRoot ('android\app\build\local-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
$classes = Join-Path $buildRoot 'classes'
$dex = Join-Path $buildRoot 'dex'
New-Item -ItemType Directory -Path $classes,$dex -Force | Out-Null
$sourceFiles = @(Get-ChildItem -LiteralPath (Join-Path $projectRoot 'android\app\src\main\java') -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classes $sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Android Java compilation failed.' }
$classesJar = Join-Path $buildRoot 'classes.jar'
& (Join-Path $javaBin 'jar.exe') --create --file $classesJar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Class packaging failed.' }
& (Join-Path $toolsRoot 'd8.bat') --lib $androidJar --min-api 26 --output $dex $classesJar
if ($LASTEXITCODE -ne 0) { throw 'DEX compilation failed.' }
$unsigned = Join-Path $buildRoot 'unsigned.apk'
$resRoot = Join-Path $buildRoot 'res'
New-Item -ItemType Directory -Path (Join-Path $resRoot 'drawable') -Force | Out-Null
$iconSource = if (Test-Path -LiteralPath 'branding\friday-icon.png') { 'branding\friday-icon.png' } else { 'build\icon.png' }
Copy-Item -LiteralPath $iconSource -Destination (Join-Path $resRoot 'drawable\myraa_icon.png')
Copy-Item -Path (Join-Path $projectRoot 'android\app\src\main\res\*') -Destination $resRoot -Recurse -Force
$compiledResources = Join-Path $buildRoot 'resources.zip'
& (Join-Path $toolsRoot 'aapt2.exe') compile --dir $resRoot -o $compiledResources
if ($LASTEXITCODE -ne 0) { throw 'Android icon resource compilation failed.' }
& (Join-Path $toolsRoot 'aapt2.exe') link --manifest 'android\app\src\main\AndroidManifest.xml' -I $androidJar -A $assetRoot --min-sdk-version 26 --target-sdk-version 35 -o $unsigned $compiledResources
if ($LASTEXITCODE -ne 0) { throw 'Android asset packaging failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::Open($unsigned, [IO.Compression.ZipArchiveMode]::Update)
try {
  # Windows AAPT2 can emit backslashes in nested asset entry names. Android's
  # AssetManager uses ZIP-standard forward slashes; normalize before signing.
  foreach ($entry in @($archive.Entries)) {
    if ($entry.FullName.Contains('\')) {
      $normalizedName=$entry.FullName.Replace('\','/')
      $buffer=New-Object IO.MemoryStream
      $sourceStream=$entry.Open()
      try { $sourceStream.CopyTo($buffer) } finally { $sourceStream.Dispose() }
      $entry.Delete()
      $normalized=$archive.CreateEntry($normalizedName)
      $targetStream=$normalized.Open()
      try { $buffer.Position=0; $buffer.CopyTo($targetStream) } finally { $targetStream.Dispose(); $buffer.Dispose() }
    }
  }
  foreach ($dexFile in (Get-ChildItem -LiteralPath $dex -Filter '*.dex')) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive, $dexFile.FullName, $dexFile.Name) | Out-Null }
}
finally { $archive.Dispose() }
$aligned = Join-Path $buildRoot 'aligned.apk'
& (Join-Path $toolsRoot 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK alignment failed.' }
$keyStore = Join-Path $projectRoot 'android\local-debug.keystore'
if (-not (Test-Path -LiteralPath $keyStore)) {
  & (Join-Path $javaBin 'keytool.exe') -genkeypair -keystore $keyStore -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=MYRAA Local Development,O=MYRAA,C=IN'
  if ($LASTEXITCODE -ne 0) { throw 'Local development signing key creation failed.' }
}
New-Item -ItemType Directory -Path 'artifacts\android' -Force | Out-Null
$apk = Join-Path $projectRoot 'artifacts\android\FRIDAY-Mobile-1.0.0-rc.11-private.apk'
& (Join-Path $toolsRoot 'apksigner.bat') sign --ks $keyStore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'APK signing failed.' }
& (Join-Path $toolsRoot 'apksigner.bat') verify --verbose $apk
if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
& (Join-Path $toolsRoot 'aapt2.exe') dump badging $apk
Get-FileHash -LiteralPath $apk -Algorithm SHA256
Write-Output "PRIVATE Android candidate: $apk"
