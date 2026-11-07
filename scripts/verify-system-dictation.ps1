param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [string]$Adb = "adb"
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$mainApk = Join-Path $repo "app-tv\build\outputs\apk\debug\app-tv-debug.apk"
$testApk = Join-Path $repo "app-tv\build\outputs\apk\androidTest\debug\app-tv-debug-androidTest.apk"
$artifactDirectory = Join-Path $repo "build\system-dictation"
$provider = "content://com.dongpiandi.videopro.dictation-debug"
$serviceComponent = "com.dongpiandi.videopro/app.jellystack.tv.SystemDictationService"
$probeActivity = "com.dongpiandi.videopro.test/app.jellystack.tv.DictationProbeActivity"

New-Item -ItemType Directory -Force -Path $artifactDirectory | Out-Null

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

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

function Get-EditorText {
    $remote = "/sdcard/video-pro-dictation.xml"
    $local = Join-Path $artifactDirectory "window.xml"
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
    if (-not (Test-Path -LiteralPath $mainApk) -or -not (Test-Path -LiteralPath $testApk)) {
        throw "Build :app-tv:assembleDebug and :app-tv:assembleDebugAndroidTest first."
    }

    Invoke-Adb install -r -t $mainApk | Out-Null
    Invoke-Adb install -r -t $testApk | Out-Null
    Invoke-Adb shell pm grant com.dongpiandi.videopro android.permission.RECORD_AUDIO | Out-Null
    Invoke-Adb shell settings put secure enabled_accessibility_services $serviceComponent | Out-Null
    Invoke-Adb shell settings put secure accessibility_enabled 1 | Out-Null
    Start-Sleep -Seconds 2

    $accessibility = Invoke-Adb shell dumpsys accessibility
    if ($accessibility -notmatch "Bound services") {
        throw "SystemDictationService did not bind."
    }

    Invoke-Adb shell am force-stop com.dongpiandi.videopro.test | Out-Null
    Invoke-Adb shell am start -W -n $probeActivity | Out-Null
    Start-Sleep -Seconds 2

    Invoke-Adb shell content call --uri $provider --method reset | Out-Null
    Invoke-Adb shell content call --uri $provider --method install | Out-Null

    $firstPress = Invoke-Adb shell content call --uri $provider --method pressVoice
    if ($firstPress -notmatch "ok=true" -or $firstPress -notmatch "editor=true") {
        throw "The first simulated voice press was not accepted: $firstPress"
    }
    Start-Sleep -Seconds 1
    $firstText = Get-EditorText
    if ($firstText -ne "第一句") {
        throw "First transcript mismatch: [$firstText]"
    }

    $secondPress = Invoke-Adb shell content call --uri $provider --method pressVoice
    if ($secondPress -notmatch "ok=true" -or $secondPress -notmatch "editor=true") {
        throw "The second simulated voice press was not accepted: $secondPress"
    }
    Start-Sleep -Seconds 1
    $secondText = Get-EditorText
    if ($secondText -ne "第一句第二句") {
        throw "Second transcript mismatch: [$secondText]"
    }

    $screenshot = Join-Path $artifactDirectory "cross-app-dictation.png"
    Invoke-Adb shell screencap -p /sdcard/video-pro-dictation.png | Out-Null
    Invoke-Adb pull /sdcard/video-pro-dictation.png $screenshot | Out-Null

    Invoke-Adb shell input keyevent 3 | Out-Null
    Start-Sleep -Seconds 1
    $withoutEditor = Invoke-Adb shell content call --uri $provider --method pressVoice
    if ($withoutEditor -notmatch "ok=false" -or $withoutEditor -notmatch "editor=false") {
        throw "A voice press was claimed without an editor: $withoutEditor"
    }

    Write-Output "PASS: cross-app first transcript = $firstText"
    Write-Output "PASS: consecutive transcript = $secondText"
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
