param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$Adb = ""
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$mainApkCandidates = @(
    (Join-Path $repo "build\voice-dictation-final\app\outputs\apk\debug\app-debug.apk"),
    (Join-Path $repo "build\optimal-tv\app\outputs\apk\debug\app-debug.apk"),
    (Join-Path $repo "build\dictation-qa\app\outputs\apk\debug\app-debug.apk")
)
$testApkCandidates = @(
    (Join-Path $repo "build\voice-dictation-final\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk"),
    (Join-Path $repo "build\optimal-tv\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk"),
    (Join-Path $repo "build\dictation-qa\app\outputs\apk\androidTest\debug\app-debug-androidTest.apk")
)
$mainApk =
    $mainApkCandidates |
    Where-Object { Test-Path -LiteralPath $_ } |
    ForEach-Object { Get-Item -LiteralPath $_ } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$testApk =
    $testApkCandidates |
    Where-Object { Test-Path -LiteralPath $_ } |
    ForEach-Object { Get-Item -LiteralPath $_ } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 -ExpandProperty FullName
$artifactDirectory = Join-Path $repo "build\system-dictation"
$packageName = "com.dongpiandi.videopro"
$testPackage = "$packageName.test"
$provider = "content://$packageName.dictation-debug"
$serviceComponent = "$packageName/com.qiubo.optimaltv.dictation.SystemDictationService"
$probeActivity = "$testPackage/com.qiubo.optimaltv.dictation.ExternalDictationProbeActivity"

if ([string]::IsNullOrWhiteSpace($Adb)) {
    $candidates = @(
        "adb",
        "C:\Users\63054\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        "D:\AndroidSDK\Sdk\platform-tools\adb.exe"
    )
    $Adb =
        $candidates |
        Where-Object {
            if ($_ -eq "adb") {
                $null -ne (Get-Command "adb" -ErrorAction SilentlyContinue)
            } else {
                Test-Path -LiteralPath $_
            }
        } |
        Select-Object -First 1
    if (-not $Adb) {
        throw "adb was not found. Pass -Adb with the full adb.exe path."
    }
}

New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    # Always pass an argument array so PowerShell never interprets adb flags such as -W.
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($Arguments -join ' ') failed:`n$output"
    }
    return ($output -join "`n")
}

function Get-SecureSetting {
    param([string]$Name)
    return (Invoke-Adb shell settings get secure $Name).Trim()
}

function Restore-SecureSetting {
    param(
        [string]$Name,
        [string]$Value
    )

    if ([string]::IsNullOrWhiteSpace($Value) -or $Value -eq "null") {
        Invoke-Adb shell settings delete secure $Name | Out-Null
    } else {
        Invoke-Adb shell settings put secure $Name $Value | Out-Null
    }
}

function Add-AccessibilityService {
    param(
        [string]$Current,
        [string]$Service
    )

    if ([string]::IsNullOrWhiteSpace($Current) -or $Current -eq "null") {
        return $Service
    }
    if (($Current -split ":" ) -contains $Service) {
        return $Current
    }
    return "$Current`:$Service"
}

function Get-EditorText {
    $remote = "/sdcard/video-pro-dictation.xml"
    $local = Join-Path $artifactDirectory "window.xml"
    Invoke-Adb shell rm -f $remote | Out-Null
    Invoke-Adb shell uiautomator dump $remote | Out-Null
    Invoke-Adb pull $remote $local | Out-Null
    [xml]$xml = Get-Content -LiteralPath $local
    $editor = $xml.SelectSingleNode('//node[@class="android.widget.EditText"]')
    if ($null -eq $editor) {
        throw "The cross-app probe did not expose an EditText."
    }
    return $editor.text
}

$oldServices = Get-SecureSetting "enabled_accessibility_services"
$oldEnabled = Get-SecureSetting "accessibility_enabled"

try {
    if (-not (Test-Path -LiteralPath $mainApk)) {
        throw "Missing $mainApk. Build :app:assembleDebug first."
    }
    if (-not (Test-Path -LiteralPath $testApk)) {
        throw "Missing $testApk. Build :app:assembleDebugAndroidTest first."
    }

    Invoke-Adb install -r -t $mainApk | Out-Null
    Invoke-Adb install -r -t $testApk | Out-Null
    Invoke-Adb shell pm grant $packageName android.permission.RECORD_AUDIO | Out-Null

    $services = Add-AccessibilityService $oldServices $serviceComponent
    Invoke-Adb shell settings put secure enabled_accessibility_services $services | Out-Null
    Invoke-Adb shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 2

    $accessibility = Invoke-Adb shell dumpsys accessibility
    $servicePattern = [regex]::Escape("com.qiubo.optimaltv.dictation.SystemDictationService")
    if ($accessibility -notmatch $servicePattern) {
        throw "SystemDictationService did not bind."
    }

    Invoke-Adb shell am force-stop $testPackage | Out-Null
    $startArguments = @("shell", "am", "start", "-W", "-n", $probeActivity)
    Invoke-Adb @startArguments | Out-Null
    Start-Sleep -Seconds 2

    Invoke-Adb shell content call --uri $provider --method reset | Out-Null
    Invoke-Adb shell content call --uri $provider --method install | Out-Null

    $firstPress = Invoke-Adb shell content call --uri $provider --method pressVoice
    if ($firstPress -notmatch "ok=true" -or $firstPress -notmatch "editor=true" -or $firstPress -notmatch "keyboard=true") {
        throw "The first simulated voice press was not accepted: $firstPress"
    }
    Start-Sleep -Seconds 1
    $firstText = Get-EditorText
    if ($firstText -ne "第一句") {
        throw "First transcript mismatch: [$firstText]"
    }

    $secondPress = Invoke-Adb shell content call --uri $provider --method pressVoice
    if ($secondPress -notmatch "ok=true" -or $secondPress -notmatch "editor=true" -or $secondPress -notmatch "keyboard=true") {
        throw "The second simulated voice press was not accepted: $secondPress"
    }
    Start-Sleep -Seconds 1
    $secondText = Get-EditorText
    if ($secondText -ne "第一句第二句") {
        throw "Second transcript mismatch: [$secondText]"
    }

    $screenshot = Join-Path $artifactDirectory "cross-app-dictation.png"
    $screencapArguments = @("shell", "screencap", "-p", "/sdcard/video-pro-dictation.png")
    Invoke-Adb @screencapArguments | Out-Null
    Invoke-Adb pull /sdcard/video-pro-dictation.png $screenshot | Out-Null

    # Back hides the IME without leaving the probe Activity. The voice key must then pass through.
    Invoke-Adb shell input keyevent 4 | Out-Null
    Start-Sleep -Seconds 1
    $withoutKeyboard = Invoke-Adb shell content call --uri $provider --method pressVoice
    if ($withoutKeyboard -notmatch "ok=false" -or $withoutKeyboard -notmatch "keyboard=false") {
        throw "A voice press was claimed without a visible input method: $withoutKeyboard"
    }

    Invoke-Adb shell input keyevent 3 | Out-Null
    Start-Sleep -Seconds 1
    $withoutEditor = Invoke-Adb shell content call --uri $provider --method pressVoice
    if ($withoutEditor -notmatch "ok=false" -or $withoutEditor -notmatch "editor=false") {
        throw "A voice press was claimed without an editor: $withoutEditor"
    }

    Write-Output "PASS: cross-app first transcript = $firstText"
    Write-Output "PASS: consecutive transcript = $secondText"
    Write-Output "PASS: voice key ignored while the input method is hidden"
    Write-Output "PASS: voice key ignored without an editor"
    Write-Output "Screenshot: $screenshot"
    Write-Output "Main APK SHA-256: $((Get-FileHash -LiteralPath $mainApk -Algorithm SHA256).Hash)"
} finally {
    try {
        Invoke-Adb shell content call --uri $provider --method reset 2>$null | Out-Null
    } catch {
        Write-Warning "Could not reset the debug dictation provider: $_"
    }
    Restore-SecureSetting "enabled_accessibility_services" $oldServices
    Restore-SecureSetting "accessibility_enabled" $oldEnabled
}
