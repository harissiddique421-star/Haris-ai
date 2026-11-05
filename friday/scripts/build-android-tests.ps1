param([string]$SdkRoot=$env:ANDROID_HOME,[string]$JavaRoot=$env:JAVA_HOME)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Set-Location -LiteralPath $projectRoot
$env:JAVA_HOME=$JavaRoot
$toolsRoot=Join-Path $SdkRoot 'build-tools\35.0.0'
$javaBin=Join-Path $JavaRoot 'bin'
$androidJar=Join-Path $SdkRoot 'platforms\android-35\android.jar'
$testBuild=Join-Path $projectRoot ('android\app\build\tests-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
$classes=Join-Path $testBuild 'classes'; $dex=Join-Path $testBuild 'dex'
New-Item -ItemType Directory -Path $classes,$dex -Force | Out-Null
$sourceFiles=@(Get-ChildItem android/tests/java -Recurse -Filter '*.java' | ForEach-Object FullName)
& (Join-Path $javaBin 'javac.exe') -encoding UTF-8 -source 8 -target 8 -classpath $androidJar -d $classes $sourceFiles
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation compilation failed.' }
$jar=Join-Path $testBuild 'tests.jar'
& (Join-Path $javaBin 'jar.exe') --create --file $jar -C $classes .
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation JAR failed.' }
& (Join-Path $toolsRoot 'd8.bat') --lib $androidJar --min-api 26 --output $dex $jar
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation DEX failed.' }
$unsigned=Join-Path $testBuild 'unsigned.apk'
& (Join-Path $toolsRoot 'aapt2.exe') link --manifest android/tests/AndroidManifest.xml -I $androidJar -o $unsigned
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation packaging failed.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive=[IO.Compression.ZipFile]::Open($unsigned,[IO.Compression.ZipArchiveMode]::Update)
try { foreach ($file in (Get-ChildItem -LiteralPath $dex -Filter '*.dex')) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$file.FullName,$file.Name) | Out-Null } } finally { $archive.Dispose() }
$aligned=Join-Path $testBuild 'aligned.apk'
& (Join-Path $toolsRoot 'zipalign.exe') -f -p 4 $unsigned $aligned
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation alignment failed.' }
$apk=Join-Path $projectRoot 'artifacts\android\MYRAA-Mobile-tests.apk'
& (Join-Path $toolsRoot 'apksigner.bat') sign --ks android/local-debug.keystore --ks-key-alias androiddebugkey --ks-pass pass:android --key-pass pass:android --out $apk $aligned
if ($LASTEXITCODE -ne 0) { throw 'Instrumentation signing failed.' }
Write-Output "Instrumentation APK: $apk"
