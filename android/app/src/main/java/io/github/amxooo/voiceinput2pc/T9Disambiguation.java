package io.github.amxooo.voiceinput2pc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

/** Dictionary-backed, sequential syllable constraints. Chinese selections remain owned by Rime. */
final class T9Disambiguation {
    private final List<String> dictionary;
    private final List<Part> parts=new ArrayList<>();
    private String digits="";
    private int activeStart, lockedPrefix;
    private boolean safe=true;
    private static final class Part {
        final int start,end; final String syllable;
        Part(int start,int end,String syllable){this.start=start;this.end=end;this.syllable=syllable;}
    }
    T9Disambiguation(List<String> spellings) {
        TreeSet<String> valid=new TreeSet<>();
        for(String s:spellings)if(s!=null&&s.matches("[a-z]+"))valid.add(s);
        dictionary=new ArrayList<>(valid);
    }
    private T9Disambiguation(T9Disambiguation other) {
        dictionary=other.dictionary;digits=other.digits;parts.addAll(other.parts);
        activeStart=other.activeStart;lockedPrefix=other.lockedPrefix;safe=other.safe;
    }
    T9Disambiguation copy(){return new T9Disambiguation(this);}
    static String code(String syllable) {
        final String mapping="22233344455566677778889999";
        StringBuilder out=new StringBuilder();
        for(int i=0;i<syllable.length();i++){
            char c=syllable.charAt(i);if(c<'a'||c>'z')return "";
            out.append(mapping.charAt(c-'a'));
        }
        return out.toString();
    }
    private int skipSeparators(int start){while(start<digits.length()&&digits.charAt(start)=='\'')start++;return start;}
    private int nextStart(){int start=lockedPrefix;for(Part p:parts)start=Math.max(start,p.end);return skipSeparators(start);}
    List<String> options() {
        if(!safe)return Collections.emptyList();
        int start=skipSeparators(Math.max(activeStart,lockedPrefix));
        if(start>=digits.length())return Collections.emptyList();
        List<String> result=new ArrayList<>();
        for(String s:dictionary)if(digits.startsWith(code(s),start))result.add(s);
        return result;
    }
    boolean choose(String syllable) {
        if(!safe||syllable==null)return false;
        if(syllable.isEmpty()){
            // An explicitly activated segment is undone together with dependent later constraints.
            for(int i=0;i<parts.size();i++)if(parts.get(i).start==activeStart&&activeStart>=lockedPrefix){
                while(parts.size()>i)parts.remove(parts.size()-1);return true;
            }
            for(int i=parts.size()-1;i>=0;i--){
                Part p=parts.get(i);if(p.start<lockedPrefix)continue;
                parts.remove(i);activeStart=p.start;return true;
            }
            return false;
        }
        if(!options().contains(syllable))return false;
        int start=skipSeparators(Math.max(activeStart,lockedPrefix));
        for(int i=parts.size()-1;i>=0;i--)if(parts.get(i).start>=start)parts.remove(i);
        parts.add(new Part(start,start+syllable.length(),syllable));
        activeStart=nextStart();return true;
    }
    boolean activate(int index) {
        List<ChineseInputEngine.SyllableSegment> all=segments();
        if(!safe||index<0||index>=all.size()||all.get(index).locked)return false;
        activeStart=all.get(index).start;return true;
    }
    boolean canUndo(){if(!safe)return false;for(Part p:parts)if(p.start>=lockedPrefix)return true;return false;}
    boolean hasSyllableConstraint(){return !parts.isEmpty();}
    String originalInput(){return digits;}
    String selected(){return parts.isEmpty()?"":parts.get(parts.size()-1).syllable;}
    private void gap(List<ChineseInputEngine.SyllableSegment> result,int start,int end) {
        if(start<lockedPrefix&&end>lockedPrefix){
            result.add(new ChineseInputEngine.SyllableSegment(start,lockedPrefix,"",true));start=lockedPrefix;
        }
        start=skipSeparators(start);
        if(start<end)result.add(new ChineseInputEngine.SyllableSegment(start,end,"",start<lockedPrefix));
    }
    List<ChineseInputEngine.SyllableSegment> segments(){
        List<ChineseInputEngine.SyllableSegment> result=new ArrayList<>();int cursor=0;
        for(Part p:parts){
            if(p.start>cursor)gap(result,cursor,p.start);
            result.add(new ChineseInputEngine.SyllableSegment(p.start,p.end,p.syllable,p.start<lockedPrefix));cursor=p.end;
        }
        if(cursor<digits.length())gap(result,cursor,digits.length());
        return result;
    }
    int activeSegment(){List<ChineseInputEngine.SyllableSegment> all=segments();for(int i=0;i<all.size();i++)if(all.get(i).start==activeStart&&!all.get(i).locked)return i;return -1;}
    String input(){
        StringBuilder out=new StringBuilder();int cursor=0;
        for(Part p:parts){
            out.append(digits,cursor,p.start);
            if(out.length()>0&&out.charAt(out.length()-1)!='\'')out.append('\'');
            out.append(p.syllable);cursor=p.end;
            if(cursor<digits.length()&&digits.charAt(cursor)!='\'')out.append('\'');
        }
        out.append(digits,cursor,digits.length());return out.toString();
    }
    void append(char c){digits+=c;activeStart=Math.max(activeStart,lockedPrefix);}
    void backspace(){
        if(digits.isEmpty())return;
        digits=digits.substring(0,digits.length()-1);
        for(int i=parts.size()-1;i>=0;i--)if(parts.get(i).end>digits.length())parts.remove(i);
        activeStart=nextStart();
    }
    /** Strict boundary mapping is valid for our unformatted mixed-letter/digit schema only. */
    void synchronize(String actualRaw,String preedit,int selectionStart){
        if(actualRaw.isEmpty()){reset();return;}
        // Rime may normalize apostrophes while a candidate is being selected.
        // Treat that as the same composition and never make one transient mismatch permanent.
        String expected=input();
        String actualComparable=actualRaw.replace("'","").replace(" ","");
        String expectedComparable=expected.replace("'","").replace(" ","");
        if(!actualComparable.equals(expectedComparable)){safe=false;return;}
        safe=true;
        if(selectionStart==0){boolean reopening=lockedPrefix>0;lockedPrefix=0;if(reopening)activeStart=nextStart();return;}
        if(selectionStart<0||selectionStart>preedit.length()){safe=false;return;}
        String tail=preedit.substring(selectionStart).replace("'","").replace(" ","");
        String raw=actualRaw.replace("'","").replace(" ","");
        if(!tail.matches("[a-z2-9]*")||!raw.endsWith(tail)){safe=false;return;}
        int consumed=raw.length()-tail.length(),source=0,count=0;
        while(source<digits.length()&&count<consumed){if(digits.charAt(source)!='\'')count++;source++;}
        if(count!=consumed){safe=false;return;}
        lockedPrefix=skipSeparators(source);
        activeStart=Math.max(activeStart,lockedPrefix);
    }
    boolean protectedState(){return !safe;}
    /** A successful authoritative set_input makes the numeric source trustworthy again. */
    void authoritativeWriteAccepted(){safe=true;}
    boolean hasChineseSelection(){return lockedPrefix>0;}
    void reset(){digits="";parts.clear();activeStart=0;lockedPrefix=0;safe=true;}
}
