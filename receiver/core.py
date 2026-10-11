import re
import sqlite3
import threading
import time
import secrets
import csv
import json
import hashlib
import math
from datetime import datetime, timezone
from contextlib import contextmanager


class InvalidMessage(ValueError):
    pass


def validate_message(data):
    if not isinstance(data, dict):
        raise InvalidMessage('消息格式无效')
    key, text = data.get('id'), data.get('text')
    if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', key):
        raise InvalidMessage('消息编号无效')
    if not isinstance(text, str) or not text or len(text) > 20000:
        raise InvalidMessage('文字为空或超过两万字')
    if any(ord(c) < 32 and c not in '\r\n\t' for c in text):
        raise InvalidMessage('文字包含无效控制字符')
    try:
        text.encode('utf-8', errors='strict')
    except UnicodeError:
        raise InvalidMessage('文字编码无效') from None
    return key, text


class Relay:
    """Persist before injection; never replay an uncertain OS side effect."""

    def __init__(self, database, injector, *, target_provider=None, clock=time.monotonic, focus_policy="current"):
        if focus_policy not in ("current", "pinned"):
            raise ValueError("Invalid focus policy")
        self.focus_policy = focus_policy
        self.database = str(database)
        self.injector = injector
        self.lock = threading.RLock()
        self._paused = False
        self.target_provider = target_provider
        self.clock = clock
        self.session = None
        with self._connect() as db:
            db.execute('CREATE TABLE IF NOT EXISTS messages '
                       '(id TEXT PRIMARY KEY, text TEXT NOT NULL, status TEXT NOT NULL, '
                       'note TEXT NOT NULL, created REAL NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS phone_outbox '
                       '(slot INTEGER PRIMARY KEY CHECK(slot=1), id TEXT NOT NULL, '
                       'text TEXT NOT NULL, status TEXT NOT NULL, created REAL NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS ordered_messages '
                       '(stream TEXT NOT NULL, seq INTEGER NOT NULL, message_id TEXT NOT NULL UNIQUE, '
                       'text_hash TEXT NOT NULL, PRIMARY KEY(stream, seq))')
            db.execute("UPDATE messages SET status='saved', note='程序曾中断，请在电脑历史中核对' WHERE status='pending'")

    @property
    def paused(self):
        return self._paused

    @paused.setter
    def paused(self, value):
        with self.lock:
            self._paused = bool(value)
            if self._paused:
                self.session = None

    def begin_session(self):
        with self.lock:
            self.session = None
            if self.paused or self.target_provider is None:
                return {'ok': False, 'note': '请先在电脑托盘恢复自动输入'}
            try:
                target = self.target_provider()
            except RuntimeError as exc:
                return {'ok': False, 'note': str(exc)}
            token = secrets.token_urlsafe(32)
            self.session = (token, target, self.clock() + 120)
            return {'ok': True, 'session': token, 'next_seq': 1, 'focus_policy': self.focus_policy,
                    'note': '已开始持续输入，文字进入电脑当前焦点' if self.focus_policy == 'current'
                            else '已连接当前电脑窗口，可以使用手机输入法'}

    def _target_for(self, data):
        session = self.session
        if session is None or data.get('session') != session[0]:
            raise RuntimeError('输入会话已失效，请选中电脑输入框后重新开始')
        if self.clock() >= session[2]:
            self.session = None
            raise RuntimeError('两分钟未输入，会话已暂停，请重新开始')
        try:
            target = self.target_provider()
            if self.focus_policy == 'pinned' and target != session[1]:
                raise RuntimeError('电脑输入窗口已改变，请选中目标后重新开始')
        except Exception:
            self.session = None
            raise
        return target

    @contextmanager
    def _connect(self):
        db = sqlite3.connect(self.database, timeout=10)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    @contextmanager
    def _disarm_on_failure(self, data):
        try:
            yield
        except Exception:
            if self.session is not None and data.get('session') == self.session[0]:
                self.session = None
            raise

    def accept(self, data):
        key, text = validate_message(data)
        seq = data.get('seq')
        stream = None
        if seq is not None:
            if type(seq) is not int or seq < 1 or seq > 2**53 - 1 or not isinstance(data.get('session'), str) or not data['session']:
                raise InvalidMessage('消息顺序编号无效')
            stream = hashlib.sha256(data['session'].encode()).hexdigest()
        with self.lock, self._disarm_on_failure(data):
            with self._connect() as db:
                row = db.execute('SELECT * FROM messages WHERE id=?', (key,)).fetchone()
                if row:
                    ordered = db.execute('SELECT stream, seq FROM ordered_messages WHERE message_id=?', (key,)).fetchone()
                    if (ordered is None) != (seq is None) or ordered and (ordered['stream'] != stream or ordered['seq'] != seq):
                        raise InvalidMessage('同一消息编号的会话或顺序不一致')
                    if row['text'] != text:
                        raise InvalidMessage('同一消息编号对应了不同文字')
                    if row['status'] == 'pending':
                        note = '上次输入结果未能记录，请核对电脑；不会自动重复输入'
                        db.execute("UPDATE messages SET status='saved', note=? WHERE id=?", (note, key))
                        return self._receipt(key, 'saved', note, seq)
                    return self._receipt(key, row['status'], row['note'], seq)
                if seq is not None:
                    conflict = db.execute('SELECT message_id FROM ordered_messages WHERE stream=? AND seq=?', (stream, seq)).fetchone()
                    if conflict:
                        raise InvalidMessage('顺序编号已被另一条消息占用')
                    previous = db.execute('SELECT max(seq) FROM ordered_messages WHERE stream=?', (stream,)).fetchone()[0] or 0
                    if seq != previous + 1:
                        raise InvalidMessage('消息乱序，未执行输入')
                    db.execute('INSERT INTO ordered_messages VALUES (?,?,?,?)',
                               (stream, seq, key, hashlib.sha256(text.encode()).hexdigest()))
                db.execute('INSERT INTO messages VALUES (?,?,?,?,?)',
                           (key, text, 'pending', '', time.time()))
            status, note = 'saved', '电脑已暂停自动输入，文字保存在历史中'
            if not self.paused:
                try:
                    if self.target_provider is None:
                        self.injector(text)
                    else:
                        target = self._target_for(data)
                        try:
                            # Text-only v2: no Enter/Tab or clipboard shortcuts.
                            plain = text.replace('\r\n', ' ').replace('\r', ' ').replace('\n', ' ').replace('\t', ' ')
                            self.injector(plain, target)
                        except Exception:
                            self.session = None
                            raise
                        token, target, _ = self.session
                        self.session = (token, target, self.clock() + 120)
                    status, note = 'inserted', '已交给电脑输入，请以目标窗口显示为准'
                except Exception as exc:
                    # Only controlled error descriptions, never include message text.
                    note = '已保存，请核对电脑：' + str(exc)[:180]
            with self._connect() as db:
                db.execute('UPDATE messages SET status=?, note=? WHERE id=?', (status, note, key))
            return self._receipt(key, status, note, seq)

    @staticmethod
    def _receipt(key, status, note, seq):
        value = {'ok': True, 'id': key, 'status': status, 'note': note}
        if seq is not None: value['seq'] = seq
        return value

    def recent(self, limit=100):
        with self.lock, self._connect() as db:
            return [dict(r) for r in db.execute('SELECT * FROM messages ORDER BY created DESC LIMIT ?', (limit,))]

    def history(self, *, keyword="", since=None, until=None, offset=0, page_size=100):
        """Bounded pages, unbounded retained history; filters use parameters, not SQL text."""
        if not isinstance(keyword, str) or len(keyword) > 20000:
            raise ValueError("Invalid search keyword")
        if type(offset) is not int or offset < 0 or type(page_size) is not int or not 1 <= page_size <= 1000:
            raise ValueError("Invalid history page")
        clauses, args = [], []
        for value, op in ((since, '>='), (until, '<=')):
            if value is not None:
                if type(value) not in (int, float) or not math.isfinite(value):
                    raise ValueError("Invalid history time")
                clauses.append('created ' + op + ' ?'); args.append(value)
        if since is not None and until is not None and since > until:
            raise ValueError("Invalid time range")
        if keyword:
            clauses.append('instr(text, ?) > 0'); args.append(keyword)
        where = (' WHERE ' + ' AND '.join(clauses)) if clauses else ''
        with self.lock, self._connect() as db:
            total = db.execute('SELECT count(*) FROM messages' + where, args).fetchone()[0]
            rows = db.execute('SELECT * FROM messages' + where + ' ORDER BY created DESC, id DESC LIMIT ? OFFSET ?',
                              [*args, page_size, offset]).fetchall()
        return {'total': total, 'offset': offset, 'items': [dict(r) for r in rows]}

    def export_history(self, stream, format, *, keyword="", since=None, until=None):
        """Stream one consistent DB snapshot; caller chooses a user-owned output file."""
        if format not in ('txt', 'md', 'json', 'csv'):
            raise ValueError("Unsupported export format")
        # Reuse validated filters; do not truncate export at UI's page size.
        self.history(keyword=keyword, since=since, until=until)
        clauses, args = [], []
        for value, op in ((since, '>='), (until, '<=')):
            if value is not None: clauses.append('created ' + op + ' ?'); args.append(value)
        if keyword: clauses.append('instr(text, ?) > 0'); args.append(keyword)
        where = (' WHERE ' + ' AND '.join(clauses)) if clauses else ''
        count = 0
        fields = ['id', 'created', 'status', 'note', 'text']
        writer = csv.DictWriter(stream, fieldnames=fields) if format == 'csv' else None
        if writer: writer.writeheader()
        if format == 'json': stream.write('[')
        with self.lock, self._connect() as db:
            db.execute('BEGIN')
            for row in db.execute('SELECT * FROM messages' + where + ' ORDER BY created, id', args):
                item = dict(row)
                stamp = datetime.fromtimestamp(item['created'], timezone.utc).isoformat()
                if format == 'json':
                    stream.write((',' if count else '') + json.dumps(item, ensure_ascii=False))
                elif format == 'csv':
                    # Stop spreadsheet formula interpretation while preserving the raw DB.
                    writer.writerow({k: "'" + str(v) if isinstance(v, str) and v.startswith(('=', '+', '-', '@', '\t', '\r')) else v
                                     for k, v in item.items()})
                elif format == 'md':
                    stream.write('\n## ' + stamp + ' · ' + item['status'] + '\n\n')
                    stream.write('\n'.join('    ' + line for line in item['text'].splitlines()) + '\n')
                else: stream.write(stamp + ' [' + item['status'] + ']\n' + item['text'] + '\n\n')
                count += 1
        if format == 'json': stream.write(']\n')
        return count

    def queue_for_phone(self, text):
        _, text = validate_message({'id': 'phone-outbox', 'text': text})
        key = secrets.token_urlsafe(24)
        with self.lock, self._connect() as db:
            db.execute('INSERT OR REPLACE INTO phone_outbox VALUES (?,?,?,?,?)',
                       (1, key, text, 'pending', time.time()))
        return {'ok': True, 'id': key, 'status': 'pending'}

    def phone_outbox(self):
        with self.lock, self._connect() as db:
            row = db.execute('SELECT id, text, status FROM phone_outbox WHERE slot=1').fetchone()
            if row is None or row['status'] != 'pending':
                return {'ok': True, 'available': False}
            return {'ok': True, 'available': True, 'id': row['id'], 'text': row['text']}

    def ack_phone_outbox(self, key):
        if not isinstance(key, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', key):
            raise InvalidMessage('消息编号无效')
        with self.lock, self._connect() as db:
            row = db.execute('SELECT id FROM phone_outbox WHERE slot=1').fetchone()
            if row is None or row['id'] != key:
                raise InvalidMessage('待接收文字编号已改变')
            db.execute("UPDATE phone_outbox SET status='received' WHERE slot=1", ())
        return {'ok': True, 'id': key, 'status': 'received'}
