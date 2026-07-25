import os
from pathlib import Path
import sys

try:
    import winreg
except ImportError:
    winreg = None


RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE_NAME = "TIDAL RPC"


def packaged_executable():
    if os.name != "nt" or not getattr(sys, "frozen", False):
        return None
    path = Path(sys.executable).resolve()
    return path if path.suffix.lower() == ".exe" else None


def startup_command(executable=None):
    executable = executable or packaged_executable()
    if executable is None:
        return None
    executable = Path(executable).resolve()
    return f'"{executable}" --minimized'


def current_command():
    if winreg is None:
        return None
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            value, _ = winreg.QueryValueEx(key, VALUE_NAME)
            return value
    except OSError:
        return None


def is_enabled():
    expected = startup_command()
    return bool(expected and current_command() == expected)


def set_enabled(enabled):
    if winreg is None:
        raise OSError("Start with Windows is available only on Windows.")
    if enabled:
        command = startup_command()
        if not command:
            raise OSError("Start with Windows is available only in the packaged application.")
        with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ, command)
    else:
        try:
            with winreg.OpenKey(
                winreg.HKEY_CURRENT_USER,
                RUN_KEY,
                0,
                winreg.KEY_SET_VALUE,
            ) as key:
                winreg.DeleteValue(key, VALUE_NAME)
        except FileNotFoundError:
            pass
