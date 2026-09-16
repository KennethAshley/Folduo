package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit hardware check: synthetic overlay pixels, real dual-panel availability, fake app readiness. */
public class HandoffTest {
    private void waitFor(java.util.function.BooleanSupplier condition, IShellBridge real) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + 4000;
        while (!condition.getAsBoolean() && SystemClock.elapsedRealtime() < deadline) { real.heartbeat(); Thread.sleep(25); }
        assertTrue("Condition reached before timeout", condition.getAsBoolean());
    }
    @Test public void frostRemainsUntilTheDestinationAppHasDrawn() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")) && DeviceSupport.supports(Build.MODEL));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        Display.Mode mode = displays.getDisplay(0).getMode();
        org.junit.Assume.assumeTrue("Run this diagnostic unfolded", Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()) / (float)Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()) > .7f);
        MotionSettings.setEnabled(context, false);
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        IShellBridge real = null;
        class Bridge extends ServiceLifecycleTest.FakeBridge {
            volatile boolean appDrawn;
            volatile int readinessChecks;
            @Override public Bundle inspect() { Bundle state = new Bundle(); state.putString("baseState", "OPENED"); return state; }
            @Override public Bundle windowState(int id) {
                readinessChecks++;
                Bundle state = new Bundle(); state.putBoolean("ready", appDrawn); state.putString("geometry", "[0,0][2448,1848]"); return state;
            }
        }
        Bridge fake = new Bridge();
        try {
            BridgeConnection.connect(context);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (BridgeConnection.bridge == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25);
            real = BridgeConnection.bridge; assertNotNull(real);
            real.startAngles(new IAngleSink.Stub() { public void angle(float value, long at, int source) { } });
            assertTrue(real.hold(true, 0).getBoolean("ok"));
            waitFor(() -> displays.getDisplay(1) != null && displays.getDisplay(1).getState() == Display.STATE_ON, real);
            BridgeConnection.bridge = fake;
            context.startForegroundService(new Intent(context, RevealService.class).setAction("start"));
            waitFor(() -> fake.sink != null, real);
            fake.sink.angle(178, SystemClock.elapsedRealtime(), HardwareAngle.SOURCE);
            fake.sink.angle(166, SystemClock.elapsedRealtime(), HardwareAngle.SOURCE);
            waitFor(() -> RevealService.showing, real);
            fake.sink.angle(178, SystemClock.elapsedRealtime(), HardwareAngle.SOURCE); // Reverse to original inner endpoint.
            waitFor(() -> fake.readinessChecks > 0, real);
            Thread.sleep(120);
            assertTrue("A released request must keep frost above an undrawn app", RevealService.showing);
            fake.appDrawn = true;
            waitFor(() -> !RevealService.showing && RevealService.completed > 0, real);
            assertEquals(0, fake.moves);
        } finally {
            MotionSettings.setEnabled(context, false);
            context.stopService(new Intent(context, RevealService.class));
            if (real != null) { real.release(); real.stopAngles(); }
            BridgeConnection.disconnect();
            instrumentation.runOnMainSync(activity::finish);
        }
    }
}
