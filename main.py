import functools
import asyncio
import logging
import os
import sys
import time

import psutil
import pypresence
from pywinauto import Desktop
import tidalapi
from PySide6.QtCore import QEvent, QObject, QThread, QTimer, QUrl, Signal, Slot
from PySide6.QtGui import QAction, QDesktopServices
from PySide6.QtNetwork import QLocalServer, QLocalSocket
from PySide6.QtWidgets import QApplication, QMenu, QSystemTrayIcon

from app_settings import (
    APP_NAME,
    Settings,
    configure_logging,
    migrate_legacy_session,
    session_path,
)
from ui import create_window, show_error, update_window
import windows_startup


CLIENT_ID = 1411287876062416908
POLL_INTERVAL_MS = 2000
logger = logging.getLogger(__name__)


def normalize_external_url(url):
    url = str(url).strip()
    return url if QUrl(url).scheme() else f"https://{url}"


def check_for_tidal():
    result = {"open": False, "playing": False, "song_playing": None}
    try:
        for window in Desktop(backend="uia").windows():
            try:
                process = psutil.Process(window.process_id())
                if process.pid == os.getpid():
                    continue
                process_name = process.name().casefold().removesuffix(".exe")
                if process_name != "tidal":
                    continue
                result["open"] = True
                title = window.window_text().strip()
                if title and title.casefold() != "tidal":
                    result["playing"] = True
                    result["song_playing"] = title
                    return result
            except (psutil.Error, OSError):
                continue
    except Exception as error:
        logger.warning("TIDAL detection failed: %s", error)
        result["error"] = True
    return result


class PlaybackWorker(QObject):
    state_changed = Signal(dict)
    authentication_required = Signal(str)
    finished = Signal()

    def __init__(self, settings):
        super().__init__()
        self.settings = settings
        self.timer = None
        self.session = None
        self.rpc = None
        self.stopping = False
        self.authenticated = False
        self.auth_link = None
        self.auth_expires_at = 0
        self.auth_retry_at = 0
        self.metadata_title = None
        self.metadata_track = None
        self.metadata_retry_at = 0
        self.previous_state = None
        self.previous_tidal_state = None
        self.previous_rpc_signature = None
        self.presence_active = False
        self.discord_connected = False
        self.reconnect_delay = 5
        self.reconnect_at = 0
        self.last_errors = {}
        self._smtc_manager = None
        self._rpc_start_sent = None
        self._last_smtc_position = None
        self._last_smtc_time = 0.0

    @Slot()
    def start(self):
        logger.info("Application worker started")
        self._create_session()
        self._init_smtc()
        self.timer = QTimer(self)
        self.timer.timeout.connect(self.poll)
        self.timer.start(POLL_INTERVAL_MS)
        self.poll()

    def _create_session(self):
        try:
            self.session = tidalapi.Session()
            request = self.session.request_session.request
            self.session.request_session.request = functools.partial(request, timeout=10)
        except Exception as error:
            self._log_once("auth", "TIDAL session setup failed: %s", error)
            return
        try:
            migrate_legacy_session(self.session)
            saved = session_path()
            if saved.exists():
                self.authenticated = bool(self.session.load_session_from_file(saved))
        except Exception as error:
            self._log_once("auth", "TIDAL authentication failed: %s", error)
        if not self.authenticated:
            self.begin_login()

    def _init_smtc(self):
        try:
            from winrt.windows.media.control import (
                GlobalSystemMediaTransportControlsSessionManager,
            )
            async def _init():
                return await GlobalSystemMediaTransportControlsSessionManager.request_async()
            self._smtc_manager = asyncio.run(_init())
        except Exception as error:
            self._log_once("smtc", "SMTC unavailable: %s", error)
            self._smtc_manager = None

    def _get_smtc_position(self):
        if not self._smtc_manager:
            return None
        try:
            session = self._smtc_manager.get_current_session()
            if not session:
                return None
            timeline = session.get_timeline_properties()
            return timeline.position.total_seconds(), timeline.end_time.total_seconds()
        except Exception:
            return None

    @Slot()
    def begin_login(self):
        if self.stopping or self.authenticated:
            return
        if self.auth_link:
            self.authentication_required.emit(self.auth_link.verification_uri_complete)
            return
        try:
            getter = getattr(self.session, "get_link_login", None)
            if getter is None:
                getter = self.session._get_link_login
            self.auth_link = getter()
            self.auth_retry_at = 0
            self.auth_expires_at = time.monotonic() + float(self.auth_link.expires_in)
            self.authentication_required.emit(self.auth_link.verification_uri_complete)
            logger.info("TIDAL sign-in requested")
        except Exception as error:
            self._log_once("auth", "Could not begin TIDAL sign-in: %s", error)

    def _poll_authentication(self):
        if not self.auth_link or time.monotonic() < self.auth_retry_at:
            return
        if time.monotonic() >= self.auth_expires_at:
            self.auth_link = None
            self.begin_login()
            return
        self.auth_retry_at = time.monotonic() + max(float(self.auth_link.interval), 2)
        try:
            processor = getattr(self.session, "process_link_login", None)
            if processor is None:
                processor = self.session._process_link_login
            if not processor(self.auth_link, until_expiry=False):
                return
            self.authenticated = True
            self.auth_link = None
            self.auth_expires_at = 0
            self.metadata_retry_at = 0
            temporary = session_path().with_suffix(".tmp")
            self.session.save_session_to_file(temporary)
            os.replace(temporary, session_path())
            logger.info("TIDAL sign-in completed")
        except TimeoutError:
            pass
        except Exception as error:
            self._log_once("auth_poll", "TIDAL sign-in check failed: %s", error)

    @Slot(bool)
    def set_enabled(self, enabled):
        self.settings["rich_presence_enabled"] = enabled
        self.previous_rpc_signature = None
        if not enabled:
            self._disconnect_rpc(clear=True)
        self.poll()

    @Slot(bool, bool)
    def set_display_options(self, show_artist_image, show_buttons):
        if self.settings["show_artist_image"] != show_artist_image:
            self.metadata_title = None
        self.settings["show_artist_image"] = show_artist_image
        self.settings["show_buttons"] = show_buttons
        self.previous_rpc_signature = None
        self.poll()

    @Slot()
    def poll(self):
        if self.stopping:
            return
        try:
            if not self.authenticated:
                self._poll_authentication()
            tidal = check_for_tidal()
            tidal_state = (
                "error"
                if tidal.get("error")
                else "playing"
                if tidal["playing"]
                else "paused"
                if tidal["open"]
                else "missing"
            )
            if tidal_state != self.previous_tidal_state:
                logger.info("TIDAL state changed: %s", tidal_state)
                self.previous_tidal_state = tidal_state

            track = None
            if tidal["playing"]:
                track = self._get_track(tidal["song_playing"])

            enabled = self.settings["rich_presence_enabled"]
            if not enabled:
                self._disconnect_rpc(clear=True)
                discord_state = "disabled"
            elif self._ensure_rpc():
                discord_state = "connected"
                if track:
                    self._update_rpc(track)
                    smtc = self._get_smtc_position()
                    if smtc and self._rpc_start_sent is not None:
                        pos, _ = smtc
                        now = time.monotonic()
                        if self._last_smtc_position is not None:
                            elapsed = now - self._last_smtc_time
                            delta = pos - self._last_smtc_position
                            if delta < -2 or delta > elapsed + 5:
                                corrected_start = int(time.time()) - int(pos)
                                self._update_rpc(track, start_override=corrected_start)
                                print(f"SEEK detected: {self._last_smtc_position:.0f}s -> {int(pos)}s", flush=True)
                        self._last_smtc_position = pos
                        self._last_smtc_time = now
                else:
                    self._clear_presence()
                if not self.discord_connected:
                    discord_state = "waiting"
            else:
                discord_state = "waiting"

            message = self._message(tidal_state, discord_state, track)
            self._emit_state(tidal_state, discord_state, track, message)
        except Exception as error:
            self._log_once("worker", "Unexpected worker failure: %s", error)
            self._emit_state("error", "error", None, "A temporary background error occurred.", True)

    def _get_track(self, title):
        fallback = {
            "id": title,
            "title": title,
            "artist": "Unknown artist",
            "album": None,
            "album_image": None,
            "artist_image": None,
            "duration": None,
            "url": None,
        }
        if not self.authenticated:
            return fallback
        if title == self.metadata_title and self.metadata_track:
            return self.metadata_track
        if time.monotonic() < self.metadata_retry_at:
            return fallback
        try:
            results = self.session.search(
                title,
                models=[tidalapi.media.Track],
                limit=5,
            )
            song = results.get("tracks", [None])[0] if results.get("tracks") else None
            if not song:
                self.metadata_retry_at = time.monotonic() + 10
                return fallback
            artists = [artist.name for artist in song.artists or [] if artist.name]
            album_image = None
            artist_image = None
            try:
                album_image = song.album.image() if song.album else None
            except Exception:
                pass
            if self.settings["show_artist_image"] and song.artists:
                try:
                    artist_image = song.artists[0].image()
                except Exception:
                    pass
            track = {
                "id": song.id,
                "title": song.name or title,
                "artist": ", ".join(artists) or "Unknown artist",
                "album": song.album.name if song.album else None,
                "album_image": album_image,
                "artist_image": artist_image,
                "duration": song.duration if song.duration and song.duration > 0 else None,
                "url": f"https://tidal.com/browse/track/{song.id}",
            }
            self.metadata_title = title
            self.metadata_track = track
            self.metadata_retry_at = 0
            self.last_errors.pop("metadata", None)
            logger.info("Track changed: %s — %s", track["title"], track["artist"])
            return track
        except Exception as error:
            self.metadata_retry_at = time.monotonic() + 10
            self._log_once("metadata", "Track metadata lookup failed: %s", error)
            return fallback

    def _ensure_rpc(self):
        if self.discord_connected:
            return True
        if time.monotonic() < self.reconnect_at:
            return False
        try:
            self.rpc = pypresence.Presence(CLIENT_ID)
            self.rpc.connect()
            self.discord_connected = True
            self.reconnect_delay = 5
            self.last_errors.pop("discord", None)
            logger.info("Discord RPC connected")
            return True
        except Exception as error:
            self._handle_rpc_failure(error)
            return False

    def _update_rpc(self, track, start_override=None):
        signature = (
            track["id"],
            track["title"],
            track["artist"],
            self.settings["show_artist_image"],
            self.settings["show_buttons"],
        )
        if start_override is None and signature == self.previous_rpc_signature:
            return
        if self.previous_rpc_signature is not None and signature != self.previous_rpc_signature:
            self._last_smtc_position = None
            self._last_smtc_time = 0.0
        now = int(time.time())
        start = start_override if start_override is not None else now
        payload = {
            "activity_type": pypresence.ActivityType.LISTENING,
            "details": str(track["title"])[:128],
            "state": str(track["artist"])[:128],
            "large_image": track["album_image"] or "hightide_x1024",
            "large_text": str(track["album"] or "TIDAL")[:128],
            "small_image": track["artist_image"] if self.settings["show_artist_image"] else None,
            "small_text": "TIDAL RPC" if track["artist_image"] else None,
            "start": start,
            "end": start + int(track["duration"]) if track["duration"] else None,
        }
        if self.settings["show_buttons"]:
            buttons = []
            if track["url"]:
                buttons.append({"label": "Listen on TIDAL", "url": track["url"]})
            buttons.append(
                {
                    "label": "Get TIDAL RPC",
                    "url": "https://github.com/mousetz/tidalrpc",
                }
            )
            payload["buttons"] = buttons
        try:
            self.rpc.update(**payload)
            self.previous_rpc_signature = signature
            self._rpc_start_sent = start
            self.presence_active = True
        except Exception as error:
            self._handle_rpc_failure(error)

    def _clear_presence(self):
        if not self.rpc or not self.presence_active:
            return
        try:
            self.rpc.clear()
        except Exception as error:
            self._handle_rpc_failure(error)
            return
        self.presence_active = False
        self.previous_rpc_signature = None
        self._rpc_start_sent = None

    def _handle_rpc_failure(self, error):
        self._log_once("discord", "Discord RPC unavailable: %s", error)
        self._disconnect_rpc()
        self.reconnect_at = time.monotonic() + self.reconnect_delay
        self.reconnect_delay = min(self.reconnect_delay * 2, 60)

    def _disconnect_rpc(self, clear=False):
        rpc = self.rpc
        self.rpc = None
        was_connected = self.discord_connected
        self.discord_connected = False
        self.previous_rpc_signature = None
        self.presence_active = False
        self._rpc_start_sent = None
        if rpc:
            if clear:
                try:
                    rpc.clear()
                except Exception:
                    pass
            try:
                rpc.close()
            except Exception:
                pass
        if was_connected:
            logger.info("Discord RPC disconnected")

    def _emit_state(self, tidal_state, discord_state, track, message, error=False):
        state = {
            "tidal_state": tidal_state,
            "discord_state": discord_state,
            "track": track,
            "message": message,
            "error": error,
            "authenticated": self.authenticated,
        }
        if state != self.previous_state:
            self.previous_state = state
            self.state_changed.emit(state)

    @staticmethod
    def _message(tidal_state, discord_state, track):
        if discord_state == "disabled":
            return "Rich Presence is disabled."
        if tidal_state == "missing":
            return "Waiting for TIDAL."
        if tidal_state == "paused":
            return "TIDAL is paused."
        if discord_state != "connected":
            return "Waiting for Discord."
        if track:
            return "Rich Presence is active."
        return "Ready."

    def _log_once(self, key, message, error):
        text = str(error)
        if self.last_errors.get(key) != text:
            logger.warning(message, error)
            self.last_errors[key] = text

    @Slot()
    def stop(self):
        if self.stopping:
            return
        self.stopping = True
        if self.timer:
            self.timer.stop()
        self._disconnect_rpc(clear=True)
        if self.session:
            try:
                self.session.request_session.close()
            except Exception:
                pass
        logger.info("Application worker stopped")
        QThread.currentThread().quit()
        self.finished.emit()


class ApplicationController(QObject):
    set_enabled = Signal(bool)
    set_display_options = Signal(bool, bool)
    request_login = Signal()
    stop_worker = Signal()

    def __init__(self, app, settings, server, start_minimized=False):
        super().__init__(app)
        self.app = app
        self.settings = settings
        self.server = server
        self.window = create_window(app, settings)
        self.window.installEventFilter(self)
        self.quitting = False
        self.auth_url = None

        self.thread = QThread(self)
        worker_settings = settings.values.copy()
        self.worker = PlaybackWorker(worker_settings)
        self.worker.moveToThread(self.thread)
        self.thread.started.connect(self.worker.start)
        self.worker.state_changed.connect(self.on_state_changed)
        self.worker.authentication_required.connect(self.on_authentication_required)
        self.set_enabled.connect(self.worker.set_enabled)
        self.set_display_options.connect(self.worker.set_display_options)
        self.request_login.connect(self.worker.begin_login)
        self.stop_worker.connect(self.worker.stop)

        self.window.richPresenceCheckBox.toggled.connect(self.on_enabled_changed)
        self.window.checkBox.toggled.connect(self.on_display_options_changed)
        self.window.checkBox_2.toggled.connect(self.on_display_options_changed)
        self.window.startupCheckBox.toggled.connect(self.on_startup_changed)
        self.window.authButton.clicked.connect(self.open_authentication)

        self.tray = self._create_tray()
        self._configure_startup_checkbox()
        self.server.newConnection.connect(self.on_instance_message)
        self.app.aboutToQuit.connect(self.shutdown)
        self.thread.start()

        if start_minimized and self.tray:
            self.window.hide()
        else:
            self.show_window()

    def _create_tray(self):
        if not QSystemTrayIcon.isSystemTrayAvailable():
            self.app.setQuitOnLastWindowClosed(True)
            return None
        tray = QSystemTrayIcon(self.app.windowIcon(), self)
        menu = QMenu(self.window)
        show_action = QAction("Open TIDAL RPC", menu)
        show_action.triggered.connect(self.show_window)
        self.tray_enabled_action = QAction("Enable Rich Presence", menu)
        self.tray_enabled_action.setCheckable(True)
        self.tray_enabled_action.setChecked(self.settings.get("rich_presence_enabled"))
        self.tray_enabled_action.toggled.connect(self.window.richPresenceCheckBox.setChecked)
        quit_action = QAction("Quit", menu)
        quit_action.triggered.connect(self.shutdown)
        menu.addAction(show_action)
        menu.addAction(self.tray_enabled_action)
        menu.addSeparator()
        menu.addAction(quit_action)
        tray.setContextMenu(menu)
        tray.setToolTip(APP_NAME)
        tray.activated.connect(self.on_tray_activated)
        tray.show()
        self.app.setQuitOnLastWindowClosed(False)
        return tray

    def _configure_startup_checkbox(self):
        available = windows_startup.packaged_executable() is not None
        checkbox = self.window.startupCheckBox
        checkbox.blockSignals(True)
        checkbox.setChecked(windows_startup.is_enabled() if available else False)
        checkbox.blockSignals(False)
        checkbox.setEnabled(available)
        if not available:
            checkbox.setToolTip("Available in the packaged Windows application.")

    @Slot(bool)
    def on_enabled_changed(self, enabled):
        try:
            self.settings.set("rich_presence_enabled", enabled)
        except OSError as error:
            show_error(self.window, f"Could not save the setting: {error}")
        if hasattr(self, "tray_enabled_action"):
            self.tray_enabled_action.blockSignals(True)
            self.tray_enabled_action.setChecked(enabled)
            self.tray_enabled_action.blockSignals(False)
        self.set_enabled.emit(enabled)

    @Slot()
    def on_display_options_changed(self):
        show_artist = self.window.checkBox.isChecked()
        show_buttons = self.window.checkBox_2.isChecked()
        try:
            self.settings.update(
                show_artist_image=show_artist,
                show_buttons=show_buttons,
            )
        except OSError as error:
            show_error(self.window, f"Could not save the settings: {error}")
        self.set_display_options.emit(show_artist, show_buttons)

    @Slot(bool)
    def on_startup_changed(self, enabled):
        checkbox = self.window.startupCheckBox
        try:
            windows_startup.set_enabled(enabled)
            logger.info("Start with Windows changed: %s", enabled)
        except OSError as error:
            checkbox.blockSignals(True)
            checkbox.setChecked(windows_startup.is_enabled())
            checkbox.blockSignals(False)
            show_error(self.window, str(error))

    @Slot(dict)
    def on_state_changed(self, state):
        update_window(self.window, state)
        if self.tray:
            track = state.get("track") or {}
            self.tray.setToolTip(track.get("title") or APP_NAME)
        self.window.authButton.setVisible(not state.get("authenticated", False))

    @Slot(str)
    def on_authentication_required(self, url):
        url = normalize_external_url(url)
        first_url = url != self.auth_url
        self.auth_url = url
        self.window.authButton.setText("Sign in to TIDAL")
        self.window.authButton.show()
        if first_url:
            QDesktopServices.openUrl(QUrl(url))

    @Slot()
    def open_authentication(self):
        if self.auth_url:
            QDesktopServices.openUrl(QUrl(self.auth_url))
        else:
            self.request_login.emit()

    @Slot()
    def show_window(self):
        self.window.showNormal()
        self.window.raise_()
        self.window.activateWindow()

    def on_tray_activated(self, reason):
        if reason in (
            QSystemTrayIcon.ActivationReason.Trigger,
            QSystemTrayIcon.ActivationReason.DoubleClick,
        ):
            self.show_window()

    @Slot()
    def on_instance_message(self):
        while self.server.hasPendingConnections():
            connection = self.server.nextPendingConnection()
            connection.waitForReadyRead(100)
            if bytes(connection.readAll()).strip() == b"show":
                self.show_window()
            connection.disconnectFromServer()

    def eventFilter(self, watched, event):
        if watched is self.window and event.type() == QEvent.Type.Close and not self.quitting:
            if self.tray:
                event.ignore()
                self.window.hide()
                if not self.settings.get("tray_notice_shown"):
                    if QSystemTrayIcon.supportsMessages():
                        self.tray.showMessage(
                            APP_NAME,
                            "TIDAL RPC is still running in the system tray.",
                            QSystemTrayIcon.MessageIcon.Information,
                            4000,
                        )
                        try:
                            self.settings.set("tray_notice_shown", True)
                        except OSError:
                            pass
                return True
            self.shutdown()
        return super().eventFilter(watched, event)

    @Slot()
    def shutdown(self):
        if self.quitting:
            return
        self.quitting = True
        logger.info("Application shutdown started")
        if self.tray:
            self.tray.hide()
        self.stop_worker.emit()
        if self.thread.isRunning() and not self.thread.wait(15_000):
            logger.error("Background worker did not stop before shutdown timeout")
        self.server.close()
        self.window.removeEventFilter(self)
        self.window.close()
        self.app.quit()
        logger.info("Application shutdown completed")


def acquire_instance():
    name = "tidalrpc-single-instance"
    socket = QLocalSocket()
    socket.connectToServer(name)
    if socket.waitForConnected(300):
        socket.write(b"show")
        socket.waitForBytesWritten(300)
        socket.disconnectFromServer()
        return None
    server = QLocalServer()
    if not server.listen(name):
        QLocalServer.removeServer(name)
        if not server.listen(name):
            raise RuntimeError("Could not create the application instance lock.")
    return server


def main():
    configure_logging()
    logger.info("Application startup")
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    app.setOrganizationName("TIDAL RPC")
    try:
        server = acquire_instance()
        if server is None:
            return 0
        controller = ApplicationController(
            app,
            Settings(),
            server,
            start_minimized="--minimized" in sys.argv,
        )
        return app.exec()
    except Exception as error:
        logger.exception("Application startup failed")
        from PySide6.QtWidgets import QMessageBox

        QMessageBox.critical(None, APP_NAME, f"TIDAL RPC could not start:\n{error}")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
