[CmdletBinding()]
param(
    [string]$Watch = '',
    [string]$SessionId = '',
    [string]$OutputDirectory = '',
    [switch]$List,
    [switch]$SelfTest
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

$script:PackageName = 'best.nagikokoro.watch6heartrateprobe'
$script:Utf8NoBom = New-Object Text.UTF8Encoding($false)

function Get-OptionalProperty([object]$Object, [string]$Name) {
    if ($null -eq $Object) { return $null }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) { return $null }
    return $property.Value
}

function Format-Metric([object]$Value) {
    if ($null -eq $Value) { return '--' }
    return $Value.ToString()
}

function Get-Median([long[]]$Values) {
    if ($null -eq $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count % 2 -eq 1) {
        $middle = [int][Math]::Floor($sorted.Count / 2.0)
        return [double]$sorted[$middle]
    }
    $upper = [int][Math]::Floor($sorted.Count / 2.0)
    return ([double]$sorted[$upper - 1] + [double]$sorted[$upper]) / 2.0
}

function Get-Percentile([long[]]$Values, [double]$Percentile) {
    if ($null -eq $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Ceiling($Percentile * $sorted.Count) - 1
    $index = [Math]::Max(0, [Math]::Min($sorted.Count - 1, $index))
    return [long]$sorted[$index]
}

function ConvertTo-TestEvents([string]$Text) {
    $events = New-Object Collections.Generic.List[object]
    foreach ($line in ($Text -split "`r?`n")) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $events.Add(($line | ConvertFrom-Json))
    }
    return ,$events.ToArray()
}

function Get-ContinuityAnalysis([object[]]$Events) {
    $startEvent = @($Events | Where-Object { $_.event -eq 'TEST_START' } | Select-Object -First 1)
    $endEvent = @($Events | Where-Object { $_.event -eq 'TEST_END' } | Select-Object -Last 1)
    if ($startEvent.Count -eq 0 -or $endEvent.Count -eq 0) {
        throw 'Raw events must contain TEST_START and TEST_END.'
    }

    $startMillis = [long]$startEvent[0].epochMillis
    $reportFinalizedMillis = [long]$endEvent[0].epochMillis
    $explicitWindowEnd = Get-OptionalProperty $endEvent[0] 'testWindowEndEpochMillis'
    $targetDuration = Get-OptionalProperty $startEvent[0] 'targetDurationMs'
    $endMillis = if ($null -ne $explicitWindowEnd) {
        [long]$explicitWindowEnd
    } elseif ($null -ne $targetDuration) {
        [Math]::Min($reportFinalizedMillis, $startMillis + [long]$targetDuration)
    } else {
        $reportFinalizedMillis
    }
    $initialInteractiveValue = Get-OptionalProperty $startEvent[0] 'screenInteractive'
    $initialInteractive = if ($null -eq $initialInteractiveValue) { $true } else { [bool]$initialInteractiveValue }
    $screenEvents = @(
        $Events |
            Where-Object { $_.event -eq 'SCREEN_ON' -or $_.event -eq 'SCREEN_OFF' } |
            ForEach-Object {
                [pscustomobject]@{
                    EpochMillis = [long]$_.epochMillis
                    Interactive = ($_.event -eq 'SCREEN_ON')
                }
            } |
            Sort-Object EpochMillis
    )

    $samplesByTimestamp = @{}
    $callbackTimes = New-Object Collections.Generic.List[long]
    $historicalCallbackBatchCount = 0L
    $maxCallbackBatchSampleCount = 0

    foreach ($event in @($Events | Where-Object { $_.event -eq 'CALLBACK_BATCH' })) {
        $receiveMillis = [long]$event.callbackReceiveEpochMillis
        $interactiveValue = Get-OptionalProperty $event 'screenInteractive'
        $deliveredWhileScreenOff = if ($null -eq $interactiveValue) { $false } else { -not [bool]$interactiveValue }
        $validInBatch = 0
        foreach ($sample in @((Get-OptionalProperty $event 'samples'))) {
            if ($null -eq $sample) { continue }
            $sampleMillis = [long]$sample.sampleEpochMillis
            $bpm = [double]$sample.bpm
            if ([double]::IsNaN($bpm) -or [double]::IsInfinity($bpm) -or $bpm -lt 20.0 -or $bpm -gt 300.0) { continue }
            $validInBatch++
            if ($sampleMillis -lt $startMillis -or $sampleMillis -gt $endMillis) { continue }
            $samplesByTimestamp[$sampleMillis] = [pscustomobject]@{
                SampleMillis = $sampleMillis
                ReceiveMillis = $receiveMillis
                Bpm = $bpm
                DeliveredWhileScreenOff = $deliveredWhileScreenOff
            }
        }
        if ($validInBatch -gt 0) {
            $callbackTimes.Add($receiveMillis)
            $maxCallbackBatchSampleCount = [Math]::Max($maxCallbackBatchSampleCount, $validInBatch)
            $historicalValue = Get-OptionalProperty $event 'containsHistoricalSamples'
            if ($null -ne $historicalValue -and [bool]$historicalValue) { $historicalCallbackBatchCount++ }
        }
    }

    $samples = @($samplesByTimestamp.Values | Sort-Object SampleMillis)
    $intervals = New-Object Collections.Generic.List[long]
    for ($index = 1; $index -lt $samples.Count; $index++) {
        $interval = [long]($samples[$index].SampleMillis - $samples[$index - 1].SampleMillis)
        if ($interval -ge 0) { $intervals.Add($interval) }
    }

    $screenOffSamples = New-Object Collections.Generic.List[object]
    foreach ($sample in $samples) {
        $interactive = $initialInteractive
        foreach ($screenEvent in $screenEvents) {
            if ($screenEvent.EpochMillis -gt $sample.SampleMillis) { break }
            $interactive = $screenEvent.Interactive
        }
        if (-not $interactive) { $screenOffSamples.Add($sample) }
    }

    $latencies = @($samples | ForEach-Object { [long][Math]::Max(0, $_.ReceiveMillis - $_.SampleMillis) })
    $screenOffLatencies = @($screenOffSamples | ForEach-Object { [long][Math]::Max(0, $_.ReceiveMillis - $_.SampleMillis) })
    $screenOffSamplesDeliveredAfterWake = @($screenOffSamples | Where-Object { -not $_.DeliveredWhileScreenOff }).Count
    $callbackSequence = @($startMillis) + @($callbackTimes) + @($reportFinalizedMillis) | Sort-Object
    $noCallbackDurations = New-Object Collections.Generic.List[long]
    for ($index = 1; $index -lt $callbackSequence.Count; $index++) {
        $noCallbackDurations.Add([long]($callbackSequence[$index] - $callbackSequence[$index - 1]))
    }

    $maxSampleInterval = if ($intervals.Count -eq 0) { $null } else { [long](($intervals | Measure-Object -Maximum).Maximum) }
    $averageSampleInterval = if ($intervals.Count -eq 0) { $null } else { [double](($intervals | Measure-Object -Average).Average) }
    $averageLatency = if ($latencies.Count -eq 0) { $null } else { [double](($latencies | Measure-Object -Average).Average) }
    $screenOffAverageLatency = if ($screenOffLatencies.Count -eq 0) { $null } else { [double](($screenOffLatencies | Measure-Object -Average).Average) }
    $longestNoCallback = if ($noCallbackDurations.Count -eq 0) { 0L } else { [long](($noCallbackDurations | Measure-Object -Maximum).Maximum) }
    $screenOffP95 = Get-Percentile $screenOffLatencies 0.95
    $firstSampleDelay = if ($samples.Count -eq 0) { $null } else { [long][Math]::Max(0, $samples[0].SampleMillis - $startMillis) }
    $lastSampleToWindowEnd = if ($samples.Count -eq 0) { $null } else { [long][Math]::Max(0, $endMillis - $samples[-1].SampleMillis) }
    $targetWindowCovered = $null -ne $firstSampleDelay -and $null -ne $lastSampleToWindowEnd -and
        $firstSampleDelay -le 10000 -and $lastSampleToWindowEnd -le 10000
    $continuousSampling = $targetWindowCovered -and $screenOffSamples.Count -gt 0 -and
        $null -ne $maxSampleInterval -and $maxSampleInterval -le 10000
    $nearRealtimeDelivery = $null -ne $screenOffP95 -and $screenOffP95 -le 5000 -and $longestNoCallback -le 10000

    return [pscustomobject]@{
        StartEpochMillis = $startMillis
        EndEpochMillis = $endMillis
        ReportFinalizedEpochMillis = $reportFinalizedMillis
        DrainDurationMs = [long][Math]::Max(0, $reportFinalizedMillis - $endMillis)
        ActualDurationMs = [long]($endMillis - $startMillis)
        TotalUniqueSamples = $samples.Count
        SamplesSampledWhileScreenOff = $screenOffSamples.Count
        AverageSampleIntervalMs = $averageSampleInterval
        MaxSampleIntervalMs = $maxSampleInterval
        FirstSampleDelayMs = $firstSampleDelay
        LastSampleToWindowEndMs = $lastSampleToWindowEnd
        TargetWindowCovered = $targetWindowCovered
        AverageDeliveryLatencyMs = $averageLatency
        MedianDeliveryLatencyMs = Get-Median $latencies
        P95DeliveryLatencyMs = Get-Percentile $latencies 0.95
        MaxDeliveryLatencyMs = if ($latencies.Count -eq 0) { $null } else { [long](($latencies | Measure-Object -Maximum).Maximum) }
        ScreenOffAverageDeliveryLatencyMs = $screenOffAverageLatency
        ScreenOffMedianDeliveryLatencyMs = Get-Median $screenOffLatencies
        ScreenOffP95DeliveryLatencyMs = $screenOffP95
        ScreenOffMaxDeliveryLatencyMs = if ($screenOffLatencies.Count -eq 0) { $null } else { [long](($screenOffLatencies | Measure-Object -Maximum).Maximum) }
        LongestNoCallbackDurationMs = $longestNoCallback
        HistoricalCallbackBatchCount = $historicalCallbackBatchCount
        MaxCallbackBatchSampleCount = $maxCallbackBatchSampleCount
        ScreenOffSamplesDeliveredAfterWake = $screenOffSamplesDeliveredAfterWake
        BatchedDeliveryDetected = $historicalCallbackBatchCount -gt 0 -or $screenOffSamplesDeliveredAfterWake -gt 0
        ContinuousSampling = $continuousSampling
        NearRealtimeDelivery = $nearRealtimeDelivery
    }
}

function Assert-Equal([object]$Expected, [object]$Actual, [string]$Name) {
    if ($Expected -ne $Actual) { throw "$Name expected=$Expected actual=$Actual" }
}

function Invoke-SelfTest {
    $fixture = @'
{"event":"TEST_START","epochMillis":0,"screenInteractive":true}
{"event":"SCREEN_OFF","epochMillis":1000}
{"event":"CALLBACK_BATCH","callbackReceiveEpochMillis":1600,"screenInteractive":false,"containsHistoricalSamples":false,"samples":[{"sampleEpochMillis":1500,"bpm":70}]}
{"event":"CALLBACK_BATCH","callbackReceiveEpochMillis":2600,"screenInteractive":false,"containsHistoricalSamples":false,"samples":[{"sampleEpochMillis":2500,"bpm":71}]}
{"event":"CALLBACK_BATCH","callbackReceiveEpochMillis":3600,"screenInteractive":false,"containsHistoricalSamples":false,"samples":[{"sampleEpochMillis":3500,"bpm":72}]}
{"event":"TEST_END","epochMillis":4000}
'@
    $analysis = Get-ContinuityAnalysis (ConvertTo-TestEvents $fixture)
    Assert-Equal 3 $analysis.TotalUniqueSamples 'TotalUniqueSamples'
    Assert-Equal 3 $analysis.SamplesSampledWhileScreenOff 'SamplesSampledWhileScreenOff'
    Assert-Equal 1000 $analysis.MaxSampleIntervalMs 'MaxSampleIntervalMs'
    Assert-Equal 1500 $analysis.FirstSampleDelayMs 'FirstSampleDelayMs'
    Assert-Equal 500 $analysis.LastSampleToWindowEndMs 'LastSampleToWindowEndMs'
    Assert-Equal $true $analysis.TargetWindowCovered 'TargetWindowCovered'
    Assert-Equal 100 $analysis.ScreenOffP95DeliveryLatencyMs 'ScreenOffP95DeliveryLatencyMs'
    Assert-Equal 1600 $analysis.LongestNoCallbackDurationMs 'LongestNoCallbackDurationMs'
    Assert-Equal $true $analysis.ContinuousSampling 'ContinuousSampling'
    Assert-Equal $true $analysis.NearRealtimeDelivery 'NearRealtimeDelivery'
    Assert-Equal 100.0 $analysis.MedianDeliveryLatencyMs 'MedianOdd'
    Assert-Equal 25.0 (Get-Median ([long[]](10, 20, 30, 90))) 'MedianEven'

    $missingTailFixture = @'
{"event":"TEST_START","epochMillis":0,"targetDurationMs":20000,"screenInteractive":false}
{"event":"CALLBACK_BATCH","callbackReceiveEpochMillis":3600,"screenInteractive":false,"samples":[{"sampleEpochMillis":3500,"bpm":72}]}
{"event":"TEST_END","epochMillis":20000}
'@
    $missingTail = Get-ContinuityAnalysis (ConvertTo-TestEvents $missingTailFixture)
    Assert-Equal 16500 $missingTail.LastSampleToWindowEndMs 'MissingTailGap'
    Assert-Equal $false $missingTail.TargetWindowCovered 'MissingTailCoverage'
    Assert-Equal $false $missingTail.ContinuousSampling 'MissingTailContinuity'
    Write-Output 'WatchTestReport self-test: PASS'
}

function Resolve-AdbPath {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $candidates = New-Object Collections.Generic.List[string]
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $candidates.Add((Join-Path $env:ANDROID_SDK_ROOT 'platform-tools\adb.exe'))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $candidates.Add((Join-Path $env:ANDROID_HOME 'platform-tools\adb.exe'))
    }
    if (-not [string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
        $candidates.Add((Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'))
    }
    $match = $candidates | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    if ($null -eq $match) { throw 'adb.exe was not found. Set ANDROID_SDK_ROOT or install Android Platform-Tools.' }
    return $match
}

function Invoke-AdbText([string]$Adb, [string[]]$Arguments) {
    $output = @(& $Adb @Arguments 2>&1)
    if ($LASTEXITCODE -ne 0) { throw (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine) }
    return (($output | ForEach-Object { $_.ToString() }) -join [Environment]::NewLine)
}

function Resolve-WatchSerial([string]$Adb, [string]$RequestedWatch) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedWatch)) {
        $state = Invoke-AdbText $Adb @('-s', $RequestedWatch, 'get-state')
        if ($state.Trim() -ne 'device') { throw "Watch $RequestedWatch is not in device state." }
        return $RequestedWatch
    }
    $deviceLines = (Invoke-AdbText $Adb @('devices', '-l')) -split "`r?`n"
    $matches = @(
        $deviceLines | ForEach-Object {
            if ($_ -match '^\s*(\S+)\s+device\s+.*(?:model:SM_R960|device:wise6bl)') { $Matches[1] }
        } | Select-Object -Unique
    )
    $ipMatch = @($matches | Where-Object { $_ -match '^\d{1,3}(?:\.\d{1,3}){3}:\d+$' } | Select-Object -First 1)
    if ($ipMatch.Count -gt 0) { return $ipMatch[0] }
    if ($matches.Count -eq 1) { return $matches[0] }
    if ($matches.Count -eq 0) { throw 'No connected Galaxy Watch6 was found. Reconnect wireless debugging first.' }
    throw "Multiple Watch6 devices are connected; pass -Watch explicitly: $($matches -join ', ')"
}

function Get-WatchSessionIds([string]$Adb, [string]$WatchSerial) {
    $listing = Invoke-AdbText $Adb @('-s', $WatchSerial, 'shell', 'run-as', $script:PackageName, 'ls', 'files/tests')
    return @(
        $listing -split "`r?`n" |
            Where-Object { $_ -match '\.json$' -and $_ -notmatch '\.events\.jsonl$' } |
            ForEach-Object { $_ -replace '\.json$', '' } |
            Sort-Object -Descending
    )
}

function Export-WatchSession(
    [string]$Adb,
    [string]$WatchSerial,
    [string]$SelectedSession,
    [string]$Destination
) {
    $sessionDirectory = Join-Path $Destination $SelectedSession
    $null = New-Item -ItemType Directory -Path $sessionDirectory -Force
    foreach ($suffix in @('.json', '.txt', '.events.jsonl')) {
        $fileName = "$SelectedSession$suffix"
        $text = Invoke-AdbText $Adb @(
            '-s', $WatchSerial, 'exec-out', 'run-as', $script:PackageName,
            'cat', "files/tests/$fileName"
        )
        [IO.File]::WriteAllText((Join-Path $sessionDirectory $fileName), $text + [Environment]::NewLine, $script:Utf8NoBom)
    }
    return $sessionDirectory
}

function Compare-WatchReport([object]$WatchReport, [object]$Analysis) {
    $mismatches = New-Object Collections.Generic.List[string]
    $statistics = Get-OptionalProperty $WatchReport 'statistics'
    $checks = @(
        @('totalUniqueSamples', 'TotalUniqueSamples'),
        @('samplesSampledWhileScreenOff', 'SamplesSampledWhileScreenOff'),
        @('maxSampleIntervalMs', 'MaxSampleIntervalMs'),
        @('firstSampleDelayMs', 'FirstSampleDelayMs'),
        @('lastSampleToWindowEndMs', 'LastSampleToWindowEndMs'),
        @('screenOffP95DeliveryLatencyMs', 'ScreenOffP95DeliveryLatencyMs'),
        @('longestNoCallbackDurationMs', 'LongestNoCallbackDurationMs'),
        @('maxCallbackBatchSampleCount', 'MaxCallbackBatchSampleCount')
    )
    foreach ($check in $checks) {
        $watchValue = Get-OptionalProperty $statistics $check[0]
        $pcValue = Get-OptionalProperty $Analysis $check[1]
        if ($null -ne $watchValue -and $watchValue -ne $pcValue) {
            $mismatches.Add("$($check[0]): watch=$watchValue pc=$pcValue")
        }
    }
    foreach ($check in @(@('targetWindowCovered', 'TargetWindowCovered'), @('continuousSampling', 'ContinuousSampling'), @('nearRealtimeDelivery', 'NearRealtimeDelivery'))) {
        $watchValue = Get-OptionalProperty $WatchReport $check[0]
        $pcValue = Get-OptionalProperty $Analysis $check[1]
        if ($null -ne $watchValue -and [bool]$watchValue -ne [bool]$pcValue) {
            $mismatches.Add("$($check[0]): watch=$watchValue pc=$pcValue")
        }
    }
    return $mismatches.ToArray()
}

if ($SelfTest) {
    Invoke-SelfTest
    exit 0
}

$adb = Resolve-AdbPath
$watchSerial = Resolve-WatchSerial $adb $Watch
$sessions = @(Get-WatchSessionIds $adb $watchSerial)
if ($List) {
    Write-Output "Watch: $watchSerial"
    $sessions | ForEach-Object { Write-Output $_ }
    exit 0
}
if ($sessions.Count -eq 0) { throw 'No persistent watch test reports were found.' }
$selectedSession = if ([string]::IsNullOrWhiteSpace($SessionId)) { $sessions[0] } else { $SessionId }
if ($selectedSession -notin $sessions) { throw "Session not found on watch: $selectedSession" }
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\test-results'))
}

$sessionDirectory = Export-WatchSession $adb $watchSerial $selectedSession $OutputDirectory
$eventsPath = Join-Path $sessionDirectory "$selectedSession.events.jsonl"
$watchReportPath = Join-Path $sessionDirectory "$selectedSession.json"
$analysis = Get-ContinuityAnalysis (ConvertTo-TestEvents ([IO.File]::ReadAllText($eventsPath, [Text.Encoding]::UTF8)))
$watchReport = [IO.File]::ReadAllText($watchReportPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
$mismatches = @(Compare-WatchReport $watchReport $analysis)
$analysisPath = Join-Path $sessionDirectory "$selectedSession.pc-analysis.json"
[IO.File]::WriteAllText($analysisPath, ($analysis | ConvertTo-Json -Depth 6) + [Environment]::NewLine, $script:Utf8NoBom)

$summaryLines = @(
    "Watch continuity cross-check",
    "watch: $watchSerial",
    "sessionId: $selectedSession",
    "totalUniqueSamples: $($analysis.TotalUniqueSamples)",
    "samplesSampledWhileScreenOff: $($analysis.SamplesSampledWhileScreenOff)",
    "maxSampleIntervalMs: $(Format-Metric $analysis.MaxSampleIntervalMs)",
    "firstSampleDelayMs: $(Format-Metric $analysis.FirstSampleDelayMs)",
    "lastSampleToWindowEndMs: $(Format-Metric $analysis.LastSampleToWindowEndMs)",
    "targetWindowCovered: $($analysis.TargetWindowCovered)",
    "screenOffP95DeliveryLatencyMs: $(Format-Metric $analysis.ScreenOffP95DeliveryLatencyMs)",
    "longestNoCallbackDurationMs: $($analysis.LongestNoCallbackDurationMs)",
    "continuousSampling: $($analysis.ContinuousSampling)",
    "nearRealtimeDelivery: $($analysis.NearRealtimeDelivery)",
    "batchedDeliveryDetected: $($analysis.BatchedDeliveryDetected)",
    "watchPcMismatchCount: $($mismatches.Count)"
)
if ($mismatches.Count -gt 0) { $summaryLines += $mismatches | ForEach-Object { "mismatch: $_" } }
$summaryPath = Join-Path $sessionDirectory "$selectedSession.pc-analysis.txt"
[IO.File]::WriteAllText($summaryPath, ($summaryLines -join [Environment]::NewLine) + [Environment]::NewLine, $script:Utf8NoBom)
$summaryLines | ForEach-Object { Write-Output $_ }
Write-Output "analysisJson: $analysisPath"
Write-Output "analysisText: $summaryPath"
