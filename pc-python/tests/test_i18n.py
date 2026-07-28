from __future__ import annotations

from vrc_heartbeat.i18n import CHINESE, ENGLISH, JAPANESE, SYSTEM, Translator, normalize_language


def test_language_value_is_normalized() -> None:
    assert normalize_language("ja") == JAPANESE
    assert normalize_language("future") == SYSTEM
    assert normalize_language(None) == SYSTEM


def test_static_and_dynamic_translations() -> None:
    english = Translator(ENGLISH)
    japanese = Translator(JAPANESE)

    assert english.text("启动接收") == "Start receiver"
    assert japanese.text("启动接收") == "受信を開始"
    assert english.text("最近 5 分钟心率曲线") == "Last 5 min heart rate chart"
    assert japanese.text("最近 5 分钟心率曲线") == "直近5分の心拍数グラフ"


def test_chinese_and_technical_identifiers_are_preserved() -> None:
    chinese = Translator(CHINESE)
    english = Translator(ENGLISH)

    assert chinese.text("运行记录") == "运行记录"
    assert english.text("Xiaomi Smart Band 10 · AA:BB:CC:DD:EE:FF") == (
        "Xiaomi Smart Band 10 · AA:BB:CC:DD:EE:FF"
    )
