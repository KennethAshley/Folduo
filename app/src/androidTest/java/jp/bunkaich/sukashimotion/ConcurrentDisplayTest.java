package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import static org.junit.Assert.*;

/** Explicit physical-device diagnostic; synthetic colors only, auto-released after 45 seconds. */
public class ConcurrentDisplayTest {
    @Test public void showBothPhysicalPanels() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")) && DeviceSupport.supports(Build.MODEL));
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = instrumentation.getTargetContext();
        MotionSettings.setEnabled(context, false);
        Activity activity = instrumentation.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        var windows = new ArrayList<WindowManager>();
        var views = new ArrayList<View>();
        IShellBridge bridge = null;
        try {
            BridgeConnection.connect(context);
            long deadline = SystemClock.elapsedRealtime() + 10000;
            while (BridgeConnection.bridge == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            bridge = BridgeConnection.bridge; assertNotNull("Real Shizuku connection", bridge);
            bridge.startAngles(new IAngleSink.Stub() { public void angle(float value, long at, int source) { } });
            DisplayManager displays = context.getSystemService(DisplayManager.class);
            Display.Mode primary = displays.getDisplay(0).getMode();
            boolean inner = Math.min(primary.getPhysicalWidth(), primary.getPhysicalHeight()) / (float)Math.max(primary.getPhysicalWidth(), primary.getPhysicalHeight()) > .7f;
            Bundle held = bridge.hold(inner, 0); assertTrue(held.toString(), held.getBoolean("ok"));
            deadline = SystemClock.elapsedRealtime() + 4000;
            while (!bothOn(displays) && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50);
            assertTrue("Both displays must be ON: " + bridge.inspect().getString("display"), bothOn(displays));
            CountDownLatch drawn = new CountDownLatch(2);
            instrumentation.runOnMainSync(() -> {
                for (int id : new int[]{0, 1}) {
                    Display display = displays.getDisplay(id);
                    Context panel = context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
                    WindowManager manager = panel.getSystemService(WindowManager.class);
                    TextView view = new TextView(panel);
                    Display.Mode mode = display.getMode();
                    boolean inside = Math.min(mode.getPhysicalWidth(), mode.getPhysicalHeight()) / (float)Math.max(mode.getPhysicalWidth(), mode.getPhysicalHeight()) > .7f;
                    view.setBackgroundColor(inside ? 0xff146342 : 0xff1549a4);
                    view.setText(inside ? "INSIDE SCREEN TEST" : "OUTSIDE SCREEN TEST");
                    view.setTextColor(Color.WHITE); view.setTextSize(28); view.setGravity(Gravity.CENTER);
                    view.setKeepScreenOn(true);
                    view.getViewTreeObserver().registerFrameCommitCallback(drawn::countDown);
                    WindowManager.LayoutParams params = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, PixelFormat.OPAQUE);
                    params.setTitle("Folduo physical display test");
                    manager.addView(view, params); windows.add(manager); views.add(view);
                }
            });
            assertTrue("Both test windows must submit a frame", drawn.await(5, TimeUnit.SECONDS));
            Bundle report = new Bundle(); report.putString("stream", "BOTH_TEST_FRAMES_COMMITTED: green inside, blue outside; hold still for 45 seconds\n");
            instrumentation.sendStatus(0, report);
            deadline = SystemClock.elapsedRealtime() + 45000;
            while (SystemClock.elapsedRealtime() < deadline) {
                assertTrue("Display test cancelled: " + bridge.inspect().getString("display"), bothOn(displays));
                Thread.sleep(250);
            }
        } finally {
            instrumentation.runOnMainSync(() -> {
                for (int i = 0; i < views.size(); i++) try { windows.get(i).removeViewImmediate(views.get(i)); } catch (IllegalArgumentException ignored) { }
                activity.finish();
            });
            if (bridge != null) { bridge.release(); bridge.stopAngles(); }
            BridgeConnection.disconnect();
        }
    }
    private static boolean bothOn(DisplayManager displays) {
        for (int id : new int[]{0, 1}) {
            Display display = displays.getDisplay(id);
            if (display == null || display.getState() != Display.STATE_ON) return false;
        }
        return true;
    }
}
