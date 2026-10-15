package io.github.amxooo.voiceinput2pc;

/** Pure state for routing confirmed text to the Android editor or a paired device. */
public final class OutputTargetController {
    public enum Target { LOCAL, REMOTE }
    public enum ToggleResult { OK, NO_PAIRED_DEVICE, SENSITIVE_FIELD }
    public enum SendFailure { REJECTED, UNKNOWN }
    public static final class SendRequest {
        public final String requestId, deviceId, text; public final int attempt;
        SendRequest(String requestId,String deviceId,String text,int attempt) {
            this.requestId=requestId; this.deviceId=deviceId; this.text=text; this.attempt=attempt;
        }
    }
    /** Durable recovery state. An in-flight request is restored as uncertain. */
    public static final class Snapshot {
        public final String pending, retryId, retryText, retryDeviceId;
        public final int retryAttempt, cursor;
        public Snapshot(String pending,String retryId,String retryText,int retryAttempt,String retryDeviceId) {
            this(pending,retryId,retryText,retryAttempt,retryDeviceId,
                    pending == null ? 0 : pending.length());
        }
        public Snapshot(String pending,String retryId,String retryText,int retryAttempt,String retryDeviceId,int cursor) {
            this.pending=pending == null ? "" : pending;
            this.retryId=retryId == null ? "" : retryId;
            this.retryText=retryText == null ? "" : retryText;
            this.retryDeviceId=retryDeviceId == null ? "" : retryDeviceId;
            this.retryAttempt=Math.max(0,retryAttempt);
            this.cursor=Math.max(0,Math.min(cursor,this.pending.length()));
        }
    }
    private Target target=Target.LOCAL;
    private String fieldKey=""; private boolean sensitive;
    private String deviceId="", deviceLabel="电脑";
    private final StringBuilder pending=new StringBuilder();
    private int cursor;
    private String composing="";
    private String inFlightId="", inFlightText="", inFlightDeviceId=""; private int inFlightAttempt;
    private String retryId="", retryText="", retryDeviceId=""; private int retryAttempt;

    public synchronized Target target() { return target; }
    public synchronized boolean isRemote() { return target == Target.REMOTE && !sensitive && !deviceId.isEmpty(); }
    public synchronized boolean sensitive() { return sensitive; }
    public synchronized String deviceId() { return deviceId; }
    public synchronized String deviceLabel() { return deviceLabel; }
    public synchronized String pendingText() { return pending.toString(); }
    public synchronized int cursor() { return cursor; }
    public synchronized String composingText() { return composing; }
    public synchronized boolean sending() { return !inFlightId.isEmpty(); }
    public synchronized boolean retryDeviceMismatch() { return !retryId.isEmpty() && !retryDeviceId.equals(deviceId); }
    /** A new editor always returns to LOCAL. Remote draft remains private. */
    public synchronized void onStartInput(String nextFieldKey,boolean nextSensitive,boolean restarting) {
        String normalized=nextFieldKey == null ? "" : nextFieldKey;
        boolean same=restarting && normalized.equals(fieldKey);
        fieldKey=normalized; sensitive=nextSensitive; composing="";
        if (!same || nextSensitive) target=Target.LOCAL;
    }
    public synchronized void onFinishInput() { target=Target.LOCAL; composing=""; }
    public synchronized void setPairing(String nextDeviceId,String nextLabel) {
        String normalized=nextDeviceId == null ? "" : nextDeviceId;
        if (target == Target.REMOTE && !deviceId.isEmpty() && !deviceId.equals(normalized)) target=Target.LOCAL;
        deviceId=normalized;
        deviceLabel=nextLabel == null || nextLabel.trim().isEmpty() ? "电脑" : nextLabel.trim();
        if (deviceId.isEmpty()) target=Target.LOCAL;
    }
    public synchronized ToggleResult toggleTarget() {
        if (sensitive) return ToggleResult.SENSITIVE_FIELD;
        if (target == Target.REMOTE) { target=Target.LOCAL; composing=""; return ToggleResult.OK; }
        if (deviceId.isEmpty()) return ToggleResult.NO_PAIRED_DEVICE;
        target=Target.REMOTE; composing=""; return ToggleResult.OK;
    }
    /** True means the text was consumed by the remote draft. */
    public synchronized boolean commitStable(String text) {
        if (!isRemote()) return false;
        if (text != null && !text.isEmpty()) {
            pending.insert(cursor,text); cursor+=text.length();
        }
        return true;
    }
    public synchronized void setComposing(String text) { composing=isRemote() && text != null ? text : ""; }
    public synchronized void clearComposing() { composing=""; }
    /** Remote deletion never falls through to the host editor or edits an uncertain prefix. */
    public synchronized boolean deleteBackward() {
        if (!isRemote()) return false;
        int protectedLength=protectedLength();
        if (cursor > protectedLength) {
            int start=Character.offsetByCodePoints(pending,cursor,-1);
            if (start >= protectedLength) { pending.delete(start,cursor); cursor=start; }
        }
        return true;
    }
    public synchronized boolean moveCursor(int delta) {
        if (!isRemote() || delta == 0) return false;
        int protectedLength=protectedLength();
        if (delta < 0 && cursor > protectedLength)
            cursor=Character.offsetByCodePoints(pending,cursor,-1);
        else if (delta > 0 && cursor < pending.length())
            cursor=Character.offsetByCodePoints(pending,cursor,1);
        return true;
    }
    private int protectedLength() {
        return !retryId.isEmpty() && pending.toString().startsWith(retryText) ? retryText.length() : 0;
    }
    public synchronized boolean canSend() {
        boolean retryValid=retryId.isEmpty() || pending.toString().startsWith(retryText)
                && retryDeviceId.equals(deviceId);
        return isRemote() && retryValid && inFlightId.isEmpty() && composing.isEmpty()
                && !pending.toString().trim().isEmpty();
    }
    public synchronized SendRequest beginSend(String freshRequestId) {
        if (!canSend() || freshRequestId == null || freshRequestId.isEmpty()) return null;
        String id=freshRequestId, text=pending.toString(); int attempt=1;
        if (!retryId.isEmpty()) {
            id=retryId; text=retryText; attempt=retryAttempt+1;
            int removed=retryText.length(); pending.delete(0,removed); cursor=Math.max(0,cursor-removed);
        } else { pending.setLength(0); cursor=0; }
        retryId=""; retryText=""; retryDeviceId=""; retryAttempt=0;
        inFlightId=id; inFlightText=text; inFlightDeviceId=deviceId; inFlightAttempt=attempt;
        return new SendRequest(id,deviceId,text,attempt);
    }
    public synchronized boolean onSendSucceeded(String requestId) {
        if (requestId == null || !requestId.equals(inFlightId)) return false;
        clearInFlight(); return true;
    }
    public synchronized boolean onSendFailed(String requestId,SendFailure failure) {
        if (requestId == null || !requestId.equals(inFlightId)) return false;
        pending.insert(0,inFlightText); cursor+=inFlightText.length();
        if (failure == SendFailure.UNKNOWN) {
            retryId=inFlightId; retryText=inFlightText; retryDeviceId=inFlightDeviceId; retryAttempt=inFlightAttempt;
        } else { retryId=""; retryText=""; retryDeviceId=""; retryAttempt=0; }
        clearInFlight(); return true;
    }
    public synchronized Snapshot snapshot() {
        String durable=pending.toString(), id=retryId, text=retryText, targetId=retryDeviceId; int attempt=retryAttempt;
        if (!inFlightId.isEmpty()) {
            durable=inFlightText+durable; id=inFlightId; text=inFlightText; targetId=inFlightDeviceId; attempt=inFlightAttempt;
        }
        int durableCursor=cursor;
        if (!inFlightId.isEmpty()) durableCursor+=inFlightText.length();
        return new Snapshot(durable,id,text,attempt,targetId,durableCursor);
    }
    public synchronized void restore(Snapshot state) {
        pending.setLength(0); cursor=0; composing=""; clearInFlight();
        retryId=""; retryText=""; retryDeviceId=""; retryAttempt=0;
        if (state == null) return;
        pending.append(state.pending); cursor=Math.max(0,Math.min(state.cursor,pending.length()));
        if (!state.retryId.isEmpty() && state.pending.startsWith(state.retryText)) {
            retryId=state.retryId; retryText=state.retryText;
            retryDeviceId=state.retryDeviceId; retryAttempt=state.retryAttempt;
        }
    }
    private void clearInFlight() { inFlightId=""; inFlightText=""; inFlightDeviceId=""; inFlightAttempt=0; }
}
