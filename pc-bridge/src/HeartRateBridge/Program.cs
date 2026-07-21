using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Forms;

[assembly: System.Reflection.AssemblyTitle("VRC Realtime Heartbeat")]
[assembly: System.Reflection.AssemblyDescription("Galaxy Watch heart-rate bridge for VRChat OSC")]
[assembly: System.Reflection.AssemblyProduct("VRC Realtime Heartbeat")]
[assembly: System.Reflection.AssemblyVersion("0.3.0.0")]
[assembly: System.Reflection.AssemblyFileVersion("0.3.0.0")]

namespace VrcRealtimeHeartbeat
{
    internal static class Program
    {
        [STAThread]
        private static int Main(string[] args)
        {
            if (args.Any(arg => string.Equals(arg, "--self-test", StringComparison.OrdinalIgnoreCase)))
            {
                try
                {
                    ProtocolSelfTest.Run();
                    return 0;
                }
                catch
                {
                    return 1;
                }
            }

            NativeMethods.EnableBestDpiAwareness();
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            Application.Run(new BridgeForm());
            return 0;
        }
    }

    internal static class NativeMethods
    {
        private static readonly IntPtr DpiAwarePerMonitorV2 = new IntPtr(-4);

        [DllImport("user32.dll", SetLastError = true)]
        private static extern bool SetProcessDpiAwarenessContext(IntPtr value);

        [DllImport("shcore.dll")]
        private static extern int SetProcessDpiAwareness(int value);

        [DllImport("user32.dll")]
        private static extern bool SetProcessDPIAware();

        internal static void EnableBestDpiAwareness()
        {
            try
            {
                if (SetProcessDpiAwarenessContext(DpiAwarePerMonitorV2)) return;
            }
            catch (EntryPointNotFoundException) { }
            catch (DllNotFoundException) { }

            try
            {
                if (SetProcessDpiAwareness(2) == 0) return;
            }
            catch (EntryPointNotFoundException) { }
            catch (DllNotFoundException) { }

            SetProcessDPIAware();
        }
    }

    internal sealed class BridgeForm : Form
    {
        private readonly UdpClient _oscClient = new UdpClient();
        private readonly Timer _timer = new Timer();
        private readonly JavaScriptSerializer _json = new JavaScriptSerializer();
        private UdpClient _receiver;
        private long _lastRealPacketMillis;
        private long _receivedCount;
        private bool _validSent;
        private int _currentBpm;
        private long _nextPulseMillis;
        private long _pulseOffMillis;
        private bool _pulseActive;
        private int _staleTimeoutMillis = 15000;

        private readonly Label _bpmLabel;
        private readonly Label _freshLabel;
        private readonly Label _statusLabel;
        private readonly Label _detailLabel;
        private readonly NumericUpDown _listenPortBox;
        private readonly NumericUpDown _oscPortBox;
        private readonly CheckBox _oscCheck;
        private readonly Button _startButton;
        private readonly Button _stopButton;
        private readonly TextBox _logBox;

        public BridgeForm()
        {
            Text = "PulseLink · VRChat 心率桥";
            ClientSize = new Size(960, 760);
            MinimumSize = new Size(976, 799);
            MaximumSize = new Size(976, 799);
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.FromArgb(8, 16, 31);
            Font = new Font("Microsoft YaHei UI", 10F);
            AutoScaleMode = AutoScaleMode.Dpi;
            DoubleBuffered = true;
            Icon = Icon.ExtractAssociatedIcon(Application.ExecutablePath);

            var brandIcon = new PictureBox
            {
                Location = new Point(26, 20),
                Size = new Size(54, 54),
                SizeMode = PictureBoxSizeMode.Zoom,
                Image = Icon.ToBitmap(),
            };
            Controls.Add(brandIcon);
            AddLabel(this, "PulseLink", 92, 18, 360, 35, 23F, FontStyle.Bold, Color.White);
            AddLabel(this, "GALAXY WATCH  ·  PHONE  ·  VRCHAT OSC", 94, 52, 430, 22, 9F, FontStyle.Bold, Color.FromArgb(125, 152, 190));
            var versionPill = new RoundedPanel
            {
                Location = new Point(825, 28),
                Size = new Size(108, 32),
                BackColor = Color.FromArgb(23, 36, 59),
                CornerRadius = 16,
            };
            Controls.Add(versionPill);
            AddLabel(versionPill, "REALTIME  v0.3", 0, 7, 108, 20, 8F, FontStyle.Bold, Color.FromArgb(76, 214, 155), ContentAlignment.MiddleCenter);

            var bpmCard = CreateCard(new Point(24, 94), new Size(570, 222), Color.FromArgb(19, 31, 52));
            AddLabel(bpmCard, "LIVE HEART RATE", 24, 20, 260, 24, 9F, FontStyle.Bold, Color.FromArgb(32, 184, 255));
            AddLabel(bpmCard, "真实传感器数据", 383, 20, 155, 24, 9F, FontStyle.Regular, Color.FromArgb(157, 170, 194), ContentAlignment.MiddleRight);

            _bpmLabel = new Label
            {
                Text = "-- BPM",
                Location = new Point(22, 52),
                Size = new Size(510, 82),
                Font = new Font("Segoe UI", 49F, FontStyle.Bold),
                ForeColor = Color.White,
                BackColor = Color.Transparent,
            };
            bpmCard.Controls.Add(_bpmLabel);

            _freshLabel = new Label
            {
                Text = "等待手机数据",
                Location = new Point(25, 139),
                Size = new Size(510, 30),
                Font = new Font("Microsoft YaHei UI", 11F, FontStyle.Bold),
                ForeColor = Color.FromArgb(157, 170, 194),
                BackColor = Color.Transparent,
            };
            bpmCard.Controls.Add(_freshLabel);
            _detailLabel = AddLabel(bpmCard, "手机：--   ·   数据包：0   ·   延迟：--", 25, 178, 510, 24, 9F, FontStyle.Regular, Color.FromArgb(125, 152, 190));

            var linkCard = CreateCard(new Point(610, 94), new Size(326, 222), Color.FromArgb(17, 27, 46));
            AddLabel(linkCard, "链路状态", 22, 18, 200, 28, 13F, FontStyle.Bold, Color.White);
            AddStep(linkCard, 58, "01", "Galaxy Watch6", "手表持续采样");
            AddStep(linkCard, 104, "02", "Android 中转", "按设置间隔发送");
            AddStep(linkCard, 150, "03", "Windows 接收器", "本机等待数据");
            _statusLabel = AddLabel(linkCard, "正在启动…", 56, 188, 245, 22, 8.5F, FontStyle.Bold, Color.FromArgb(255, 197, 109));

            var settingsCard = CreateCard(new Point(24, 332), new Size(912, 174), Color.FromArgb(17, 27, 46));
            AddLabel(settingsCard, "连接与 OSC", 22, 17, 240, 28, 13F, FontStyle.Bold, Color.White);
            AddLabel(settingsCard, "本机 IPv4  " + GetLocalIpv4Text(), 255, 19, 630, 24, 9F, FontStyle.Regular, Color.FromArgb(125, 152, 190), ContentAlignment.MiddleRight);
            AddLabel(settingsCard, "手机 UDP 端口", 22, 58, 180, 22, 9F, FontStyle.Regular, Color.FromArgb(157, 170, 194));
            _listenPortBox = new NumericUpDown
            {
                Location = new Point(22, 83),
                Size = new Size(170, 34),
                Minimum = 1,
                Maximum = 65535,
                Value = 9123,
                BackColor = Color.FromArgb(29, 44, 69),
                ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle,
            };
            settingsCard.Controls.Add(_listenPortBox);

            AddLabel(settingsCard, "VRChat OSC 端口", 215, 58, 180, 22, 9F, FontStyle.Regular, Color.FromArgb(157, 170, 194));
            _oscPortBox = new NumericUpDown
            {
                Location = new Point(215, 83),
                Size = new Size(170, 34),
                Minimum = 1,
                Maximum = 65535,
                Value = 9000,
                BackColor = Color.FromArgb(29, 44, 69),
                ForeColor = Color.White,
                BorderStyle = BorderStyle.FixedSingle,
            };
            settingsCard.Controls.Add(_oscPortBox);

            _oscCheck = new CheckBox
            {
                Text = "转发到 VRChat OSC",
                Location = new Point(410, 84),
                Size = new Size(205, 32),
                Checked = true,
                ForeColor = Color.White,
                BackColor = Color.Transparent,
            };
            settingsCard.Controls.Add(_oscCheck);

            _startButton = new RoundedButton
            {
                Text = "启动接收",
                Location = new Point(635, 76),
                Size = new Size(120, 44),
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(32, 184, 255),
                ForeColor = Color.FromArgb(0, 30, 45),
                Font = new Font("Microsoft YaHei UI", 9.5F, FontStyle.Bold),
                CornerRadius = 13,
            };
            _startButton.FlatAppearance.BorderSize = 0;
            _startButton.Click += delegate { StartReceiver(); };
            settingsCard.Controls.Add(_startButton);

            _stopButton = new RoundedButton
            {
                Text = "停止",
                Location = new Point(769, 76),
                Size = new Size(116, 44),
                Enabled = false,
                FlatStyle = FlatStyle.Flat,
                BackColor = Color.FromArgb(58, 36, 48),
                ForeColor = Color.FromArgb(255, 91, 98),
                Font = new Font("Microsoft YaHei UI", 9.5F, FontStyle.Bold),
                CornerRadius = 13,
            };
            _stopButton.FlatAppearance.BorderSize = 0;
            _stopButton.Click += delegate { StopReceiver(true); };
            settingsCard.Controls.Add(_stopButton);
            AddLabel(settingsCard, "参数  HR_Tens · HR_Ones · HRValid · HRPulse", 22, 137, 550, 22, 8.5F, FontStyle.Regular, Color.FromArgb(125, 152, 190));
            AddLabel(settingsCard, "真实数据超时会自动发送 HRValid=false", 585, 137, 300, 22, 8.5F, FontStyle.Regular, Color.FromArgb(125, 152, 190), ContentAlignment.MiddleRight);

            var logCard = CreateCard(new Point(24, 522), new Size(912, 210), Color.FromArgb(17, 27, 46));
            AddLabel(logCard, "实时日志", 22, 16, 200, 28, 13F, FontStyle.Bold, Color.White);
            AddLabel(logCard, "只显示最近事件", 680, 18, 205, 22, 8.5F, FontStyle.Regular, Color.FromArgb(125, 152, 190), ContentAlignment.MiddleRight);
            _logBox = new TextBox
            {
                Location = new Point(22, 52),
                Size = new Size(863, 136),
                Multiline = true,
                ReadOnly = true,
                ScrollBars = ScrollBars.Vertical,
                BackColor = Color.FromArgb(10, 19, 34),
                ForeColor = Color.FromArgb(185, 199, 221),
                BorderStyle = BorderStyle.None,
                Font = new Font("Consolas", 9F),
            };
            logCard.Controls.Add(_logBox);

            _timer.Interval = 100;
            _timer.Tick += delegate { OnTimerTick(); };
            _timer.Start();

            Shown += delegate { StartReceiver(); };
            FormClosed += delegate
            {
                _timer.Stop();
                StopReceiver(false);
                _oscClient.Close();
            };
        }

        private RoundedPanel CreateCard(Point location, Size size, Color color)
        {
            var card = new RoundedPanel
            {
                Location = location,
                Size = size,
                BackColor = color,
                CornerRadius = 22,
            };
            Controls.Add(card);
            return card;
        }

        private static Label AddLabel(Control parent, string text, int x, int y, int width, int height, float size, FontStyle style, Color color, ContentAlignment alignment = ContentAlignment.MiddleLeft)
        {
            var label = new Label
            {
                Text = text,
                Location = new Point(x, y),
                Size = new Size(width, height),
                Font = new Font("Microsoft YaHei UI", size, style),
                ForeColor = color,
                BackColor = Color.Transparent,
                TextAlign = alignment,
            };
            parent.Controls.Add(label);
            return label;
        }

        private static void AddStep(Control parent, int y, string number, string title, string detail)
        {
            var badge = new RoundedPanel
            {
                Location = new Point(22, y),
                Size = new Size(34, 34),
                CornerRadius = 11,
                BackColor = Color.FromArgb(25, 66, 96),
            };
            parent.Controls.Add(badge);
            AddLabel(badge, number, 0, 6, 34, 22, 8F, FontStyle.Bold, Color.FromArgb(32, 184, 255), ContentAlignment.MiddleCenter);
            AddLabel(parent, title, 68, y - 1, 215, 20, 9F, FontStyle.Bold, Color.White);
            AddLabel(parent, detail, 68, y + 18, 215, 18, 8F, FontStyle.Regular, Color.FromArgb(125, 152, 190));
        }

        private void StartReceiver()
        {
            try
            {
                CloseReceiver();
                _receiver = new UdpClient((int)_listenPortBox.Value);
                _receiver.Client.Blocking = false;
                _statusLabel.Text = "监听 UDP 0.0.0.0:" + _listenPortBox.Value;
                _statusLabel.ForeColor = Color.FromArgb(76, 214, 155);
                _startButton.Enabled = false;
                _stopButton.Enabled = true;
                _listenPortBox.Enabled = false;
                AppendLog("接收器已启动，请将手机目标设为本机局域网 IPv4。");
            }
            catch (Exception error)
            {
                _statusLabel.Text = "启动失败：" + error.Message;
                _statusLabel.ForeColor = Color.FromArgb(255, 91, 98);
                AppendLog(_statusLabel.Text);
            }
        }

        private void StopReceiver(bool writeLog)
        {
            CloseReceiver();
            SetHeartRateValid(false);
            StopPulse();
            _statusLabel.Text = "已停止";
            _statusLabel.ForeColor = Color.FromArgb(157, 170, 194);
            _startButton.Enabled = true;
            _stopButton.Enabled = false;
            _listenPortBox.Enabled = true;
            if (writeLog)
            {
                AppendLog("接收器已停止。");
            }
        }

        private void CloseReceiver()
        {
            if (_receiver == null)
            {
                return;
            }

            _receiver.Close();
            _receiver = null;
        }

        private void OnTimerTick()
        {
            try
            {
                while (_receiver != null && _receiver.Available > 0)
                {
                    IPEndPoint sender = new IPEndPoint(IPAddress.Any, 0);
                    byte[] bytes = _receiver.Receive(ref sender);
                    ProcessPacket(bytes, sender);
                }

                long now = NowMillis();
                if (_lastRealPacketMillis > 0 && now - _lastRealPacketMillis >= _staleTimeoutMillis)
                {
                    long ageSeconds = (now - _lastRealPacketMillis) / 1000;
                    _freshLabel.Text = "信号超时  |  " + ageSeconds + " 秒前";
                    if (_validSent)
                    {
                        SetHeartRateValid(false);
                        StopPulse();
                        AppendLog("真实心率超过 " + (_staleTimeoutMillis / 1000) + " 秒未更新，已发送 HRValid=false。");
                    }
                }

                UpdatePulse(now);
            }
            catch (SocketException error)
            {
                if (error.SocketErrorCode != SocketError.WouldBlock)
                {
                    AppendLog("UDP 错误：" + error.Message);
                }
            }
            catch (Exception error)
            {
                AppendLog("数据包错误：" + error.Message);
            }
        }

        private void ProcessPacket(byte[] bytes, IPEndPoint sender)
        {
            string text = Encoding.UTF8.GetString(bytes);
            Dictionary<string, object> packet = _json.Deserialize<Dictionary<string, object>>(text);
            long sequence = RequiredInt64(packet, "sequence");
            long sampleMillis = RequiredInt64(packet, "sampleEpochMillis");
            int bpm = checked((int)RequiredInt64(packet, "bpm"));
            string type = packet.ContainsKey("type") && packet["type"] != null ? Convert.ToString(packet["type"]) : string.Empty;
            bool isRealHeartRate = string.Equals(type, "heart_rate", StringComparison.Ordinal);
            object intervalValue;
            if (packet.TryGetValue("phoneForwardIntervalSeconds", out intervalValue) && intervalValue != null)
            {
                int seconds = Math.Max(1, Math.Min(30, Convert.ToInt32(intervalValue)));
                _staleTimeoutMillis = Math.Max(10000, seconds * 2500);
            }

            if (sampleMillis <= 0)
            {
                throw new InvalidOperationException("sampleEpochMillis 无效。");
            }
            if (bpm <= 0 || bpm > 300)
            {
                throw new InvalidOperationException("BPM 超出有效范围：" + bpm);
            }

            long now = NowMillis();
            long latency = Math.Max(0L, now - sampleMillis);
            _receivedCount++;

            SendAck(sequence, now, sender);

            _detailLabel.Text = "手机：" + sender.Address + "   ·   数据包：" + _receivedCount + "   ·   延迟：" + latency + " ms";
            _statusLabel.Text = "已连接  " + sender.Address;
            _statusLabel.ForeColor = Color.FromArgb(76, 214, 155);

            if (isRealHeartRate)
            {
                _lastRealPacketMillis = now;
                _currentBpm = bpm;
                _bpmLabel.Text = bpm + " BPM";
                _freshLabel.Text = "●  数据正常   ·   端到端 " + latency + " ms";
                _freshLabel.ForeColor = Color.FromArgb(76, 214, 155);
                SendHeartRateOsc(bpm);
                if (!_validSent)
                {
                    SetHeartRateValid(true);
                }
                if (_nextPulseMillis == 0)
                {
                    _nextPulseMillis = now;
                }
            }
            else
            {
                _bpmLabel.Text = "链路测试";
                _freshLabel.Text = "已通过  |  端到端 " + latency + " ms";
                _freshLabel.ForeColor = Color.FromArgb(32, 184, 255);
            }

            AppendLog("类型=" + type + " 序号=" + sequence + " BPM=" + bpm + " 手机=" + sender.Address + " 延迟=" + latency + "ms 回执=已发送");
        }

        private void SendAck(long sequence, long now, IPEndPoint sender)
        {
            string ackJson = "{\"type\":\"pc_ack\",\"sequence\":" + sequence + ",\"pcEpochMillis\":" + now + "}";
            byte[] ackBytes = Encoding.UTF8.GetBytes(ackJson);
            _receiver.Send(ackBytes, ackBytes.Length, sender);
        }

        private void SendHeartRateOsc(int bpm)
        {
            SendOsc(OscCodec.Int("/avatar/parameters/HeartRate", bpm));
            float normalized = (float)Math.Min(1.0, Math.Max(0.0, (bpm - 40.0) / 160.0));
            SendOsc(OscCodec.Float("/avatar/parameters/HeartRateNormalized", normalized));

            int twoDigitBpm = Math.Min(99, Math.Max(0, bpm));
            SendOsc(OscCodec.Int("/avatar/parameters/HR_Tens", twoDigitBpm / 10));
            SendOsc(OscCodec.Int("/avatar/parameters/HR_Ones", twoDigitBpm % 10));
        }

        private void SetHeartRateValid(bool valid)
        {
            if (_validSent == valid && valid)
            {
                return;
            }

            SendOsc(OscCodec.Bool("/avatar/parameters/HeartRateValid", valid));
            SendOsc(OscCodec.Bool("/avatar/parameters/HRValid", valid));
            _validSent = valid;
        }

        private void UpdatePulse(long now)
        {
            if (!_validSent || _currentBpm <= 0)
            {
                return;
            }

            if (_pulseActive && now >= _pulseOffMillis)
            {
                SendOsc(OscCodec.Bool("/avatar/parameters/HRPulse", false));
                _pulseActive = false;
            }

            if (!_pulseActive && now >= _nextPulseMillis)
            {
                SendOsc(OscCodec.Bool("/avatar/parameters/HRPulse", true));
                _pulseActive = true;
                _pulseOffMillis = now + 120;
                _nextPulseMillis = now + Math.Max(250, 60000 / _currentBpm);
            }
        }

        private void StopPulse()
        {
            if (_pulseActive || _nextPulseMillis > 0)
            {
                SendOsc(OscCodec.Bool("/avatar/parameters/HRPulse", false));
            }
            _pulseActive = false;
            _nextPulseMillis = 0;
            _pulseOffMillis = 0;
            _currentBpm = 0;
        }

        private void SendOsc(byte[] packet)
        {
            if (!_oscCheck.Checked)
            {
                return;
            }

            try
            {
                var endpoint = new IPEndPoint(IPAddress.Loopback, (int)_oscPortBox.Value);
                _oscClient.Send(packet, packet.Length, endpoint);
            }
            catch (Exception error)
            {
                AppendLog("OSC 发送失败：" + error.Message);
            }
        }

        private void AppendLog(string message)
        {
            _logBox.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + message + Environment.NewLine);
            if (_logBox.TextLength > 24000)
            {
                _logBox.Text = _logBox.Text.Substring(_logBox.TextLength - 16000);
                _logBox.SelectionStart = _logBox.TextLength;
                _logBox.ScrollToCaret();
            }
        }

        private static long RequiredInt64(Dictionary<string, object> packet, string name)
        {
            object value;
            if (!packet.TryGetValue(name, out value) || value == null)
            {
                throw new InvalidOperationException("缺少字段：" + name);
            }
            return Convert.ToInt64(value);
        }

        private static long NowMillis()
        {
            return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        }

        private static string GetLocalIpv4Text()
        {
            var addresses = NetworkInterface.GetAllNetworkInterfaces()
                .Where(network => network.OperationalStatus == OperationalStatus.Up)
                .SelectMany(network => network.GetIPProperties().UnicastAddresses)
                .Select(info => info.Address)
                .Where(address => address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(address))
                .Select(address => address.ToString())
                .Distinct()
                .ToArray();
            return addresses.Length == 0 ? "--" : string.Join(", ", addresses);
        }
    }

    internal sealed class RoundedPanel : Panel
    {
        public int CornerRadius { get; set; } = 16;

        protected override void OnSizeChanged(EventArgs e)
        {
            base.OnSizeChanged(e);
            UpdateRegion();
        }

        private void UpdateRegion()
        {
            if (Width <= 0 || Height <= 0) return;
            using (GraphicsPath path = RoundedRectanglePath(new Rectangle(0, 0, Width, Height), CornerRadius))
            {
                Region = new Region(path);
            }
        }

        internal static GraphicsPath RoundedRectanglePath(Rectangle bounds, int radius)
        {
            int diameter = Math.Max(2, radius * 2);
            var path = new GraphicsPath();
            path.AddArc(bounds.Left, bounds.Top, diameter, diameter, 180, 90);
            path.AddArc(bounds.Right - diameter, bounds.Top, diameter, diameter, 270, 90);
            path.AddArc(bounds.Right - diameter, bounds.Bottom - diameter, diameter, diameter, 0, 90);
            path.AddArc(bounds.Left, bounds.Bottom - diameter, diameter, diameter, 90, 90);
            path.CloseFigure();
            return path;
        }
    }

    internal sealed class RoundedButton : Button
    {
        public int CornerRadius { get; set; } = 12;

        protected override void OnSizeChanged(EventArgs e)
        {
            base.OnSizeChanged(e);
            if (Width <= 0 || Height <= 0) return;
            using (GraphicsPath path = RoundedPanel.RoundedRectanglePath(new Rectangle(0, 0, Width, Height), CornerRadius))
            {
                Region = new Region(path);
            }
        }
    }

    internal static class OscCodec
    {
        public static byte[] Int(string address, int value)
        {
            byte[] valueBytes = BitConverter.GetBytes(value);
            if (BitConverter.IsLittleEndian)
            {
                Array.Reverse(valueBytes);
            }
            return Join(String(address), String(",i"), valueBytes);
        }

        public static byte[] Float(string address, float value)
        {
            byte[] valueBytes = BitConverter.GetBytes(value);
            if (BitConverter.IsLittleEndian)
            {
                Array.Reverse(valueBytes);
            }
            return Join(String(address), String(",f"), valueBytes);
        }

        public static byte[] Bool(string address, bool value)
        {
            return Join(String(address), String(value ? ",T" : ",F"), new byte[0]);
        }

        private static byte[] String(string value)
        {
            byte[] raw = Encoding.UTF8.GetBytes(value);
            int paddedLength = ((raw.Length + 1 + 3) / 4) * 4;
            byte[] result = new byte[paddedLength];
            Array.Copy(raw, result, raw.Length);
            return result;
        }

        private static byte[] Join(byte[] first, byte[] second, byte[] third)
        {
            byte[] result = new byte[first.Length + second.Length + third.Length];
            Array.Copy(first, 0, result, 0, first.Length);
            Array.Copy(second, 0, result, first.Length, second.Length);
            Array.Copy(third, 0, result, first.Length + second.Length, third.Length);
            return result;
        }
    }

    internal static class ProtocolSelfTest
    {
        public static void Run()
        {
            byte[] heartRate = OscCodec.Int("/avatar/parameters/HeartRate", 72);
            byte[] tens = OscCodec.Int("/avatar/parameters/HR_Tens", 7);
            byte[] ones = OscCodec.Int("/avatar/parameters/HR_Ones", 2);
            byte[] valid = OscCodec.Bool("/avatar/parameters/HRValid", true);
            byte[] pulse = OscCodec.Bool("/avatar/parameters/HRPulse", true);

            AssertPacket(heartRate, "/avatar/parameters/HeartRate");
            AssertPacket(tens, "/avatar/parameters/HR_Tens");
            AssertPacket(ones, "/avatar/parameters/HR_Ones");
            AssertPacket(valid, "/avatar/parameters/HRValid");
            AssertPacket(pulse, "/avatar/parameters/HRPulse");
        }

        private static void AssertPacket(byte[] packet, string expectedAddress)
        {
            string actualAddress = Encoding.ASCII.GetString(packet).Split('\0')[0];
            if (!string.Equals(actualAddress, expectedAddress, StringComparison.Ordinal) || packet.Length % 4 != 0)
            {
                throw new InvalidOperationException("OSC packet self-test failed for " + expectedAddress);
            }
        }
    }
}
