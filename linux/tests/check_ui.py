"""Run under xvfb-run on Linux; exercise real GTK layout and invalid settings."""
import os
from pathlib import Path
import sys
import tempfile
import time
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from adb_ssh_tunnel.core import Network, config_dir, default_config, save_config
from adb_ssh_tunnel.ui import Application, Adw, GLib


def settle():
    until = time.monotonic() + 0.4
    while time.monotonic() < until:
        while GLib.MainContext.default().pending():
            GLib.MainContext.default().iteration(False)
        time.sleep(0.01)


with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, {'XDG_CONFIG_HOME': tmp}), \
        patch('adb_ssh_tunnel.ui.current_network', return_value=Network('wifi:Test network', '127.0.0.1', 'test0')):
    config_dir().mkdir()
    path = config_dir() / 'config.json'
    path.write_text('{broken')
    app = Application()
    app.register(None)
    app.activate()
    settle()
    assert isinstance(app.window.get_content(), Adw.StatusPage)
    assert path.read_text() == '{broken', 'Invalid settings must not be replaced'
    path.write_text('[]')
    app.open_settings()
    assert isinstance(app.window.get_content(), Adw.StatusPage)
    assert path.read_text() == '[]'
    for language in ('en', 'ru'):
        save_config(default_config() | {'language': language})
        app.open_settings()
        for width in (360, 1024):
            app.window.set_default_size(width, 800)
            settle()
            assert app.window.get_width() <= width, (language, width, app.window.get_width())
            assert app.status.get_text(), 'The status must be visible'
            assert app.toggle.get_sensitive(), 'The start/stop control must be available'
            print(f'GTK {language} {width}px: layout and controls OK')
    app.window.destroy()
    app.quit()
