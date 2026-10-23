package io.github.amxooo.voiceinput2pc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** A candidate is supplied by a real decoder; UI never invents a Chinese dictionary. */
public interface ChineseInputEngine extends AutoCloseable {
    enum Layout { FULL_PINYIN, T9 }
    final class SyllableSegment {
        /** Offsets into the original numeric keystroke sequence, including typed apostrophes. */
        public final int start,end;
        public final String syllable;
        public final boolean locked;
        public SyllableSegment(int start,int end,String syllable,boolean locked) {
            this.start=start;this.end=end;this.syllable=syllable;this.locked=locked;
        }
    }
    final class Snapshot {
        public final String rawInput, numericInput;
        public final List<SyllableSegment> syllableSegments;
        public final int activeSyllableSegment;
        public final boolean canUndoSyllable;
        public final String preedit, selectedSyllable;
        /** UTF-16 offsets into preedit, suitable for Android text spans. */
        public final int cursor, selectionStart, selectionEnd;
        public final List<String> candidateComments, syllables;
        public final List<String> candidates;
        public final int page;
        public final boolean hasNext, available;
        public final String error;
        public Snapshot(String rawInput, List<String> candidates, int page,
                boolean hasNext, boolean available, String error) {
            this(rawInput, candidates, page, hasNext, available, error, rawInput,
                    0, 0, 0, Collections.emptyList(), Collections.emptyList(), "");
        }
        public Snapshot(String rawInput, List<String> candidates, int page,
                boolean hasNext, boolean available, String error, String preedit,
                int cursor, int selectionStart, int selectionEnd,
                List<String> candidateComments, List<String> syllables, String selectedSyllable) {
            this(rawInput,candidates,page,hasNext,available,error,preedit,cursor,selectionStart,
                    selectionEnd,candidateComments,syllables,selectedSyllable,
                    Collections.emptyList(),-1,false);
        }
        public Snapshot(String rawInput,List<String> candidates,int page,boolean hasNext,
                boolean available,String error,String preedit,int cursor,int selectionStart,
                int selectionEnd,List<String> candidateComments,List<String> syllables,String selectedSyllable,
                List<SyllableSegment> syllableSegments,int activeSyllableSegment,boolean canUndoSyllable) {
            this(rawInput,candidates,page,hasNext,available,error,preedit,cursor,selectionStart,selectionEnd,
                    candidateComments,syllables,selectedSyllable,syllableSegments,activeSyllableSegment,
                    canUndoSyllable,rawInput);
        }
        public Snapshot(String rawInput,List<String> candidates,int page,boolean hasNext,
                boolean available,String error,String preedit,int cursor,int selectionStart,
                int selectionEnd,List<String> candidateComments,List<String> syllables,String selectedSyllable,
                List<SyllableSegment> syllableSegments,int activeSyllableSegment,boolean canUndoSyllable,
                String numericInput) {
            this.numericInput=numericInput==null?"":numericInput;
            this.syllableSegments=Collections.unmodifiableList(new ArrayList<>(syllableSegments));
            this.activeSyllableSegment=activeSyllableSegment;this.canUndoSyllable=canUndoSyllable;
            this.rawInput = rawInput == null ? "" : rawInput;
            this.preedit = preedit == null ? "" : preedit;
            this.cursor = cursor; this.selectionStart = selectionStart; this.selectionEnd = selectionEnd;
            this.candidateComments = Collections.unmodifiableList(new ArrayList<>(candidateComments));
            this.syllables = Collections.unmodifiableList(new ArrayList<>(syllables));
            this.selectedSyllable = selectedSyllable == null ? "" : selectedSyllable;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.page = page; this.hasNext = hasNext; this.available = available;
            this.error = error == null ? "" : error;
        }
    }
    Snapshot snapshot();
    void input(char value);
    void backspace();
    /** Empty removes the activated constraint (and dependent tail), or the last editable constraint. */
    default boolean chooseSyllable(String syllable) { return false; }
    default boolean activateSyllable(int index) { return false; }
    /** Returns only a real committed decoder result, or null for an unfinished selection. */
    String select(int index);
    void nextPage();
    void previousPage();
    void setLayout(Layout layout);
    default void setLearningEnabled(boolean enabled) {}
    void reset();
    @Override void close();
}
