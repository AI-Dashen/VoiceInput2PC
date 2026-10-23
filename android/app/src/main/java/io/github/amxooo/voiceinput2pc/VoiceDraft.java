package io.github.amxooo.voiceinput2pc;

/** Pure rules for combining independently recognized speech segments. */
final class VoiceDraft {
    private VoiceDraft() { }

    static String append(String existing, String segment) {
        String base = existing == null ? "" : existing;
        String next = segment == null ? "" : segment.trim();
        if (base.isEmpty()) return next;
        if (next.isEmpty()) return base;
        int left = base.codePointBefore(base.length());
        int right = next.codePointAt(0);
        boolean needsSpace = isAsciiWord(left) && isAsciiWord(right)
                && !Character.isWhitespace(left);
        return base + (needsSpace ? " " : "") + next;
    }

    /** Preserve the author's exact prefix/suffix while inserting one recognized segment. */
    static String insert(String prefix,String segment,String suffix) {
        String before=prefix == null ? "" : prefix;
        String after=suffix == null ? "" : suffix;
        if (segment == null || segment.trim().isEmpty()) return before+after;
        String left=append(before,segment);
        boolean space=!left.isEmpty() && !after.isEmpty()
                && isAsciiWord(left.codePointBefore(left.length())) && isAsciiWord(after.codePointAt(0));
        return left+(space ? " " : "")+after;
    }

    private static boolean isAsciiWord(int value) {
        return value < 128 && Character.isLetterOrDigit(value);
    }
}
