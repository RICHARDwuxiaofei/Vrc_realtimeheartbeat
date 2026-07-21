param([switch]$SelfTest)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

function Get-OscStringBytes([string]$Value) {
    $raw = [Text.Encoding]::UTF8.GetBytes($Value)
    $lengthWithNull = $raw.Length + 1
    $paddedLength = [Math]::Ceiling($lengthWithNull / 4.0) * 4
    $result = New-Object byte[] $paddedLength
    [Array]::Copy($raw, $result, $raw.Length)
    return $result
}

function Join-Bytes([byte[]]$First, [byte[]]$Second, [byte[]]$Third) {
    $length = $First.Length + $Second.Length + $(if ($null -eq $Third) { 0 } else { $Third.Length })
    $result = New-Object byte[] $length
    [Array]::Copy($First, 0, $result, 0, $First.Length)
    [Array]::Copy($Second, 0, $result, $First.Length, $Second.Length)
    if ($null -ne $Third) { [Array]::Copy($Third, 0, $result, $First.Length + $Second.Length, $Third.Length) }
    return $result
}

function New-OscIntPacket([string]$Address, [int]$Value) {
    $valueBytes = [BitConverter]::GetBytes($Value)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($valueBytes) }
    return Join-Bytes (Get-OscStringBytes $Address) (Get-OscStringBytes ',i') $valueBytes
}

function New-OscFloatPacket([string]$Address, [single]$Value) {
    $valueBytes = [BitConverter]::GetBytes($Value)
    if ([BitConverter]::IsLittleEndian) { [Array]::Reverse($valueBytes) }
    return Join-Bytes (Get-OscStringBytes $Address) (Get-OscStringBytes ',f') $valueBytes
}

function New-OscBoolPacket([string]$Address, [bool]$Value) {
    $tag = if ($Value) { ',T' } else { ',F' }
    return Join-Bytes (Get-OscStringBytes $Address) (Get-OscStringBytes $tag) $null
}

if ($SelfTest) {
    $cases = @(
        @('/avatar/parameters/HR_Value', 142),
        @('/avatar/parameters/HR_Hundreds', 1),
        @('/avatar/parameters/HR_Tens', 4),
        @('/avatar/parameters/HR_Ones', 2)
    )
    foreach ($case in $cases) {
        $packet = New-OscIntPacket $case[0] $case[1]
        $address = [Text.Encoding]::ASCII.GetString($packet).Split([char]0)[0]
        $addressLength = [Text.Encoding]::ASCII.GetByteCount($case[0]) + 1
        $typeTagOffset = [int]([Math]::Ceiling($addressLength / 4.0) * 4)
        $typeTag = [Text.Encoding]::ASCII.GetString($packet, $typeTagOffset, 2)
        if ($address -ne $case[0] -or $typeTag -ne ',i' -or $packet.Length % 4 -ne 0) {
            throw "OSC Int32 packet self-test failed for $($case[0])."
        }
    }
    Write-Output 'HeartRateBridge protocol self-test: PASS'
    exit 0
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()

$script:receiver = $null
$script:oscClient = New-Object Net.Sockets.UdpClient
$script:lastPacketMillis = 0L
$script:validSent = $false
$script:receivedCount = 0L
$script:phoneAddress = '--'

$form = New-Object Windows.Forms.Form
$form.Text = 'Heart Rate Bridge'
$form.ClientSize = New-Object Drawing.Size(720, 650)
$form.MinimumSize = New-Object Drawing.Size(736, 689)
$form.StartPosition = 'CenterScreen'
$form.BackColor = [Drawing.Color]::FromArgb(247, 247, 252)
$form.Font = New-Object Drawing.Font('Segoe UI', 10)

function New-Label([string]$Text, [int]$X, [int]$Y, [int]$Width, [int]$Height, [single]$Size, [Drawing.FontStyle]$Style) {
    $label = New-Object Windows.Forms.Label
    $label.Text = $Text
    $label.Location = New-Object Drawing.Point($X, $Y)
    $label.Size = New-Object Drawing.Size($Width, $Height)
    $label.Font = New-Object Drawing.Font('Segoe UI', $Size, $Style)
    $label.ForeColor = [Drawing.Color]::FromArgb(40, 38, 45)
    $label.BackColor = [Drawing.Color]::Transparent
    $null = $form.Controls.Add($label)
    return $label
}

$null = New-Label 'Heart Rate Bridge' 28 22 400 42 23 ([Drawing.FontStyle]::Bold)
$null = New-Label 'Phone UDP  ->  Windows  ->  VRChat OSC' 31 61 500 26 10 ([Drawing.FontStyle]::Regular)

$bpmCard = New-Object Windows.Forms.Panel
$bpmCard.Location = New-Object Drawing.Point(28, 103)
$bpmCard.Size = New-Object Drawing.Size(664, 133)
$bpmCard.BackColor = [Drawing.Color]::FromArgb(232, 222, 248)
$form.Controls.Add($bpmCard)
$bpmCaption = New-Object Windows.Forms.Label
$bpmCaption.Text = 'CURRENT HEART RATE'
$bpmCaption.Location = New-Object Drawing.Point(22, 18)
$bpmCaption.Size = New-Object Drawing.Size(300, 25)
$bpmCaption.Font = New-Object Drawing.Font('Segoe UI Semibold', 9)
$bpmCard.Controls.Add($bpmCaption)
$bpmLabel = New-Object Windows.Forms.Label
$bpmLabel.Text = '-- BPM'
$bpmLabel.Location = New-Object Drawing.Point(18, 42)
$bpmLabel.Size = New-Object Drawing.Size(360, 66)
$bpmLabel.Font = New-Object Drawing.Font('Segoe UI', 35, [Drawing.FontStyle]::Bold)
$bpmLabel.ForeColor = [Drawing.Color]::FromArgb(63, 45, 92)
$bpmCard.Controls.Add($bpmLabel)
$freshLabel = New-Object Windows.Forms.Label
$freshLabel.Text = 'Waiting for phone data'
$freshLabel.Location = New-Object Drawing.Point(385, 56)
$freshLabel.Size = New-Object Drawing.Size(250, 46)
$freshLabel.TextAlign = [Drawing.ContentAlignment]::MiddleRight
$bpmCard.Controls.Add($freshLabel)

$null = New-Label 'Connection' 30 255 250 28 13 ([Drawing.FontStyle]::Bold)
$statusLabel = New-Label 'Stopped' 31 287 640 28 11 ([Drawing.FontStyle]::Regular)
$detailLabel = New-Label 'Phone: --    Packets: 0    Last latency: --' 31 318 640 28 10 ([Drawing.FontStyle]::Regular)
$localAddresses = [Net.Dns]::GetHostAddresses([Net.Dns]::GetHostName()) | Where-Object { $_.AddressFamily -eq [Net.Sockets.AddressFamily]::InterNetwork -and -not [Net.IPAddress]::IsLoopback($_) }
$localIpText = (($localAddresses | ForEach-Object { $_.IPAddressToString }) -join ', ')
$localLabel = New-Label ("PC IPv4: " + $(if ($localIpText) { $localIpText } else { '--' })) 31 349 640 27 9 ([Drawing.FontStyle]::Regular)

$null = New-Label 'UDP listen port' 31 394 180 24 9 ([Drawing.FontStyle]::Regular)
$listenPortBox = New-Object Windows.Forms.NumericUpDown
$listenPortBox.Location = New-Object Drawing.Point(31, 420)
$listenPortBox.Size = New-Object Drawing.Size(180, 32)
$listenPortBox.Minimum = 1
$listenPortBox.Maximum = 65535
$listenPortBox.Value = 9123
$form.Controls.Add($listenPortBox)

$null = New-Label 'VRChat OSC port' 233 394 180 24 9 ([Drawing.FontStyle]::Regular)
$oscPortBox = New-Object Windows.Forms.NumericUpDown
$oscPortBox.Location = New-Object Drawing.Point(233, 420)
$oscPortBox.Size = New-Object Drawing.Size(180, 32)
$oscPortBox.Minimum = 1
$oscPortBox.Maximum = 65535
$oscPortBox.Value = 9000
$form.Controls.Add($oscPortBox)

$oscCheck = New-Object Windows.Forms.CheckBox
$oscCheck.Text = 'Forward to VRChat OSC'
$oscCheck.Location = New-Object Drawing.Point(441, 421)
$oscCheck.Size = New-Object Drawing.Size(230, 30)
$oscCheck.Checked = $true
$form.Controls.Add($oscCheck)

$startButton = New-Object Windows.Forms.Button
$startButton.Text = 'Start receiver'
$startButton.Location = New-Object Drawing.Point(31, 476)
$startButton.Size = New-Object Drawing.Size(180, 43)
$startButton.FlatStyle = [Windows.Forms.FlatStyle]::Flat
$startButton.BackColor = [Drawing.Color]::FromArgb(103, 80, 164)
$startButton.ForeColor = [Drawing.Color]::White
$startButton.FlatAppearance.BorderSize = 0
$form.Controls.Add($startButton)

$stopButton = New-Object Windows.Forms.Button
$stopButton.Text = 'Stop'
$stopButton.Location = New-Object Drawing.Point(225, 476)
$stopButton.Size = New-Object Drawing.Size(130, 43)
$stopButton.Enabled = $false
$form.Controls.Add($stopButton)

$logBox = New-Object Windows.Forms.TextBox
$logBox.Location = New-Object Drawing.Point(31, 544)
$logBox.Size = New-Object Drawing.Size(640, 75)
$logBox.Multiline = $true
$logBox.ReadOnly = $true
$logBox.ScrollBars = 'Vertical'
$logBox.BackColor = [Drawing.Color]::White
$form.Controls.Add($logBox)

function Add-Log([string]$Message) {
    $line = (Get-Date -Format 'HH:mm:ss') + '  ' + $Message
    $logBox.AppendText($line + [Environment]::NewLine)
    Write-Host $line
}

function Send-Osc([byte[]]$Packet) {
    if (-not $oscCheck.Checked) { return }
    $endpoint = New-Object Net.IPEndPoint([Net.IPAddress]::Loopback, [int]$oscPortBox.Value)
    $null = $script:oscClient.Send($Packet, $Packet.Length, $endpoint)
}

function Set-HeartRateValid([bool]$Valid) {
    Send-Osc (New-OscBoolPacket '/avatar/parameters/HeartRateValid' $Valid)
    $script:validSent = $Valid
}

function Send-HeartRateOsc([int]$Bpm) {
    $value = [Math]::Min(999, [Math]::Max(0, $Bpm))

    Send-Osc (New-OscIntPacket '/avatar/parameters/HR_Value' $value)
    Send-Osc (New-OscIntPacket '/avatar/parameters/HR_Hundreds' ([int][Math]::Floor($value / 100)))
    Send-Osc (New-OscIntPacket '/avatar/parameters/HR_Tens' ([int][Math]::Floor(($value % 100) / 10)))
    Send-Osc (New-OscIntPacket '/avatar/parameters/HR_Ones' ($value % 10))

    Send-Osc (New-OscIntPacket '/avatar/parameters/HeartRate' $value)
    $normalized = [single][Math]::Min(1.0, [Math]::Max(0.0, ($value - 40.0) / 160.0))
    Send-Osc (New-OscFloatPacket '/avatar/parameters/HeartRateNormalized' $normalized)
}

$startButton.Add_Click({
    try {
        if ($null -ne $script:receiver) { $script:receiver.Close() }
        $script:receiver = New-Object Net.Sockets.UdpClient([int]$listenPortBox.Value)
        $script:receiver.Client.Blocking = $false
        $statusLabel.Text = "Listening on UDP 0.0.0.0:$($listenPortBox.Value)"
        $statusLabel.ForeColor = [Drawing.Color]::FromArgb(36, 121, 72)
        $startButton.Enabled = $false
        $stopButton.Enabled = $true
        $listenPortBox.Enabled = $false
        Add-Log 'Receiver started. Set the phone target IP to this PC IPv4 address.'
    } catch {
        $statusLabel.Text = 'Start failed: ' + $_.Exception.Message
        $statusLabel.ForeColor = [Drawing.Color]::Firebrick
        Add-Log $statusLabel.Text
    }
})

$stopButton.Add_Click({
    if ($null -ne $script:receiver) { $script:receiver.Close(); $script:receiver = $null }
    $statusLabel.Text = 'Stopped'
    $statusLabel.ForeColor = [Drawing.Color]::FromArgb(40, 38, 45)
    $startButton.Enabled = $true
    $stopButton.Enabled = $false
    $listenPortBox.Enabled = $true
    Add-Log 'Receiver stopped.'
})

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 100
$timer.Add_Tick({
    try {
        while ($null -ne $script:receiver -and $script:receiver.Available -gt 0) {
            $sender = New-Object Net.IPEndPoint([Net.IPAddress]::Any, 0)
            $bytes = $script:receiver.Receive([ref]$sender)
            $text = [Text.Encoding]::UTF8.GetString($bytes)
            $packet = $text | ConvertFrom-Json
            if ($null -eq $packet.sequence -or $null -eq $packet.bpm) { throw 'Missing sequence or bpm.' }
            $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $sampleMillis = [long]$packet.sampleEpochMillis
            $latency = [Math]::Max([long]0, [long]($now - $sampleMillis))
            $bpm = [int]$packet.bpm
            $isRealHeartRate = ([string]$packet.type -eq 'heart_rate')
            $script:lastPacketMillis = $now
            $script:receivedCount++
            $script:phoneAddress = $sender.Address.IPAddressToString
            $bpmLabel.Text = $(if ($isRealHeartRate) { "$bpm BPM" } else { 'LINK TEST' })
            $freshLabel.Text = $(if ($isRealHeartRate) { "Live  |  ${latency} ms end-to-end" } else { "Passed  |  ${latency} ms end-to-end" })
            $detailLabel.Text = "Phone: $($script:phoneAddress)    Packets: $($script:receivedCount)    Last latency: ${latency} ms"
            $statusLabel.Text = "Connected - receiving from $($script:phoneAddress)"
            $statusLabel.ForeColor = [Drawing.Color]::FromArgb(36, 121, 72)

            $ackJson = '{"type":"pc_ack","sequence":' + ([long]$packet.sequence) + ',"pcEpochMillis":' + $now + '}'
            $ackBytes = [Text.Encoding]::UTF8.GetBytes($ackJson)
            $null = $script:receiver.Send($ackBytes, $ackBytes.Length, $sender)

            if ($isRealHeartRate) {
                Send-HeartRateOsc $bpm
                if (-not $script:validSent) { Set-HeartRateValid $true }
            }
            Add-Log "type=$($packet.type) seq=$($packet.sequence) bpm=$bpm phone=$($script:phoneAddress) latency=${latency}ms ack=sent"
        }

        if ($script:lastPacketMillis -gt 0) {
            $now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
            $age = $now - $script:lastPacketMillis
            if ($age -ge 10000) {
                $freshLabel.Text = "Stale  |  $([Math]::Floor($age / 1000)) seconds ago"
                if ($script:validSent) { Set-HeartRateValid $false; Add-Log 'Heart rate became stale; HeartRateValid=false.' }
            }
        }
    } catch [Net.Sockets.SocketException] {
        if ($_.Exception.SocketErrorCode -ne [Net.Sockets.SocketError]::WouldBlock) { Add-Log ('UDP error: ' + $_.Exception.Message) }
    } catch {
        Add-Log ('Packet error: ' + $_.Exception.Message)
    }
})
$timer.Start()

$form.Add_FormClosed({
    $timer.Stop()
    if ($null -ne $script:receiver) { $script:receiver.Close() }
    $script:oscClient.Close()
})

$form.Add_Shown({ $startButton.PerformClick() })
[void]$form.ShowDialog()
