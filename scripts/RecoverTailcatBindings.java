import com.googlecode.d2j.reader.*;
import com.googlecode.d2j.visitors.*;
import com.googlecode.d2j.dex.Dex2jar;
import java.nio.file.*;
import java.util.*;
public class RecoverTailcatBindings {
 static boolean keep(String n) {return (n.startsWith("Lgo/Seq") || n.startsWith("Lgo/Universe") || n.equals("Lgo/error;")) || n.startsWith("Lio/github/amxooo/voiceinput2pc/tailcatbridge/");}
 public static void main(String[] a) throws Exception {
  DexFileReader r=new DexFileReader(Paths.get(a[0]));
  BaseDexFileReader filtered=new BaseDexFileReader(){
   public int getDexVersion(){return r.getDexVersion();}
   public List<String> getClassNames(){List<String> out=new ArrayList<>();for(String n:r.getClassNames())if(keep(n))out.add(n);return out;}
   DexFileVisitor filter(DexFileVisitor v){return new DexFileVisitor(v){public DexClassVisitor visit(int acc,String n,String s,String[] i){return keep(n)?super.visit(acc,n,s,i):null;}};}
   public void accept(DexFileVisitor v){r.accept(filter(v));}
   public void accept(DexFileVisitor v,int c){r.accept(filter(v),c);}
   public void accept(DexFileVisitor v,int idx,int c){r.accept(filter(v),idx,c);}
  };
  System.out.println(filtered.getClassNames());
  Dex2jar.from(filtered).skipDebug().computeFrames(true).to(Paths.get(a[1]));
 }
}
