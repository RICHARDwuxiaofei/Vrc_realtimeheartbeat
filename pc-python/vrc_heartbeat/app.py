from __future__ import annotations

import csv
import ctypes
from datetime import datetime
import queue
import socket
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
from typing import Any
import webbrowser

from . import __version__
from .analytics import HeartRateHistory, HeartRateSample
from .pairing import build_pairing_uri
from .runtime import BridgeRuntime, RuntimeConfig
from .settings import AppSettings, load_settings, save_settings
from .updates import fetch_latest_release, is_newer_version


BG = "#f3f3f1"
PANEL = "#ffffff"
TEXT = "#202124"
MUTED = "#6f7378"
BORDER = "#d8d9d6"
ACCENT = "#c6404d"
ACCENT_DARK = "#aa3340"
GOOD = "#31845b"
WARN = "#b07722"
CHART_GRID = "#e8e8e5"


def enable_dpi_awareness() -> None:
    if sys.platform != "win32":
        return
    try:
        ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except (AttributeError, OSError):
        try:
            ctypes.windll.shcore.SetProcessDpiAwareness(2)
        except (AttributeError, OSError):
            try:
                ctypes.windll.user32.SetProcessDPIAware()
            except (AttributeError, OSError):
                pass


class HeartRateBridgeApp:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.settings = load_settings()
        self.runtime: BridgeRuntime | None = None
        self.events: queue.Queue[tuple[str, dict[str, Any]]] = queue.Queue()
        self.packet_count = 0
        self.history = HeartRateHistory()
        self.csv_samples: list[HeartRateSample] = []
        self.csv_exported_count = 0
        self.latest_release_url = ""
        self._qr_photo: Any = None

        self.listen_port = tk.StringVar(value=str(self.settings.listen_port))
        self.osc_port = tk.StringVar(value=str(self.settings.osc_port))
        self.forward_osc = tk.BooleanVar(value=self.settings.forward_osc)
        self.csv_recording = tk.BooleanVar(value=False)
        self.bpm_text = tk.StringVar(value="--")
        self.signal_text = tk.StringVar(value="等待手机数据")
        self.detail_text = tk.StringVar(value="尚未收到数据包")
        self.receiver_text = tk.StringVar(value="未启动")
        self.phone_text = tk.StringVar(value="--")
        self.osc_text = tk.StringVar(value="已开启" if self.settings.forward_osc else "已关闭")
        self.minimum_text = tk.StringVar(value="--")
        self.maximum_text = tk.StringVar(value="--")
        self.average_text = tk.StringVar(value="--")
        self.csv_text = tk.StringVar(value="CSV 未记录")
        self.update_text = tk.StringVar(value="正在检查 GitHub…")

        self._configure_window()
        self._configure_styles()
        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(50, self._poll_events)
        self.root.after(250, self.start_receiver)
        self.root.after(1_000, self._refresh_chart)
        self.root.after(1_200, self.check_for_updates)

    def _configure_window(self) -> None:
        self.root.title("VRChat 心率桥 · Python")
        self.root.geometry("940x900")
        self.root.minsize(820, 780)
        self.root.configure(background=BG)
        try:
            self.root.iconbitmap(_resource_path("heart-relay.ico"))
        except (tk.TclError, OSError):
            pass

    def _configure_styles(self) -> None:
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure("TEntry", fieldbackground=PANEL, foreground=TEXT, bordercolor=BORDER, padding=7)
        style.configure("TCheckbutton", background=PANEL, foreground=TEXT, font=("Microsoft YaHei UI", 9))
        style.map("TCheckbutton", background=[("active", PANEL)])
        style.configure(
            "Primary.TButton",
            background=ACCENT,
            foreground="#ffffff",
            bordercolor=ACCENT,
            padding=(14, 8),
            font=("Microsoft YaHei UI", 9, "bold"),
        )
        style.map("Primary.TButton", background=[("active", ACCENT_DARK), ("disabled", "#d2a7ac")])
        style.configure(
            "Secondary.TButton",
            background="#ececea",
            foreground=TEXT,
            bordercolor=BORDER,
            padding=(14, 8),
            font=("Microsoft YaHei UI", 9),
        )
        style.map("Secondary.TButton", background=[("active", "#dfdfdc")])

    def _build_ui(self) -> None:
        outer = tk.Frame(self.root, bg=BG)
        outer.pack(fill="both", expand=True, padx=22, pady=16)

        header = tk.Frame(outer, bg=BG)
        header.pack(fill="x", pady=(0, 11))
        tk.Label(header, text="♥", bg=BG, fg=ACCENT, font=("Segoe UI Symbol", 24, "bold")).pack(side="left")
        title_block = tk.Frame(header, bg=BG)
        title_block.pack(side="left", padx=(9, 0))
        tk.Label(
            title_block,
            text="VRChat 心率桥",
            bg=BG,
            fg=TEXT,
            font=("Microsoft YaHei UI", 16, "bold"),
        ).pack(anchor="w")
        tk.Label(
            title_block,
            text=f"Python 版 {__version__}   ·   Watch → Phone → PC",
            bg=BG,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="w", pady=(2, 0))
        update_button = ttk.Button(
            header,
            textvariable=self.update_text,
            style="Secondary.TButton",
            command=self._open_or_check_update,
        )
        update_button.pack(side="right", anchor="e", pady=(3, 0))

        summary = tk.Frame(outer, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        summary.pack(fill="x")
        bpm_column = tk.Frame(summary, bg=PANEL)
        bpm_column.pack(side="left", fill="both", expand=True, padx=22, pady=14)
        tk.Label(bpm_column, text="当前心率", bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 9)).pack(anchor="w")
        bpm_line = tk.Frame(bpm_column, bg=PANEL)
        bpm_line.pack(anchor="w")
        tk.Label(bpm_line, textvariable=self.bpm_text, bg=PANEL, fg=TEXT, font=("Segoe UI", 34, "bold")).pack(side="left")
        tk.Label(bpm_line, text=" BPM", bg=PANEL, fg=MUTED, font=("Segoe UI", 13, "bold")).pack(
            side="left", anchor="s", pady=(0, 6)
        )
        self.signal_label = tk.Label(
            bpm_column, textvariable=self.signal_text, bg=PANEL, fg=MUTED,
            font=("Microsoft YaHei UI", 9, "bold"),
        )
        self.signal_label.pack(anchor="w")
        tk.Label(
            bpm_column, textvariable=self.detail_text, bg=PANEL, fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="w", pady=(3, 0))

        stats = tk.Frame(summary, bg="#fafaf8")
        stats.pack(side="left", fill="y", padx=1, pady=1)
        tk.Label(stats, text="最近 10 分钟", bg="#fafaf8", fg=TEXT, font=("Microsoft YaHei UI", 9, "bold")).pack(
            anchor="w", padx=18, pady=(15, 8)
        )
        stats_row = tk.Frame(stats, bg="#fafaf8")
        stats_row.pack(padx=18, pady=(0, 14))
        self._stat_value(stats_row, "最低", self.minimum_text, 0)
        self._stat_value(stats_row, "最高", self.maximum_text, 1)
        self._stat_value(stats_row, "平均", self.average_text, 2)

        status_column = tk.Frame(summary, bg="#fafaf8", width=240)
        status_column.pack(side="right", fill="y", padx=(0, 1), pady=1)
        status_column.pack_propagate(False)
        tk.Label(status_column, text="连接状态", bg="#fafaf8", fg=TEXT, font=("Microsoft YaHei UI", 9, "bold")).pack(
            anchor="w", padx=18, pady=(15, 7)
        )
        self._status_row(status_column, "UDP 接收器", self.receiver_text)
        self._status_row(status_column, "手机", self.phone_text)
        self._status_row(status_column, "VRChat OSC", self.osc_text)

        chart_panel = tk.Frame(outer, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        chart_panel.pack(fill="x", pady=(11, 0))
        chart_header = tk.Frame(chart_panel, bg=PANEL)
        chart_header.pack(fill="x", padx=16, pady=(10, 2))
        tk.Label(chart_header, text="最近 10 分钟心率曲线", bg=PANEL, fg=TEXT, font=("Microsoft YaHei UI", 10, "bold")).pack(side="left")
        tk.Label(chart_header, textvariable=self.csv_text, bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(side="right")
        self.chart = tk.Canvas(chart_panel, height=170, bg="#fafaf8", highlightthickness=0)
        self.chart.pack(fill="x", padx=16, pady=(3, 13))
        self.chart.bind("<Configure>", lambda _event: self._draw_chart())

        settings_panel = tk.Frame(outer, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        settings_panel.pack(fill="x", pady=(11, 0))
        tk.Label(settings_panel, text="连接与工具", bg=PANEL, fg=TEXT, font=("Microsoft YaHei UI", 10, "bold")).grid(
            row=0, column=0, columnspan=8, sticky="w", padx=16, pady=(12, 8)
        )
        self._entry_field(settings_panel, 1, 0, "手机 UDP 端口", self.listen_port)
        self._entry_field(settings_panel, 1, 2, "VRChat OSC 端口", self.osc_port)
        ttk.Checkbutton(
            settings_panel, text="发送到 VRChat OSC", variable=self.forward_osc, command=self._update_osc_label,
        ).grid(row=1, column=4, columnspan=2, sticky="w", padx=12, pady=(0, 12))
        ttk.Checkbutton(
            settings_panel, text="记录 CSV 数据", variable=self.csv_recording, command=self._toggle_csv_recording,
        ).grid(row=1, column=6, columnspan=2, sticky="w", padx=(8, 16), pady=(0, 12))

        self.start_button = ttk.Button(settings_panel, text="启动接收", style="Primary.TButton", command=self.start_receiver)
        self.start_button.grid(row=2, column=0, sticky="ew", padx=(16, 4), pady=(0, 13))
        self.stop_button = ttk.Button(
            settings_panel, text="停止", style="Secondary.TButton", command=self.stop_receiver, state="disabled",
        )
        self.stop_button.grid(row=2, column=1, sticky="ew", padx=4, pady=(0, 13))
        ttk.Button(
            settings_panel, text="Avatar 参数测试", style="Secondary.TButton", command=self.send_avatar_test,
        ).grid(row=2, column=2, columnspan=2, sticky="ew", padx=4, pady=(0, 13))
        ttk.Button(
            settings_panel, text="显示配对二维码", style="Secondary.TButton", command=self.show_pairing_qr,
        ).grid(row=2, column=4, columnspan=2, sticky="ew", padx=4, pady=(0, 13))
        ttk.Button(
            settings_panel, text="一键诊断", style="Secondary.TButton", command=self.run_diagnostics,
        ).grid(row=2, column=6, sticky="ew", padx=4, pady=(0, 13))
        ttk.Button(
            settings_panel, text="导出 CSV…", style="Secondary.TButton", command=self.export_csv,
        ).grid(row=2, column=7, sticky="ew", padx=(4, 16), pady=(0, 13))
        for column in range(8):
            settings_panel.grid_columnconfigure(column, weight=1)

        log_panel = tk.Frame(outer, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        log_panel.pack(fill="both", expand=True, pady=(11, 0))
        tk.Label(log_panel, text="运行记录", bg=PANEL, fg=TEXT, font=("Microsoft YaHei UI", 10, "bold")).pack(
            anchor="w", padx=16, pady=(10, 6)
        )
        self.log = tk.Text(
            log_panel, height=6, wrap="word", relief="flat", borderwidth=0,
            bg="#f7f7f5", fg="#45484c", insertbackground=TEXT,
            font=("Cascadia Mono", 8), padx=12, pady=8, state="disabled",
        )
        self.log.pack(fill="both", expand=True, padx=16, pady=(0, 13))

    def _stat_value(self, parent: tk.Widget, label: str, value: tk.StringVar, column: int) -> None:
        block = tk.Frame(parent, bg="#fafaf8")
        block.grid(row=0, column=column, padx=(0 if column == 0 else 16, 0))
        tk.Label(block, text=label, bg="#fafaf8", fg=MUTED, font=("Microsoft YaHei UI", 8)).pack()
        tk.Label(block, textvariable=value, bg="#fafaf8", fg=TEXT, font=("Segoe UI", 17, "bold")).pack()

    def _status_row(self, parent: tk.Widget, label: str, value: tk.StringVar) -> None:
        row = tk.Frame(parent, bg="#fafaf8")
        row.pack(fill="x", padx=18, pady=2)
        tk.Label(row, text=label, bg="#fafaf8", fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(side="left")
        tk.Label(row, textvariable=value, bg="#fafaf8", fg=TEXT, font=("Microsoft YaHei UI", 8, "bold")).pack(side="right")

    def _entry_field(self, parent: tk.Widget, row: int, column: int, label: str, variable: tk.StringVar) -> None:
        field = tk.Frame(parent, bg=PANEL)
        field.grid(row=row, column=column, columnspan=2, sticky="ew", padx=(16 if column == 0 else 8, 4), pady=(0, 10))
        tk.Label(field, text=label, bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(anchor="w")
        entry = ttk.Entry(field, textvariable=variable, width=12)
        entry.pack(fill="x", pady=(3, 0))
        if variable is self.listen_port:
            self.listen_port_entry = entry
        elif variable is self.osc_port:
            self.osc_port_entry = entry

    def start_receiver(self) -> None:
        if self.runtime is not None and self.runtime.running:
            return
        try:
            listen_port = parse_port(self.listen_port.get())
            osc_port = parse_port(self.osc_port.get())
            settings = AppSettings(listen_port, osc_port, self.forward_osc.get())
            save_settings(settings)
            self.settings = settings
            self.runtime = BridgeRuntime(
                RuntimeConfig(listen_port=listen_port, osc_port=osc_port, forward_osc=self.forward_osc.get()),
                self._enqueue_event,
            )
            self.runtime.start()
            self.start_button.configure(state="disabled")
            self.stop_button.configure(state="normal")
            self.listen_port_entry.configure(state="disabled")
            self.osc_port_entry.configure(state="disabled")
            self._append_log(f"开始监听 UDP 0.0.0.0:{listen_port}")
        except (ValueError, OSError) as exc:
            if self.runtime is not None:
                self.runtime.stop()
                self.runtime = None
            self.receiver_text.set("启动失败")
            self.signal_text.set("无法启动接收器")
            self.signal_label.configure(fg=ACCENT)
            self._append_log(f"启动失败：{exc}")

    def stop_receiver(self) -> None:
        runtime, self.runtime = self.runtime, None
        if runtime is not None:
            runtime.stop()
        self.start_button.configure(state="normal")
        self.stop_button.configure(state="disabled")
        self.listen_port_entry.configure(state="normal")
        self.osc_port_entry.configure(state="normal")
        self.receiver_text.set("已停止")
        self.signal_text.set("接收器已停止")
        self.signal_label.configure(fg=MUTED)

    def send_avatar_test(self) -> None:
        runtime = self.runtime
        if runtime is None or not runtime.running:
            messagebox.showwarning("Avatar 参数测试", "请先启动 UDP 接收器。")
            return
        if not self.forward_osc.get():
            messagebox.showwarning("Avatar 参数测试", "请先开启“发送到 VRChat OSC”。")
            return
        if runtime.send_avatar_test(123):
            self._append_log("已发送 Avatar 参数测试：123 BPM + HRValid + HRPulse")
            self.signal_text.set("Avatar 测试已发送")
            self.signal_label.configure(fg=GOOD)

    def show_pairing_qr(self) -> None:
        addresses = local_ipv4_addresses()
        if not addresses or addresses == ["--"]:
            messagebox.showerror("配对二维码", "没有找到可用的本机局域网 IPv4。")
            return
        try:
            port = parse_port(self.listen_port.get())
            uri = build_pairing_uri(addresses[0], port)
            import qrcode
            from PIL import ImageTk

            image = qrcode.make(uri).resize((300, 300))
            self._qr_photo = ImageTk.PhotoImage(image)
        except ImportError:
            messagebox.showerror("配对二维码", "二维码组件未安装，请重新安装或使用最新版 EXE。")
            return
        except ValueError as exc:
            messagebox.showerror("配对二维码", str(exc))
            return

        popup = tk.Toplevel(self.root)
        popup.title("手机扫码配对")
        popup.configure(bg=PANEL)
        popup.resizable(False, False)
        tk.Label(popup, image=self._qr_photo, bg=PANEL).pack(padx=24, pady=(22, 8))
        tk.Label(popup, text=f"{addresses[0]}:{port}", bg=PANEL, fg=TEXT, font=("Segoe UI", 13, "bold")).pack()
        tk.Label(
            popup, text="在手机端点击“扫码配对”，识别后会自动保存电脑地址。",
            bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 9),
        ).pack(padx=24, pady=(7, 20))

    def run_diagnostics(self) -> None:
        runtime = self.runtime
        addresses = local_ipv4_addresses()
        checks = [
            ("本机 IPv4", addresses != ["--"], ", ".join(addresses)),
            ("UDP 接收器", runtime is not None and runtime.running, self.receiver_text.get()),
            ("VRChat OSC", self.forward_osc.get(), f"127.0.0.1:{self.osc_port.get()}"),
            ("手机数据", self.packet_count > 0, self.phone_text.get()),
        ]
        lines = [f"{'✓' if passed else '✗'} {name}：{detail}" for name, passed, detail in checks]
        if all(passed for _, passed, _ in checks):
            result = "电脑端链路状态正常。"
        elif runtime is not None and runtime.running:
            result = "接收器正常；未通过项可能只是手机尚未发送或 OSC 被关闭。"
        else:
            result = "请先启动接收器，再让手机运行“一键诊断”。"
        self._append_log("电脑诊断：" + "；".join(lines))
        messagebox.showinfo("一键诊断", result + "\n\n" + "\n".join(lines))

    def _toggle_csv_recording(self) -> None:
        if self.csv_recording.get():
            self.csv_text.set(f"CSV 记录中 · 已缓存 {len(self.csv_samples)} 条")
            self._append_log("CSV 记录已开启；仅缓存到内存，不会自动创建文件")
        else:
            self.csv_text.set(f"CSV 已暂停 · 已缓存 {len(self.csv_samples)} 条")
            self._append_log("CSV 记录已暂停；需要时请手动点击“导出 CSV”")

    def export_csv(self) -> None:
        if not self.csv_samples:
            messagebox.showinfo("导出 CSV", "还没有记录的数据。请先开启“记录 CSV 数据”。")
            return
        filename = filedialog.asksaveasfilename(
            title="导出心率 CSV",
            defaultextension=".csv",
            initialfile=f"heart-rate-{datetime.now():%Y%m%d-%H%M%S}.csv",
            filetypes=[("CSV 文件", "*.csv")],
        )
        if not filename:
            return
        try:
            with open(filename, "w", newline="", encoding="utf-8-sig") as handle:
                writer = csv.writer(handle)
                writer.writerow(["timestamp", "epoch_ms", "bpm", "phone_ip", "latency_ms"])
                for sample in self.csv_samples:
                    writer.writerow([
                        datetime.fromtimestamp(sample.epoch_ms / 1_000).isoformat(timespec="milliseconds"),
                        sample.epoch_ms,
                        sample.bpm,
                        sample.sender,
                        sample.latency_ms,
                    ])
            self.csv_exported_count = len(self.csv_samples)
            self._append_log(f"CSV 已手动导出：{filename}")
            messagebox.showinfo("导出 CSV", f"已导出 {len(self.csv_samples)} 条数据。")
        except OSError as exc:
            messagebox.showerror("导出 CSV", f"写入失败：{exc}")

    def check_for_updates(self) -> None:
        self.update_text.set("正在检查 GitHub…")

        def worker() -> None:
            try:
                release = fetch_latest_release()
                self._enqueue_event("update_result", release)
            except Exception as exc:
                self._enqueue_event("update_error", {"message": str(exc)})

        threading.Thread(target=worker, name="github-update-check", daemon=True).start()

    def _open_or_check_update(self) -> None:
        if self.latest_release_url:
            webbrowser.open(self.latest_release_url)
        else:
            self.check_for_updates()

    def _enqueue_event(self, kind: str, data: dict[str, Any]) -> None:
        self.events.put((kind, data))

    def _poll_events(self) -> None:
        try:
            while True:
                kind, data = self.events.get_nowait()
                self._handle_event(kind, data)
        except queue.Empty:
            pass
        self.root.after(50, self._poll_events)

    def _handle_event(self, kind: str, data: dict[str, Any]) -> None:
        if kind == "listening":
            self.receiver_text.set(f"监听 {data['port']}")
            return
        if kind == "packet":
            packet = data["packet"]
            self.packet_count += 1
            self.phone_text.set(data["sender"])
            latency = int(data["latency_ms"])
            if packet.is_real_heart_rate:
                now_ms = int(time.time() * 1_000)
                sample = HeartRateSample(now_ms, packet.bpm, data["sender"], latency)
                self.history.add(sample)
                if self.csv_recording.get():
                    self.csv_samples.append(sample)
                    self.csv_text.set(f"CSV 记录中 · 已缓存 {len(self.csv_samples)} 条")
                self.bpm_text.set(str(packet.bpm))
                self.signal_text.set("数据正常")
                self.signal_label.configure(fg=GOOD)
                self._update_stats()
                self._draw_chart()
            else:
                self.signal_text.set("手机 → 电脑诊断通过")
                self.signal_label.configure(fg=GOOD)
            self.detail_text.set(f"数据包 {self.packet_count}   ·   端到端 {latency} ms")
            self._append_log(
                f"{packet.packet_type}  seq={packet.sequence}  bpm={packet.bpm}  "
                f"phone={data['sender']}  latency={latency}ms  ack=ok"
            )
            return
        if kind == "stale":
            self.bpm_text.set("--")
            self.signal_text.set("心率信号超时")
            self.signal_label.configure(fg=WARN)
            self._append_log("真实心率超时，已发送 HRValid=false")
            return
        if kind == "update_result":
            tag = str(data.get("tag", ""))
            if is_newer_version(tag, __version__):
                self.latest_release_url = str(data.get("url", ""))
                self.update_text.set(f"发现新版本 {tag} · 点击打开")
                self._append_log(f"GitHub 有新版本：{tag}")
            else:
                self.latest_release_url = ""
                self.update_text.set(f"已是最新版 {__version__}")
            return
        if kind == "update_error":
            self.update_text.set("更新检查失败 · 点击重试")
            self._append_log("GitHub 更新检查失败：" + data.get("message", "未知错误"))
            return
        if kind in {"invalid_packet", "error"}:
            self._append_log(data.get("message", kind))
            return
        if kind == "stopped":
            self.receiver_text.set("已停止")

    def _refresh_chart(self) -> None:
        self.history.prune()
        self._update_stats()
        self._draw_chart()
        self.root.after(1_000, self._refresh_chart)

    def _update_stats(self) -> None:
        stats = self.history.stats()
        if stats is None:
            self.minimum_text.set("--")
            self.maximum_text.set("--")
            self.average_text.set("--")
            return
        self.minimum_text.set(str(stats.minimum))
        self.maximum_text.set(str(stats.maximum))
        self.average_text.set(f"{stats.average:.1f}")

    def _draw_chart(self) -> None:
        canvas = self.chart
        canvas.delete("all")
        width = max(canvas.winfo_width(), 200)
        height = max(canvas.winfo_height(), 100)
        left, top, right, bottom = 42, 12, width - 12, height - 24
        for index in range(5):
            y = top + (bottom - top) * index / 4
            canvas.create_line(left, y, right, y, fill=CHART_GRID)
        canvas.create_text(left, bottom + 13, text="-10 分钟", anchor="w", fill=MUTED, font=("Microsoft YaHei UI", 7))
        canvas.create_text(right, bottom + 13, text="现在", anchor="e", fill=MUTED, font=("Microsoft YaHei UI", 7))
        samples = self.history.samples()
        if not samples:
            canvas.create_text(width / 2, height / 2, text="等待真实心率数据", fill=MUTED, font=("Microsoft YaHei UI", 10))
            return
        values = [item.bpm for item in samples]
        low = max(30, min(values) - 10)
        high = min(240, max(values) + 10)
        if high - low < 20:
            high = low + 20
        now_ms = int(time.time() * 1_000)
        start_ms = now_ms - self.history.window_ms
        points: list[float] = []
        for sample in samples:
            x = left + (sample.epoch_ms - start_ms) / self.history.window_ms * (right - left)
            y = bottom - (sample.bpm - low) / (high - low) * (bottom - top)
            points.extend((x, y))
        canvas.create_text(left - 5, top, text=str(high), anchor="e", fill=MUTED, font=("Segoe UI", 7))
        canvas.create_text(left - 5, bottom, text=str(low), anchor="e", fill=MUTED, font=("Segoe UI", 7))
        if len(points) >= 4:
            canvas.create_line(*points, fill=ACCENT, width=2, smooth=True)
        else:
            x, y = points
            canvas.create_oval(x - 3, y - 3, x + 3, y + 3, fill=ACCENT, outline="")

    def _update_osc_label(self) -> None:
        self.osc_text.set("已开启" if self.forward_osc.get() else "已关闭")
        if self.runtime is not None:
            self.runtime.set_forward_osc(self.forward_osc.get())
        self.settings.forward_osc = self.forward_osc.get()
        try:
            save_settings(self.settings)
        except OSError as exc:
            self._append_log(f"保存设置失败：{exc}")

    def _append_log(self, line: str) -> None:
        self.log.configure(state="normal")
        self.log.insert("end", f"{datetime.now():%H:%M:%S}  {line}\n")
        lines = int(self.log.index("end-1c").split(".")[0])
        if lines > 220:
            self.log.delete("1.0", f"{lines - 180}.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def close(self) -> None:
        if len(self.csv_samples) > self.csv_exported_count:
            should_close = messagebox.askyesno(
                "尚有未导出的 CSV 数据",
                f"还有 {len(self.csv_samples) - self.csv_exported_count} 条记录未导出。\n"
                "程序不会自动创建文件，确定直接退出吗？",
            )
            if not should_close:
                return
        runtime, self.runtime = self.runtime, None
        if runtime is not None:
            runtime.stop()
        self.root.destroy()


def parse_port(value: str) -> int:
    try:
        port = int(value)
    except ValueError as exc:
        raise ValueError("端口必须是整数") from exc
    if not 1 <= port <= 65_535:
        raise ValueError("端口必须在 1–65535 之间")
    return port


def local_ipv4_addresses() -> list[str]:
    addresses: set[str] = set()
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = item[4][0]
            if not address.startswith("127."):
                addresses.add(address)
    except OSError:
        pass
    return sorted(addresses) or ["--"]


def _resource_path(name: str) -> str:
    from pathlib import Path

    frozen_root = getattr(sys, "_MEIPASS", None)
    if frozen_root:
        return str(Path(frozen_root) / name)
    return str(Path(__file__).resolve().parents[2] / "pc-bridge" / "assets" / name)


def main() -> None:
    enable_dpi_awareness()
    root = tk.Tk()
    HeartRateBridgeApp(root)
    root.mainloop()
