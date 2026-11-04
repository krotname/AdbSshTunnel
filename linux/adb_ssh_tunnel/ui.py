"""Adaptive GTK UI. All service and settings operations are per-user."""
from __future__ import annotations

import locale
from pathlib import Path
import subprocess
import threading

import gi
gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
from gi.repository import Adw, Gio, GLib, Gtk, Pango

from . import __version__
from .core import (APP_ID, USERNAME, config_dir, current_network, default_config,
                   load_config, normalize_key, read_status, save_config)
import asyncssh


class Application(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.window = None
        self.busy = False

    def t(self, en, ru):
        lang = self.cfg.get('language', 'system')
        russian = lang == 'ru' or (lang == 'system' and (locale.getlocale()[0] or '').startswith('ru'))
        return ru if russian else en

    def label(self, text='', css=None, selectable=False):
        item = Gtk.Label(label=text, xalign=0, wrap=True, selectable=selectable)
        item.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
        if css:
            item.add_css_class(css)
        return item

    def card(self, title):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
        for prop in ('top', 'bottom', 'start', 'end'):
            getattr(box, 'set_margin_' + prop)(14)
        box.append(self.label(title, 'heading'))
        frame = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        frame.add_css_class('card')
        frame.append(box)
        self.content.append(frame)
        return box

    def button(self, text, callback, css=None):
        button = Gtk.Button(label=text)
        if css:
            button.add_css_class(css)
        button.connect('clicked', callback)
        return button

    def do_activate(self):
        if self.window:
            self.window.present()
            return
        self.window = Adw.ApplicationWindow(application=self, title='ADB SSH Tunnel')
        self.window.set_default_size(420, 800)
        self.open_settings()
        self.window.present()

    def open_settings(self):
        try:
            self.cfg = load_config()
        except (OSError, ValueError) as exc:
            self.cfg = default_config()
            page = Adw.StatusPage(title=self.t('Cannot load settings', 'Ошибка настроек'),
                                 description=str(exc)[:300], icon_name='dialog-error-symbolic')
            page.set_child(self.button(self.t('Retry', 'Повторить'), lambda _: self.open_settings()))
            self.window.set_content(page)
            return
        self.build()
        if getattr(self, 'refresh_timer', None):
            GLib.source_remove(self.refresh_timer)
        self.refresh_timer = GLib.timeout_add_seconds(2, self.refresh)
        self.refresh()

    def build(self):
        self.window.set_focus(None)
        root = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
        header = Adw.HeaderBar()
        header.set_title_widget(Adw.WindowTitle(title='ADB SSH Tunnel', subtitle=f'Linux {__version__}'))
        root.append(header)
        self.toast = Adw.ToastOverlay()
        root.append(self.toast)
        self.toast.set_vexpand(True)
        scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER)
        self.toast.set_child(scroll)
        clamp = Adw.Clamp(maximum_size=720, tightening_threshold=480)
        scroll.set_child(clamp)
        self.content = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        for prop in ('top', 'bottom', 'start', 'end'):
            getattr(self.content, 'set_margin_' + prop)(16)
        clamp.set_child(self.content)
        self.window.set_content(root)

        box = self.card(self.t('Tunnel', 'Туннель'))
        self.status = self.label('', 'title-3')
        box.append(self.status)
        self.details = self.label('', selectable=True)
        box.append(self.details)
        self.toggle = self.button('', self.toggle_tunnel, 'suggested-action')
        box.append(self.toggle)
        self.autostart = Gtk.Switch(valign=Gtk.Align.CENTER)
        try:
            enabled = subprocess.run(['systemctl', '--user', 'is-enabled', 'adb-ssh-tunnel.service'],
                                     capture_output=True, text=True, timeout=2).stdout.strip() == 'enabled'
        except (OSError, subprocess.SubprocessError):
            enabled = False
        self.autostart.set_active(enabled)
        row = Gtk.Box(spacing=12)
        caption = self.label(self.t('Start at sign-in', 'Запуск при входе'))
        caption.set_hexpand(True)
        row.append(caption)
        row.append(self.autostart)
        box.append(row)
        self.autostart.connect('notify::active', self.change_autostart)

        box = self.card(self.t('Allowed networks', 'Разрешённые сети'))
        self.network_label = self.label('', selectable=True)
        box.append(self.network_label)
        box.append(self.button(self.t('Allow current network', 'Разрешить текущую сеть'),
                               lambda _: self.rule_current('allow')))
        box.append(self.button(self.t('Block current network', 'Заблокировать текущую сеть'),
                               lambda _: self.rule_current('deny')))
        self.mode = Gtk.DropDown.new_from_strings([
            self.t('Allowlist', 'Список разрешённых'),
            self.t('Blocklist', 'Кроме заблокированных'),
            self.t('Disabled', 'Все сети закрыты')])
        self.mode.set_selected(('allowlist', 'blocklist', 'disabled').index(self.cfg['mode']))
        box.append(self.mode)
        self.mode.connect('notify::selected', self.change_mode)
        self.rules_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
        box.append(self.rules_box)
        box.append(self.label(self.t('Blocked entries win. Unknown networks stay blocked. '
                                    'Wi-Fi uses SSID; Ethernet/mobile use NetworkManager profile UUID.',
                                    'Блокировка приоритетнее разрешения. Неизвестные сети закрыты. '
                                    'Wi-Fi — по SSID; Ethernet и мобильная сеть — по UUID профиля NetworkManager.'),
                              'dim-label'))
        self.render_rules()

        box = self.card(self.t('Computer public keys', 'Публичные ключи компьютеров'))
        box.append(self.label(self.t('Keep private keys on the computer. Import one OpenSSH .pub key.',
                                    'Приватный ключ оставьте на компьютере. Импортируйте публичный ключ OpenSSH (.pub).')))
        box.append(self.button(self.t('Import public key', 'Импортировать ключ'), self.key_dialog))
        self.keys_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        box.append(self.keys_box)
        self.render_keys()

        box = self.card(self.t('Connect from a computer', 'Подключение с компьютера'))
        box.append(self.label(self.t('Verify the host fingerprint below through a trusted connection.',
                                    'Перед подключением сверьте отпечаток сервера ниже по доверенному каналу.')))
        self.commands = self.label('', 'monospace', True)
        box.append(self.commands)
        self.host_label = self.label('', 'dim-label', True)
        box.append(self.host_label)
        box.append(self.label(self.t('This is the Linux ADB server, not Android adbd. Use adb -H/-P, '
                                    'not adb connect. An empty device list means no Android device is attached.',
                                    'Это сервер ADB в Linux, а не Android adbd. Используйте adb -H/-P. '
                                    'Пустой список устройств означает, что Android не подключён.')))

        box = self.card(self.t('Settings', 'Настройки'))
        box.append(self.label(self.t('SSH port (1024–65535, except 5037)', 'Порт SSH (1024–65535, кроме 5037)')))
        self.port = Gtk.Entry(text=str(self.cfg['port']), input_purpose=Gtk.InputPurpose.DIGITS)
        box.append(self.port)
        box.append(self.button(self.t('Save port', 'Сохранить порт'), self.change_port))
        box.append(self.label(self.t('Language', 'Язык')))
        language = Gtk.DropDown.new_from_strings(['System / Системный', 'English', 'Русский'])
        language.set_selected(('system', 'en', 'ru').index(self.cfg['language']))
        box.append(language)
        language.connect('notify::selected', self.change_language)
        box.append(self.label(self.t('Key-only access to 127.0.0.1:5037. SSH shell, PTY, SFTP/SCP, '
                                    'agent, X11 and reverse forwarding are disabled. '
                                    'System SSH is separate. Closing this window keeps an enabled tunnel running.',
                                    'Только ключи и 127.0.0.1:5037. SSH shell, PTY, SFTP/SCP, агент, X11 '
                                    'и обратные туннели запрещены. Системный SSH работает отдельно. '
                                    'При закрытии окна включённый туннель продолжает работать.'), 'dim-label'))

    def message(self, text):
        self.toast.add_toast(Adw.Toast(title=text))

    def persist(self):
        try:
            save_config(self.cfg)
            return True
        except (OSError, ValueError) as exc:
            self.message(str(exc))
            self.cfg = load_config()
            return False

    def service(self, action, callback=None):
        self.busy = True
        self.toggle.set_sensitive(False)

        def worker():
            try:
                result = subprocess.run(['systemctl', '--user', action, 'adb-ssh-tunnel.service'],
                                        check=True, capture_output=True, text=True, timeout=15)
                error = None
            except (OSError, subprocess.SubprocessError) as exc:
                error = str(exc)
            def done():
                self.busy = False
                self.toggle.set_sensitive(True)
                if error:
                    self.message(self.t('Service operation failed: ', 'Ошибка службы: ') + error)
                elif callback:
                    callback()
                self.refresh()
                return False
            GLib.idle_add(done)
        threading.Thread(target=worker, daemon=True).start()

    def toggle_tunnel(self, _):
        self.cfg['enabled'] = not self.cfg['enabled']
        if self.persist():
            self.service('start' if self.cfg['enabled'] else 'stop')

    def change_autostart(self, switch, _):
        self.service('enable' if switch.get_active() else 'disable')

    def change_port(self, _):
        try:
            self.cfg['port'] = int(self.port.get_text())
            if self.persist():
                self.message(self.t('Port saved', 'Порт сохранён'))
        except ValueError:
            self.message(self.t('Enter a valid port', 'Введите корректный порт'))

    def change_mode(self, dropdown, _):
        self.cfg['mode'] = ('allowlist', 'blocklist', 'disabled')[dropdown.get_selected()]
        self.persist()

    def change_language(self, dropdown, _):
        self.cfg['language'] = ('system', 'en', 'ru')[dropdown.get_selected()]
        if self.persist():
            self.build()
            self.refresh()

    def rule_current(self, kind):
        if not getattr(self, 'network', None) or not self.network.identity:
            self.message(self.t('Current network is unknown', 'Текущая сеть не определена'))
            return
        identity = self.network.identity
        other = 'deny' if kind == 'allow' else 'allow'
        self.cfg[other] = [x for x in self.cfg[other] if x != identity]
        if identity not in self.cfg[kind]:
            self.cfg[kind].append(identity)
        self.persist()
        self.render_rules()

    @staticmethod
    def clear(box):
        while box.get_first_child():
            box.remove(box.get_first_child())

    def render_rules(self):
        self.clear(self.rules_box)
        for kind in ('allow', 'deny'):
            for identity in self.cfg[kind]:
                caption = self.t('Allowed: ', 'Разрешено: ') if kind == 'allow' else self.t('Blocked: ', 'Блок: ')
                self.rules_box.append(self.label(caption + identity))
                def remove(_, field=kind, value=identity):
                    self.cfg[field].remove(value)
                    self.persist()
                    self.render_rules()
                self.rules_box.append(self.button(self.t('Remove rule', 'Удалить правило'), remove))

    def render_keys(self):
        self.clear(self.keys_box)
        for key in self.cfg['keys']:
            self.keys_box.append(self.label(asyncssh.import_public_key(key).get_fingerprint(), 'monospace', True))
            def remove(_, value=key):
                self.cfg['keys'].remove(value)
                self.persist()
                self.render_keys()
            self.keys_box.append(self.button(self.t('Remove key', 'Удалить ключ'), remove))
        if not self.cfg['keys']:
            self.keys_box.append(self.label(self.t('No keys imported', 'Ключи не добавлены'), 'dim-label'))

    def key_dialog(self, _):
        dialog = Adw.Window(transient_for=self.window, modal=True,
                            title=self.t('Import public key', 'Импорт ключа'), default_width=360, default_height=400)
        body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)
        for prop in ('top', 'bottom', 'start', 'end'):
            getattr(body, 'set_margin_' + prop)(16)
        dialog.set_content(body)
        body.append(self.label(self.t('Paste one OpenSSH public key or select a .pub file.',
                                     'Вставьте публичный ключ OpenSSH или выберите файл .pub.')))
        view = Gtk.TextView(wrap_mode=Gtk.WrapMode.CHAR)
        scroller = Gtk.ScrolledWindow(min_content_height=150, vexpand=True)
        scroller.set_child(view)
        body.append(scroller)
        def choose(_):
            chooser = Gtk.FileChooserNative(title=self.t('Public key', 'Публичный ключ'),
                                            transient_for=dialog, action=Gtk.FileChooserAction.OPEN)
            def selected(chooser, response):
                if response == Gtk.ResponseType.ACCEPT:
                    try:
                        path = Path(chooser.get_file().get_path())
                        if path.stat().st_size > 16384:
                            raise ValueError('Key file is too large')
                        view.get_buffer().set_text(path.read_text(encoding='utf-8'))
                    except (OSError, ValueError, TypeError) as exc:
                        self.message(str(exc))
                chooser.destroy()
            chooser.connect('response', selected)
            chooser.show()
        body.append(self.button(self.t('Select .pub file', 'Выбрать файл .pub'), choose))
        error = self.label('', 'error')
        body.append(error)
        def add(_):
            buffer = view.get_buffer()
            try:
                key = normalize_key(buffer.get_text(buffer.get_start_iter(), buffer.get_end_iter(), False))
                if key not in self.cfg['keys']:
                    self.cfg['keys'].append(key)
                if self.persist():
                    self.render_keys()
                    dialog.close()
            except ValueError as exc:
                error.set_text(str(exc))
        body.append(self.button(self.t('Import', 'Добавить'), add, 'suggested-action'))
        body.append(self.button(self.t('Cancel', 'Отмена'), lambda _: dialog.close()))
        dialog.present()

    def refresh(self):
        # NetworkManager can time out. Never block GTK's event loop on it.
        if not getattr(self, 'network_refreshing', False):
            self.network_refreshing = True
            def worker():
                network = current_network()
                def done():
                    self.network = network
                    self.network_refreshing = False
                    self.network_label.set_text(network.identity or self.t('Unknown network', 'Неизвестная сеть'))
                    self.network_label.set_tooltip_text(network.interface + ' ' + network.address)
                    return False
                GLib.idle_add(done)
            threading.Thread(target=worker, daemon=True).start()
        try:
            self.cfg = load_config()
            state = read_status()
        except (OSError, ValueError):
            state = {'state': 'error', 'error': self.t('Cannot read settings or status', 'Ошибка чтения настроек или состояния')}
        names = {
            'stopped': self.t('Stopped', 'Остановлен'),
            'listening': self.t('Tunnel ready', 'Туннель готов'),
            'no_keys': self.t('Import a public key', 'Добавьте публичный ключ'),
            'network_blocked': self.t('Network blocked', 'Сеть заблокирована'),
            'adb_unavailable': self.t('ADB server unavailable', 'Сервер ADB недоступен'),
            'error': self.t('Tunnel error', 'Ошибка туннеля')}
        self.status.set_text(names.get(state.get('state'), self.t('Starting…', 'Запуск…')))
        if state.get('error'):
            details = state['error']
        elif state.get('adb') == 'ready':
            details = self.t('Local ADB server: ready\n', 'Локальный сервер ADB: готов\n')
            details += state.get('devices') or self.t('Android devices: none connected', 'Устройства Android: не подключены')
            details += self.t('\nSSH connections: ', '\nSSH-подключения: ') + str(state.get('connections', 0))
        else:
            details = self.t('Start the local server with adb start-server. Keep ADB on loopback.',
                             'Запустите локальный сервер: adb start-server. ADB должен слушать только loopback.')
        self.details.set_text(details)
        self.toggle.set_label(self.t('Stop tunnel', 'Остановить туннель') if self.cfg['enabled'] else
                              self.t('Start tunnel', 'Запустить туннель'))
        address = state.get('address') or getattr(self, 'network', None) and self.network.address or 'LINUX_IP'
        self.commands.set_text(f'ssh -N -L 15037:127.0.0.1:5037 \\\n  -p {self.cfg["port"]} -i YOUR_KEY \\\n  {USERNAME}@{address}\n\nadb -H 127.0.0.1 -P 15037 devices -l')
        self.host_label.set_text(self.t('Host fingerprint: ', 'Отпечаток сервера: ') + state.get('fingerprint', '—'))
        return True
