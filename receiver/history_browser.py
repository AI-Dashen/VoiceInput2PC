"""Desktop history state and file export, independent of Tk and Windows APIs."""
from datetime import datetime, timedelta
import os
from pathlib import Path
import tempfile


def parse_history_filters(keyword='', since='', until=''):
    """Dates use local time; an end date includes the entire selected day."""
    def parse(value, end=False):
        value = value.strip()
        if not value:
            return None
        try:
            stamp = datetime.fromisoformat(value)
            if len(value) == 10 and end:
                stamp += timedelta(days=1)
                return stamp.timestamp() - 0.000001
            return stamp.timestamp()
        except (ValueError, OverflowError, OSError):
            raise ValueError('时间格式应为 YYYY-MM-DD 或 YYYY-MM-DD HH:MM:SS') from None
    start, end = parse(since), parse(until, True)
    if start is not None and end is not None and start > end:
        raise ValueError('开始时间不能晚于结束时间')
    return dict(keyword=keyword, since=start, until=end)


class HistoryBrowser:
    def __init__(self, relay, page_size=100):
        self.relay = relay
        self.page_size = page_size
        self.offset = 0
        self.filters = parse_history_filters()
        self.page = {'total': 0, 'offset': 0, 'items': []}

    def query(self, keyword='', since='', until=''):
        filters = parse_history_filters(keyword, since, until)
        page = self.relay.history(**filters, offset=0, page_size=self.page_size)
        self.filters, self.offset, self.page = filters, 0, page
        return page

    def refresh(self):
        page = self.relay.history(**self.filters, offset=self.offset, page_size=self.page_size)
        if self.offset and self.offset >= page['total']:
            self.offset = max(0, (page['total'] - 1) // self.page_size * self.page_size)
            page = self.relay.history(**self.filters, offset=self.offset, page_size=self.page_size)
        self.page = page
        return page

    def move(self, direction):
        previous = self.offset
        if direction > 0 and self.offset + self.page_size < self.page['total']:
            self.offset += self.page_size
        elif direction < 0:
            self.offset = max(0, self.offset - self.page_size)
        try:
            return self.refresh()
        except Exception:
            self.offset = previous
            raise

    def export(self, destination, format):
        """Replace the chosen file only after a successful complete export."""
        destination = Path(destination)
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', newline='',
                                             dir=destination.parent, prefix='.history-',
                                             suffix='.tmp', delete=False) as stream:
                temporary = Path(stream.name)
                count = self.relay.export_history(stream, format, **self.filters)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, destination)
            return count
        finally:
            if temporary is not None and temporary.exists():
                temporary.unlink()
