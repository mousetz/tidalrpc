from PySide6.QtCore import QFile, Qt
from PySide6.QtGui import QIcon
from PySide6.QtUiTools import QUiLoader
from PySide6.QtWidgets import QFrame, QMessageBox, QScrollArea, QSizePolicy

from app_settings import resource_path


def create_window(app, settings):
    loader = QUiLoader()
    file = QFile(str(resource_path("QTdesign.ui")))
    if not file.open(QFile.ReadOnly):
        raise RuntimeError("The application interface could not be loaded.")
    window = loader.load(file)
    file.close()
    if window is None:
        raise RuntimeError(loader.errorString())

    icon = QIcon(str(resource_path("tidal_icon.png")))
    app.setWindowIcon(icon)
    window.setWindowIcon(icon)

    content = window.takeCentralWidget()
    content.setMinimumWidth(0)
    content.setSizePolicy(
        QSizePolicy.Policy.Ignored,
        QSizePolicy.Policy.Preferred,
    )
    scroll_area = QScrollArea(window)
    scroll_area.setFrameShape(QFrame.Shape.NoFrame)
    scroll_area.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
    scroll_area.setWidgetResizable(True)
    scroll_area.setWidget(content)
    window.setCentralWidget(scroll_area)
    window.tidalStatusLabel.setWordWrap(True)
    window.rpcStatusLabel.setWordWrap(True)

    window.richPresenceCheckBox.setChecked(settings.get("rich_presence_enabled"))
    window.checkBox.setChecked(settings.get("show_artist_image"))
    window.checkBox_2.setChecked(settings.get("show_buttons"))
    window.authButton.hide()
    return window


def set_status(label, text, state):
    label.setText(text)
    label.setProperty("state", state)
    label.style().unpolish(label)
    label.style().polish(label)


def update_window(window, state):
    tidal_state = state.get("tidal_state", "missing")
    discord_state = state.get("discord_state", "waiting")
    tidal_text = {
        "missing": "Not running",
        "paused": "Open · Paused",
        "playing": "Playing",
        "error": "Detection error",
    }.get(tidal_state, "Waiting")
    discord_text = {
        "connected": "Connected",
        "waiting": "Waiting for Discord",
        "disabled": "Disabled",
        "error": "Connection error",
    }.get(discord_state, "Waiting")
    set_status(
        window.tidalStatusLabel,
        tidal_text,
        "connected" if tidal_state == "playing" else tidal_state,
    )
    set_status(window.rpcStatusLabel, discord_text, discord_state)
    set_status(
        window.overallStatusLabel,
        state.get("message", "Starting…"),
        "error" if state.get("error") else "normal",
    )

    track = state.get("track") or {}
    title = track.get("title") or "Nothing playing"
    artist = track.get("artist") or "Open TIDAL and start a track"
    window.trackLabel.setText(title)
    window.trackLabel.setToolTip(title)
    window.artistLabel.setText(artist)
    window.artistLabel.setToolTip(artist)


def show_error(window, message):
    QMessageBox.warning(window, "TIDAL RPC", message)
