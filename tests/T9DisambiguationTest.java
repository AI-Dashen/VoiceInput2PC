package io.github.amxooo.voiceinput2pc;
import java.util.Arrays;
import java.util.Collections;
public final class T9DisambiguationTest {
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        T9Disambiguation t=new T9Disambiguation(Arrays.asList("ni","mi","hao","gan","gao","pin","jia","bad!","ni"));
        for(char c:"64426746542".toCharArray())t.append(c);
        check(t.options().equals(Arrays.asList("mi","ni")),"dictionary options first segment");
        check(!t.choose("hao"),"reject mismatched first segment");
        check(t.choose("ni")&&t.input().equals("ni'426746542"),"first constraint");
        check(t.hasSyllableConstraint(),"selected spelling requires authoritative edit mapping");
        check(t.options().contains("hao")&&!t.options().contains("ni"),"advance to next segment");
        check(t.choose("hao")&&t.choose("pin")&&t.choose("jia"),"select four syllables");
        check(t.input().equals("ni'hao'pin'jia")&&t.options().isEmpty(),"all segments constrained");
        check(t.choose("")&&t.input().equals("ni'hao'pin'542"),"undo last segment");
        t.backspace();check(t.input().equals("ni'hao'pin'54"),"delete pending tail");
        check(t.activate(1)&&t.choose("gao"),"edit earlier unlocked segment");
        check(t.input().equals("ni'gao'74654"),"invalidate dependent later constraints");
        check(t.activate(0)&&t.choose("")&&t.input().equals("6442674654"),"undo activated segment and dependent tail");
        t.reset();check(!t.hasSyllableConstraint(),"reset clears spelling constraints");
        for(char c:"64'426".toCharArray())t.append(c);
        check(t.choose("ni")&&t.choose("hao")&&t.input().equals("ni'hao"),"explicit separator");
        t.synchronize("ni'hao","你hao",1);
        check(!t.activate(0)&&t.activate(1),"selected Chinese prefix locked only");
        check(t.choose("gao")&&t.input().equals("ni'gao"),"edit tail while preserving selected prefix");
        check(t.choose("")&&t.input().equals("ni'426"),"undo tail only");
        check(!t.choose(""),"cannot undo locked prefix");
        t.synchronize("ni'426","ni'426",0);
        check(!t.hasChineseSelection()&&t.activate(0),"Rime undo reopens prefix");
        t.reset();for(char c:"64426".toCharArray())t.append(c);
        t.synchronize("64426","你426",1);
        check(t.options().contains("hao")&&t.choose("hao"),"numeric partial tail disambiguation");
        check(t.input().equals("64'hao"),"preserve exact numeric selected prefix");
        t.synchronize("64'hao","你'hao",1);
        check(!t.protectedState(),"strict suffix boundary mapping");
        t.synchronize("64'hao","你?",1);
        check(t.protectedState()&&t.options().isEmpty()&&!t.choose(""),"protect unmappable context");
        t.append('4');check(t.protectedState(),"append alone does not pretend decoder accepted repair");
        t.authoritativeWriteAccepted();check(!t.protectedState(),"accepted authoritative write recovers state");
        t.synchronize(t.input(),t.input(),0);check(!t.protectedState(),"matching snapshot clears transient protection");
        t.reset();for(char c:"64426".toCharArray())t.append(c);t.choose("ni");t.choose("hao");
        t.backspace();check(t.input().equals("ni'42"),"delete crosses selected syllable and removes its constraint");
        t.backspace();t.backspace();check(t.input().equals("ni"),"delete reaches first boundary");
        t.backspace();check(t.input().equals("6")&&t.selected().isEmpty(),"delete through first constraint");
        ChineseInputEngine.Snapshot old=new ChineseInputEngine.Snapshot("64",Collections.singletonList("你"),0,false,true,"");
        check(old.preedit.equals("64")&&old.syllableSegments.isEmpty(),"legacy snapshot compatibility");
        ChineseInputEngine fake=new ChineseInputEngine(){
            public Snapshot snapshot(){return old;}public void input(char v){}public void backspace(){}
            public String select(int i){return null;}public void nextPage(){}public void previousPage(){}
            public void setLayout(Layout l){}public void reset(){}public void close(){}
        };
        KeyboardController controller=new KeyboardController(fake);controller.setMode(KeyboardController.Mode.T9);
        ChineseInputEngine.Snapshot s=new ChineseInputEngine.Snapshot("64426",Arrays.asList("你好"),0,false,true,"",
                "64 426",6,0,6,Arrays.asList("ni hao"),Arrays.asList("ni","mi"),"");
        check(controller.displayPreedit(s).equals("ni hao"),"panel shows actual decoder spelling");
        check(controller.displayComposition(s).equals("你好"),"host compatibility unchanged");
        check(controller.displayPreedit(old).equals("继续输入或删除"),"panel exposes safe recovery status without numeric lookup code");
        System.out.println("T9DisambiguationTest passed: sequential constraints, edit, undo, selected-prefix preservation and deletion");
    }
}
