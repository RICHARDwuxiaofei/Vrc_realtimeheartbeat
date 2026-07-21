from __future__ import annotations

import ctypes
import queue
import socket
import sys
import tkinter as tk
from tkinter import ttk
from typing import Any

from . import __version__
from .runtime import BridgeRuntime, RuntimeConfig
from .settings import AppSettings, load_settings, save_settings


BG = "#f3f3f1"
PANEL = "#ffffff"
TEXT = "#202124"
MUTED = "#6f7378"
BORDER = "#d8d9d6"
ACCENT = "#c6404d"
ACCENT_DARK = "#aa3340"
GOOD = "#31845b"
WARN = "#b07722"


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

        self.listen_port = tk.StringVar(value=str(self.settings.listen_port))
        self.osc_port = tk.StringVar(value=str(self.settings.osc_port))
        self.forward_osc = tk.BooleanVar(value=self.settings.forward_osc)
        self.bpm_text = tk.StringVar(value="--")
        self.signal_text = tk.StringVar(value="等待手机数据")
        self.detail_text = tk.StringVar(value="尚未收到数据包")
        self.receiver_text = tk.StringVar(value="未启动")
        self.phone_text = tk.StringVar(value="--")
        self.osc_text = tk.StringVar(value="已开启" if self.settings.forward_osc else "已关闭")

        self._configure_window()
        self._configure_styles()
        self._build_ui()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(50, self._poll_events)
        self.root.after(250, self.start_receiver)

    def _configure_window(self) -> None:
        self.root.title("VRChat 心率桥 · Python")
        self.root.geometry("800x720")
        self.root.minsize(740, 660)
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
            padding=(18, 8),
            font=("Microsoft YaHei UI", 9, "bold"),
        )
        style.map("Primary.TButton", background=[("active", ACCENT_DARK), ("disabled", "#d2a7ac")])
        style.configure(
            "Secondary.TButton",
            background="#ececea",
            foreground=TEXT,
            bordercolor=BORDER,
            padding=(18, 8),
            font=("Microsoft YaHei UI", 9),
        )
        style.map("Secondary.TButton", background=[("active", "#dfdfdc")])

    def _build_ui(self) -> None:
        outer = tk.Frame(self.root, bg=BG)
        outer.pack(fill="both", expand=True, padx=24, pady=20)

        header = tk.Frame(outer, bg=BG)
        header.pack(fill="x", pady=(0, 14))
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
        tk.Label(
            header,
            text="本机 IPv4  " + ", ".join(local_ipv4_addresses()),
            bg=BG,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(side="right", anchor="e", pady=(6, 0))

        summary = tk.Frame(outer, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        summary.pack(fill="x")
        bpm_column = tk.Frame(summary, bg=PANEL, width=280)
        bpm_column.pack(side="left", fill="both", expand=True, padx=22, pady=18)
        tk.Label(bpm_column, text="当前心率", bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 9)).pack(anchor="w")
        bpm_line = tk.Frame(bpm_column, bg=PANEL)
        bpm_line.pack(anchor="w", pady=(3, 1))
        tk.Label(
            bpm_line,
            textvariable=self.bpm_text,
            bg=PANEL,
            fg=TEXT,
            font=("Segoe UI", 38, "bold"),
        ).pack(side="left")
        tk.Label(bpm_line, text=" BPM", bg=PANEL, fg=MUTED, font=("Segoe UI", 14, "bold")).pack(
            side="left", anchor="s", pady=(0, 7)
        )
        self.signal_label = tk.Label(
            bpm_column,
            textvariable=self.signal_text,
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 9, "bold"),
        )
        self.signal_label.pack(anchor="w", pady=(2, 0))
        tk.Label(
            bpm_column,
            textvariable=self.detail_text,
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="w", pady=(4, 0))

        status_column = tk.Frame(summary, bg="#fafaf8", width=290)
        status_column.pack(side="right", fill="y", padx=(0, 1), pady=1)
        status_column.pack_propagate(False)
        tk.Label(
            status_column,
            text="连接状态",
            bg="#fafaf8",
            fg=TEXT,
            font=("Microsoft YaHei UI", 10, "bold"),
        ).pack(anchor="w", padx=20, pady=(17, 9))
        self._status_row(status_column, "UDP 接收器", self.receiver_text)
        self._status_row(status_column, "手机", self.phone_text)
        self._status_row(status_column, "VRChat OSC", self.osc_text)

        settings_panel = tk.Frame(outer, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        settings_panel.pack(fill="x", pady=(14, 0))
        tk.Label(
            settings_panel,
            text="连接设置",
            bg=PANEL,
            fg=TEXT,
            font=("Microsoft YaHei UI", 10, "bold"),
        ).grid(row=0, column=0, columnspan=6, sticky="w", padx=18, pady=(14, 10))
        self._entry_field(settings_panel, 1, 0, "手机 UDP 端口", self.listen_port)
        self._entry_field(settings_panel, 1, 2, "VRChat OSC 端口", self.osc_port)
        check = ttk.Checkbutton(
            settings_panel,
            text="发送到 VRChat OSC",
            variable=self.forward_osc,
            command=self._update_osc_label,
        )
        check.grid(row=1, column=4, sticky="w", padx=(18, 8), pady=(0, 16))
        self.start_button = ttk.Button(
            settings_panel,
            text="启动接收",
            style="Primary.TButton",
            command=self.start_receiver,
        )
        self.start_button.grid(row=2, column=0, columnspan=2, sticky="ew", padx=(18, 6), pady=(0, 16))
        self.stop_button = ttk.Button(
            settings_panel,
            text="停止",
            style="Secondary.TButton",
            command=self.stop_receiver,
            state="disabled",
        )
        self.stop_button.grid(row=2, column=2, columnspan=2, sticky="ew", padx=6, pady=(0, 16))
        tk.Label(
            settings_panel,
            text="真实数据超时后自动发送 HRValid=false",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).grid(row=2, column=4, columnspan=2, sticky="e", padx=(12, 18), pady=(0, 16))
        for column in range(6):
            settings_panel.grid_columnconfigure(column, weight=1)

        log_panel = tk.Frame(outer, bg=PANEL, highlightbackground=BORDER, highlightthickness=1)
        log_panel.pack(fill="both", expand=True, pady=(14, 0))
        tk.Label(
            log_panel,
            text="运行记录",
            bg=PANEL,
            fg=TEXT,
            font=("Microsoft YaHei UI", 10, "bold"),
        ).pack(anchor="w", padx=18, pady=(13, 8))
        self.log = tk.Text(
            log_panel,
            height=9,
            wrap="word",
            relief="flat",
            borderwidth=0,
            bg="#f7f7f5",
            fg="#45484c",
            insertbackground=TEXT,
            font=("Cascadia Mono", 8),
            padx=12,
            pady=10,
            state="disabled",
        )
        self.log.pack(fill="both", expand=True, padx=18, pady=(0, 16))

    def _status_row(self, parent: tk.Widget, label: str, value: tk.StringVar) -> None:
        row = tk.Frame(parent, bg="#fafaf8")
        row.pack(fill="x", padx=20, pady=3)
        tk.Label(row, text=label, bg="#fafaf8", fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(side="left")
        tk.Label(row, textvariable=value, bg="#fafaf8", fg=TEXT, font=("Microsoft YaHei UI", 8, "bold")).pack(
            side="right"
        )

    def _entry_field(self, parent: tk.Widget, row: int, column: int, label: str, variable: tk.StringVar) -> None:
        field = tk.Frame(parent, bg=PANEL)
        field.grid(row=row, column=column, columnspan=2, sticky="ew", padx=(18, 6), pady=(0, 14))
        tk.Label(field, text=label, bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(anchor="w")
        entry = ttk.Entry(field, textvariable=variable, width=12)
        entry.pack(fill="x", pady=(4, 0))
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
                RuntimeConfig(
                    listen_port=listen_port,
                    osc_port=osc_port,
                    forward_osc=self.forward_osc.get(),
                ),
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
                self.bpm_text.set(str(packet.bpm))
                self.signal_text.set("数据正常")
                self.signal_label.configure(fg=GOOD)
            else:
                self.signal_text.set("链路测试通过")
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
        if kind in {"invalid_packet", "error"}:
            self._append_log(data.get("message", kind))
            return
        if kind == "stopped":
            self.receiver_text.set("已停止")

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
        from datetime import datetime

        self.log.configure(state="normal")
        self.log.insert("end", f"{datetime.now():%H:%M:%S}  {line}\n")
        lines = int(self.log.index("end-1c").split(".")[0])
        if lines > 220:
            self.log.delete("1.0", f"{lines - 180}.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def close(self) -> None:
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
