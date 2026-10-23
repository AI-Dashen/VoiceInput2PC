package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.*;
import java.util.*;

/** App-owned Rime session: candidate text comes exclusively from librime. */
public final class RimeChineseInputEngine implements ChineseInputEngine {
    private long session;
    private Layout layout = Layout.FULL_PINYIN;
    private boolean learningEnabled = true;
    private String failure = "";
    private static boolean loaded;
    private T9Disambiguation t9 = new T9Disambiguation(Collections.emptyList());
    public RimeChineseInputEngine(Context context) {
        synchronized (RimeChineseInputEngine.class) {
            try {
                if (!loaded) { System.loadLibrary("rimebridge"); loaded = true; }
                File base = new File(context.getFilesDir(), "rime");
                File shared = new File(base, "shared"), user = new File(base, "user");
                if (!user.isDirectory() && !user.mkdirs()) throw new IOException("创建词库目录失败");
                ensureAssets(context.getAssets(), shared);
                List<String> spellings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        context.getAssets().open("rime/t9-syllables.txt"), "UTF-8"))) {
                    String line; while ((line=reader.readLine()) != null) spellings.add(line);
                }
                t9 = new T9Disambiguation(spellings);
                session = nativeCreate(shared.getAbsolutePath(), user.getAbsolutePath());
                if (session == 0) throw new IOException("中文引擎初始化失败：" + nativeError());
                setLayout(layout);
            } catch (Exception | LinkageError error) {
                String reason = error.getMessage();
                failure = "中文引擎尚不可用：" + (reason == null ? error.getClass().getSimpleName() : reason.substring(0, Math.min(reason.length(), 300)));
            }
        }
    }
    private static void ensureAssets(AssetManager assets, File shared) throws IOException {
        String expected;
        try (InputStream in = assets.open("rime/version.txt")) { expected = readSmall(in); }
        File version = new File(shared, "version.txt");
        if (version.isFile()) {
            try (InputStream in = new FileInputStream(version)) {
                if (expected.equals(readSmall(in))) return;
            }
        }
        copyAssets(assets, "rime", shared);
        try (FileOutputStream out = new FileOutputStream(version)) { out.write(expected.getBytes("UTF-8")); out.getFD().sync(); }
    }
    private static String readSmall(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[128]; int n;
        while ((n = in.read(b)) != -1) { if (out.size() + n > 4096) throw new IOException("资源版本异常"); out.write(b, 0, n); }
        return new String(out.toByteArray(), "UTF-8");
    }
    private static void copyAssets(AssetManager assets, String path, File target) throws IOException {
        String[] names = assets.list(path);
        if (names == null) throw new IOException("词库资源缺失");
        if (names.length > 0) {
            if (!target.isDirectory() && !target.mkdirs()) throw new IOException("创建词库资源目录失败");
            for (String name : names) copyAssets(assets, path + "/" + name, new File(target, name));
        } else {
            if (path.equals("rime/version.txt")) return;
            File temp = new File(target.getParentFile(), target.getName() + ".pending");
            try (InputStream in = assets.open(path); FileOutputStream out = new FileOutputStream(temp)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            if (!temp.renameTo(target)) throw new IOException("部署词库资源失败");
        }
    }
    private static int number(String value) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return 0; }
    }
    @Override public synchronized Snapshot snapshot() {
        if (session == 0) return new Snapshot("", Collections.emptyList(), 0, false, false, failure);
        String[] data = nativeSnapshot(session);
        if (data == null || data.length < 7 || (data.length-7)%2 != 0)
            return new Snapshot("", Collections.emptyList(), 0, false, false, "中文引擎状态读取失败");
        List<String> words=new ArrayList<>(), comments=new ArrayList<>();
        for (int i=7;i<data.length;i+=2) { words.add(data[i]); comments.add(data[i+1]); }
        if (layout == Layout.T9) t9.synchronize(data[0],data[3],number(data[5]));
        return new Snapshot(data[0], words, number(data[1]), "1".equals(data[2]), true, "",
                data[3], number(data[4]), number(data[5]), number(data[6]), comments,
                layout == Layout.T9 ? t9.options() : Collections.emptyList(),
                layout == Layout.T9 ? t9.selected() : "",
                layout == Layout.T9 ? t9.segments() : Collections.emptyList(),
                layout == Layout.T9 ? t9.activeSegment() : -1,
                layout == Layout.T9 && t9.canUndo(),
                layout == Layout.T9 ? t9.originalInput() : data[0]);
    }
    @Override public synchronized void input(char value) {
        if (session == 0) return;
        if (layout == Layout.T9 && ((value >= '2' && value <= '9') || value == '\'')) {
            T9Disambiguation previous=t9.copy();t9.append(value);
            // Ordinary T9 typing must stay incremental. Replacing the complete
            // input on every key invalidates Rime's segmentation cache and makes
            // a long ambiguous digit sequence progressively more expensive.
            // set_input remains the repair path after an observed decoder drift
            // and the deliberate path for an explicit syllable constraint.
            boolean accepted=t9.protectedState()
                    ? nativeSetInput(session,t9.input()) : nativeKey(session,value);
            if (!accepted) accepted=nativeSetInput(session,t9.input());
            if (accepted) t9.authoritativeWriteAccepted(); else t9=previous;
        } else nativeKey(session,value);
    }
    @Override public synchronized void backspace() {
        if (session == 0) return;
        snapshot();
        if (layout == Layout.T9 && !t9.hasChineseSelection()) {
            T9Disambiguation previous=t9.copy();t9.backspace();
            // A chosen syllable changes N numeric keys into M letters. Native
            // backspace would then remove only one letter and desynchronize the
            // numeric source, so constrained edits intentionally use set_input.
            boolean accepted=t9.hasSyllableConstraint()
                    ? nativeSetInput(session,t9.input()) : nativeKey(session,0xff08);
            if (!accepted) accepted=nativeSetInput(session,t9.input());
            if (accepted) t9.authoritativeWriteAccepted(); else t9=previous;
        } else nativeKey(session,0xff08); // Rime first reopens its selected Chinese segment.
        snapshot();
    }
    @Override public synchronized boolean activateSyllable(int index) {
        if (session == 0 || layout != Layout.T9) return false;
        snapshot();return t9.activate(index);
    }
    @Override public synchronized boolean chooseSyllable(String syllable) {
        if (session == 0 || layout != Layout.T9) return false;
        snapshot();
        T9Disambiguation previous=t9.copy();
        if (!t9.choose(syllable)) return false;
        if (nativeSetInput(session,t9.input())) {t9.authoritativeWriteAccepted();return true;}
        t9=previous;return false;
    }
    @Override public synchronized String select(int index) {
        if (session == 0 || index < 0) return null;
        String result=nativeSelect(session,index);
        Snapshot state=snapshot();
        if (state.rawInput.isEmpty()) t9.reset();
        return result;
    }
    @Override public synchronized void nextPage() { if (session != 0) nativeKey(session, 0xff56); }
    @Override public synchronized void previousPage() { if (session != 0) nativeKey(session, 0xff55); }
    @Override public synchronized void setLayout(Layout value) {
        layout = Objects.requireNonNull(value);
        t9.reset();
        String schema = value == Layout.T9 ? "t9" : "rime_ice";
        if (!learningEnabled) schema += "_private";
        if (session != 0 && !nativeSchema(session, schema)) {
            failure = "中文输入方案加载失败";
            nativeDestroy(session); session = 0;
        }
    }
    @Override public synchronized void setLearningEnabled(boolean enabled) {
        if (learningEnabled != enabled) { learningEnabled = enabled; reset(); setLayout(layout); }
    }
    @Override public synchronized void reset() { t9.reset(); if (session != 0) nativeClear(session); }
    @Override public synchronized void close() { if (session != 0) nativeDestroy(session); session = 0; }
    private static native String nativeError();
    private static native long nativeCreate(String shared, String user);
    private static native String[] nativeSnapshot(long session);
    private static native boolean nativeKey(long session, int key);
    private static native boolean nativeSetInput(long session, String input);
    private static native String nativeSelect(long session, int index);
    private static native boolean nativeSchema(long session, String schema);
    private static native void nativeClear(long session);
    private static native void nativeDestroy(long session);
}
