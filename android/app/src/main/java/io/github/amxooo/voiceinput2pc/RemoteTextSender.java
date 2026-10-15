package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Executes one explicit remote draft send. Controller state stays in the IME. */
final class RemoteTextSender implements AutoCloseable {
    interface Callback {
        void onAcknowledged(String requestId,String status,String note,long seq);
        void onRejected(String requestId,String note);
        void onUncertain(String requestId,String note);
    }
    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private volatile boolean closed;

    RemoteTextSender(Context context,SharedPreferences prefs) {
        this.context=context.getApplicationContext(); this.prefs=prefs;
    }

    void send(PairingConfig pairing,OutputTargetController.SendRequest request,Callback callback) {
        if (closed || pairing == null || request == null || callback == null) return;
        worker.execute(() -> {
            try {
                RelayTransport transport=RelayTransportFactory.create(context,prefs,pairing);
                JSONObject armed=transport.session(pairing.host);
                Object sessionValue=armed.opt("session");
                if (!Boolean.TRUE.equals(armed.opt("ok")) || !(sessionValue instanceof String)
                        || ((String)sessionValue).isEmpty()) {
                    String note=safe(armed.optString("note","电脑没有建立输入会话"));
                    post(() -> callback.onUncertain(request.requestId,note)); return;
                }
                // One-shot IME sends deliberately omit seq. The receiver persists UUID
                // idempotency across renewed sessions, while ordered seq is session-bound.
                long seq=0;
                JSONObject body=new JSONObject().put("id",request.requestId).put("text",request.text)
                        .put("session",(String)sessionValue);
                JSONObject result=transport.request(pairing.host,body);
                Object id=result.opt("id"), status=result.opt("status");
                boolean sequenceMatches=!result.has("seq");
                if (!(id instanceof String) || !request.requestId.equals(id)
                        || !(status instanceof String) || !sequenceMatches) {
                    post(() -> callback.onUncertain(request.requestId,"电脑回执不完整，请勿重复发送")); return;
                }
                String state=(String)status, note=safe(result.optString("note",""));
                if (Boolean.TRUE.equals(result.opt("ok"))
                        && ("inserted".equals(state) || "saved".equals(state))) {
                    post(() -> callback.onAcknowledged(request.requestId,state,note,seq));
                } else post(() -> callback.onUncertain(request.requestId,
                        note.isEmpty() ? "电脑回执状态未知" : note));
            } catch (Exception failure) {
                String note=safe(failure == null ? "" : failure.getMessage());
                post(() -> callback.onUncertain(request.requestId,
                        note.isEmpty() ? "连接中断，发送结果未知" : note));
            }
        });
    }

    private void post(Runnable task) { if (!closed) main.post(() -> { if (!closed) task.run(); }); }

    private static String safe(String value) {
        String text=value == null ? "" : value;
        text=text.replaceAll("tc[A-Za-z0-9_-]{20,4094}","tc<redacted>");
        return text.length() > 180 ? text.substring(0,180) : text;
    }
    @Override public void close() { closed=true; worker.shutdownNow(); }
}
