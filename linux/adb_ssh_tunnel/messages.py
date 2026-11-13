"""Plain-language tunnel states and problems. Pure Python, no GTK, so it is unit-tested."""
from __future__ import annotations

from dataclasses import dataclass

# How long a problem must persist before the screen turns red. Short daemon
# start-up gaps (status file not written yet, first network probe) stay amber.
PROBLEM_DELAY = 1.5
# A freshly started service needs a few seconds to import asyncssh and write
# its first heartbeat, especially on phones.
SLOW_PROBLEMS = {'stopped': 8.0}


@dataclass(frozen=True)
class Problem:
    key: str
    icon: str
    title: tuple[str, str]
    what: tuple[str, str]
    todo: tuple[str, str]
    # 'networks', 'keys', 'port' open the matching screen; 'retry' restarts the service.
    action: str
    details: str = ''


def _problem(key, icon, title, what, todo, action, details=''):
    return Problem(key, icon, title, what, todo, action, details)


def _service_texts(snap: bool):
    if snap:
        return ("Background tunnel didn't start", 'Фоновый туннель не запустился'), \
            ('The app could not run its background tunnel process in this Snap session.',
             'Не удалось запустить фоновый процесс туннеля в сеансе Snap.'), \
            ('1. Tap Try again.\n2. If it repeats, quit the app from More and open it again.',
             '1. Нажмите «Попробовать снова».\n2. Если повторится, выйдите из приложения '
             'в «Дополнительно» и откройте его снова.')
    return ("Background service didn't start", 'Фоновая служба не запустилась'), \
        ('The system did not run adb-ssh-tunnel.service in your user session.',
         'Система не запустила adb-ssh-tunnel.service в вашем сеансе.'), \
        ('1. Tap Try again.\n2. If it repeats, run in a terminal:\n'
         'systemctl --user status adb-ssh-tunnel.service',
         '1. Нажмите «Попробовать снова».\n2. Если повторится, выполните в терминале:\n'
         'systemctl --user status adb-ssh-tunnel.service')


def _stopped_texts(snap: bool):
    if snap:
        return ("The tunnel process isn't running", 'Процесс туннеля не работает'), \
            ('The tunnel is switched on, but its background process is not running.',
             'Туннель включён, но его фоновый процесс не запущен.'), \
            ('1. Tap Try again.\n2. If it repeats, quit the app from More and open it again.',
             '1. Нажмите «Попробовать снова».\n2. Если повторится, выйдите из приложения '
             'в «Дополнительно» и откройте его снова.')
    return ("The tunnel service isn't running", 'Служба туннеля не работает'), \
        ('The tunnel is switched on, but its background service is not running.',
         'Туннель включён, но его фоновая служба не запущена.'), \
        ('1. Tap Try again.\n2. If it repeats, run in a terminal:\n'
         'journalctl --user -u adb-ssh-tunnel.service',
         '1. Нажмите «Попробовать снова».\n2. Если повторится, выполните в терминале:\n'
         'journalctl --user -u adb-ssh-tunnel.service')


def classify(config: dict, state: dict, network_identity: str = '', service_error: str = '',
             snap: bool = False):
    """Return (tone, problem). Tone is off, working, ready or attention.

    The problem is returned even before it is confirmed; the interface delays it.
    When ``snap`` is true, the repair hints avoid systemctl/journalctl, which a
    strictly confined Snap cannot use.
    """
    if service_error:
        (title_en, title_ru), (what_en, what_ru), (todo_en, todo_ru) = _service_texts(snap)
        return 'attention', _problem(
            'service', 'dialog-warning-symbolic',
            (title_en, title_ru),
            (what_en, what_ru),
            (todo_en, todo_ru),
            'retry', service_error)
    if not config.get('enabled'):
        return 'off', None
    name = state.get('state')
    error = str(state.get('error') or '')
    if name == 'listening':
        return 'ready', None
    if name == 'no_keys':
        return 'attention', _problem(
            'keys', 'adbst-key-symbolic',
            ("Add your computer's key", 'Добавьте ключ компьютера'),
            ('The tunnel lets in only computers whose public key it knows. No key is saved yet.',
             'Туннель пускает только компьютеры, чей публичный ключ он знает. Ключ ещё не добавлен.'),
            ('1. On the computer, open ~/.ssh/id_ed25519.pub.\n'
             '2. Copy the whole line — it starts with ssh-ed25519.\n'
             '3. Tap the button below and paste it.',
             '1. На компьютере откройте ~/.ssh/id_ed25519.pub.\n'
             '2. Скопируйте всю строку — она начинается с ssh-ed25519.\n'
             '3. Нажмите кнопку ниже и вставьте её.'),
            'keys')
    if name == 'network_blocked' and not (state.get('network') or network_identity):
        return 'attention', _problem(
            'offline', 'adbst-offline-symbolic',
            ('No network', 'Нет сети'),
            ('This device is not connected to Wi-Fi, Ethernet or mobile data, '
             'so the tunnel has nothing to work on.',
             'Устройство не подключено ни к Wi-Fi, ни к Ethernet, ни к мобильной сети — '
             'туннелю не на чем работать.'),
            ('1. Connect to Wi-Fi or another network.\n'
             '2. The tunnel starts by itself as soon as a network appears.',
             '1. Подключитесь к Wi-Fi или другой сети.\n'
             '2. Туннель запустится сам, как только появится сеть.'),
            'networks')
    if name == 'network_blocked':
        return 'attention', _problem(
            'blocked', 'adbst-blocked-symbolic',
            ("This network isn't allowed", 'Эта сеть не разрешена'),
            ('Your rules allow the tunnel only on chosen networks, and the current one is not allowed.',
             'По вашим правилам туннель работает только в выбранных сетях, а текущая не разрешена.'),
            ('1. Open Networks & keys.\n2. Tap Allow this network.',
             '1. Откройте «Сети и ключи».\n2. Нажмите «Разрешить эту сеть».'),
            'networks')
    if name == 'adb_unavailable':
        return 'attention', _problem(
            'adb', 'adbst-terminal-symbolic',
            ("ADB isn't ready yet", 'ADB ещё не готов'),
            ('The tunnel is on, but the local ADB server at 127.0.0.1:5037 does not answer. '
             'Until it does, the SSH port stays closed.',
             'Туннель включён, но локальный сервер ADB на 127.0.0.1:5037 не отвечает. '
             'Пока он молчит, порт SSH закрыт.'),
            ('1. Open a terminal and run: adb start-server\n2. Tap Try again.',
             '1. Откройте терминал и выполните: adb start-server\n2. Нажмите «Попробовать снова».'),
            'retry')
    if name == 'error':
        lowered = error.lower()
        if 'address already in use' in lowered or 'errno 98' in lowered:
            port = config.get('port', '')
            return 'attention', _problem(
                'port', 'adbst-terminal-symbolic',
                (f'Port {port} is busy', f'Порт {port} занят'),
                (f'Another program already uses SSH port {port}, so the tunnel cannot open it.',
                 f'Порт SSH {port} уже занят другой программой, поэтому туннель не может его открыть.'),
                ('1. Open Port & startup.\n2. Choose another port, for example 19192, and save.',
                 '1. Откройте «Порт и запуск».\n2. Выберите другой порт, например 19192, и сохраните.'),
                'port', error)
        if 'unsafe' in lowered:
            return 'attention', _problem(
                'folder', 'dialog-warning-symbolic',
                ('The settings folder is unsafe', 'Папка настроек небезопасна'),
                ('~/.config/adb-ssh-tunnel is a link or belongs to another user, '
                 'so the app refuses to use it.',
                 'Папка ~/.config/adb-ssh-tunnel — ссылка или принадлежит другому пользователю, '
                 'поэтому приложение её не использует.'),
                ('1. Make the folder a normal folder owned by you.\n2. Tap Try again.',
                 '1. Сделайте папку обычной и своей.\n2. Нажмите «Попробовать снова».'),
                'retry', error)
        return 'attention', _problem(
            'unknown', 'adbst-error-symbolic',
            ('Something went wrong', 'Что-то пошло не так'),
            ('The tunnel stopped with an unexpected message.',
             'Туннель остановился с неожиданным сообщением.'),
            ('1. Tap Try again.\n2. If it repeats, open Technical details and copy them '
             'when you ask for help.',
             '1. Нажмите «Попробовать снова».\n2. Если повторится, откройте «Технические подробности» '
             'и скопируйте их, когда будете просить помощи.'),
            'retry', error)
    if name == 'stopped':
        # Enabled, but the daemon has not written a fresh heartbeat.
        (title_en, title_ru), (what_en, what_ru), (todo_en, todo_ru) = _stopped_texts(snap)
        return 'working', _problem(
            'stopped', 'dialog-warning-symbolic',
            (title_en, title_ru),
            (what_en, what_ru),
            (todo_en, todo_ru),
            'retry')
    return 'working', None


class Debounce:
    """Show a problem only after it has lasted long enough to be real."""

    def __init__(self, delay: float = PROBLEM_DELAY):
        self.delay = delay
        self.key = None
        self.since = 0.0

    def confirm(self, problem, now: float):
        if problem is None:
            self.key = None
            return None
        if problem.key != self.key:
            self.key, self.since = problem.key, now
        if problem.key == 'service':
            return problem
        delay = SLOW_PROBLEMS.get(problem.key, self.delay)
        return problem if now - self.since >= delay else None


def shown_tone(tone: str, problem) -> str:
    """An unconfirmed problem keeps the calm amber 'getting ready' look."""
    if problem is not None:
        return 'attention'
    return 'working' if tone == 'attention' else tone
