import csv
from datetime import datetime
import io
import json
from pathlib import Path
import tempfile
import unittest
from receiver.core import Relay
from receiver.history_browser import HistoryBrowser, parse_history_filters


class HistoryBrowserTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.relay = Relay(self.folder / 'messages.db', lambda text: None)
        self.start = datetime(2026, 10, 1).timestamp()
        with self.relay._connect() as db:
            db.executemany('INSERT INTO messages VALUES (?,?,?,?,?)', [
                (f'message-{i:04}', ('关键词' if i % 2 == 0 else '其他') + f' {i} 🙂\n第二行',
                 'saved', '', self.start + i * 86400) for i in range(235)])
        self.browser = HistoryBrowser(self.relay)

    def test_all_235_records_are_browsable_without_retention_cap(self):
        seen = set()
        page = self.browser.query()
        self.assertEqual(235, page['total'])
        for _ in range(3):
            seen.update(row['id'] for row in page['items'])
            page = self.browser.move(1)
        self.assertEqual(235, len(seen))
        self.assertEqual(200, self.browser.offset)
        self.assertEqual(35, len(page['items']))
        self.browser.move(-1)
        self.assertEqual(100, self.browser.offset)
        self.assertEqual(235, self.relay.history()['total'])

    def test_keyword_and_inclusive_local_date_filters_query_real_db(self):
        page = self.browser.query('关键词', '2026-10-01', '2026-10-05')
        self.assertEqual(3, page['total'])
        self.assertEqual(['message-0004', 'message-0002', 'message-0000'],
                         [row['id'] for row in page['items']])
        self.assertEqual(0, self.browser.offset)
        self.assertEqual([], self.browser.query('no such phrase')['items'])

    def test_invalid_query_preserves_applied_filters_and_page(self):
        self.browser.query('关键词')
        self.browser.move(1)
        before = (self.browser.filters.copy(), self.browser.offset, self.browser.page)
        for since, until in [('bad date', ''), ('2026-10-10', '2026-10-01')]:
            with self.assertRaises(ValueError):
                self.browser.query('其他', since, until)
            self.assertEqual(before, (self.browser.filters, self.browser.offset, self.browser.page))

    def test_four_exports_include_all_pages_and_respect_query(self):
        for keyword, expected in [('', 235), ('关键词', 118)]:
            self.browser.query(keyword)
            self.browser.move(1)
            for format in ('txt', 'md', 'json', 'csv'):
                destination = self.folder / ('output.' + format)
                self.assertEqual(expected, self.browser.export(destination, format))
                output = destination.read_text(encoding='utf-8')
                self.assertIn('🙂', output)
                if format == 'json':
                    self.assertEqual(expected, len(json.loads(output)))
                    self.assertTrue(all('session' not in item for item in json.loads(output)))
                elif format == 'csv':
                    self.assertEqual(expected, len(list(csv.DictReader(io.StringIO(output)))))
                elif format == 'md':
                    self.assertEqual(expected, output.count('\n## '))
                else:
                    self.assertEqual(expected, output.count('[saved]'))
        self.assertFalse(list(self.folder.glob('.history-*.tmp')))

    def test_failed_export_preserves_destination_and_removes_partial_file(self):
        destination = self.folder / 'history.txt'
        destination.write_text('existing user file', encoding='utf-8')
        self.relay.database = str(self.folder / 'unavailable' / 'database')
        with self.assertRaises(Exception):
            self.browser.export(destination, 'txt')
        self.assertEqual('existing user file', destination.read_text())
        self.assertFalse(list(self.folder.glob('.history-*.tmp')))

    def test_exact_timestamp_and_blank_bounds(self):
        self.assertEqual({'keyword': '词', 'since': None, 'until': None}, parse_history_filters('词'))
        filters = parse_history_filters('', '2026-10-01 12:34:56', '2026-10-01 12:34:56')
        self.assertEqual(datetime(2026, 10, 1, 12, 34, 56).timestamp(), filters['since'])
        self.assertEqual(filters['since'], filters['until'])


if __name__ == '__main__':
    unittest.main()
