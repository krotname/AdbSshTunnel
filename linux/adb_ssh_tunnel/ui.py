"""Adaptive GTK UI. All service and settings operations are per-user."""
from __future__ import annotations

import locale
import math
from pathlib import Path
import subprocess
import threading
import time

import gi
gi.require_version('Gtk', '4.0')
gi.require_version('Adw', '1')
gi.require_version('Gdk', '4.0')
gi.require_version('Gsk', '4.0')
gi.require_version('Graphene', '1.0')
from gi.repository import Adw, Gdk, Gio, GLib, Graphene, Gsk, Gtk, Pango

from . import __version__
from .core import (APP_ID, USERNAME, current_network, default_config,
                   load_config, normalize_key, read_status, save_config)
from .messages import Debounce, classify, shown_tone
import asyncssh

PROJECT_URL = 'https://github.com/krotname/AdbSshTunnel'
SERVICE = 'adb-ssh-tunnel.service'
ICONS = Path(__file__).resolve().with_name('icons')

# Shared with the Android app: tile accents and the power-button tones.
TILES = {
    'blue': ('#2563EB', '#E0ECFF', '#DBEAFE'),
    'green': ('#16A34A', '#DCFCE7', '#DCFCE7'),
    'violet': ('#7C3AED', '#EDE9FE', '#EDE9FE'),
    'orange': ('#EA580C', '#FFEDD5', '#FFEDD5'),
    'red': ('#E11D48', '#FFE4E6', '#FFE4E6'),
}
POWER = {
    'ready': ('#34D399', '#059669'),
    'working': ('#FBBF24', '#D97706'),
    'attention': ('#FB7185', '#E11D48'),
    'off': ('#CBD5E1', '#94A3B8'),
}
TONE_TEXT = {
    False: {'ready': '#047857', 'working': '#B45309', 'attention': '#BE123C', 'off': '#374151'},
    True: {'ready': '#6EE7B7', 'working': '#FCD34D', 'attention': '#FDA4AF', 'off': '#CBD5E1'},
}
PAGE_BG = {False: '#F6F7FB', True: '#0F1115'}


def rgba(value: str, alpha: float = 1.0) -> Gdk.RGBA:
    color = Gdk.RGBA()
    color.parse(value)
    color.alpha = alpha
    return color


def paint(rgb, alpha: float = 1.0) -> Gdk.RGBA:
    # PyGObject ignores constructor keywords for boxed types, so set fields.
    color = Gdk.RGBA()
    color.red, color.green, color.blue = rgb
    color.alpha = alpha
    return color


def mix(a, b, k):
    return tuple(x + (y - x) * k for x, y in zip(a, b))


def channels(value: str) -> tuple:
    color = rgba(value)
    return (color.red, color.green, color.blue)


CSS = """
.tunnel-bg, window.tunnel-window {{ background-color: {page}; }}
.tunnel-bg headerbar {{ background: transparent; box-shadow: none; }}
.app-title {{ font-size: 26px; font-weight: 800; color: {ink}; }}
.app-subtitle {{ color: {muted}; }}
.power-button, .power-button:hover, .power-button:active, .power-button:checked {{
  background: none; border: none; box-shadow: none; padding: 0; border-radius: 999px;
  transition: transform 160ms ease-out; }}
.power-button:active {{ transform: scale(0.96); }}
.power-button:focus-visible {{ outline: 3px solid alpha({focus}, 0.6); outline-offset: -18px; }}
.power-icon {{ color: white; -gtk-icon-shadow: 0 1px 3px alpha(black, 0.25); }}
.status-title {{ font-size: 24px; font-weight: 800; }}
.tone-ready {{ color: {tone_ready}; }}
.tone-working {{ color: {tone_working}; }}
.tone-attention {{ color: {tone_attention}; }}
.tone-off {{ color: {tone_off}; }}
.hint {{ color: {muted}; }}
.where {{ font-family: monospace; color: {muted}; background-color: {chip_bg};
  border-radius: 999px; padding: 4px 12px; }}
.problem-chip {{ background-color: {problem_bg}; color: {problem_fg}; border-radius: 999px;
  padding: 8px 16px; font-weight: 700; box-shadow: none; border: none; }}
.problem-chip:hover {{ background-color: {problem_hover}; }}
.tile {{ border-radius: 22px; padding: 14px; box-shadow: none; border: 1px solid {tile_border};
  transition: transform 160ms ease-out, background-color 200ms; }}
.tile:active {{ transform: scale(0.97); }}
.compact .tile {{ padding: 10px 12px; }}
.tile-title {{ font-weight: 800; color: {ink}; }}
.tile-summary {{ color: {muted}; font-size: 13px; font-weight: 400; }}
.bubble {{ border-radius: 999px; min-width: 40px; min-height: 40px; }}
.bubble image {{ color: {bubble_icon}; }}
.hero-bubble {{ border-radius: 999px; min-width: 76px; min-height: 76px; }}
.hero-bubble image {{ color: {bubble_icon}; }}
.hero-title {{ font-size: 26px; font-weight: 800; color: {ink}; }}
.code-card {{ background-color: {code_bg}; border-radius: 16px; padding: 14px;
  border: 1px solid {code_border}; }}
.code-text {{ font-family: monospace; color: #E2E8F0; }}
.code-card button {{ color: #E2E8F0; }}
.soft-card {{ background-color: {card_bg}; border-radius: 16px; padding: 16px;
  border: 1px solid {card_border}; }}
.error-card {{ background-color: {problem_bg}; color: {problem_fg}; border-radius: 14px; padding: 12px; }}
.btn-green {{ background-color: #16A34A; color: white; }}
.btn-green:hover {{ background-color: #15803D; }}
.note {{ color: {muted}; font-size: 13px; }}
.mono {{ font-family: monospace; }}
"""


def tile_css(dark: bool) -> str:
    rules = []
    for name, (accent, light_bg, bubble_light) in TILES.items():
        if dark:
            bg, hover, bubble, icon = f'alpha({accent}, 0.16)', f'alpha({accent}, 0.24)', bubble_light, accent
        else:
            bg, hover, bubble, icon = light_bg, f'mix({light_bg}, {accent}, 0.08)', accent, 'white'
        rules.append(f'.tile-{name} {{ background-color: {bg}; }}')
        rules.append(f'.tile-{name}:hover {{ background-color: {hover}; }}')
        rules.append(f'.accent-{name} {{ background-color: {bubble}; }}')
        rules.append(f'.accent-{name} image {{ color: {icon}; }}')
    return '\n'.join(rules)


def stylesheet(dark: bool) -> str:
    tones = TONE_TEXT[dark]
    tokens = dict(
        page=PAGE_BG[dark], ink='#F3F4F6' if dark else '#111827', muted='#9CA3AF' if dark else '#6B7280',
        focus='#60A5FA' if dark else '#2563EB',
        tone_ready=tones['ready'], tone_working=tones['working'],
        tone_attention=tones['attention'], tone_off=tones['off'],
        chip_bg='alpha(white, 0.06)' if dark else 'alpha(#111827, 0.05)',
        problem_bg='alpha(#E11D48, 0.20)' if dark else '#FFE4E6',
        problem_hover='alpha(#E11D48, 0.28)' if dark else '#FECDD3',
        problem_fg='#FDA4AF' if dark else '#BE123C',
        tile_border='alpha(white, 0.06)' if dark else 'transparent',
        bubble_icon='white',
        code_bg='#020617' if dark else '#0F172A', code_border='alpha(white, 0.08)' if dark else 'transparent',
        card_bg='alpha(white, 0.05)' if dark else 'white',
        card_border='alpha(white, 0.07)' if dark else 'alpha(#111827, 0.06)')
    return CSS.format(**tokens) + tile_css(dark)


class PowerGlow(Gtk.Widget):
    """The big round button face: halo, ring, gap and a gradient disc.

    Colours glide between states. The ring spins while the tunnel gets ready,
    breathes while it is on and rests when it is off. Animation runs only when
    something moves and respects the desktop's reduce-motion setting.
    """

    SIZE = 216
    COMPACT = 168
    FADE = 0.45

    def __init__(self, page_bg: str):
        super().__init__()
        self.set_size_request(self.SIZE, self.SIZE)
        self.set_halign(Gtk.Align.CENTER)
        self.page = channels(page_bg)
        self.tone = 'off'
        self.start = channels(POWER['off'][0])
        self.end = channels(POWER['off'][1])
        self.from_colors = (self.start, self.end)
        self.fade_started = 0.0
        self.glow = 0.0
        self.spin = 0.0
        self.tick_id = 0
        self.last = 0.0
        self.set_can_target(False)

    def motion(self) -> bool:
        settings = Gtk.Settings.get_default()
        return bool(settings is None or settings.get_property('gtk-enable-animations'))

    def set_page(self, page_bg: str):
        self.page = channels(page_bg)
        self.queue_draw()

    def set_tone(self, tone: str):
        if tone == self.tone:
            return
        self.tone = tone
        self.from_colors = (self.start, self.end)
        self.fade_started = time.monotonic()
        if not self.motion():
            self.start, self.end = (channels(v) for v in POWER[tone])
        self.ensure_ticking()
        self.queue_draw()

    def ensure_ticking(self):
        if self.tick_id or not self.motion():
            return
        self.last = time.monotonic()
        self.tick_id = self.add_tick_callback(self.on_tick)

    def on_tick(self, _widget, _clock):
        now = time.monotonic()
        dt, self.last = min(now - self.last, 0.1), now
        progress = min(1.0, (now - self.fade_started) / self.FADE)
        eased = 1 - (1 - progress) ** 3
        target = tuple(channels(v) for v in POWER[self.tone])
        self.start = mix(self.from_colors[0], target[0], eased)
        self.end = mix(self.from_colors[1], target[1], eased)
        want = {'off': 0.0, 'working': 1.0, 'ready': 1.0, 'attention': 0.8}[self.tone]
        self.glow += (want - self.glow) * min(1.0, dt * 6)
        if self.tone == 'working':
            self.spin = (self.spin + dt * 300) % 360
        self.queue_draw()
        if self.tone == 'off' and progress >= 1.0 and self.glow < 0.01:
            self.glow = 0.0
            self.tick_id = 0
            return GLib.SOURCE_REMOVE
        return GLib.SOURCE_CONTINUE

    def do_unrealize(self):
        if self.tick_id:
            self.remove_tick_callback(self.tick_id)
            self.tick_id = 0
        Gtk.Widget.do_unrealize(self)

    @staticmethod
    def stops(*items):
        result = []
        for offset, rgb, alpha in items:
            stop = Gsk.ColorStop()
            stop.offset = offset
            stop.color = paint(rgb, alpha)
            result.append(stop)
        return result

    @staticmethod
    def circle(cx, cy, radius):
        rounded = Gsk.RoundedRect()
        rounded.init_from_rect(Graphene.Rect().init(cx - radius, cy - radius, radius * 2, radius * 2), radius)
        return rounded

    def do_snapshot(self, snapshot):
        width, height = self.get_width(), self.get_height()
        size = min(width, height)
        cx, cy = width / 2, height / 2
        bounds = Graphene.Rect().init(cx - size / 2, cy - size / 2, size, size)
        center = Graphene.Point().init(cx, cy)
        moving = self.motion()
        now = time.monotonic()
        breath = 0.5 + 0.5 * math.sin(now * 2 * math.pi / 2.6) if moving else 1.0
        glow = self.glow if moving else (0.0 if self.tone == 'off' else 1.0)
        accent = self.end
        ring_outer = size * 0.43
        ring_width = 7.0
        gap = 6.0
        disc = ring_outer - ring_width - gap

        # Halo.
        if glow > 0.01:
            strength = glow * (0.30 + 0.16 * breath if self.tone == 'ready' else 0.40)
            snapshot.append_radial_gradient(
                bounds, center, size / 2, size / 2, 0.0, 1.0,
                self.stops((0.0, accent, strength), (0.62, accent, strength * 0.75),
                           (1.0, accent, 0.0)))

        # Ring.
        snapshot.push_rounded_clip(self.circle(cx, cy, ring_outer))
        ring_bounds = Graphene.Rect().init(cx - ring_outer, cy - ring_outer, ring_outer * 2, ring_outer * 2)
        if self.tone == 'working' and glow > 0.01:
            snapshot.append_conic_gradient(
                ring_bounds, center, self.spin,
                self.stops((0.0, accent, 0.0), (0.55, accent, 0.10), (0.92, self.start, 0.95),
                           (1.0, (1.0, 1.0, 1.0), 1.0)))
        else:
            alpha = {'off': 0.55, 'ready': 0.55 + 0.45 * breath, 'attention': 0.85}.get(self.tone, 0.8)
            snapshot.append_color(paint(accent, alpha), ring_bounds)
        snapshot.pop()

        # Gap in the page colour, so the ring floats.
        inner = ring_outer - ring_width
        snapshot.push_rounded_clip(self.circle(cx, cy, inner))
        snapshot.append_color(paint(self.page), Graphene.Rect().init(cx - inner, cy - inner, inner * 2, inner * 2))
        snapshot.pop()

        # Disc with a soft shadow, a diagonal gradient and a highlight.
        snapshot.append_outset_shadow(self.circle(cx, cy, disc),
                                      paint(accent, 0.35),
                                      0, 6, 0, 18)
        disc_bounds = Graphene.Rect().init(cx - disc, cy - disc, disc * 2, disc * 2)
        snapshot.push_rounded_clip(self.circle(cx, cy, disc))
        snapshot.append_linear_gradient(
            disc_bounds, Graphene.Point().init(cx - disc, cy - disc), Graphene.Point().init(cx + disc, cy + disc),
            self.stops((0.0, self.start, 1.0), (1.0, self.end, 1.0)))
        snapshot.append_radial_gradient(
            disc_bounds, Graphene.Point().init(cx - disc * 0.35, cy - disc * 0.45), disc * 0.9, disc * 0.9,
            0.0, 1.0, self.stops((0.0, (1.0, 1.0, 1.0), 0.32), (1.0, (1.0, 1.0, 1.0), 0.0)))
        snapshot.pop()


class Application(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)
        self.window = None
        self.busy = False
        self.cfg = default_config()
        self.state = {'state': 'stopped'}
        self.network = None
        self.network_checked = 0.0
        self.network_refreshing = False
        self.service_error = ''
        self.debounce = Debounce()
        self.problem = None
        self.tone = None
        self.shown = {}
        self.css = None
        self.style_handler = 0

    # Small builders ---------------------------------------------------------

    def t(self, en, ru):
        lang = self.cfg.get('language', 'system')
        russian = lang == 'ru' or (lang == 'system' and (locale.getlocale()[0] or '').startswith('ru'))
        return ru if russian else en

    def pick(self, pair):
        return self.t(*pair)

    def label(self, text='', css=None, selectable=False, center=False):
        item = Gtk.Label(label=text, xalign=0.5 if center else 0, wrap=True, selectable=selectable)
        item.set_wrap_mode(Pango.WrapMode.WORD_CHAR)
        if center:
            item.set_justify(Gtk.Justification.CENTER)
        for name in (css or '').split():
            item.add_css_class(name)
        return item

    def button(self, text, callback, css=None):
        button = Gtk.Button(label=text)
        for name in (css or '').split():
            button.add_css_class(name)
        button.connect('clicked', callback)
        return button

    @staticmethod
    def margins(widget, value):
        for prop in ('top', 'bottom', 'start', 'end'):
            getattr(widget, 'set_margin_' + prop)(value)
        return widget

    def bubble(self, icon, accent, size=40, icon_size=20, css='bubble'):
        box = Gtk.Box(halign=Gtk.Align.START, valign=Gtk.Align.CENTER)
        box.add_css_class(css)
        box.add_css_class('accent-' + accent)
        box.set_size_request(size, size)
        image = Gtk.Image(icon_name=icon, pixel_size=icon_size, hexpand=True, vexpand=True)
        box.append(image)
        return box

    def dark(self):
        return Adw.StyleManager.get_default().get_dark()

    def apply_style(self, *_):
        display = Gdk.Display.get_default()
        if display is None:
            return
        provider = Gtk.CssProvider()
        text = stylesheet(self.dark())
        if hasattr(provider, 'load_from_string'):
            provider.load_from_string(text)
        else:
            provider.load_from_data(text, -1)
        if self.css:
            Gtk.StyleContext.remove_provider_for_display(display, self.css)
        Gtk.StyleContext.add_provider_for_display(display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self.css = provider
        glow = getattr(self, 'glow', None)
        if glow:
            glow.set_page(PAGE_BG[self.dark()])

    # Window -----------------------------------------------------------------

    def do_activate(self):
        if self.window:
            self.window.present()
            return
        display = Gdk.Display.get_default()
        if display is not None:
            Gtk.IconTheme.get_for_display(display).add_search_path(str(ICONS))
        self.window = Adw.ApplicationWindow(application=self, title='ADB SSH Tunnel')
        self.window.add_css_class('tunnel-window')
        self.window.set_default_size(420, 800)
        # Breakpoints need an explicit minimum size; 360×294 is the GNOME mobile minimum.
        self.window.set_size_request(360, 294)
        self.compact = False
        short = Adw.Breakpoint.new(Adw.BreakpointCondition.parse('max-height: 720px'))
        short.connect('apply', lambda *_: self.set_compact(True))
        short.connect('unapply', lambda *_: self.set_compact(False))
        self.window.add_breakpoint(short)
        self.apply_style()
        self.style_handler = Adw.StyleManager.get_default().connect('notify::dark', self.apply_style)
        self.open_settings()
        self.window.present()

    def open_settings(self):
        try:
            self.cfg = load_config()
        except (OSError, ValueError) as exc:
            self.cfg = default_config()
            page = Adw.StatusPage(title=self.t('Cannot load settings', 'Ошибка настроек'),
                                  description=str(exc)[:300], icon_name='adbst-error-symbolic')
            page.set_child(self.button(self.t('Retry', 'Повторить'), lambda _: self.open_settings(),
                                       'pill suggested-action'))
            page.add_css_class('tunnel-bg')
            self.window.set_content(page)
            return
        self.build()
        if getattr(self, 'refresh_timer', None):
            GLib.source_remove(self.refresh_timer)
        self.refresh_timer = GLib.timeout_add(1000, self.refresh)
        self.refresh()

    def page(self, title, tag=None):
        """A navigation page with a back button and a clamped, scrollable column."""
        column = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=18)
        self.margins(column, 16)
        column.set_margin_bottom(28)
        clamp = Adw.Clamp(maximum_size=560, tightening_threshold=400, child=column)
        scroll = Gtk.ScrolledWindow(hscrollbar_policy=Gtk.PolicyType.NEVER, child=clamp, vexpand=True)
        view = Adw.ToolbarView(content=scroll)
        view.add_css_class('tunnel-bg')
        header = Adw.HeaderBar(show_title=tag is None)
        view.add_top_bar(header)
        page = Adw.NavigationPage(title=title, child=view)
        if tag:
            page.set_tag(tag)
        if tag == 'main':
            self.main_header, self.main_scroll = header, scroll
        return page, column

    def hero(self, column, icon, accent, title, subtitle):
        box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10, halign=Gtk.Align.CENTER)
        bubble = self.bubble(icon, accent, 76, 36, 'hero-bubble')
        bubble.set_halign(Gtk.Align.CENTER)
        box.append(bubble)
        box.append(self.label(title, 'hero-title', center=True))
        if subtitle:
            box.append(self.label(subtitle, 'dim-label', center=True))
        column.append(box)

    def group(self, column, title='', description=''):
        group = Adw.PreferencesGroup(title=title, description=description)
        column.append(group)
        return group

    def note(self, column, text):
        column.append(self.label(text, 'note'))

    def build(self):
        self.window.set_focus(None)
        self.shown = {}
        self.tone = None
        self.toast = Adw.ToastOverlay()
        self.nav = Adw.NavigationView()
        self.toast.set_child(self.nav)
        self.window.set_content(self.toast)

        page, column = self.page('ADB SSH Tunnel', 'main')
        self.main_column = column
        column.set_spacing(10)
        column.set_margin_top(0)
        title = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        title.append(self.label('ADB SSH Tunnel', 'app-title', center=True))
        title.append(self.label(self.t('ADB from your computer, safely over SSH',
                                       'ADB с компьютера — безопасно, через SSH'), 'app-subtitle', center=True))
        column.append(title)
        self.title_box = title

        self.glow = PowerGlow(PAGE_BG[self.dark()])
        overlay = Gtk.Overlay(child=self.glow)
        icon = Gtk.Image(icon_name='adbst-power-symbolic', pixel_size=64,
                         halign=Gtk.Align.CENTER, valign=Gtk.Align.CENTER)
        icon.add_css_class('power-icon')
        overlay.add_overlay(icon)
        self.power_icon = icon
        self.toggle = Gtk.Button(child=overlay, halign=Gtk.Align.CENTER)
        self.toggle.add_css_class('power-button')
        self.toggle.connect('clicked', self.toggle_tunnel)
        column.append(self.toggle)

        self.status = self.label('', 'status-title', center=True)
        column.append(self.status)
        self.where = self.label('', 'where', selectable=True, center=True)
        self.where.set_halign(Gtk.Align.CENTER)
        column.append(self.where)

        chip_box = Gtk.Box(spacing=8)
        chip_box.append(Gtk.Image(icon_name='dialog-warning-symbolic'))
        self.chip_text = Gtk.Label(ellipsize=Pango.EllipsizeMode.END)
        chip_box.append(self.chip_text)
        chip_box.append(Gtk.Image(icon_name='go-next-symbolic'))
        self.chip = Gtk.Button(child=chip_box, halign=Gtk.Align.CENTER, visible=False)
        self.chip.add_css_class('problem-chip')
        self.chip.connect('clicked', lambda _: self.open_problem())
        column.append(self.chip)

        self.hint = self.label('', 'hint', center=True)
        column.append(self.hint)

        grid = Gtk.Grid(column_spacing=10, row_spacing=10, column_homogeneous=True, row_homogeneous=True)
        grid.set_margin_top(8)
        self.tiles = {}
        for index, (key, accent, icon, name, opener) in enumerate((
                ('connect', 'blue', 'adbst-connect-symbolic', self.t('Connect', 'Подключение'), self.open_connect),
                ('networks', 'green', 'adbst-wifi-symbolic', self.t('Networks & keys', 'Сети и ключи'),
                 self.open_networks),
                ('port', 'violet', 'adbst-terminal-symbolic', self.t('Port & startup', 'Порт и запуск'),
                 self.open_port),
                ('more', 'orange', 'emblem-system-symbolic', self.t('More', 'Дополнительно'), self.open_more))):
            body = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
            body.append(self.bubble(icon, accent))
            body.append(self.label(name, 'tile-title'))
            summary = self.label('', 'tile-summary')
            summary.set_lines(2)
            summary.set_ellipsize(Pango.EllipsizeMode.END)
            body.append(summary)
            tile = Gtk.Button(child=body)
            tile.add_css_class('tile')
            tile.add_css_class('tile-' + accent)
            tile.connect('clicked', lambda _b, f=opener: f())
            grid.attach(tile, index % 2, index // 2, 1, 1)
            self.tiles[key] = summary
        column.append(grid)
        self.nav.add(page)
        self.set_compact(getattr(self, 'compact', False))

    def set_compact(self, compact: bool):
        """Short windows, such as Linux phones under Phosh, keep the whole main screen without scrolling."""
        self.compact = compact
        if not getattr(self, 'title_box', None):
            return
        (self.window.add_css_class if compact else self.window.remove_css_class)('compact')
        size = PowerGlow.COMPACT if compact else PowerGlow.SIZE
        self.glow.set_size_request(size, size)
        self.power_icon.set_pixel_size(50 if compact else 64)
        self.title_box.set_visible(not compact)
        self.main_header.set_show_title(compact)
        self.main_column.set_margin_bottom(12 if compact else 28)

    # Deeper pages -----------------------------------------------------------

    def push(self, page):
        self.nav.push(page)

    def code_card(self, column, caption, text_getter):
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        card.add_css_class('code-card')
        head = Gtk.Box(spacing=8)
        head.append(self.label(caption, 'code-text dim-label'))
        head.get_first_child().set_hexpand(True)
        copy = Gtk.Button(icon_name='edit-copy-symbolic', tooltip_text=self.t('Copy', 'Копировать'))
        copy.add_css_class('flat')
        copy.add_css_class('circular')
        head.append(copy)
        card.append(head)
        text = self.label(text_getter(), 'code-text', selectable=True)
        card.append(text)

        def copied(_):
            self.window.get_clipboard().set(text.get_text())
            self.message(self.t('Copied', 'Скопировано'))
        copy.connect('clicked', copied)
        column.append(card)
        return text

    def commands(self):
        address = (self.state.get('address') or (self.network and self.network.address) or 'LINUX_IP')
        return (f'ssh -N -L 15037:127.0.0.1:5037 \\\n  -p {self.cfg["port"]} -i YOUR_KEY \\\n'
                f'  {USERNAME}@{address}', 'adb -H 127.0.0.1 -P 15037 devices -l')

    def open_connect(self):
        page, column = self.page(self.t('Connect', 'Подключение'))
        self.hero(column, 'adbst-connect-symbolic', 'blue', self.t('Connect', 'Подключение'),
                  self.t('Run these two commands on your computer', 'Выполните две команды на компьютере'))
        self.ssh_text = self.code_card(column, self.t('1 · Open the tunnel', '1 · Откройте туннель'),
                                       lambda: self.commands()[0])
        self.code_card(column, self.t('2 · Use ADB as usual', '2 · Пользуйтесь ADB как обычно'),
                       lambda: self.commands()[1])
        group = self.group(column, self.t('Server fingerprint', 'Отпечаток сервера'),
                           self.t('Compare it through a trusted channel before the first connection.',
                                  'Сверьте его по доверенному каналу перед первым подключением.'))
        self.fingerprint_row = Adw.ActionRow(title=self.state.get('fingerprint', '—'), title_selectable=True)
        self.fingerprint_row.add_css_class('mono')
        group.add(self.fingerprint_row)
        group = self.group(column, self.t('Right now', 'Сейчас'))
        self.adb_row = Adw.ActionRow(title=self.t('Local ADB server', 'Локальный сервер ADB'))
        self.devices_row = Adw.ActionRow(title=self.t('Android devices', 'Устройства Android'),
                                         subtitle_selectable=True)
        self.connections_row = Adw.ActionRow(title=self.t('SSH connections', 'SSH-подключения'))
        for row in (self.adb_row, self.devices_row, self.connections_row):
            group.add(row)
        self.note(column, self.t('This is the Linux ADB server, not Android adbd. Use adb -H/-P, not adb connect. '
                                 'An empty device list means no Android device is attached.',
                                 'Это сервер ADB в Linux, а не Android adbd. Используйте adb -H/-P, а не adb connect. '
                                 'Пустой список устройств означает, что Android не подключён.'))
        self.update_connect()
        self.push(page)

    def update_connect(self):
        if not getattr(self, 'adb_row', None):
            return
        state = self.state
        self.ssh_text.set_text(self.commands()[0])
        self.fingerprint_row.set_title(state.get('fingerprint', '—'))
        ready = state.get('adb') == 'ready'
        self.adb_row.set_subtitle(self.t('Ready', 'Готов') if ready else
                                  self.t('Not answering — run adb start-server', 'Не отвечает — выполните adb start-server')
                                  if state.get('adb') == 'unavailable' else
                                  self.t('Checked while the tunnel is on', 'Проверяется, когда туннель включён'))
        self.devices_row.set_subtitle(state.get('devices') or self.t('None connected', 'Не подключены'))
        self.connections_row.set_subtitle(str(state.get('connections', 0)))

    def open_networks(self):
        page, column = self.page(self.t('Networks & keys', 'Сети и ключи'), )
        self.hero(column, 'adbst-wifi-symbolic', 'green', self.t('Networks & keys', 'Сети и ключи'),
                  self.t('Where the tunnel works and which computers may enter',
                         'Где работает туннель и каким компьютерам можно войти'))
        group = self.group(column, self.t('This network', 'Эта сеть'))
        self.network_row = Adw.ActionRow()
        self.network_row.set_title_selectable(True)
        group.add(self.network_row)
        buttons = Gtk.Box(spacing=10, homogeneous=True)
        buttons.set_margin_top(10)
        buttons.append(self.button(self.t('Allow', 'Разрешить'), lambda _: self.rule_current('allow'),
                                   'pill btn-green'))
        buttons.append(self.button(self.t('Block', 'Запретить'), lambda _: self.rule_current('deny'),
                                   'pill destructive-action'))
        group.add(buttons)

        group = self.group(column, self.t('Where the tunnel may work', 'Где можно работать'))
        self.mode = Adw.ComboRow(title=self.t('Networks', 'Сети'), model=Gtk.StringList.new([
            self.t('Only allowed ones', 'Только разрешённые'),
            self.t('All except blocked', 'Все, кроме заблокированных'),
            self.t('None — all closed', 'Никакие — всё закрыто')]))
        self.mode.set_selected(('allowlist', 'blocklist', 'disabled').index(self.cfg['mode']))
        self.mode.connect('notify::selected', self.change_mode)
        group.add(self.mode)
        self.rules_group = self.group(column, self.t('Rules', 'Правила'))
        self.rules_rows = []
        self.render_rules()
        self.note(column, self.t('Blocked entries win. Unknown networks stay blocked. '
                                 'Wi-Fi uses SSID; Ethernet/mobile use NetworkManager profile UUID.',
                                 'Блокировка приоритетнее разрешения. Неизвестные сети закрыты. '
                                 'Wi-Fi — по SSID; Ethernet и мобильная сеть — по UUID профиля NetworkManager.'))

        self.keys_group = self.group(column, self.t('Computer keys', 'Ключи компьютеров'),
                                     self.t('Keep private keys on the computer. Add only the public .pub key.',
                                            'Приватный ключ оставьте на компьютере. Добавьте только публичный .pub.'))
        add = Gtk.Button(icon_name='list-add-symbolic', tooltip_text=self.t('Add key', 'Добавить ключ'))
        add.add_css_class('flat')
        add.connect('clicked', lambda _: self.open_add_key())
        self.keys_group.set_header_suffix(add)
        self.keys_rows = []
        self.render_keys()
        column.append(self.button(self.t('Add a computer key', 'Добавить ключ компьютера'),
                                  lambda _: self.open_add_key(), 'pill suggested-action'))
        self.update_network()
        self.push(page)

    def update_network(self):
        row = getattr(self, 'network_row', None)
        if row is None:
            return
        network = self.network
        if network and network.identity:
            row.set_title(network.identity)
            row.set_subtitle(f'{network.interface} · {network.address}'.strip(' ·'))
            allowed = network.identity in self.cfg['allow']
            blocked = network.identity in self.cfg['deny']
            row.set_subtitle(row.get_subtitle() + ' · ' + (
                self.t('allowed', 'разрешена') if allowed else
                self.t('blocked', 'заблокирована') if blocked else self.t('no rule', 'без правила')))
        else:
            row.set_title(self.t('Unknown network', 'Неизвестная сеть'))
            row.set_subtitle(self.t('Not connected or not recognised', 'Нет подключения или сеть не распознана'))

    def open_add_key(self):
        page, column = self.page(self.t('Add key', 'Добавить ключ'))
        self.hero(column, 'adbst-key-symbolic', 'green', self.t('Add a computer key', 'Ключ компьютера'),
                  self.t('Paste one OpenSSH public key or select a .pub file.',
                         'Вставьте публичный ключ OpenSSH или выберите файл .pub.'))
        steps = self.label(self.t('1. On the computer, open ~/.ssh/id_ed25519.pub.\n'
                                  '2. Copy the whole line — it starts with ssh-ed25519.\n'
                                  '3. Paste it below and tap Add.',
                                  '1. На компьютере откройте ~/.ssh/id_ed25519.pub.\n'
                                  '2. Скопируйте всю строку — она начинается с ssh-ed25519.\n'
                                  '3. Вставьте её ниже и нажмите «Добавить».'), 'soft-card')
        column.append(steps)
        view = Gtk.TextView(wrap_mode=Gtk.WrapMode.CHAR, top_margin=10, bottom_margin=10,
                            left_margin=10, right_margin=10)
        view.add_css_class('mono')
        scroller = Gtk.ScrolledWindow(min_content_height=120, child=view)
        scroller.add_css_class('card')
        column.append(scroller)
        error = self.label('', 'error-card')
        error.set_visible(False)
        column.append(error)

        def choose(_):
            dialog = Gtk.FileDialog(title=self.t('Public key', 'Публичный ключ'))
            pattern = Gtk.FileFilter(name='OpenSSH .pub')
            pattern.add_pattern('*.pub')
            filters = Gio.ListStore.new(Gtk.FileFilter)
            filters.append(pattern)
            dialog.set_filters(filters)

            def selected(source, result):
                try:
                    path = Path(source.open_finish(result).get_path())
                    if path.stat().st_size > 16384:
                        raise ValueError('Key file is too large')
                    view.get_buffer().set_text(path.read_text(encoding='utf-8'))
                except GLib.Error:
                    return
                except (OSError, ValueError, TypeError) as exc:
                    error.set_text(str(exc))
                    error.set_visible(True)
            dialog.open(self.window, None, selected)

        def add(_):
            buffer = view.get_buffer()
            try:
                key = normalize_key(buffer.get_text(buffer.get_start_iter(), buffer.get_end_iter(), False))
                if key not in self.cfg['keys']:
                    self.cfg['keys'].append(key)
                if self.persist():
                    self.render_keys()
                    self.nav.pop()
                    self.message(self.t('Key added', 'Ключ добавлен'))
            except ValueError as exc:
                error.set_text(str(exc))
                error.set_visible(True)
        buttons = Gtk.Box(spacing=10, homogeneous=True)
        buttons.append(self.button(self.t('.pub file', 'Файл .pub'), choose, 'pill'))
        buttons.append(self.button(self.t('Add', 'Добавить'), add, 'pill suggested-action'))
        column.append(buttons)
        self.push(page)

    def open_port(self):
        page, column = self.page(self.t('Port & startup', 'Порт и запуск'))
        self.hero(column, 'adbst-terminal-symbolic', 'violet', self.t('Port & startup', 'Порт и запуск'),
                  self.t('How computers reach this device', 'Как компьютеры находят это устройство'))
        group = self.group(column, self.t('SSH port', 'Порт SSH'),
                           self.t('1024–65535, except 5037', '1024–65535, кроме 5037'))
        self.port = Adw.EntryRow(title=self.t('Port', 'Порт'), text=str(self.cfg['port']),
                                 show_apply_button=True, input_purpose=Gtk.InputPurpose.DIGITS)
        self.port.connect('apply', self.change_port)
        group.add(self.port)
        self.port_error = self.label('', 'error-card')
        self.port_error.set_visible(False)
        column.append(self.port_error)
        group = self.group(column, self.t('Startup', 'Запуск'))
        self.autostart = Adw.SwitchRow(title=self.t('Start at sign-in', 'Запуск при входе'),
                                       subtitle=self.t('Run the tunnel service when you log in',
                                                       'Запускать службу туннеля при входе в систему'))
        self.autostart.set_active(self.autostart_enabled())
        self.autostart.connect('notify::active', self.change_autostart)
        group.add(self.autostart)
        self.note(column, self.t('Closing this window keeps an enabled tunnel running.',
                                 'При закрытии окна включённый туннель продолжает работать.'))
        self.push(page)

    def open_more(self):
        page, column = self.page(self.t('More', 'Дополнительно'), )
        page.set_tag('more')
        self.hero(column, 'emblem-system-symbolic', 'orange', self.t('More', 'Дополнительно'),
                  self.t('Language, safety and help', 'Язык, безопасность и помощь'))
        group = self.group(column, self.t('Language', 'Язык'))
        language = Adw.ComboRow(title=self.t('Interface language', 'Язык интерфейса'),
                                model=Gtk.StringList.new(['System / Системный', 'English', 'Русский']))
        language.set_selected(('system', 'en', 'ru').index(self.cfg['language']))
        language.connect('notify::selected', self.change_language)
        group.add(language)
        group = self.group(column, self.t('About', 'О приложении'))
        group.add(Adw.ActionRow(title=self.t('Version', 'Версия'), subtitle=f'Linux {__version__}'))
        security = Adw.ActionRow(title=self.t('Security', 'Безопасность'), subtitle=self.t(
            'Key-only access to 127.0.0.1:5037. SSH shell, PTY, SFTP/SCP, agent, X11 and reverse '
            'forwarding are disabled. System SSH is separate.',
            'Только ключи и 127.0.0.1:5037. SSH shell, PTY, SFTP/SCP, агент, X11 и обратные туннели '
            'запрещены. Системный SSH работает отдельно.'))
        group.add(security)
        diagnostics = Adw.ActionRow(title=self.t('Diagnostics', 'Диагностика'),
                                    subtitle=f'journalctl --user -u {SERVICE}', subtitle_selectable=True)
        group.add(diagnostics)
        project = Adw.ActionRow(title=self.t('Project page', 'Страница проекта'), subtitle=PROJECT_URL,
                                activatable=True)
        project.add_suffix(Gtk.Image(icon_name='adbst-link-symbolic'))
        project.connect('activated', lambda _: Gtk.UriLauncher(uri=PROJECT_URL).launch(self.window, None, None))
        group.add(project)
        self.push(page)

    def open_problem(self):
        problem = self.problem
        if problem is None:
            return
        page, column = self.page(self.t('What happened', 'Что случилось'))
        page.set_tag('problem')
        self.hero(column, problem.icon, 'red', self.pick(problem.title), '')
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        card.add_css_class('soft-card')
        card.append(self.label(self.t('What happened', 'Что произошло'), 'heading'))
        card.append(self.label(self.pick(problem.what)))
        column.append(card)
        card = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=8)
        card.add_css_class('soft-card')
        card.append(self.label(self.t('What to do', 'Что сделать'), 'heading'))
        card.append(self.label(self.pick(problem.todo), selectable=True))
        column.append(card)
        actions = {
            'keys': (self.t('Add a key', 'Добавить ключ'), self.open_add_key),
            'networks': (self.t('Open Networks & keys', 'Открыть «Сети и ключи»'), self.open_networks),
            'port': (self.t('Open Port & startup', 'Открыть «Порт и запуск»'), self.open_port),
            'retry': (self.t('Try again', 'Попробовать снова'), self.retry),
        }
        text, action = actions[problem.action]

        def run(_):
            self.nav.pop()
            action()
        column.append(self.button(text, run, 'pill suggested-action'))
        details = '\n'.join(x for x in (
            f'state: {self.state.get("state", "?")}',
            problem.details,
            f'network: {self.network.identity}' if self.network and self.network.identity else '',
            f'adb: {self.state.get("adb")}' if self.state.get('adb') else '',
            f'Linux {__version__}') if x)
        expander = Gtk.Expander(label=self.t('Technical details', 'Технические подробности'))
        expander.set_child(self.label(details, 'mono note', selectable=True))
        column.append(expander)
        self.push(page)

    # Actions ------------------------------------------------------------------

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

    @staticmethod
    def autostart_enabled():
        try:
            return subprocess.run(['systemctl', '--user', 'is-enabled', SERVICE], capture_output=True,
                                  text=True, timeout=2).stdout.strip() == 'enabled'
        except (OSError, subprocess.SubprocessError):
            return False

    def service(self, action, callback=None):
        self.busy = True
        self.toggle.set_sensitive(False)

        def worker():
            try:
                subprocess.run(['systemctl', '--user', action, SERVICE],
                               check=True, capture_output=True, text=True, timeout=15)
                error = ''
            except subprocess.CalledProcessError as exc:
                error = (exc.stderr or exc.stdout or str(exc)).strip()[:300]
            except (OSError, subprocess.SubprocessError) as exc:
                error = str(exc)[:300]

            def done():
                self.busy = False
                self.toggle.set_sensitive(True)
                if action in ('start', 'stop', 'restart'):
                    self.service_error = error
                elif error:
                    self.message(self.t('Service operation failed: ', 'Ошибка службы: ') + error)
                if not error and callback:
                    callback()
                self.refresh()
                return False
            GLib.idle_add(done)
        threading.Thread(target=worker, daemon=True).start()

    def toggle_tunnel(self, _):
        if self.busy:
            return
        self.cfg['enabled'] = not self.cfg['enabled']
        self.service_error = ''
        if self.persist():
            self.service('start' if self.cfg['enabled'] else 'stop')
        self.refresh()

    def retry(self):
        self.service_error = ''
        self.debounce = Debounce()
        if not self.cfg['enabled']:
            self.cfg['enabled'] = True
            if not self.persist():
                return
        self.service('restart')

    def change_autostart(self, switch, _):
        self.service('enable' if switch.get_active() else 'disable')

    def change_port(self, entry):
        try:
            port = int(entry.get_text().strip())
            candidate = dict(self.cfg, port=port)
            save_config(candidate)
            self.cfg = candidate
            self.port_error.set_visible(False)
            self.message(self.t('Port saved', 'Порт сохранён'))
        except (OSError, ValueError):
            self.port_error.set_text(self.t('Use a number from 1024 to 65535, except 5037.',
                                            'Укажите число от 1024 до 65535, кроме 5037.'))
            self.port_error.set_visible(True)

    def change_mode(self, row, _):
        self.cfg['mode'] = ('allowlist', 'blocklist', 'disabled')[row.get_selected()]
        self.persist()

    def change_language(self, row, _):
        self.cfg['language'] = ('system', 'en', 'ru')[row.get_selected()]
        if self.persist():
            # Rebuild with the new language and come back to the same screen.
            GLib.idle_add(self.rebuild_more)

    def rebuild_more(self):
        self.build()
        self.refresh()
        self.open_more()
        return False

    def rule_current(self, kind):
        if not self.network or not self.network.identity:
            self.message(self.t('Current network is unknown', 'Текущая сеть не определена'))
            return
        identity = self.network.identity
        other = 'deny' if kind == 'allow' else 'allow'
        self.cfg[other] = [x for x in self.cfg[other] if x != identity]
        if identity not in self.cfg[kind]:
            self.cfg[kind].append(identity)
        if self.persist():
            self.message(self.t('Network allowed', 'Сеть разрешена') if kind == 'allow' else
                         self.t('Network blocked', 'Сеть заблокирована'))
        self.render_rules()
        self.update_network()

    def render_rules(self):
        group = getattr(self, 'rules_group', None)
        if group is None:
            return
        for row in self.rules_rows:
            group.remove(row)
        self.rules_rows = []
        for kind in ('allow', 'deny'):
            for identity in self.cfg[kind]:
                row = Adw.ActionRow(title=identity, title_selectable=True,
                                    subtitle=self.t('Allowed', 'Разрешена') if kind == 'allow' else
                                    self.t('Blocked', 'Заблокирована'))
                row.add_prefix(Gtk.Image(icon_name='adbst-check-symbolic' if kind == 'allow' else
                                         'adbst-blocked-symbolic'))

                def remove(_, field=kind, value=identity):
                    self.cfg[field].remove(value)
                    self.persist()
                    self.render_rules()
                    self.update_network()
                trash = Gtk.Button(icon_name='user-trash-symbolic', valign=Gtk.Align.CENTER,
                                   tooltip_text=self.t('Remove rule', 'Удалить правило'))
                trash.add_css_class('flat')
                trash.connect('clicked', remove)
                row.add_suffix(trash)
                group.add(row)
                self.rules_rows.append(row)
        if not self.rules_rows:
            row = Adw.ActionRow(title=self.t('No rules yet', 'Правил пока нет'),
                                subtitle=self.t('Allow this network above', 'Разрешите эту сеть выше'))
            group.add(row)
            self.rules_rows.append(row)

    def render_keys(self):
        group = getattr(self, 'keys_group', None)
        if group is None:
            return
        for row in self.keys_rows:
            group.remove(row)
        self.keys_rows = []
        for key in self.cfg['keys']:
            parts = key.split(None, 2)
            row = Adw.ActionRow(title=asyncssh.import_public_key(key).get_fingerprint(), title_selectable=True,
                                subtitle=parts[2] if len(parts) > 2 else parts[0])
            row.add_css_class('mono')
            row.add_prefix(Gtk.Image(icon_name='adbst-key-symbolic'))

            def remove(_, value=key):
                self.cfg['keys'].remove(value)
                self.persist()
                self.render_keys()
            trash = Gtk.Button(icon_name='user-trash-symbolic', valign=Gtk.Align.CENTER,
                               tooltip_text=self.t('Remove key', 'Удалить ключ'))
            trash.add_css_class('flat')
            trash.connect('clicked', remove)
            row.add_suffix(trash)
            group.add(row)
            self.keys_rows.append(row)
        if not self.keys_rows:
            row = Adw.ActionRow(title=self.t('No keys yet', 'Ключей пока нет'),
                                subtitle=self.t('Without a key nobody can connect', 'Без ключа никто не подключится'))
            group.add(row)
            self.keys_rows.append(row)

    # Live state ---------------------------------------------------------------

    def refresh(self):
        # NetworkManager can time out. Never block GTK's event loop on it.
        now = time.monotonic()
        if not self.network_refreshing and now - self.network_checked >= 3:
            self.network_refreshing = True
            self.network_checked = now

            def worker():
                network = current_network()

                def done():
                    self.network = network
                    self.network_refreshing = False
                    self.update_network()
                    self.render_main()
                    return False
                GLib.idle_add(done)
            threading.Thread(target=worker, daemon=True).start()
        try:
            self.cfg = load_config()
            self.state = read_status()
        except (OSError, ValueError):
            self.state = {'state': 'error', 'error': self.t('Cannot read settings or status',
                                                            'Ошибка чтения настроек или состояния')}
        self.render_main()
        self.update_connect()
        return True

    def set_once(self, key, value, apply):
        if self.shown.get(key) != value:
            self.shown[key] = value
            apply(value)

    def render_main(self):
        identity = self.network.identity if self.network else ''
        tone, problem = classify(self.cfg, self.state, identity, self.service_error)
        self.problem = self.debounce.confirm(problem, time.monotonic())
        tone = shown_tone(tone, self.problem)
        if tone != self.tone:
            for name in ('ready', 'working', 'attention', 'off'):
                self.status.remove_css_class('tone-' + name)
            self.status.add_css_class('tone-' + tone)
            self.tone = tone
            self.glow.set_tone(tone)
        status = {'ready': self.t('Tunnel is on', 'Туннель включён'),
                  'working': self.t('Getting ready…', 'Подготовка…'),
                  'attention': self.t('Needs attention', 'Нужно внимание'),
                  'off': self.t('Tunnel is off', 'Туннель выключен')}[tone]
        self.set_once('status', status, self.status.set_text)
        if self.problem:
            hint = self.t('Fix it, then tap the big button again',
                          'Исправьте и снова нажмите на большую кнопку') if not self.cfg['enabled'] else \
                self.t('Tap the red note above to fix it', 'Нажмите на красную подсказку выше')
        elif not self.cfg['enabled'] and not self.cfg['keys']:
            hint = self.t('First add your SSH key in “Networks & keys”',
                          'Сначала добавьте SSH-ключ в «Сети и ключи»')
        elif self.cfg['enabled']:
            hint = self.t('Tap the big button to turn it off', 'Нажмите на большую кнопку, чтобы выключить')
        else:
            hint = self.t('Tap the big button to turn it on', 'Нажмите на большую кнопку, чтобы включить')
        self.set_once('hint', hint, self.hint.set_text)
        action = self.t('Turn the tunnel off', 'Выключить туннель') if self.cfg['enabled'] else \
            self.t('Turn the tunnel on', 'Включить туннель')
        if self.shown.get('action') != action:
            self.shown['action'] = action
            self.toggle.set_tooltip_text(action)
            self.toggle.update_property([Gtk.AccessibleProperty.LABEL], [action])
        where = f'{self.state.get("address")} : {self.cfg["port"]}' if tone == 'ready' and \
            self.state.get('address') else ''
        self.set_once('where', where, lambda v: (self.where.set_text(v), self.where.set_visible(bool(v))))
        chip = self.pick(self.problem.title) if self.problem else ''
        self.set_once('chip', chip, lambda v: (self.chip_text.set_text(v), self.chip.set_visible(bool(v))))

        keys = len(self.cfg['keys'])
        summaries = {
            'connect': self.t('Commands for your computer', 'Команды для компьютера'),
            'networks': (self.t('Keys: ', 'Ключей: ') + str(keys) +
                         (' · ' + identity.split(':', 1)[-1] if identity else '')),
            'port': self.t('Port ', 'Порт ') + str(self.cfg['port']),
            'more': {'system': self.t('Language: system', 'Язык: системный'), 'en': 'English',
                     'ru': 'Русский'}[self.cfg['language']] + ' · ' + self.t('About', 'О приложении'),
        }
        for key, text in summaries.items():
            self.set_once('tile-' + key, text, self.tiles[key].set_text)
