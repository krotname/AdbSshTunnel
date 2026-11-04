import argparse
import asyncio
import json
import signal

from . import __version__


def main():
    parser = argparse.ArgumentParser(description='Key-only SSH access to the local Linux ADB server')
    parser.add_argument('--version', action='version', version=__version__)
    parser.add_argument('command', nargs='?', choices=('gui', 'serve', 'status'), default='gui')
    args = parser.parse_args()
    if args.command == 'gui':
        from .ui import Application
        return Application().run(None)
    from .core import Gateway, read_status
    if args.command == 'status':
        print(json.dumps(read_status(), ensure_ascii=False))
        return 0

    async def serve():
        task = asyncio.create_task(Gateway().run())
        loop = asyncio.get_running_loop()
        for sig in (signal.SIGTERM, signal.SIGINT):
            loop.add_signal_handler(sig, task.cancel)
        try:
            await task
        except asyncio.CancelledError:
            pass
    asyncio.run(serve())
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
