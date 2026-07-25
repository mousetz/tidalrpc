import configparser
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import tempfile


APP_NAME = "TIDAL RPC"
DEFAULTS = {
    "rich_presence_enabled": True,
    "tray_notice_shown": False,
    "show_artist_image": False,
    "show_buttons": False,
}


def resource_path(name):
    return Path(__file__).resolve().parent / name


def app_data_dir(base=None):
    if base:
        path = Path(base)
    elif os.name == "nt":
        path = Path(os.environ.get("LOCALAPPDATA", Path.home())) / APP_NAME
    else:
        path = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config")) / "tidalrpc"
    path.mkdir(parents=True, exist_ok=True)
    return path


class Settings:
    def __init__(self, path=None):
        self.path = Path(path) if path else app_data_dir() / "settings.ini"
        self.values = DEFAULTS.copy()
        self.load()

    def load(self):
        self.values = DEFAULTS.copy()
        config = configparser.ConfigParser()
        try:
            if self.path.exists():
                config.read(self.path, encoding="utf-8")
            else:
                config.read(resource_path("settings.ini"), encoding="utf-8")
            if "Settings" in config:
                for key, default in DEFAULTS.items():
                    self.values[key] = config["Settings"].getboolean(key, fallback=default)
            elif "UI" in config and "my_list" in config["UI"]:
                legacy = [part.strip() == "True" for part in config["UI"]["my_list"].split(",")]
                if legacy:
                    self.values["show_artist_image"] = legacy[0]
                if len(legacy) > 1:
                    self.values["show_buttons"] = legacy[1]
        except (OSError, configparser.Error, ValueError):
            self.values = DEFAULTS.copy()
        return self

    def get(self, key):
        return self.values[key]

    def set(self, key, value):
        self.update(**{key: value})

    def update(self, **values):
        if any(key not in DEFAULTS for key in values):
            raise KeyError(next(key for key in values if key not in DEFAULTS))
        previous = self.values.copy()
        self.values.update({key: bool(value) for key, value in values.items()})
        try:
            self.save()
        except Exception:
            self.values = previous
            raise

    def save(self):
        config = configparser.ConfigParser()
        config["Settings"] = {key: str(value) for key, value in self.values.items()}
        self.path.parent.mkdir(parents=True, exist_ok=True)
        descriptor, temporary_name = tempfile.mkstemp(
            dir=self.path.parent,
            prefix=f"{self.path.name}.",
            suffix=".tmp",
        )
        try:
            with os.fdopen(descriptor, "w", encoding="utf-8") as file:
                config.write(file)
                file.flush()
                os.fsync(file.fileno())
            os.replace(temporary_name, self.path)
        except Exception:
            try:
                os.unlink(temporary_name)
            except OSError:
                pass
            raise


def session_path():
    return app_data_dir() / "tidal-session.json"


def migrate_legacy_session(session, legacy_path=None, destination=None):
    legacy_path = Path(legacy_path or resource_path("credentials.ini"))
    destination = Path(destination or session_path())
    if destination.exists() or not legacy_path.exists():
        return destination.exists()
    try:
        token_type, access_token, _expiry_time, refresh_token = (
            line.strip() for line in legacy_path.read_text(encoding="utf-8").splitlines()[:4]
        )
        if not all((token_type, access_token, refresh_token)):
            return False
        session.load_oauth_session(token_type, access_token, refresh_token)
        if not session.check_login():
            return False
        destination.parent.mkdir(parents=True, exist_ok=True)
        temporary = destination.with_suffix(".tmp")
        session.save_session_to_file(temporary)
        os.replace(temporary, destination)
        return True
    except Exception:
        return False


def configure_logging(log_path=None):
    path = Path(log_path or app_data_dir() / "tidalrpc.log")
    handler = RotatingFileHandler(path, maxBytes=512_000, backupCount=1, encoding="utf-8")
    handler.setFormatter(logging.Formatter("%(asctime)s - %(levelname)s - %(message)s"))
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    logging.getLogger("tidalapi").setLevel(logging.WARNING)
    if not any(isinstance(item, RotatingFileHandler) for item in root.handlers):
        root.addHandler(handler)
    return path
