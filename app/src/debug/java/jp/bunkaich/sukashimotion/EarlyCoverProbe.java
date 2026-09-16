package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;

/** Explicit 60-second hardware probe. Each dual-screen request lasts at most 5 seconds. */
public final class EarlyCoverProbe {
    private static volatile long heldAt;
    private static volatile boolean armed, initiallyInner;
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        ShellBridge bridge = new ShellBridge();
        DualDisplayControl control = new DualDisplayControl();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("currentActivityThread").invoke(null);
        Context context = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        System.out.println("READY: fully open or close, then slowly fold in the opposite direction. " + control.describe());
        try {
            bridge.startAngles(new IAngleSink.Stub() {
                public void angle(float value, long at, int source) {
                    if (source != 0) return;
                    System.out.println("HINGE " + value + " at=" + at);
                    try {
                        if (value >= 177 || value <= 3) {
                            control.close(); heldAt = 0; armed = true; initiallyInner = value >= 177;
                            System.out.println("ENDPOINT " + control.describe());
                        } else if (armed && heldAt == 0) {
                            armed = false;
                            System.out.println("MOTION_START opening=" + !initiallyInner + " " + control.describe());
                            control.hold(initiallyInner, 0); heldAt = SystemClock.elapsedRealtime();
                            System.out.println("BOTH_DISPLAYS_REQUEST delayMs=" + (heldAt - at) + " " + control.describe());
                        }
                    } catch (Exception error) { System.out.println("ERROR " + ShellBridge.message(error)); control.close(); heldAt = 0; }
                }
            });
            long deadline = SystemClock.elapsedRealtime() + 60000, reported = 0;
            while (SystemClock.elapsedRealtime() < deadline) {
                bridge.heartbeat();
                long now = SystemClock.elapsedRealtime();
                if (heldAt > 0 && now - heldAt > 5000) { control.close(); heldAt = 0; System.out.println("WATCHDOG_RELEASED"); }
                if (heldAt > 0 && heldAt != reported && now - heldAt > 300) {
                    reported = heldAt;
                    for (Display display : displays.getDisplays()) if (display.getDisplayId() <= 1)
                        System.out.println("PANEL " + display.getDisplayId() + " state=" + display.getState() + " " + display.getMode().getPhysicalWidth() + "x" + display.getMode().getPhysicalHeight());
                }
                Thread.sleep(100);
            }
        } finally {
            bridge.stopAngles(); control.close();
            System.out.println("FINISHED " + control.describe());
            bridge.destroy();
        }
    }
}
