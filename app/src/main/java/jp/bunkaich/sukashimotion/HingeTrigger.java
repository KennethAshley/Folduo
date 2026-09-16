package jp.bunkaich.sukashimotion;

/** Hysteresis accommodates the hardware log's roughly 10-degree reporting steps. */
final class HingeTrigger {
    static final int NONE = 0, START = 1, FINISH = 2;
    private boolean armed, moving;
    int update(float angle) {
        if (!Float.isFinite(angle) || angle < 0 || angle > 180) return NONE;
        if (angle <= 10 || angle >= 170) {
            boolean finished = moving;
            armed = true; moving = false;
            return finished ? FINISH : NONE;
        }
        if (!armed) return NONE;
        armed = false; moving = true;
        return START;
    }
    void reset() { armed = moving = false; }
    static boolean nativeEndpoint(boolean inner, String baseState) {
        return (inner ? "OPENED" : "CLOSED").equals(baseState);
    }
}
