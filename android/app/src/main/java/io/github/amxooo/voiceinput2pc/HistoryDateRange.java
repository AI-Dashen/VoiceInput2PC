package io.github.amxooo.voiceinput2pc;

import java.time.LocalDate;
import java.time.ZoneId;

/** Inclusive local calendar dates; next-day boundary handles daylight-saving days. */
final class HistoryDateRange {
    final long since, until;
    private HistoryDateRange(long since, long until) { this.since = since; this.until = until; }
    static HistoryDateRange bounds(long since, long until) {
        if (since < 0 || until < since) throw new IllegalArgumentException("Invalid history range");
        return new HistoryDateRange(since, until);
    }
    static HistoryDateRange parse(String from, String to) {
        ZoneId zone = ZoneId.systemDefault();
        long start = from.trim().isEmpty() ? 0 : LocalDate.parse(from.trim()).atStartOfDay(zone).toInstant().toEpochMilli();
        long end = to.trim().isEmpty() ? Long.MAX_VALUE : LocalDate.parse(to.trim()).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() - 1;
        if (start < 0 || end < start) throw new IllegalArgumentException("开始日期须早于结束日期");
        return new HistoryDateRange(start, end);
    }
}
