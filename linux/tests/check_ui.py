"""Run under xvfb-run on Linux; exercise real GTK layout and invalid settings."""
import os
from pathlib import Path
import sys
import tempfile
import time
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from adb_ssh_tunnel.core import Network, config_dir, default_config, save_config
from adb_ssh_tunnel.ui import Application, Adw, GLib, Gtk, PowerGlow


def settle():
    until = time.monotonic() + 0.4
    while time.monotonic() < until:
        while GLib.MainContext.default().pending():
            GLib.MainContext.default().iteration(False)
        time.sleep(0.01)


def wait_for(condition, seconds=3.0):
    until = time.monotonic() + seconds
    while time.monotonic() < until and not condition():
        while GLib.MainContext.default().pending():
            GLib.MainContext.default().iteration(False)
        time.sleep(0.01)
    return condition()


def check_glow_pacing():
    """Per-frame redraws only while the glow moves; steady breathing is throttled."""
    settings = Gtk.Settings.get_default()
    settings.set_property('gtk-enable-animations', True)
    window = Gtk.Window(default_width=300, default_height=300)
    glow = PowerGlow('#ffffff')
    window.set_child(glow)
    window.present()
    settle()
    glow.set_tone('working')
    settle()
    assert glow.tick_id and not glow.breath_id, 'The connecting spin uses every frame'
    glow.set_tone('ready')
    assert wait_for(lambda: not glow.tick_id and glow.breath_id), 'Settled ready state must switch to slow breathing'
    draws = []
    glow.queue_draw = lambda: draws.append(time.monotonic())
    wait_for(lambda: False, 1.0)
    del glow.queue_draw
    assert 5 <= len(draws) <= PowerGlow.BREATH_FPS + 3, f'Breathing redraws per second: {len(draws)}'
    window.set_visible(False)
    settle()
    assert not glow.breath_id and not glow.tick_id, 'A hidden button must not redraw'
    window.set_visible(True)
    assert wait_for(lambda: glow.breath_id), 'Breathing resumes when the button is shown again'
    for tone in ('attention', 'off'):
        glow.set_tone(tone)
        assert wait_for(lambda: not glow.tick_id and not glow.breath_id), f'A still {tone} state must stop redrawing'
    assert glow.glow == 0.0
    settings.set_property('gtk-enable-animations', False)
    glow.set_tone('ready')
    settle()
    assert not glow.tick_id and not glow.breath_id, 'Reduced motion keeps the button still'
    settings.set_property('gtk-enable-animations', True)
    window.destroy()
    print(f'GTK glow pacing: spin per frame, breathing {len(draws)}/s, idle when hidden or still')


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
            for opener in (app.open_connect, app.open_networks, app.open_add_key, app.open_port, app.open_more):
                opener()
                settle()
                assert app.window.get_width() <= width, (opener.__name__, language, width, app.window.get_width())
                app.nav.pop_to_tag('main')
            settle()
            print(f'GTK {language} {width}px: layout, controls and deeper pages OK')
        # Linux phones: the whole main screen fits a short window without scrolling.
        app.window.set_default_size(360, 680)
        settle()
        scroll = app.main_scroll.get_vadjustment()
        assert app.compact, 'A short window must switch to the compact main screen'
        assert scroll.get_upper() <= scroll.get_page_size() + 1, (language, scroll.get_upper(), scroll.get_page_size())
        app.window.set_default_size(420, 800)
        settle()
        assert not app.compact, 'A tall window must leave the compact main screen'
        print(f'GTK {language} 360x680: compact main screen fits without scrolling')
    # A failed write must show its own reason, not the port-range hint.
    app.open_port()
    settle()
    app.port.set_text('19192')
    with patch('adb_ssh_tunnel.ui.save_config', side_effect=OSError('No space left on device')):
        app.change_port(app.port)
    assert 'No space left on device' in app.port_error.get_text(), app.port_error.get_text()
    assert app.cfg['port'] != 19192, 'A port that was not saved must not be shown as saved'
    app.port.set_text('80')
    app.change_port(app.port)
    assert '1024' in app.port_error.get_text(), app.port_error.get_text()
    app.nav.pop_to_tag('main')
    print('GTK port errors: write failure and invalid number are told apart')
    check_glow_pacing()
    app.window.destroy()
    app.quit()
