import io
import json
import csv
import tempfile
import unittest
from pathlib import Path
from receiver.core import Relay


class CurrentFocusTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.target = 'window-A'
        self.typed = []
        self.relay = Relay(Path(self.temp.name) / 'history.db', self.inject,
                           target_provider=lambda: self.target)
        self.session = self.relay.begin_session()['session']

    def inject(self, text, target):
        self.typed.append((text, target))

    def test_next_confirmed_chunk_follows_current_focus_without_new_session(self):
        first = {'id': 'one', 'text': '第一句', 'session': self.session}
        self.assertEqual('inserted', self.relay.accept(first)['status'])
        self.target = 'window-B'
        self.assertEqual('inserted', self.relay.accept({'id': 'two', 'text': '第二句', 'session': self.session})['status'])
        self.relay.accept(first)
        self.assertEqual([('第一句', 'window-A'), ('第二句', 'window-B')], self.typed)

    def test_failure_after_capture_disarms_and_does_not_replay(self):
        def failure(text, target):
            self.typed.append((text, target))
            raise RuntimeError('focus changed during injection')
        self.relay.injector = failure
        request = {'id': 'uncertain', 'text': '不确定', 'session': self.session}
        self.assertEqual('saved', self.relay.accept(request)['status'])
        self.relay.accept(request)
        self.assertEqual(1, len(self.typed))
        self.assertIsNone(self.relay.session)


class OrderedStreamTests(CurrentFocusTests):
    def message(self, id, seq, text='文字'):
        return {'id': id, 'seq': seq, 'text': text, 'session': self.session}

    def test_ordered_receipt_and_replay_across_restart(self):
        request = self.message('ordered-one', 1)
        self.assertEqual(1, self.relay.accept(request)['seq'])
        self.relay.accept(request)
        restarted = Relay(self.relay.database, self.inject, target_provider=lambda: self.target)
        self.assertEqual('inserted', restarted.accept(request)['status'])
        self.assertEqual(1, len(self.typed))
        # Old token cannot inject a new chunk after receiver restart.
        self.assertEqual('saved', restarted.accept(self.message('ordered-two', 2))['status'])
        self.assertEqual(1, len(self.typed))

    def test_gap_conflicting_sequence_and_changed_payload_are_rejected(self):
        from receiver.core import InvalidMessage
        for request in [self.message('gap', 2), self.message('bool', True)]:
            with self.assertRaises(InvalidMessage): self.relay.accept(request)
        self.assertEqual([], self.typed)
        self.session = self.relay.begin_session()['session']
        self.relay.accept(self.message('original', 1))
        for request in [self.message('other-id', 1), self.message('original', 1, '另一句话'),
                        self.message('original', 2)]:
            with self.assertRaises(InvalidMessage): self.relay.accept(request)
        self.assertEqual(1, len(self.typed))

    def test_no_session_tokens_stored_in_database(self):
        self.relay.accept(self.message('private-session', 1))
        self.assertNotIn(self.session.encode(), Path(self.relay.database).read_bytes())


class HistoryTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.relay = Relay(Path(self.temp.name) / 'history.db', lambda _: None)
        for i in range(125): self.relay.accept({'id': f'row-{i:03d}', 'text': '中文' + str(i)})

    def test_all_history_retained_and_search_is_literal(self):
        self.assertEqual(125, self.relay.history()['total'])
        self.assertEqual(25, len(self.relay.history(offset=100)['items']))
        self.assertEqual(0, self.relay.history(keyword="' OR 1=1 --")['total'])
        self.assertEqual(0, self.relay.history(keyword='%')['total'])
        self.assertEqual(1, self.relay.history(keyword='中文124')['total'])

    def test_export_all_four_formats_without_100_row_truncation(self):
        for format in ['txt', 'md', 'json', 'csv']:
            out = io.StringIO()
            self.assertEqual(125, self.relay.export_history(out, format))
            if format == 'json': self.assertEqual(125, len(json.loads(out.getvalue())))
            if format == 'csv': self.assertEqual(125, len(list(csv.DictReader(io.StringIO(out.getvalue())))))
            self.assertIn('中文124', out.getvalue())

    def test_time_filters_and_invalid_bounds(self):
        first = self.relay.history()['items'][0]['created']
        self.assertEqual(0, self.relay.history(since=first + 10)['total'])
        for args in [{'since': float('nan')}, {'since': 2, 'until': 1}, {'offset': -1}, {'page_size': 0}]:
            with self.assertRaises(ValueError): self.relay.history(**args)

    def test_csv_formula_is_neutralized_and_db_is_unchanged(self):
        self.relay.accept({'id': 'formula', 'text': '=SUM(1,2)'})
        out = io.StringIO()
        self.relay.export_history(out, 'csv', keyword='=SUM')
        row = next(csv.DictReader(io.StringIO(out.getvalue())))
        self.assertEqual("'=SUM(1,2)", row['text'])
        self.assertEqual('=SUM(1,2)', self.relay.history(keyword='=SUM')['items'][0]['text'])


if __name__ == '__main__': unittest.main()
