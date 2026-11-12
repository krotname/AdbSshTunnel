import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from adb_ssh_tunnel.messages import Debounce, classify, shown_tone  # noqa: E402

ON = {'enabled': True, 'port': 19191}
OFF = {'enabled': False, 'port': 19191}


class ClassifyTest(unittest.TestCase):
    def test_off_and_ready(self):
        self.assertEqual(classify(OFF, {'state': 'stopped'}), ('off', None))
        self.assertEqual(classify(ON, {'state': 'listening'}), ('ready', None))
        self.assertEqual(classify(ON, {'state': 'starting'}), ('working', None))

    def test_problems_lead_to_their_screens(self):
        cases = {
            'no_keys': ('keys', 'keys'),
            'adb_unavailable': ('adb', 'retry'),
        }
        for state, (key, action) in cases.items():
            tone, problem = classify(ON, {'state': state})
            self.assertEqual((tone, problem.key, problem.action), ('attention', key, action), state)

    def test_network(self):
        _, offline = classify(ON, {'state': 'network_blocked'})
        self.assertEqual((offline.key, offline.action), ('offline', 'networks'))
        _, blocked = classify(ON, {'state': 'network_blocked', 'network': 'wifi:Cafe'})
        self.assertEqual(blocked.key, 'blocked')
        _, blocked = classify(ON, {'state': 'network_blocked'}, network_identity='wifi:Cafe')
        self.assertEqual(blocked.key, 'blocked')

    def test_errors_are_plain_but_keep_details(self):
        raw = '[Errno 98] error while attempting to bind on address: address already in use'
        _, port = classify(ON, {'state': 'error', 'error': raw})
        self.assertEqual((port.key, port.action, port.details), ('port', 'port', raw))
        self.assertIn('19191', port.title[0])
        self.assertIn('19191', port.title[1])
        _, folder = classify(ON, {'state': 'error', 'error': 'Unsafe config directory'})
        self.assertEqual(folder.key, 'folder')
        _, unknown = classify(ON, {'state': 'error', 'error': 'boom'})
        self.assertEqual((unknown.key, unknown.details), ('unknown', 'boom'))

    def test_service_error_wins_even_when_off(self):
        tone, problem = classify(OFF, {'state': 'stopped'}, service_error='Unit not found')
        self.assertEqual((tone, problem.key, problem.action, problem.details),
                         ('attention', 'service', 'retry', 'Unit not found'))

    def test_every_text_has_both_languages(self):
        states = [{'state': name} for name in ('no_keys', 'network_blocked', 'adb_unavailable', 'stopped')]
        states += [{'state': 'error', 'error': e} for e in ('errno 98', 'unsafe', 'x')]
        problems = [classify(ON, s)[1] for s in states] + [classify(ON, {}, service_error='x')[1]]
        for problem in problems:
            for pair in (problem.title, problem.what, problem.todo):
                self.assertEqual(len(pair), 2)
                self.assertTrue(all(pair), problem.key)
                self.assertNotEqual(pair[0], pair[1], problem.key)


class DebounceTest(unittest.TestCase):
    def test_short_glitches_stay_calm(self):
        _, problem = classify(ON, {'state': 'adb_unavailable'})
        gate = Debounce(1.5)
        self.assertIsNone(gate.confirm(problem, 10.0))
        self.assertEqual(shown_tone('attention', gate.confirm(problem, 11.0)), 'working')
        self.assertIs(gate.confirm(problem, 11.6), problem)
        self.assertIsNone(gate.confirm(None, 12.0))
        self.assertIsNone(gate.confirm(problem, 12.1), 'A new episode waits again')

    def test_slow_start_and_immediate_service_errors(self):
        _, stopped = classify(ON, {'state': 'stopped'})
        gate = Debounce(1.5)
        gate.confirm(stopped, 0.0)
        self.assertIsNone(gate.confirm(stopped, 5.0), 'Daemon start-up must not flash red')
        self.assertIs(gate.confirm(stopped, 8.5), stopped)
        _, service = classify(ON, {}, service_error='failed')
        self.assertIs(Debounce().confirm(service, 0.0), service)

    def test_tone(self):
        self.assertEqual(shown_tone('ready', None), 'ready')
        self.assertEqual(shown_tone('off', None), 'off')
        self.assertEqual(shown_tone('working', object()), 'attention')


if __name__ == '__main__':
    unittest.main()
