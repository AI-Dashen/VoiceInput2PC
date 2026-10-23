package io.github.amxooo.voiceinput2pc;
import android.content.Context;
import java.io.File;
public class HostRimeT9Probe {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void show(String tag,ChineseInputEngine.Snapshot s){System.out.println(tag+" raw="+s.rawInput+" preedit="+s.preedit+" selection="+s.selectionStart+":"+s.selectionEnd+" words="+s.candidates+" comments="+s.candidateComments+" options="+s.syllables+" selected="+s.selectedSyllable);}
 public static void main(String[] args){
  try(RimeChineseInputEngine e=new RimeChineseInputEngine(new Context(new File(args[0]),new File(args[1])))){
   check(e.snapshot().available,e.snapshot().error);
   e.setLayout(ChineseInputEngine.Layout.T9);
   for(char c:"64426".toCharArray())e.input(c);
   show("digits",e.snapshot());check(e.snapshot().syllables.contains("ni"),"ni option");
   check(e.chooseSyllable("mi"),"mi constraint");show("choose-mi",e.snapshot());
   check(e.snapshot().candidateComments.get(0).startsWith("mi"),"mi actually constrains decoder");
   check(e.chooseSyllable(""),"clear constraint");check(e.snapshot().rawInput.equals("64426"),"restore digits");
   check(!e.chooseSyllable("hao"),"invalid first syllable");
   check(e.chooseSyllable("ni"),"ni constraint");show("choose-ni",e.snapshot());
   int index=e.snapshot().candidates.indexOf("你好");check(index>=0,"real nihao candidate");
   check("你好".equals(e.select(index)),"final exact-once commit");check(e.snapshot().rawInput.isEmpty(),"cleared after commit");
   e.setLearningEnabled(false);
   for(char c:"64426".toCharArray())e.input(c);
   check(e.chooseSyllable("ni"),"private schema constraint");show("private-ni",e.snapshot());
   e.reset();for(char c:"64426".toCharArray())e.input(c);e.chooseSyllable("ni");
   for(int p=0;p<12 && !e.snapshot().candidates.contains("你");p++)e.nextPage();
   index=e.snapshot().candidates.indexOf("你");check(index>=0,"partial ni candidate");
   check(e.select(index)==null,"partial is not commit");show("partial-ni",e.snapshot());
   check(e.snapshot().selectionStart==1,"UTF8 byte3 becomes Java offset1");
   check(e.snapshot().syllables.isEmpty()&&!e.chooseSyllable("mi"),"disable disambiguation after partial");
   check(e.snapshot().preedit.startsWith("你"),"preserve selected Chinese");
   e.reset();e.setLayout(ChineseInputEngine.Layout.FULL_PINYIN);
   for(char c:"nihao".toCharArray())e.input(c);
   check(e.snapshot().candidates.contains("你好"),"full pinyin regression");
   System.out.println("HostRimeT9Probe passed (Termux JVM + actual JNI/librime; Android stub only)");
  }
 }
}
