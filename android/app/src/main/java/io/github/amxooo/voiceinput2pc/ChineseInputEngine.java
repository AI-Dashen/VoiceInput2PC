package io.github.amxooo.voiceinput2pc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A candidate is supplied by a real decoder; UI never invents a Chinese dictionary. */
public interface ChineseInputEngine extends AutoCloseable {
    enum Layout { FULL_PINYIN, T9 }
    final class Snapshot {
        public final String rawInput;
        public final List<String> candidates;
        public final int page;
        public final boolean hasNext, available;
        public final String error;
        public Snapshot(String rawInput, List<String> candidates, int page,
                boolean hasNext, boolean available, String error) {
            this.rawInput = rawInput == null ? "" : rawInput;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.page = page; this.hasNext = hasNext; this.available = available;
            this.error = error == null ? "" : error;
        }
    }
    Snapshot snapshot();
    void input(char value);
    void backspace();
    /** Returns only a real committed decoder result, or null for an unfinished selection. */
    String select(int index);
    void nextPage();
    void previousPage();
    void setLayout(Layout layout);
    default void setLearningEnabled(boolean enabled) {}
    void reset();
    @Override void close();
}
