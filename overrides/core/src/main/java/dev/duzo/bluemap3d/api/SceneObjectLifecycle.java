package dev.duzo.bluemap3d.api;

/**
 * Default lifecycle policy for objects exposed by one provider.
 *
 * <p>Persistent is deliberately the default: moving world objects should survive
 * temporary unloads, server restarts and be eligible for historical recording unless
 * a provider explicitly opts out.
 */
public enum SceneObjectLifecycle {
    PERSISTENT(true, true),
    RESTORE_ONLY(true, false),
    HISTORY_ONLY(false, true),
    LIVE_ONLY(false, false);

    private final boolean restoreOnLaunch;
    private final boolean recordHistory;

    SceneObjectLifecycle(boolean restoreOnLaunch, boolean recordHistory) {
        this.restoreOnLaunch = restoreOnLaunch;
        this.recordHistory = recordHistory;
    }

    public boolean restoreOnLaunch() {
        return restoreOnLaunch;
    }

    public boolean recordHistory() {
        return recordHistory;
    }
}
