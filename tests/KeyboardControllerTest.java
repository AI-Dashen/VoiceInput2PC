package io.github.amxooo.voiceinput2pc;
import java.util.*;
public final class KeyboardControllerTest {
    static int checks;
    static void equal(Object e,Object a) { checks++; if (!Objects.equals(e,a)) throw new AssertionError(e+" != "+a); }
    // A fixture verifies UI/controller routing; production candidates come from Rime only.
    static final class Fixture implements ChineseInputEngine {
        String raw=""; int page; boolean available=true; Layout layout;
        public Snapshot snapshot() { return new Snapshot(raw,raw.isEmpty()?Collections.emptyList():Arrays.asList("候选一","候选二"),page,page==0,available,"fixture"); }
        public void input(char c) { raw+=c; } public void backspace() { if (!raw.isEmpty()) raw=raw.substring(0,raw.length()-1); }
        public String select(int i) { String result=snapshot().candidates.get(i);raw="";return result; }
        public void nextPage() { page++; } public void previousPage() { page=Math.max(0,page-1); }
        public void setLayout(Layout l) { layout=l; } public void reset() { raw="";page=0; } public void close() { }
    }
    public static void main(String[] args) {
        Fixture f=new Fixture(); KeyboardController k=new KeyboardController(f);
        equal(null,k.type("n")); equal("n",k.snapshot().rawInput);
        equal(null,k.select(-1));equal(null,k.select(9));equal("候选二",k.select(1));equal("",f.raw);
        k.type("a");k.backspace();equal("",f.raw);
        k.type("a");k.nextPage();equal(1,k.snapshot().page);k.previousPage();equal(0,k.snapshot().page);
        k.setMode(KeyboardController.Mode.T9);equal(ChineseInputEngine.Layout.T9,f.layout);k.type("2");equal("2",f.raw);
        equal("候选一",k.displayComposition(k.snapshot()));
        equal("，",k.type("，"));equal(" ",k.type(" "));equal("2",f.raw);
        f.available=false;f.raw="";equal("",k.displayComposition(k.snapshot()));
        f.available=true;
        k.setMode(KeyboardController.Mode.ENGLISH);equal("",f.raw);equal("a",k.type("a"));k.toggleCase();equal("A",k.type("a"));
        k.setMode(KeyboardController.Mode.NUMBERS);equal("8",k.type("8"));
        k.setMode(KeyboardController.Mode.SYMBOLS);equal("，",k.type("，"));
        k.setMode(KeyboardController.Mode.PINYIN);f.available=false;equal(null,k.type("n"));equal("",f.raw);
        InputSession session=new InputSession();long g=session.begin("field","app",1,1).generation;
        session.startListening(g);session.startRecognizing(g);session.showPreview(g,"原文");
        equal(true,session.updatePreview(g,"修改"));equal("修改",session.commit(g));equal(false,session.updatePreview(g,"重复"));
        System.out.println(checks+" keyboard checks passed");
    }
}
