from __future__ import annotations

import ctypes
from datetime import datetime
import os
import queue
import sys
import threading
import time
import tkinter as tk
from tkinter import filedialog, messagebox, ttk
import traceback
from typing import Any
import webbrowser

from . import __version__
from .analytics import HeartRateSample
from .app_logging import AppFileLogger
from .ble_direct import BleHeartRateClient
from .diagnostic_csv import DiagnosticCsvStore
from .input_sources import INPUT_SOURCE_LABELS, PHONE_RELAY, XIAOMI_PC_BLE
from .i18n import LANGUAGES, Translator
from .network import local_ipv4_addresses as discover_local_ipv4_addresses
from .pairing import build_pairing_uri
from .runtime import BridgeRuntime, RuntimeConfig
from .settings import (
    DEFAULT_RELAY_INTERVAL_SECONDS,
    RELAY_INTERVAL_OPTIONS,
    AppSettings,
    load_settings,
    normalize_relay_interval,
    save_settings,
)
from .updates import fetch_latest_release, is_newer_version


BG = "#0b0b0f"
PANEL = "#121216"
PANEL_ELEVATED = "#1b1b20"
FIELD = "#242329"
TEXT = "#e6e1e5"
MUTED = "#cac4d0"
BORDER = "#49454f"
PRIMARY = "#d0bcff"
PRIMARY_DARK = "#b69df8"
ACCENT = "#ffb4ab"
ACCENT_DARK = "#e99b93"
GOOD = "#8bd5a3"
WARN = "#ffc56d"
CHART_GRID = "#302d35"
CARD_RADIUS = 22
CONTROL_RADIUS = 14
RELAY_INTERVAL_LABELS = {
    1: "1 秒（实时）",
    5: "5 秒（省电）",
    10: "10 秒（超省电）",
}


class LocalizedStringVar(tk.StringVar):
    def __init__(self, master: tk.Misc, translate: Any, value: object = "") -> None:
        self._translate = translate
        super().__init__(master=master, value=self._translate(value))

    def set(self, value: object) -> None:
        super().set(self._translate(value))


def _draw_rounded_rectangle(
    canvas: tk.Canvas,
    x1: float,
    y1: float,
    x2: float,
    y2: float,
    radius: float,
    **options: Any,
) -> int:
    radius = max(0.0, min(radius, (x2 - x1) / 2, (y2 - y1) / 2))
    points = (
        x1 + radius, y1,
        x2 - radius, y1,
        x2, y1,
        x2, y1 + radius,
        x2, y2 - radius,
        x2, y2,
        x2 - radius, y2,
        x1 + radius, y2,
        x1, y2,
        x1, y2 - radius,
        x1, y1 + radius,
        x1, y1,
    )
    return canvas.create_polygon(points, smooth=True, splinesteps=36, **options)


class RoundedCard(tk.Canvas):
    def __init__(
        self,
        parent: tk.Widget,
        *,
        fill: str = PANEL,
        radius: int = CARD_RADIUS,
        inset: int = 10,
    ) -> None:
        parent_bg = str(parent.cget("bg"))
        super().__init__(
            parent,
            bg=parent_bg,
            highlightthickness=0,
            borderwidth=0,
            height=80,
        )
        self.fill = fill
        self.radius = radius
        self.inset = inset
        self.body = tk.Frame(self, bg=fill)
        self._body_window = self.create_window((inset, inset), window=self.body, anchor="nw")
        super().bind("<Configure>", self._on_resize)
        self.body.bind("<Configure>", self._on_body_resize, add="+")

    def _on_resize(self, event: tk.Event) -> None:
        width = max(1, int(event.width))
        height = max(1, int(event.height))
        self.delete("rounded-background")
        _draw_rounded_rectangle(
            self,
            1,
            1,
            width - 1,
            height - 1,
            self.radius,
            fill=self.fill,
            outline="",
            tags="rounded-background",
        )
        self.tag_lower("rounded-background")
        self.itemconfigure(self._body_window, width=max(1, width - self.inset * 2))

    def _on_body_resize(self, _event: tk.Event) -> None:
        requested = self.body.winfo_reqheight() + self.inset * 2
        if requested > 0 and int(float(self.cget("height"))) != requested:
            tk.Canvas.configure(self, height=requested)


class RoundedButton(tk.Canvas):
    def __init__(
        self,
        parent: tk.Widget,
        *,
        text: str = "",
        textvariable: tk.StringVar | None = None,
        command: Any = None,
        variant: str = "secondary",
        state: str = "normal",
        width: int = 144,
        height: int = 44,
    ) -> None:
        parent_bg = str(parent.cget("bg"))
        super().__init__(
            parent,
            bg=parent_bg,
            highlightthickness=0,
            borderwidth=0,
            width=width,
            height=height,
            takefocus=1,
        )
        self._text = text
        self._textvariable = textvariable
        self._command = command
        self._variant = variant
        self._state = state
        self._hovered = False
        if textvariable is not None:
            textvariable.trace_add("write", lambda *_args: self._draw())
        super().bind("<Configure>", lambda _event: self._draw())
        super().bind("<Enter>", self._enter)
        super().bind("<Leave>", self._leave)
        super().bind("<Button-1>", self._click)
        super().bind("<Return>", self._click)
        super().bind("<space>", self._click)
        self._draw()

    def configure(self, cnf: Any = None, **kwargs: Any) -> Any:
        if cnf:
            kwargs.update(cnf)
        handled = False
        for name in ("state", "text", "command"):
            if name in kwargs:
                value = kwargs.pop(name)
                setattr(self, f"_{name}", value)
                handled = True
        result = tk.Canvas.configure(self, **kwargs) if kwargs else None
        if handled:
            self._draw()
        return result

    config = configure

    def _enter(self, _event: tk.Event) -> None:
        self._hovered = True
        self._draw()

    def _leave(self, _event: tk.Event) -> None:
        self._hovered = False
        self._draw()

    def _click(self, _event: tk.Event) -> str:
        if self._state != "disabled" and self._command is not None:
            self._command()
        return "break"

    def _draw(self) -> None:
        if not self.winfo_exists():
            return
        width = max(self.winfo_width(), int(float(self.cget("width"))))
        height = max(self.winfo_height(), int(float(self.cget("height"))))
        disabled = self._state == "disabled"
        if self._variant == "primary":
            fill = "#5d5667" if disabled else PRIMARY_DARK if self._hovered else PRIMARY
            foreground = "#a49daa" if disabled else "#281b3e"
        else:
            fill = "#18171b" if disabled else "#34313a" if self._hovered else FIELD
            foreground = "#68636d" if disabled else TEXT
        self.delete("all")
        _draw_rounded_rectangle(
            self,
            1,
            1,
            width - 1,
            height - 1,
            CONTROL_RADIUS,
            fill=fill,
            outline="",
        )
        label = self._textvariable.get() if self._textvariable is not None else self._text
        self.create_text(
            width / 2,
            height / 2,
            text=label,
            fill=foreground,
            font=("Microsoft YaHei UI", 9, "bold" if self._variant == "primary" else "normal"),
        )
        self.configure(cursor="arrow" if disabled else "hand2")


class RoundedEntry(tk.Canvas):
    def __init__(self, parent: tk.Widget, *, textvariable: tk.StringVar, width: int = 132) -> None:
        parent_bg = str(parent.cget("bg"))
        super().__init__(
            parent,
            bg=parent_bg,
            highlightthickness=0,
            borderwidth=0,
            width=width,
            height=44,
        )
        self._focused = False
        self.entry = tk.Entry(
            self,
            textvariable=textvariable,
            relief="flat",
            borderwidth=0,
            bg=FIELD,
            fg=TEXT,
            insertbackground=TEXT,
            disabledbackground=FIELD,
            disabledforeground="#68636d",
            selectbackground=PRIMARY_DARK,
            selectforeground="#281b3e",
            font=("Microsoft YaHei UI", 9),
        )
        self._window = self.create_window(12, 22, window=self.entry, anchor="w")
        super().bind("<Configure>", self._on_resize)
        self.entry.bind("<FocusIn>", self._focus_in)
        self.entry.bind("<FocusOut>", self._focus_out)

    def configure(self, cnf: Any = None, **kwargs: Any) -> Any:
        if cnf:
            kwargs.update(cnf)
        state = kwargs.pop("state", None)
        if state is not None:
            self.entry.configure(state=state)
        return tk.Canvas.configure(self, **kwargs) if kwargs else None

    config = configure

    def _focus_in(self, _event: tk.Event) -> None:
        self._focused = True
        self._draw_border()

    def _focus_out(self, _event: tk.Event) -> None:
        self._focused = False
        self._draw_border()

    def _on_resize(self, event: tk.Event) -> None:
        self.coords(self._window, 12, event.height / 2)
        self.itemconfigure(self._window, width=max(24, event.width - 24), height=25)
        self._draw_border()

    def _draw_border(self) -> None:
        width = max(1, self.winfo_width())
        height = max(1, self.winfo_height())
        self.delete("rounded-field")
        _draw_rounded_rectangle(
            self,
            1,
            1,
            width - 1,
            height - 1,
            CONTROL_RADIUS,
            fill=FIELD,
            outline=PRIMARY if self._focused else BORDER,
            width=1,
            tags="rounded-field",
        )
        self.tag_lower("rounded-field")


class RoundedCombobox(tk.Canvas):
    def __init__(
        self,
        parent: tk.Widget,
        *,
        textvariable: tk.StringVar,
        values: list[str] | tuple[str, ...] = (),
        state: str = "readonly",
        width: int = 240,
    ) -> None:
        parent_bg = str(parent.cget("bg"))
        super().__init__(
            parent,
            bg=parent_bg,
            highlightthickness=0,
            borderwidth=0,
            width=width,
            height=44,
        )
        self.combo = ttk.Combobox(
            self,
            textvariable=textvariable,
            values=values,
            state=state,
            style="Rounded.TCombobox",
        )
        self._window = self.create_window(10, 22, window=self.combo, anchor="w")
        super().bind("<Configure>", self._on_resize)
        self.combo.bind("<FocusIn>", lambda _event: self._draw_border(True))
        self.combo.bind("<FocusOut>", lambda _event: self._draw_border(False))

    def configure(self, cnf: Any = None, **kwargs: Any) -> Any:
        if cnf:
            kwargs.update(cnf)
        combo_options = {}
        for name in ("state", "values"):
            if name in kwargs:
                combo_options[name] = kwargs.pop(name)
        if combo_options:
            self.combo.configure(**combo_options)
        return tk.Canvas.configure(self, **kwargs) if kwargs else None

    config = configure

    def bind(self, sequence: str | None = None, func: Any = None, add: Any = None) -> Any:
        if sequence and sequence.startswith("<<"):
            return self.combo.bind(sequence, func, add)
        return tk.Canvas.bind(self, sequence, func, add)

    def _on_resize(self, event: tk.Event) -> None:
        self.coords(self._window, 10, event.height / 2)
        self.itemconfigure(self._window, width=max(24, event.width - 20), height=28)
        self._draw_border(False)

    def _draw_border(self, focused: bool) -> None:
        width = max(1, self.winfo_width())
        height = max(1, self.winfo_height())
        self.delete("rounded-field")
        _draw_rounded_rectangle(
            self,
            1,
            1,
            width - 1,
            height - 1,
            CONTROL_RADIUS,
            fill=FIELD,
            outline=PRIMARY if focused else BORDER,
            width=1,
            tags="rounded-field",
        )
        self.tag_lower("rounded-field")


class PillToggle(tk.Canvas):
    def __init__(
        self,
        parent: tk.Widget,
        *,
        text: str,
        variable: tk.BooleanVar,
        command: Any = None,
        width: int = 250,
    ) -> None:
        parent_bg = str(parent.cget("bg"))
        super().__init__(
            parent,
            bg=parent_bg,
            highlightthickness=0,
            borderwidth=0,
            width=width,
            height=42,
            cursor="hand2",
            takefocus=1,
        )
        self._text = text
        self._variable = variable
        self._command = command
        variable.trace_add("write", lambda *_args: self._draw())
        super().bind("<Configure>", lambda _event: self._draw())
        super().bind("<Button-1>", self._toggle)
        super().bind("<Return>", self._toggle)
        super().bind("<space>", self._toggle)
        self._draw()

    def _toggle(self, _event: tk.Event) -> str:
        self._variable.set(not self._variable.get())
        if self._command is not None:
            self._command()
        return "break"

    def _draw(self) -> None:
        width = max(self.winfo_width(), int(float(self.cget("width"))))
        enabled = self._variable.get()
        self.delete("all")
        _draw_rounded_rectangle(
            self,
            1,
            7,
            51,
            35,
            14,
            fill=PRIMARY if enabled else FIELD,
            outline=PRIMARY if enabled else BORDER,
            width=1,
        )
        knob_x = 37 if enabled else 15
        self.create_oval(
            knob_x - 9,
            12,
            knob_x + 9,
            30,
            fill="#281b3e" if enabled else MUTED,
            outline="",
        )
        self.create_text(
            64,
            21,
            text=self._text,
            anchor="w",
            fill=TEXT,
            font=("Microsoft YaHei UI", 9),
            width=max(40, width - 68),
        )


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
        self.translator = Translator(self.settings.language)
        self.tr = self.translator.text
        self.restart_requested = False
        self.input_source_labels = {
            key: self.tr(label)
            for key, label in INPUT_SOURCE_LABELS.items()
        }
        self.relay_interval_labels = {
            seconds: self.tr(RELAY_INTERVAL_LABELS[seconds])
            for seconds in RELAY_INTERVAL_OPTIONS
        }
        self.relay_interval_seconds_by_label = {
            label: seconds for seconds, label in self.relay_interval_labels.items()
        }
        self.language_options = self.translator.language_options()
        self.language_codes_by_label = {
            label: code
            for code, label in self.language_options.items()
        }
        self.runtime: BridgeRuntime | None = None
        self.events: queue.Queue[tuple[str, dict[str, Any]]] = queue.Queue()
        self.ble_client = BleHeartRateClient(self._enqueue_event)
        self.ble_devices: dict[str, tuple[str, str]] = {}
        self.ble_connected = False
        self.ble_scanning = False
        self.packet_count = 0
        self.diagnostic_csv = DiagnosticCsvStore()
        self.app_log = AppFileLogger()
        self.diagnostic_session_started = False
        self.latest_release_url = ""
        self.update_popup: tk.Toplevel | None = None
        self.update_popup_tag = ""
        self._qr_photo: Any = None

        self.listen_port = tk.StringVar(value=str(self.settings.listen_port))
        self.osc_port = tk.StringVar(value=str(self.settings.osc_port))
        self.forward_osc = tk.BooleanVar(value=self.settings.forward_osc)
        self.relay_interval_choice = tk.StringVar(
            value=self.relay_interval_labels.get(
                self.settings.relay_interval_seconds,
                self.relay_interval_labels[DEFAULT_RELAY_INTERVAL_SECONDS],
            )
        )
        self.input_source_label = tk.StringVar(value=self.input_source_labels[self.settings.input_source])
        self.language_choice = tk.StringVar(value=self.language_options[self.settings.language])
        saved_ble_label = (
            f"{self.settings.ble_name or self.tr('已保存设备')} · {self.settings.ble_address}"
            if self.settings.ble_address
            else ""
        )
        self.ble_device_choice = tk.StringVar(value=saved_ble_label)
        self.ble_status_text = self._localized_var("直连模式未启用")
        self.diagnostic_mode = tk.BooleanVar(value=False)
        self.diagnostic_toggle_text = self._localized_var("开始曲线记录")
        self.chart_minutes = tk.IntVar(value=1)
        self.bpm_text = tk.StringVar(value="--")
        self.signal_text = self._localized_var(
            value="等待手机数据" if self.settings.input_source == PHONE_RELAY else "等待小米手环 BLE"
        )
        self.detail_text = self._localized_var("尚未收到数据包")
        self.receiver_text = self._localized_var("未启动")
        self.phone_text = self._localized_var("--")
        self.osc_text = self._localized_var("已开启" if self.settings.forward_osc else "已关闭")
        self.minimum_text = tk.StringVar(value="--")
        self.maximum_text = tk.StringVar(value="--")
        self.average_text = tk.StringVar(value="--")
        self.csv_text = self._localized_var("点击右上角开始记录")
        self.chart_title = self._localized_var("最近 1 分钟心率曲线")
        self.update_text = self._localized_var("正在检查 GitHub…")
        self.log_path_text = self._localized_var(f"自动日志 · {self.app_log.path}")

        self._configure_window()
        self._configure_styles()
        self._build_ui()
        self._localize_widget_tree(self.root)
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.report_callback_exception = self._report_callback_exception
        self._append_log(f"应用启动 · v{__version__}")
        self._append_log(f"日志文件：{self.app_log.path}")
        self.root.after(50, self._poll_events)
        self.root.after(250, self.start_receiver)
        self.root.after(1_000, self._refresh_chart)
        self.root.after(1_200, self.check_for_updates)

    def _localized_var(self, value: object) -> LocalizedStringVar:
        return LocalizedStringVar(self.root, self.tr, value)

    def _configure_window(self) -> None:
        self.root.title(self.tr("VRChat 心率桥 · Python"))
        self.root.geometry("1120x820")
        self.root.minsize(900, 640)
        self.root.configure(background=BG)
        try:
            self.root.iconbitmap(_resource_path("heart-relay.ico"))
        except (tk.TclError, OSError):
            pass

    def _configure_styles(self) -> None:
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure(
            "TEntry",
            fieldbackground=FIELD,
            foreground=TEXT,
            insertcolor=TEXT,
            bordercolor=FIELD,
            lightcolor=FIELD,
            darkcolor=FIELD,
            padding=10,
        )
        style.map("TEntry", bordercolor=[("focus", PRIMARY)])
        style.configure(
            "Rounded.TCombobox",
            fieldbackground=FIELD,
            background=FIELD,
            foreground=TEXT,
            arrowcolor=MUTED,
            bordercolor=FIELD,
            lightcolor=FIELD,
            darkcolor=FIELD,
            borderwidth=0,
            relief="flat",
            padding=5,
        )
        style.map(
            "Rounded.TCombobox",
            fieldbackground=[("readonly", FIELD)],
            foreground=[("readonly", TEXT)],
            selectbackground=[("readonly", FIELD)],
            selectforeground=[("readonly", TEXT)],
            bordercolor=[("focus", PRIMARY)],
        )
        style.configure("TCheckbutton", background=PANEL, foreground=TEXT, font=("Microsoft YaHei UI", 9))
        style.map("TCheckbutton", background=[("active", PANEL)])
        style.configure(
            "Primary.TButton",
            background=PRIMARY,
            foreground="#281b3e",
            bordercolor=PRIMARY,
            lightcolor=PRIMARY,
            darkcolor=PRIMARY,
            padding=(18, 11),
            font=("Microsoft YaHei UI", 9, "bold"),
        )
        style.map(
            "Primary.TButton",
            background=[("active", PRIMARY_DARK), ("disabled", "#5d5667")],
            foreground=[("disabled", "#a49daa")],
        )
        style.configure(
            "Secondary.TButton",
            background=FIELD,
            foreground=TEXT,
            bordercolor=FIELD,
            lightcolor=FIELD,
            darkcolor=FIELD,
            padding=(16, 10),
            font=("Microsoft YaHei UI", 9),
        )
        style.map(
            "Secondary.TButton",
            background=[("active", "#34313a"), ("disabled", "#18171b")],
            foreground=[("disabled", "#68636d")],
        )
        style.configure(
            "Dark.Vertical.TScrollbar",
            background=FIELD,
            troughcolor=BG,
            bordercolor=BG,
            arrowcolor=MUTED,
            lightcolor=FIELD,
            darkcolor=FIELD,
        )

    def _build_ui(self) -> None:
        header = tk.Frame(self.root, bg=BG)
        header.pack(fill="x", padx=28, pady=(16, 10))
        brand_row = tk.Frame(header, bg=BG)
        brand_row.pack(fill="x")
        brand = tk.Canvas(
            brand_row,
            bg=BG,
            width=48,
            height=48,
            highlightthickness=0,
            borderwidth=0,
        )
        brand.pack(side="left")
        _draw_rounded_rectangle(
            brand,
            0,
            0,
            48,
            48,
            16,
            fill=PANEL_ELEVATED,
            outline="",
        )
        brand.create_text(24, 25, text="♥", fill=ACCENT, font=("Segoe UI Symbol", 23, "bold"))
        title_block = tk.Frame(brand_row, bg=BG)
        title_block.pack(side="left", padx=(13, 0))
        tk.Label(
            title_block,
            text="VRChat 心率桥",
            bg=BG,
            fg=TEXT,
            font=("Microsoft YaHei UI", 19, "bold"),
        ).pack(anchor="w")
        tk.Label(
            title_block,
            text=f"WATCH  ·  PHONE  ·  PC  ·  VRCHAT     v{__version__}",
            bg=BG,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8, "bold"),
        ).pack(anchor="w", pady=(3, 0))

        viewport = tk.Frame(self.root, bg=BG)
        viewport.pack(fill="both", expand=True)
        self.content_canvas = tk.Canvas(viewport, bg=BG, highlightthickness=0, borderwidth=0)
        page_scrollbar = ttk.Scrollbar(
            viewport,
            orient="vertical",
            command=self.content_canvas.yview,
            style="Dark.Vertical.TScrollbar",
        )
        self.content_canvas.configure(yscrollcommand=page_scrollbar.set)
        self.content_canvas.pack(side="left", fill="both", expand=True)
        page_scrollbar.pack(side="right", fill="y")
        outer = tk.Frame(self.content_canvas, bg=BG)
        self.content_window = self.content_canvas.create_window((0, 0), window=outer, anchor="nw")
        outer.bind("<Configure>", self._on_content_configure)
        self.content_canvas.bind("<Configure>", self._on_canvas_configure)
        self.root.bind_all("<MouseWheel>", self._on_mousewheel, add="+")
        self.root.bind_all("<Button-4>", self._on_mousewheel, add="+")
        self.root.bind_all("<Button-5>", self._on_mousewheel, add="+")

        content = tk.Frame(outer, bg=BG)
        content.pack(fill="both", expand=True, padx=28, pady=(0, 28))

        summary_card = RoundedCard(content, fill=PANEL_ELEVATED)
        summary_card.pack(fill="x")
        summary = summary_card.body
        tk.Frame(summary, bg=ACCENT, width=5).pack(side="left", fill="y")
        bpm_column = tk.Frame(summary, bg=PANEL_ELEVATED)
        bpm_column.pack(side="left", fill="both", expand=True, padx=24, pady=19)
        tk.Label(
            bpm_column,
            text="实时心率",
            bg=PANEL_ELEVATED,
            fg=MUTED,
            font=("Microsoft YaHei UI", 9, "bold"),
        ).pack(anchor="w")
        bpm_line = tk.Frame(bpm_column, bg=PANEL_ELEVATED)
        bpm_line.pack(anchor="w")
        tk.Label(
            bpm_line,
            textvariable=self.bpm_text,
            bg=PANEL_ELEVATED,
            fg=TEXT,
            font=("Segoe UI", 48, "bold"),
        ).pack(side="left")
        tk.Label(bpm_line, text=" BPM", bg=PANEL_ELEVATED, fg=ACCENT, font=("Segoe UI", 12, "bold")).pack(
            side="left", anchor="s", pady=(0, 10),
        )
        self.signal_label = tk.Label(
            bpm_column, textvariable=self.signal_text, bg=PANEL_ELEVATED, fg=MUTED,
            font=("Microsoft YaHei UI", 9, "bold"),
        )
        self.signal_label.pack(anchor="w")
        tk.Label(
            bpm_column, textvariable=self.detail_text, bg=PANEL_ELEVATED, fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="w", pady=(3, 0))

        self.stats_panel = tk.Frame(summary, bg=PANEL)
        self.stats_panel.pack(side="left", fill="y", padx=(0, 1), pady=1)
        tk.Label(
            self.stats_panel,
            text="区间统计",
            bg=PANEL,
            fg=TEXT,
            font=("Microsoft YaHei UI", 9, "bold"),
        ).pack(
            anchor="w", padx=20, pady=(18, 10),
        )
        stats_row = tk.Frame(self.stats_panel, bg=PANEL)
        stats_row.pack(padx=20, pady=(0, 18))
        self._stat_value(stats_row, "最低", self.minimum_text, 0)
        self._stat_value(stats_row, "最高", self.maximum_text, 1)
        self._stat_value(stats_row, "平均", self.average_text, 2)

        # English status labels and values are materially wider than Chinese.
        # Keep enough room for e.g. "Input engine" + "Listening on 9123"
        # without letting the two packed labels overlap.
        self.status_column = tk.Frame(summary, bg=PANEL, width=325)
        self.status_column.pack(side="right", fill="y", padx=(0, 1), pady=1)
        self.status_column.pack_propagate(False)
        tk.Label(self.status_column, text="链路状态", bg=PANEL, fg=TEXT, font=("Microsoft YaHei UI", 9, "bold")).pack(
            anchor="w", padx=20, pady=(18, 9),
        )
        self._status_row(self.status_column, "输入引擎", self.receiver_text)
        self._status_row(self.status_column, "输入设备", self.phone_text)
        self._status_row(self.status_column, "VRChat OSC", self.osc_text)

        chart_card = RoundedCard(content, fill=PANEL)
        chart_card.pack(fill="x", pady=(14, 0))
        self.chart_panel = chart_card.body
        chart_header = tk.Frame(self.chart_panel, bg=PANEL)
        chart_header.pack(fill="x", padx=20, pady=(17, 3))
        tk.Label(
            chart_header, textvariable=self.chart_title, bg=PANEL, fg=TEXT,
            font=("Microsoft YaHei UI", 12, "bold"),
        ).pack(side="left")
        RoundedButton(
            chart_header,
            textvariable=self.diagnostic_toggle_text,
            variant="primary",
            command=self.toggle_diagnostic_view,
            width=210,
            height=38,
        ).pack(side="right")
        tk.Label(
            self.chart_panel,
            textvariable=self.csv_text,
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
            justify="right",
            wraplength=520,
        ).pack(anchor="e", padx=20, pady=(0, 2))
        slider_row = tk.Frame(self.chart_panel, bg=PANEL)
        slider_row.pack(fill="x", padx=20)
        tk.Label(slider_row, text="显示范围", bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(side="left")
        tk.Scale(
            slider_row,
            from_=1,
            to=10,
            orient="horizontal",
            variable=self.chart_minutes,
            command=self._change_chart_minutes,
            showvalue=True,
            resolution=1,
            bg=PANEL,
            fg=TEXT,
            highlightthickness=0,
            troughcolor=CHART_GRID,
            activebackground=ACCENT,
        ).pack(side="left", fill="x", expand=True, padx=(12, 0))
        self.chart = tk.Canvas(self.chart_panel, height=190, bg=PANEL_ELEVATED, highlightthickness=0)
        self.chart.pack(fill="x", padx=20, pady=(5, 18))
        self.chart.bind("<Configure>", lambda _event: self._draw_chart())

        source_card = RoundedCard(content, fill=PANEL)
        source_card.pack(fill="x", pady=(14, 0))
        self.source_panel = source_card.body
        tk.Label(
            self.source_panel,
            text="心率来源",
            bg=PANEL,
            fg=TEXT,
            font=("Microsoft YaHei UI", 12, "bold"),
        ).grid(row=0, column=0, columnspan=6, sticky="w", padx=20, pady=(17, 4))
        tk.Label(
            self.source_panel,
            text="选择 Galaxy Watch 手机中转，或让小米手环直接连接这台电脑",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).grid(row=1, column=0, columnspan=6, sticky="w", padx=20, pady=(0, 12))
        self.source_combo = RoundedCombobox(
            self.source_panel,
            textvariable=self.input_source_label,
            values=list(self.input_source_labels.values()),
            state="readonly",
            width=230,
        )
        self.source_combo.grid(row=2, column=0, columnspan=2, sticky="ew", padx=(20, 6), pady=(0, 10))
        self.source_combo.bind("<<ComboboxSelected>>", self._change_input_source)
        self.ble_device_combo = RoundedCombobox(
            self.source_panel,
            textvariable=self.ble_device_choice,
            state="disabled",
            width=270,
        )
        self.ble_device_combo.grid(row=2, column=2, columnspan=2, sticky="ew", padx=6, pady=(0, 10))
        self.ble_device_combo.bind("<<ComboboxSelected>>", lambda _event: self._refresh_source_controls())
        self.ble_scan_button = RoundedButton(
            self.source_panel,
            text="扫描",
            variant="secondary",
            command=self.scan_ble_devices,
            state="disabled",
            width=108,
        )
        self.ble_scan_button.grid(row=2, column=4, sticky="ew", padx=6, pady=(0, 10))
        self.ble_connect_button = RoundedButton(
            self.source_panel,
            text="连接",
            variant="primary",
            command=self.connect_selected_ble_device,
            state="disabled",
            width=108,
        )
        self.ble_connect_button.grid(row=2, column=5, sticky="ew", padx=(6, 20), pady=(0, 10))
        tk.Label(
            self.source_panel,
            textvariable=self.ble_status_text,
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).grid(row=3, column=0, columnspan=4, sticky="w", padx=20, pady=(0, 16))
        self.ble_disconnect_button = RoundedButton(
            self.source_panel,
            text="断开 BLE",
            variant="secondary",
            command=self.disconnect_ble_device,
            state="disabled",
            width=150,
        )
        self.ble_disconnect_button.grid(row=3, column=4, columnspan=2, sticky="e", padx=(6, 20), pady=(0, 16))
        for column in range(6):
            self.source_panel.grid_columnconfigure(column, weight=1)

        settings_card = RoundedCard(content, fill=PANEL)
        settings_card.pack(fill="x", pady=(14, 0))
        self.settings_panel = settings_card.body
        settings_panel = self.settings_panel
        tk.Label(settings_panel, text="连接与工具", bg=PANEL, fg=TEXT, font=("Microsoft YaHei UI", 12, "bold")).grid(
            row=0, column=0, columnspan=6, sticky="w", padx=20, pady=(17, 10),
        )
        self._entry_field(settings_panel, 1, 0, "手机 UDP 端口", self.listen_port)
        self._entry_field(settings_panel, 1, 2, "VRChat OSC 端口", self.osc_port)
        PillToggle(
            settings_panel, text="发送到 VRChat OSC", variable=self.forward_osc, command=self._update_osc_label,
        ).grid(row=1, column=4, columnspan=2, sticky="w", padx=(12, 20), pady=(0, 12))
        interval_block = tk.Frame(settings_panel, bg=PANEL)
        interval_block.grid(row=2, column=0, columnspan=2, sticky="ew", padx=(20, 8), pady=(0, 10))
        tk.Label(
            interval_block,
            text="手机/手表发送频率",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="w")
        self.relay_interval_combo = RoundedCombobox(
            interval_block,
            textvariable=self.relay_interval_choice,
            values=list(self.relay_interval_labels.values()),
            state="readonly",
            width=240,
        )
        self.relay_interval_combo.pack(fill="x", pady=(3, 0))
        self.relay_interval_combo.bind("<<ComboboxSelected>>", self._change_relay_interval)
        tk.Label(
            settings_panel,
            text="电脑修改后通过 ACK 同步到手机，再由手机同步到手表",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
            justify="left",
            wraplength=430,
        ).grid(row=2, column=2, columnspan=4, sticky="w", padx=(8, 20), pady=(0, 10))
        PillToggle(
            settings_panel, text="记录曲线与统计（诊断模式）",
            variable=self.diagnostic_mode, command=self._toggle_diagnostic_mode, width=520,
        ).grid(row=3, column=0, columnspan=6, sticky="w", padx=20, pady=(2, 14))

        self.start_button = RoundedButton(
            settings_panel,
            text="启动接收",
            variant="primary",
            command=self.start_receiver,
            width=180,
        )
        self.start_button.grid(row=4, column=0, columnspan=2, sticky="ew", padx=(20, 6), pady=(0, 14))
        self.stop_button = RoundedButton(
            settings_panel,
            text="停止",
            variant="secondary",
            command=self.stop_receiver,
            state="disabled",
            width=180,
        )
        self.stop_button.grid(row=4, column=2, columnspan=2, sticky="ew", padx=6, pady=(0, 14))
        self.qr_button = RoundedButton(
            settings_panel,
            text="显示配对二维码",
            variant="secondary",
            command=self.show_pairing_qr,
            width=220,
        )
        self.qr_button.grid(row=4, column=4, columnspan=2, sticky="ew", padx=(6, 20), pady=(0, 14))
        tk.Label(
            settings_panel,
            text="高级工具",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8, "bold"),
        ).grid(row=5, column=0, columnspan=6, sticky="w", padx=20, pady=(0, 7))
        self.avatar_test_button = RoundedButton(
            settings_panel,
            text="Avatar 参数测试",
            variant="secondary",
            command=self.send_avatar_test,
            state="disabled",
            width=220,
        )
        self.avatar_test_button.grid(row=6, column=0, columnspan=2, sticky="ew", padx=(20, 6), pady=(0, 18))
        self.diagnostic_button = RoundedButton(
            settings_panel,
            text="一键诊断",
            variant="secondary",
            command=self.run_diagnostics,
            state="disabled",
            width=180,
        )
        self.diagnostic_button.grid(row=6, column=2, columnspan=2, sticky="ew", padx=6, pady=(0, 18))
        self.export_button = RoundedButton(
            settings_panel,
            text="导出 CSV…",
            variant="secondary",
            command=self.export_csv,
            state="disabled",
            width=180,
        )
        self.export_button.grid(row=6, column=4, columnspan=2, sticky="ew", padx=(6, 20), pady=(0, 18))
        for column in range(6):
            settings_panel.grid_columnconfigure(column, weight=1)

        log_card = RoundedCard(content, fill=PANEL)
        log_card.pack(fill="both", expand=True, pady=(14, 0))
        log_panel = log_card.body
        log_header = tk.Frame(log_panel, bg=PANEL)
        log_header.pack(fill="x", padx=20, pady=(14, 2))
        tk.Label(log_header, text="运行记录", bg=PANEL, fg=TEXT, font=("Microsoft YaHei UI", 12, "bold")).pack(
            side="left",
        )
        RoundedButton(
            log_header,
            text="打开日志文件夹",
            variant="secondary",
            command=self._open_log_folder,
            width=154,
            height=38,
        ).pack(side="right")
        tk.Label(
            log_panel,
            textvariable=self.log_path_text,
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(
            anchor="w",
            padx=20,
            pady=(0, 8),
        )
        self.log = tk.Text(
            log_panel, height=6, wrap="word", relief="flat", borderwidth=0,
            bg=PANEL_ELEVATED, fg=MUTED, insertbackground=TEXT,
            font=("Cascadia Mono", 9), padx=14, pady=12, state="disabled",
        )
        self.log.pack(fill="both", expand=True, padx=20, pady=(0, 18))

        preferences_card = RoundedCard(content, fill=PANEL)
        preferences_card.pack(fill="x", pady=(14, 0))
        preferences_panel = preferences_card.body
        tk.Label(
            preferences_panel,
            text="偏好设置",
            bg=PANEL,
            fg=TEXT,
            font=("Microsoft YaHei UI", 12, "bold"),
        ).grid(row=0, column=0, columnspan=4, sticky="w", padx=20, pady=(17, 12))
        language_block = tk.Frame(preferences_panel, bg=PANEL)
        language_block.grid(row=1, column=0, columnspan=2, sticky="ew", padx=(20, 10), pady=(0, 18))
        tk.Label(
            language_block,
            text="语言",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="w")
        self.language_combo = RoundedCombobox(
            language_block,
            textvariable=self.language_choice,
            values=list(self.language_options.values()),
            state="readonly",
            width=230,
        )
        self.language_combo.pack(fill="x", pady=(3, 0))
        self.language_combo.bind("<<ComboboxSelected>>", self._change_language)
        update_block = tk.Frame(preferences_panel, bg=PANEL)
        update_block.grid(row=1, column=2, columnspan=2, sticky="ew", padx=(10, 20), pady=(0, 18))
        tk.Label(
            update_block,
            text="版本与更新",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 8),
        ).pack(anchor="w")
        RoundedButton(
            update_block,
            textvariable=self.update_text,
            variant="secondary",
            command=self._open_or_check_update,
            width=260,
        ).pack(fill="x", pady=(3, 0))
        for column in range(4):
            preferences_panel.grid_columnconfigure(column, weight=1)
        self._refresh_source_controls()

    def _on_content_configure(self, _event: tk.Event) -> None:
        self.content_canvas.configure(scrollregion=self.content_canvas.bbox("all"))

    def _on_canvas_configure(self, event: tk.Event) -> None:
        self.content_canvas.itemconfigure(self.content_window, width=event.width)

    def _on_mousewheel(self, event: tk.Event) -> str:
        if getattr(event, "num", None) == 4:
            units = -3
        elif getattr(event, "num", None) == 5:
            units = 3
        else:
            delta = getattr(event, "delta", 0)
            if delta == 0:
                return "break"
            units = -max(1, min(6, abs(delta) // 40)) if delta > 0 else max(1, min(6, abs(delta) // 40))
        self.content_canvas.yview_scroll(units, "units")
        return "break"

    def _stat_value(self, parent: tk.Widget, label: str, value: tk.StringVar, column: int) -> None:
        block = tk.Frame(parent, bg=PANEL)
        block.grid(row=0, column=column, padx=(0 if column == 0 else 16, 0))
        tk.Label(block, text=label, bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 8)).pack()
        tk.Label(block, textvariable=value, bg=PANEL, fg=TEXT, font=("Segoe UI", 18, "bold")).pack()

    def _status_row(self, parent: tk.Widget, label: str, value: tk.StringVar) -> None:
        row = tk.Frame(parent, bg=PANEL)
        row.pack(fill="x", padx=20, pady=3)
        tk.Label(row, text=label, bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(side="left")
        tk.Label(row, textvariable=value, bg=PANEL, fg=TEXT, font=("Microsoft YaHei UI", 8, "bold")).pack(side="right")

    def _entry_field(self, parent: tk.Widget, row: int, column: int, label: str, variable: tk.StringVar) -> None:
        field = tk.Frame(parent, bg=PANEL)
        field.grid(row=row, column=column, columnspan=2, sticky="ew", padx=(20 if column == 0 else 8, 4), pady=(0, 10))
        tk.Label(field, text=label, bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 8)).pack(anchor="w")
        entry = RoundedEntry(field, textvariable=variable, width=132)
        entry.pack(fill="x", pady=(3, 0))
        if variable is self.listen_port:
            self.listen_port_entry = entry
        elif variable is self.osc_port:
            self.osc_port_entry = entry

    def _localize_widget_tree(self, widget: tk.Misc) -> None:
        if isinstance(widget, RoundedButton) and widget._textvariable is None:
            widget.configure(text=self.tr(widget._text))
        elif isinstance(widget, PillToggle):
            widget._text = self.tr(widget._text)
            widget._draw()
        elif isinstance(widget, (tk.Label, tk.Button, tk.Checkbutton)):
            try:
                current = widget.cget("text")
                if current:
                    widget.configure(text=self.tr(current))
            except tk.TclError:
                pass
        for child in widget.winfo_children():
            self._localize_widget_tree(child)

    def _change_language(self, _event: tk.Event | None = None) -> None:
        language = self.language_codes_by_label.get(self.language_choice.get())
        if language is None or language == self.settings.language:
            return
        previous_language = self.settings.language
        if self.diagnostic_csv.has_unexported_rows:
            proceed = messagebox.askyesno(
                self.tr("尚有未导出的 CSV 数据"),
                self.tr(
                    f"还有 {self.diagnostic_csv.row_count - self.diagnostic_csv.exported_row_count} 条记录未导出。\n"
                    "切换语言会重新载入界面，确定继续吗？"
                ),
            )
            if not proceed:
                self.language_choice.set(self.language_options[self.settings.language])
                return
        self.settings.language = language
        try:
            save_settings(self.settings)
        except OSError as exc:
            self.settings.language = previous_language
            self._show_error("语言", f"保存设置失败：{exc}")
            self.language_choice.set(self.language_options[self.settings.language])
            return
        self.restart_requested = True
        self._shutdown()

    def _show_info(self, title: str, message: object) -> Any:
        return messagebox.showinfo(self.tr(title), self.tr(message))

    def _show_warning(self, title: str, message: object) -> Any:
        return messagebox.showwarning(self.tr(title), self.tr(message))

    def _show_error(self, title: str, message: object) -> Any:
        return messagebox.showerror(self.tr(title), self.tr(message))

    def _selected_input_source(self) -> str:
        selected = self.input_source_label.get()
        return next(
            (key for key, label in self.input_source_labels.items() if label == selected),
            PHONE_RELAY,
        )

    def _change_input_source(self, _event: tk.Event | None = None) -> None:
        source = self._selected_input_source()
        if source == self.settings.input_source:
            self._refresh_source_controls()
            return
        was_running = self.runtime is not None and self.runtime.running
        if was_running:
            self.stop_receiver()
        self.settings.input_source = source
        self.packet_count = 0
        self.bpm_text.set("--")
        self.detail_text.set("尚未收到当前来源数据")
        self.phone_text.set("--")
        try:
            save_settings(self.settings)
        except OSError as exc:
            self._append_log(f"保存心率来源失败：{exc}")
        self._refresh_source_controls()
        if source == XIAOMI_PC_BLE:
            self.signal_text.set("等待小米手环 BLE")
            self._append_log("已切换为电脑直连小米手环；手机中转心率已禁用")
        else:
            self.signal_text.set("等待手机数据")
            self._append_log("已切换为手机 UDP 中转；电脑 BLE 已禁用")
        if was_running:
            self.root.after(100, self.start_receiver)

    def _refresh_source_controls(self) -> None:
        direct = self._selected_input_source() == XIAOMI_PC_BLE
        running = self.runtime is not None and self.runtime.running
        direct_running = direct and running
        available = direct_running and not self.ble_connected and not self.ble_scanning
        self.ble_device_combo.configure(state="readonly" if available else "disabled")
        self.ble_scan_button.configure(state="normal" if available else "disabled")
        self.ble_connect_button.configure(
            state="normal" if available and self.ble_device_choice.get() in self.ble_devices else "disabled"
        )
        self.ble_disconnect_button.configure(
            state="normal" if direct_running and self.ble_connected else "disabled"
        )
        self.relay_interval_combo.configure(state="disabled" if direct else "readonly")
        self.qr_button.configure(state="disabled" if direct else "normal")
        if not direct:
            self.ble_status_text.set("直连模式未启用；继续使用手机 UDP 中转")

    def scan_ble_devices(self) -> None:
        if self._selected_input_source() != XIAOMI_PC_BLE:
            return
        if self.runtime is None or not self.runtime.running:
            self._show_info("扫描小米手环", "请先启动接收器。")
            return
        self.ble_scan_button.configure(state="disabled")
        self.ble_status_text.set("正在扫描标准心率设备…")
        self.ble_client.scan()

    def connect_selected_ble_device(self) -> None:
        selected = self.ble_devices.get(self.ble_device_choice.get())
        if selected is None:
            self._show_info("连接小米手环", "请先扫描并选择一个心率设备。")
            return
        address, name = selected
        self.ble_client.connect(address, name)

    def disconnect_ble_device(self) -> None:
        self.ble_client.disconnect()
        self.ble_connected = False
        self.ble_scanning = False
        self.ble_status_text.set("已断开；可重新扫描或连接")
        self.phone_text.set("--")

    def start_receiver(self) -> None:
        if self.runtime is not None and self.runtime.running:
            return
        try:
            listen_port = parse_port(self.listen_port.get())
            osc_port = parse_port(self.osc_port.get())
            input_source = self._selected_input_source()
            settings = AppSettings(
                listen_port=listen_port,
                osc_port=osc_port,
                forward_osc=self.forward_osc.get(),
                relay_interval_seconds=self.settings.relay_interval_seconds,
                relay_interval_updated_epoch_millis=self.settings.relay_interval_updated_epoch_millis,
                ignored_update_tag=self.settings.ignored_update_tag,
                input_source=input_source,
                ble_address=self.settings.ble_address,
                ble_name=self.settings.ble_name,
                language=self.settings.language,
            )
            save_settings(settings)
            self.settings = settings
            self.runtime = BridgeRuntime(
                RuntimeConfig(
                    listen_port=listen_port,
                    osc_port=osc_port,
                    forward_osc=self.forward_osc.get(),
                    input_source=input_source,
                    relay_interval_seconds=settings.relay_interval_seconds,
                    relay_interval_updated_epoch_millis=settings.relay_interval_updated_epoch_millis,
                ),
                self._enqueue_event,
            )
            self.runtime.start()
            self.runtime.set_diagnostic_mode(self.diagnostic_mode.get())
            if input_source == XIAOMI_PC_BLE:
                if not self.ble_client.start():
                    raise RuntimeError("Windows BLE 组件无法启动")
                self.ble_client.scan(auto_connect_address=self.settings.ble_address)
            else:
                self.ble_client.stop()
            self.start_button.configure(state="disabled")
            self.stop_button.configure(state="normal")
            self.listen_port_entry.configure(state="disabled")
            self.osc_port_entry.configure(state="disabled")
            if input_source == XIAOMI_PC_BLE:
                self.receiver_text.set("BLE 直连引擎")
                self._append_log("电脑 BLE 直连已启动；正在扫描标准心率服务 0x180D")
            else:
                self._append_log(f"开始监听 UDP 0.0.0.0:{listen_port}")
            self._refresh_source_controls()
        except (ValueError, OSError, RuntimeError) as exc:
            if self.runtime is not None:
                self.runtime.stop()
                self.runtime = None
            self.ble_client.stop()
            self.receiver_text.set("启动失败")
            self.signal_text.set("无法启动接收器")
            self.signal_label.configure(fg=ACCENT)
            self._append_log(f"启动失败：{exc}")
            self._refresh_source_controls()

    def stop_receiver(self) -> None:
        self.ble_client.stop()
        self.ble_connected = False
        self.ble_scanning = False
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
        self._refresh_source_controls()

    def send_avatar_test(self) -> None:
        runtime = self.runtime
        if runtime is None or not runtime.running:
            self._show_warning("Avatar 参数测试", "请先启动接收器。")
            return
        if not self.forward_osc.get():
            self._show_warning("Avatar 参数测试", "请先开启“发送到 VRChat OSC”。")
            return
        if runtime.send_avatar_test(123):
            self._append_log("已发送 Avatar 参数测试：123 BPM + HRValid + HRPulse")
            self.signal_text.set("Avatar 测试已发送")
            self.signal_label.configure(fg=GOOD)

    def show_pairing_qr(self) -> None:
        addresses = local_ipv4_addresses()
        if not addresses or addresses == ["--"]:
            self._show_error("配对二维码", "没有找到可用的本机局域网 IPv4。")
            return
        try:
            port = parse_port(self.listen_port.get())
            uri = build_pairing_uri(addresses[0], port)
            import qrcode
            from PIL import ImageTk

            image = qrcode.make(uri).resize((300, 300))
            self._qr_photo = ImageTk.PhotoImage(image)
        except ImportError:
            self._show_error("配对二维码", "二维码组件未安装，请重新安装或使用最新版 EXE。")
            return
        except ValueError as exc:
            self._show_error("配对二维码", str(exc))
            return

        popup = tk.Toplevel(self.root)
        popup.title(self.tr("手机扫码配对"))
        popup.configure(bg=PANEL)
        popup.resizable(False, False)
        tk.Label(popup, image=self._qr_photo, bg=PANEL).pack(padx=24, pady=(22, 8))
        tk.Label(popup, text=f"{addresses[0]}:{port}", bg=PANEL, fg=TEXT, font=("Segoe UI", 13, "bold")).pack()
        tk.Label(
            popup, text=self.tr("在手机端点击“扫码配对”，识别后会自动保存电脑地址。"),
            bg=PANEL, fg=MUTED, font=("Microsoft YaHei UI", 9),
        ).pack(padx=24, pady=(7, 20))

    def run_diagnostics(self) -> None:
        if not self.diagnostic_mode.get():
            self._show_info("一键诊断", "请先开启诊断模式。")
            return
        runtime = self.runtime
        direct = self._selected_input_source() == XIAOMI_PC_BLE
        if direct:
            checks = [
                ("输入模式", True, "电脑直连小米手环 BLE"),
                ("BLE 引擎", runtime is not None and runtime.running, self.receiver_text.get()),
                ("小米手环", self.ble_connected, self.ble_status_text.get()),
                ("真实心率", self.packet_count > 0, self.bpm_text.get() + " BPM"),
                ("VRChat OSC", self.forward_osc.get(), f"127.0.0.1:{self.osc_port.get()}"),
            ]
        else:
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
            result = "接收器正常；未通过项可能只是当前来源尚未发送或 OSC 被关闭。"
        else:
            result = "请先启动接收器，再连接当前选择的心率来源。"
        self._append_log("电脑诊断：" + "；".join(lines))
        self._show_info("一键诊断", result + "\n\n" + "\n".join(lines))

    def toggle_diagnostic_view(self) -> None:
        self.diagnostic_mode.set(not self.diagnostic_mode.get())
        self._toggle_diagnostic_mode()

    def _toggle_diagnostic_mode(self) -> None:
        enabled = self.diagnostic_mode.get()
        self.diagnostic_toggle_text.set("停止曲线记录" if enabled else "开始曲线记录")
        if enabled:
            try:
                if self.diagnostic_session_started:
                    self.diagnostic_csv.resume()
                else:
                    self.diagnostic_csv.begin()
                    self.diagnostic_session_started = True
            except OSError as exc:
                self.diagnostic_mode.set(False)
                self._show_error("诊断模式", f"无法创建诊断 CSV：{exc}")
                return
            self.avatar_test_button.configure(state="normal")
            self.diagnostic_button.configure(state="normal")
            self.export_button.configure(state="normal")
            self.csv_text.set(f"CSV 追加写入 · {self.diagnostic_csv.row_count} 条")
            self._append_log("诊断模式已开启：扩展字段、CSV、曲线和统计开始工作")
        else:
            self.diagnostic_csv.stop()
            self.avatar_test_button.configure(state="disabled")
            self.diagnostic_button.configure(state="disabled")
            self.export_button.configure(state="disabled")
            self.csv_text.set("点击右上角开始记录")
            self._append_log("诊断模式已关闭：停止扩展字段和 CSV 写入")
        if self.runtime is not None:
            self.runtime.set_diagnostic_mode(enabled)

    def export_csv(self) -> None:
        if self.diagnostic_csv.row_count == 0:
            self._show_info("导出 CSV", "还没有诊断数据。请先开启诊断模式。")
            return
        filename = filedialog.asksaveasfilename(
            title=self.tr("导出心率 CSV"),
            defaultextension=".csv",
            initialfile=f"heart-rate-{datetime.now():%Y%m%d-%H%M%S}.csv",
            filetypes=[(self.tr("CSV 文件"), "*.csv")],
        )
        if not filename:
            return
        try:
            from pathlib import Path

            self.diagnostic_csv.export(Path(filename))
            self._append_log(f"CSV 已手动导出：{filename}")
            self._show_info("导出 CSV", f"已导出 {self.diagnostic_csv.row_count} 条数据。")
        except OSError as exc:
            self._show_error("导出 CSV", f"写入失败：{exc}")

    def _change_chart_minutes(self, value: str) -> None:
        minutes = max(1, min(10, int(float(value))))
        self.chart_minutes.set(minutes)
        self.chart_title.set(f"最近 {minutes} 分钟心率曲线")
        if self.diagnostic_mode.get():
            self._refresh_chart_once()

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

    def _destroy_update_popup(self) -> None:
        popup, self.update_popup = self.update_popup, None
        self.update_popup_tag = ""
        if popup is not None:
            try:
                popup.destroy()
            except tk.TclError:
                pass

    def _dismiss_update_popup(self, popup: tk.Toplevel) -> None:
        if self.update_popup is popup:
            self.update_popup = None
            self.update_popup_tag = ""
        try:
            popup.destroy()
        except tk.TclError:
            pass

    def _open_update_release(self, popup: tk.Toplevel, url: str) -> None:
        self._dismiss_update_popup(popup)
        if not url:
            return
        try:
            webbrowser.open(url)
        except OSError as exc:
            self._append_log(f"打开 GitHub Release 失败：{exc}")

    def _ignore_update_version(self, popup: tk.Toplevel, tag: str) -> None:
        previous = self.settings.ignored_update_tag
        self.settings.ignored_update_tag = tag
        try:
            save_settings(self.settings)
        except OSError as exc:
            self.settings.ignored_update_tag = previous
            self._append_log(f"保存设置失败：{exc}")
            self._show_error("忽略更新", f"保存设置失败：{exc}")
            return
        self._dismiss_update_popup(popup)
        self._append_log(f"已忽略版本：{tag}")

    def _show_update_popup(self, tag: str, url: str) -> None:
        if not tag or self.settings.ignored_update_tag == tag:
            return
        existing = self.update_popup
        if existing is not None:
            try:
                if existing.winfo_exists() and self.update_popup_tag == tag:
                    existing.lift()
                    return
            except tk.TclError:
                pass
            self._destroy_update_popup()

        popup = tk.Toplevel(self.root)
        self.update_popup = popup
        self.update_popup_tag = tag
        popup.title(self.tr("发现新版本"))
        popup.configure(background=PANEL)
        popup.resizable(False, False)
        popup.transient(self.root)
        popup.protocol("WM_DELETE_WINDOW", lambda: self._dismiss_update_popup(popup))

        body = tk.Frame(popup, bg=PANEL)
        body.pack(fill="both", expand=True, padx=26, pady=22)
        tk.Label(
            body,
            text=self.tr("发现新版本"),
            bg=PANEL,
            fg=TEXT,
            font=("Microsoft YaHei UI", 15, "bold"),
        ).pack(anchor="w")
        tk.Label(
            body,
            text=self.tr(f"当前版本：v{__version__}\n最新版本：{tag}"),
            justify="left",
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 10),
        ).pack(anchor="w", pady=(10, 18))
        tk.Label(
            body,
            text=self.tr("更新是可选的，可以关闭此窗口；忽略后本版本不再提醒。"),
            justify="left",
            wraplength=360,
            bg=PANEL,
            fg=MUTED,
            font=("Microsoft YaHei UI", 9),
        ).pack(anchor="w", pady=(0, 18))
        buttons = tk.Frame(body, bg=PANEL)
        buttons.pack(fill="x")
        RoundedButton(
            buttons,
            text=self.tr("打开 Release"),
            command=lambda: self._open_update_release(popup, url),
            variant="primary",
            width=122,
        ).pack(side="left")
        RoundedButton(
            buttons,
            text=self.tr("忽略此版本"),
            command=lambda: self._ignore_update_version(popup, tag),
            variant="secondary",
            width=122,
        ).pack(side="left", padx=(8, 0))
        RoundedButton(
            buttons,
            text=self.tr("关闭"),
            command=lambda: self._dismiss_update_popup(popup),
            variant="secondary",
            width=86,
        ).pack(side="right")

        popup.update_idletasks()
        width = popup.winfo_width()
        height = popup.winfo_height()
        x = self.root.winfo_x() + max(0, (self.root.winfo_width() - width) // 2)
        y = self.root.winfo_y() + max(0, (self.root.winfo_height() - height) // 2)
        popup.geometry(f"+{x}+{y}")
        popup.lift()
        popup.focus_force()

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
        if kind == "direct_ready":
            self.receiver_text.set("BLE 直连引擎")
            return
        if kind == "ble_devices":
            self.ble_devices.clear()
            labels: list[str] = []
            for device in data.get("devices", []):
                label = f"{device['name']} · {device['address']} · {device['rssi']} dBm"
                labels.append(label)
                self.ble_devices[label] = (str(device["address"]), str(device["name"]))
            self.ble_device_combo.configure(values=labels)
            preferred = next(
                (
                    label
                    for label, (address, _name) in self.ble_devices.items()
                    if address == self.settings.ble_address
                ),
                labels[0] if labels else "",
            )
            self.ble_device_choice.set(preferred)
            self.ble_scan_button.configure(state="normal")
            self._refresh_source_controls()
            return
        if kind == "ble_status":
            message = str(data.get("message", "BLE 状态已更新"))
            self.ble_status_text.set(message)
            self.ble_scanning = bool(data.get("scanning", False))
            connected = bool(data.get("connected", False))
            self.ble_connected = connected
            self.phone_text.set(str(data.get("name", "小米手环 BLE")) if connected else "--")
            if connected:
                self.signal_text.set("BLE 已连接，等待心率")
                self.signal_label.configure(fg=GOOD)
                address = str(data.get("address", ""))
                name = str(data.get("name", "小米手环"))
                if address:
                    self.settings.ble_address = address
                    self.settings.ble_name = name
                    try:
                        save_settings(self.settings)
                    except OSError as exc:
                        self._append_log(f"保存 BLE 设备失败：{exc}")
            self._append_log(message)
            self._refresh_source_controls()
            return
        if kind == "ble_error":
            message = str(data.get("message", "未知 BLE 错误"))
            self.ble_connected = False
            self.ble_scanning = False
            self.ble_status_text.set(message)
            self.signal_text.set("BLE 连接异常")
            self.signal_label.configure(fg=ACCENT)
            self.ble_scan_button.configure(state="normal")
            self._append_log(message)
            self._refresh_source_controls()
            return
        if kind == "ble_warning":
            self._append_log(str(data.get("message", "BLE 数据警告")))
            return
        if kind == "ble_heart_rate":
            runtime = self.runtime
            if runtime is not None:
                runtime.accept_direct_heart_rate(
                    int(data["bpm"]),
                    int(data["sample_epoch_ms"]),
                    str(data.get("name", "小米手环")),
                    str(data.get("address", "")),
                )
            return
        if kind == "packet":
            packet = data["packet"]
            source = str(packet.payload.get("source", "galaxy_watch"))
            source_text = {
                "galaxy_watch": "Galaxy Watch",
                "watch_diagnostic_simulator": "手表模拟心率（非传感器）",
                "xiaomi_band_ble": "小米手环 BLE",
                "xiaomi_band_pc_ble": "小米手环 → 电脑 BLE",
            }.get(source, source)
            self.packet_count += 1
            self.phone_text.set(data["sender"])
            latency = int(data["latency_ms"])
            if packet.is_real_heart_rate:
                self.bpm_text.set(str(packet.bpm))
                self.signal_text.set("模拟心率（非传感器）" if packet.is_simulated else "数据正常")
                self.signal_label.configure(fg=WARN if packet.is_simulated else GOOD)
                if self.diagnostic_mode.get():
                    now_ms = int(time.time() * 1_000)
                    sample = HeartRateSample(now_ms, packet.bpm, data["sender"], latency)
                    try:
                        self.diagnostic_csv.append(
                            sample,
                            packet.payload,
                            datetime.fromtimestamp(now_ms / 1_000).isoformat(timespec="milliseconds"),
                        )
                        self.csv_text.set(f"CSV 追加写入 · {self.diagnostic_csv.row_count} 条")
                    except OSError as exc:
                        self._append_log(f"诊断 CSV 写入失败：{exc}")
                    self._refresh_chart_once()
            else:
                self.signal_text.set("手机 → 电脑诊断通过")
                self.signal_label.configure(fg=GOOD)
            self.detail_text.set(
                f"{source_text}   ·   数据包 {self.packet_count}   ·   端到端 {latency} ms"
            )
            if self.diagnostic_mode.get() or not packet.is_real_heart_rate:
                self._append_log(
                    f"{packet.packet_type}  seq={packet.sequence}  bpm={packet.bpm}  "
                    f"source={source}  input={data['sender']}  latency={latency}ms  ack=ok"
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
                if tag == self.settings.ignored_update_tag:
                    self.latest_release_url = ""
                    self.update_text.set(f"已忽略版本 {tag}")
                    self._destroy_update_popup()
                else:
                    self.latest_release_url = str(data.get("url", ""))
                    self.update_text.set(f"发现新版本 {tag} · 点击打开")
                    self._append_log(f"GitHub 有新版本：{tag}")
                    self._show_update_popup(tag, self.latest_release_url)
            else:
                self.latest_release_url = ""
                self.update_text.set(f"已是最新版 {__version__}")
                self._destroy_update_popup()
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
        if self.diagnostic_mode.get():
            self._refresh_chart_once()
        self.root.after(1_000, self._refresh_chart)

    def _refresh_chart_once(self) -> None:
        samples = self.diagnostic_csv.read_window(
            int(time.time() * 1_000),
            self.chart_minutes.get(),
        )
        self._update_stats(samples)
        self._draw_chart(samples)

    def _update_stats(self, samples: tuple[HeartRateSample, ...]) -> None:
        if not samples:
            self.minimum_text.set("--")
            self.maximum_text.set("--")
            self.average_text.set("--")
            return
        values = [sample.bpm for sample in samples]
        self.minimum_text.set(str(min(values)))
        self.maximum_text.set(str(max(values)))
        self.average_text.set(f"{sum(values) / len(values):.1f}")

    def _draw_chart(self, samples: tuple[HeartRateSample, ...] | None = None) -> None:
        canvas = self.chart
        canvas.delete("all")
        width = max(canvas.winfo_width(), 200)
        height = max(canvas.winfo_height(), 100)
        left, top, right, bottom = 42, 12, width - 12, height - 24
        for index in range(5):
            y = top + (bottom - top) * index / 4
            canvas.create_line(left, y, right, y, fill=CHART_GRID)
        minutes = self.chart_minutes.get()
        canvas.create_text(
            left, bottom + 13, text=self.tr(f"-{minutes} 分钟"), anchor="w",
            fill=MUTED, font=("Microsoft YaHei UI", 7),
        )
        canvas.create_text(right, bottom + 13, text=self.tr("现在"), anchor="e", fill=MUTED, font=("Microsoft YaHei UI", 7))
        if samples is None:
            samples = self.diagnostic_csv.read_window(int(time.time() * 1_000), minutes)
        if not samples:
            canvas.create_text(
                width / 2,
                height / 2,
                text=self.tr("等待真实心率数据"),
                fill=MUTED,
                font=("Microsoft YaHei UI", 10),
            )
            return
        values = [item.bpm for item in samples]
        low = max(30, min(values) - 10)
        high = min(240, max(values) + 10)
        if high - low < 20:
            high = low + 20
        now_ms = int(time.time() * 1_000)
        window_ms = minutes * 60_000
        start_ms = now_ms - window_ms
        points: list[float] = []
        for sample in samples:
            x = left + (sample.epoch_ms - start_ms) / window_ms * (right - left)
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

    def _change_relay_interval(self, _event: tk.Event | None = None) -> None:
        seconds = self.relay_interval_seconds_by_label.get(self.relay_interval_choice.get())
        if seconds is None:
            return
        seconds = normalize_relay_interval(seconds)
        previous_seconds = self.settings.relay_interval_seconds
        previous_updated = self.settings.relay_interval_updated_epoch_millis
        if seconds == previous_seconds:
            return
        updated = max(int(time.time() * 1_000), previous_updated + 1)
        self.settings.relay_interval_seconds = seconds
        self.settings.relay_interval_updated_epoch_millis = updated
        try:
            save_settings(self.settings)
        except OSError as exc:
            self.settings.relay_interval_seconds = previous_seconds
            self.settings.relay_interval_updated_epoch_millis = previous_updated
            self.relay_interval_choice.set(self.relay_interval_labels[previous_seconds])
            self._show_error("发送频率", f"保存设置失败：{exc}")
            return
        if self.runtime is not None:
            self.runtime.set_relay_interval(seconds, updated)
        self._append_log(
            f"发送频率已切换为每 {seconds} 秒；下一份手机数据的 ACK 将同步手机和手表"
        )

    def _append_log(self, line: str) -> None:
        localized = self.tr(line)
        self.app_log.info(localized)
        self.log_path_text.set(f"自动日志 · {self.app_log.path}")
        self.log.configure(state="normal")
        self.log.insert("end", f"{datetime.now():%H:%M:%S}  {localized}\n")
        lines = int(self.log.index("end-1c").split(".")[0])
        if lines > 220:
            self.log.delete("1.0", f"{lines - 180}.0")
        self.log.see("end")
        self.log.configure(state="disabled")

    def _open_log_folder(self) -> None:
        try:
            self.app_log.directory.mkdir(parents=True, exist_ok=True)
            if sys.platform == "win32":
                os.startfile(self.app_log.directory)  # type: ignore[attr-defined]
            else:
                raise OSError("仅 Windows 支持直接打开日志文件夹")
            self._append_log(f"已打开日志文件夹：{self.app_log.directory}")
        except OSError as exc:
            self._append_log(f"打开日志文件夹失败：{exc}")
            self._show_error("无法打开日志文件夹", str(exc))

    def _report_callback_exception(
        self,
        exception_type: type[BaseException],
        exception: BaseException,
        exception_traceback: Any,
    ) -> None:
        stack = "".join(traceback.format_exception(exception_type, exception, exception_traceback))
        self.app_log.error(self.tr("Tk 回调异常\n") + stack)
        try:
            self._append_log(f"界面操作异常：{exception}")
        except tk.TclError:
            pass

    def close(self) -> None:
        if self.diagnostic_csv.has_unexported_rows:
            should_close = messagebox.askyesno(
                self.tr("尚有未导出的 CSV 数据"),
                self.tr(
                    f"还有 {self.diagnostic_csv.row_count - self.diagnostic_csv.exported_row_count} 条记录未导出。\n"
                    "程序不会自动导出到用户文件，确定直接退出吗？"
                ),
            )
            if not should_close:
                return
        self._shutdown()

    def _shutdown(self) -> None:
        self._destroy_update_popup()
        self.diagnostic_csv.stop()
        self.ble_client.stop()
        runtime, self.runtime = self.runtime, None
        if runtime is not None:
            runtime.stop()
        self.app_log.info(self.tr("应用退出"))
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
    return discover_local_ipv4_addresses()


def _resource_path(name: str) -> str:
    from pathlib import Path

    frozen_root = getattr(sys, "_MEIPASS", None)
    if frozen_root:
        return str(Path(frozen_root) / name)
    return str(Path(__file__).resolve().parents[1] / "assets" / name)


def main() -> None:
    enable_dpi_awareness()
    while True:
        root = tk.Tk()
        app = HeartRateBridgeApp(root)
        root.mainloop()
        if not app.restart_requested:
            break
