package io.github.amxooo.voiceinput2pc;

/** Process-local microphone arbitration. Both consumers run in the default process. */
final class MicrophoneLease {
    private static String owner;
    private MicrophoneLease() {}
    static synchronized boolean tryAcquire(String requestedOwner) {
        if (requestedOwner == null || requestedOwner.isEmpty()) throw new IllegalArgumentException("owner");
        // A second start, even with the same owner, is not another lease.
        if (owner != null) return false;
        owner = requestedOwner;
        return true;
    }
    static synchronized void release(String requestedOwner) {
        if (requestedOwner != null && requestedOwner.equals(owner)) owner = null;
    }
    static synchronized boolean isOwnedBy(String requestedOwner) {
        return requestedOwner != null && requestedOwner.equals(owner);
    }
}
