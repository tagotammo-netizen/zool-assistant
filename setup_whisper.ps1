# Jarvis: download what the experimental offline Whisper engine needs.
#   1) native sherpa-onnx libraries -> app\src\main\jniLibs   (then rebuild the app in Android Studio)
#   2) a Whisper model + the voice detector -> whisper-files\model   (copy this folder to the phone)
# Usage (PowerShell, inside the jarvis-android folder):   .\setup_whisper.ps1            (tiny model)
param([string]$Model = "tiny")
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $root "whisper-files"
$release = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
New-Item -ItemType Directory -Force $work | Out-Null

Write-Host "1/3 native libraries (v2.0.1) ..."
$libTar = Join-Path $work "sherpa-onnx-v2.0.1-android.tar.bz2"
if (-not (Test-Path $libTar)) { Invoke-WebRequest "$release/v2.0.1/sherpa-onnx-v2.0.1-android.tar.bz2" -OutFile $libTar }
$libDir = Join-Path $work "android"
New-Item -ItemType Directory -Force $libDir | Out-Null
tar -xf $libTar -C $libDir
$jni = Get-ChildItem $libDir -Recurse -Directory -Filter "arm64-v8a" | Select-Object -First 1
if (-not $jni) { throw "arm64-v8a libraries not found in the archive" }
$dest = Join-Path $root "app\src\main\jniLibs\arm64-v8a"
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item "$($jni.FullName)\*.so" $dest -Force
Write-Host "   copied to $dest"

Write-Host "2/3 Whisper model '$Model' ..."
$modelTar = Join-Path $work "sherpa-onnx-whisper-$Model.tar.bz2"
if (-not (Test-Path $modelTar)) { Invoke-WebRequest "$release/asr-models/sherpa-onnx-whisper-$Model.tar.bz2" -OutFile $modelTar }
$unpacked = Join-Path $work "unpacked"
New-Item -ItemType Directory -Force $unpacked | Out-Null
tar -xf $modelTar -C $unpacked
$out = Join-Path $work "model"
New-Item -ItemType Directory -Force $out | Out-Null
Get-ChildItem $unpacked -Recurse -File | Where-Object { $_.Name -like "*.int8.onnx" -or $_.Name -like "*tokens.txt" } | Copy-Item -Destination $out -Force

Write-Host "3/3 voice detector ..."
Invoke-WebRequest "$release/asr-models/silero_vad.onnx" -OutFile (Join-Path $out "silero_vad.onnx")

Write-Host ""
Write-Host "Done."
Write-Host " - Rebuild the app in Android Studio (Sync, then Run)."
Write-Host " - Copy this folder to the phone (USB): $out"
Write-Host " - In Jarvis settings: 'Choose Whisper folder', then turn Whisper on."
Get-ChildItem $out | Select-Object Name, @{n="MB";e={[math]::Round($_.Length/1MB,1)}}
