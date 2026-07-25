# TIDAL RPC

TIDAL RPC is a compact Windows utility that displays the track playing in the
TIDAL desktop app as Discord Rich Presence.

## Features

- Detects playing, paused, stopped, and closed TIDAL states.
- Shows track, artist, album artwork, and optional TIDAL buttons in Discord.
- Reconnects automatically when Discord opens or restarts.
- Runs in the system tray when the window is closed.
- Supports per-user “Start with Windows” in packaged builds.
- Stores user settings under `%LOCALAPPDATA%\TIDAL RPC`.

TIDAL RPC currently supports Windows 10 and Windows 11. Playback detection uses
Windows UI Automation and the title of the official TIDAL desktop window.

## Development

Python 3.11 is the supported build version.

```powershell
py -3.11 -m venv .venv
.\.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
python main.py
```

The first launch opens TIDAL’s device sign-in page. Discord and TIDAL may be
opened before or after TIDAL RPC.

## Tests

```powershell
py -3.11 -m py_compile main.py ui.py app_settings.py windows_startup.py
py -3.11 -m unittest discover -s tests -v
```

## Build

Build the Windows GUI executable on Windows:

```powershell
py -3.11 -m PyInstaller --clean --noconfirm tidalrpc.spec
```

The finished application is written to `dist\TIDAL RPC.exe`. The build has no
console window and includes the Qt interface and application icons.

“Start TIDAL RPC when I sign in” is intentionally disabled during interpreted
development so it cannot register a temporary Python command. In the packaged
application it writes only the `TIDAL RPC` value under the current user’s
Windows `Run` key.

## Privacy and diagnostics

OAuth session data, settings, and `tidalrpc.log` remain in the current user’s
application-data directory. Track names are logged only when the detected track
changes. Tokens are never logged.

## License

This project is licensed under the MIT License.
