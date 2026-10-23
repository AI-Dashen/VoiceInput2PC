package io.github.amxooo.voiceinput2pc;
import android.content.Context;
import java.io.File;
public class HostRimeT9Probe {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void show(String tag,ChineseInputEngine.Snapshot s){System.out.println(tag+" raw="+s.rawInput+" preedit="+s.preedit+" selection="+s.selectionStart+":"+s.selectionEnd+" words="+s.candidates+" comments="+s.candidateComments+" options="+s.syllables+" segments="+s.syllableSegments.size()+" active="+s.activeSyllableSegment+" undo="+s.canUndoSyllable);}
 static void type(RimeChineseInputEngine e,String s){for(char c:s.toCharArray())e.input(c);}
 static int find(RimeChineseInputEngine e,String word){for(int p=0;p<30;p++){int i=e.snapshot().candidates.indexOf(word);if(i>=0)return i;if(!e.snapshot().hasNext)break;e.nextPage();}return -1;}
 public static void main(String[] args){
  try(RimeChineseInputEngine e=new RimeChineseInputEngine(new Context(new File(args[0]),new File(args[1])))){
   check(e.snapshot().available,e.snapshot().error);e.setLayout(ChineseInputEngine.Layout.T9);
   type(e,"64426746542");check(e.chooseSyllable("ni"),"ni");check(e.chooseSyllable("hao"),"hao");
   check(e.chooseSyllable("pin")&&e.chooseSyllable("jia"),"third fourth syllable");show("FOUR-SEGMENTS",e.snapshot());
   check(e.snapshot().rawInput.equals("ni'hao'pin'jia"),"actual mixed code");
   check(e.chooseSyllable(""),"undo fourth");show("UNDO-FOURTH",e.snapshot());
   check(e.snapshot().rawInput.equals("ni'hao'pin'542"),"only fourth constraint removed");
   e.backspace();check(e.snapshot().rawInput.equals("ni'hao'pin'54"),"delete pending tail");
   check(e.activateSyllable(1)&&e.chooseSyllable("gao"),"edit second");show("EDIT-SECOND",e.snapshot());
   check(e.snapshot().rawInput.equals("ni'gao'74654"),"dependent constraints removed");
   check(e.activateSyllable(0)&&e.chooseSyllable(""),"undo activated earlier segment");
   check(e.snapshot().rawInput.equals("6442674654")&&e.snapshot().numericInput.equals("6442674654"),"original numeric code restored");
   e.reset();type(e,"64426");int index=find(e,"你");check(index>=0,"numeric partial candidate");
   check(e.select(index)==null,"partial not committed");show("NUMERIC-PARTIAL",e.snapshot());
   check(e.snapshot().selectionStart==1&&e.snapshot().syllables.contains("hao"),"UTF16 boundary and pending tail");
   check(e.chooseSyllable("hao"),"constrain after chosen Chinese");show("PARTIAL-THEN-HAO",e.snapshot());
   check(e.snapshot().preedit.startsWith("你")&&e.snapshot().selectionStart==1,"selected Chinese preserved");
   type(e,"746542");check(e.chooseSyllable("pin")&&e.chooseSyllable("jia"),"extend after partial");
   show("PARTIAL-EXTENDED",e.snapshot());check(e.snapshot().preedit.startsWith("你"),"prefix stays selected");
   check(e.chooseSyllable(""),"undo after partial");check(e.snapshot().preedit.startsWith("你"),"undo retains prefix");
   e.backspace();show("REOPEN-CHINESE",e.snapshot());
   check(e.snapshot().selectionStart==0,"Rime Backspace reopens selected segment first");
   e.backspace();show("DELETE-AFTER-REOPEN",e.snapshot());
   check(e.snapshot().rawInput.endsWith("54"),"next backspace deletes digit");
   e.reset();type(e,"64426");check(e.chooseSyllable("ni")&&e.chooseSyllable("hao"),"two segments");
   e.backspace();check(e.snapshot().rawInput.equals("ni'42"),"back through constraint");
   e.backspace();e.backspace();check(e.snapshot().rawInput.equals("ni"),"reach first boundary");
   e.backspace();check(e.snapshot().rawInput.equals("6"),"remove first constraint");e.backspace();check(e.snapshot().rawInput.isEmpty(),"delete all");
   type(e,"64426");check(e.chooseSyllable("ni")&&e.chooseSyllable("hao"),"prepare commit");
   index=find(e,"你好");check(index>=0&&"你好".equals(e.select(index)),"final commit");
   check(e.snapshot().rawInput.isEmpty()&&e.select(0)==null,"exact-once commit");
   e.setLearningEnabled(false);type(e,"64426");check(e.chooseSyllable("ni")&&e.chooseSyllable("hao"),"private sequential");show("PRIVATE",e.snapshot());
   e.reset();e.setLayout(ChineseInputEngine.Layout.FULL_PINYIN);type(e,"nihao");check(e.snapshot().candidates.contains("你好"),"full pinyin regression");
   System.out.println("HostRimeT9Probe passed: multi-segment/undo/edit/partial-Chinese/continued composition/delete/private/final commit");
  }
 }
}
