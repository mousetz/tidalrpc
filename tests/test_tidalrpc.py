import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from app_settings import DEFAULTS, Settings, resource_path
import windows_startup


class SettingsTests(unittest.TestCase):
    def test_defaults_and_round_trip(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.ini"
            settings = Settings(path)
            self.assertEqual(settings.values, DEFAULTS)
            settings.set("rich_presence_enabled", False)
            settings.set("show_buttons", True)
            loaded = Settings(path)
            self.assertFalse(loaded.get("rich_presence_enabled"))
            self.assertTrue(loaded.get("show_buttons"))

    def test_malformed_file_uses_defaults(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.ini"
            path.write_text("[Settings\ninvalid", encoding="utf-8")
            self.assertEqual(Settings(path).values, DEFAULTS)

    def test_legacy_settings_are_migrated(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "settings.ini"
            path.write_text("[UI]\nmy_list = True,False,0\n", encoding="utf-8")
            settings = Settings(path)
            self.assertTrue(settings.get("show_artist_image"))
            self.assertFalse(settings.get("show_buttons"))

    def test_resource_paths_resolve_from_the_application(self):
        self.assertTrue(resource_path("QTdesign.ui").is_file())
        self.assertTrue(resource_path("tidal_icon.png").is_file())


class StartupTests(unittest.TestCase):
    def test_command_quotes_paths_and_starts_minimized(self):
        executable = Path("C:/Program Files/TIDAL RPC/TIDAL RPC.exe")
        command = windows_startup.startup_command(executable)
        self.assertTrue(command.startswith('"'))
        self.assertTrue(command.endswith('" --minimized'))
        self.assertIn("TIDAL RPC.exe", command)

    def test_development_launch_has_no_startup_command(self):
        with mock.patch.object(windows_startup.sys, "frozen", False, create=True):
            self.assertIsNone(windows_startup.packaged_executable())

    def test_registry_state_requires_the_exact_current_command(self):
        with mock.patch.object(windows_startup, "startup_command", return_value='"app.exe" --minimized'):
            with mock.patch.object(windows_startup, "current_command", return_value='"app.exe" --minimized'):
                self.assertTrue(windows_startup.is_enabled())
            with mock.patch.object(windows_startup, "current_command", return_value='"old.exe"'):
                self.assertFalse(windows_startup.is_enabled())


try:
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    from PySide6.QtCore import QEventLoop, QMetaObject, Qt, QThread, QTimer
    from PySide6.QtWidgets import QApplication
    from main import ApplicationController, PlaybackWorker, normalize_external_url
    import main

    QT_AVAILABLE = True
except ImportError:
    QT_AVAILABLE = False


@unittest.skipUnless(QT_AVAILABLE, "PySide6 runtime is not installed")
class WorkerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_worker_can_run_without_blocking_the_gui_thread(self):
        settings = DEFAULTS.copy()
        worker = PlaybackWorker(settings)
        thread = QThread()
        worker.moveToThread(thread)
        gui_timer_fired = []
        loop = QEventLoop()

        def slow_poll(_worker):
            QThread.msleep(80)

        with mock.patch.object(PlaybackWorker, "_create_session", lambda _worker: None):
            with mock.patch.object(PlaybackWorker, "poll", slow_poll):
                thread.started.connect(worker.start)
                QTimer.singleShot(10, lambda: gui_timer_fired.append(True))
                QTimer.singleShot(150, loop.quit)
                thread.start()
                loop.exec()
                QMetaObject.invokeMethod(
                    worker,
                    "stop",
                    Qt.ConnectionType.QueuedConnection,
                )
                thread.wait()
        self.assertEqual(gui_timer_fired, [True])

    def test_presence_messages_cover_missing_and_disabled_states(self):
        self.assertEqual(
            PlaybackWorker._message("missing", "waiting", None),
            "Waiting for TIDAL.",
        )
        self.assertEqual(
            PlaybackWorker._message("playing", "disabled", {"title": "Track"}),
            "Rich Presence is disabled.",
        )

    def test_missing_tidal_and_discord_emit_waiting_state(self):
        worker = PlaybackWorker(DEFAULTS.copy())
        worker.authenticated = True
        states = []
        worker.state_changed.connect(states.append)
        with mock.patch.object(
            main,
            "check_for_tidal",
            return_value={"open": False, "playing": False, "song_playing": None},
        ):
            with mock.patch.object(worker, "_ensure_rpc", return_value=False):
                worker.poll()
        self.assertEqual(states[-1]["tidal_state"], "missing")
        self.assertEqual(states[-1]["discord_state"], "waiting")

    def test_close_hides_and_shutdown_stops_the_worker(self):
        class FakeTray:
            hidden = False

            def hide(self):
                self.hidden = True

            def setToolTip(self, _text):
                pass

            def showMessage(self, *_args):
                pass

        class FakeSignal:
            def connect(self, _slot):
                pass

        class FakeServer:
            newConnection = FakeSignal()
            closed = False

            def close(self):
                self.closed = True

        with tempfile.TemporaryDirectory() as directory:
            server = FakeServer()
            tray = FakeTray()
            settings = Settings(Path(directory) / "settings.ini")
            with mock.patch.object(
                ApplicationController,
                "_create_tray",
                return_value=tray,
            ):
                with mock.patch.object(PlaybackWorker, "start", lambda _worker: None):
                    controller = ApplicationController(
                        self.app,
                        settings,
                        server,
                    )
                    controller.window.close()
                    self.app.processEvents()
                    self.assertFalse(controller.window.isVisible())
                    self.assertTrue(controller.thread.isRunning())
                    controller.shutdown()
                    self.assertFalse(controller.thread.isRunning())
                    self.assertTrue(tray.hidden)
                    self.assertTrue(server.closed)

    def test_scheme_less_tidal_login_url_is_opened_as_https(self):
        self.assertEqual(
            normalize_external_url("link.tidal.com/HGNZK"),
            "https://link.tidal.com/HGNZK",
        )
