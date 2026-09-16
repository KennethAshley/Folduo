package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RevealServiceTest {
    private void waitFor(java.util.function.BooleanSupplier condition)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+7000;
        while(!condition.getAsBoolean()&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(25);
        assertTrue("Condition reached within seven seconds",condition.getAsBoolean());
    }
    @Test public void captureMustCoverTheNativeSwitchAndCancelledCapturesNeverAppear()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        MotionSettings.setEnabled(context,false);
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        class Bridge extends ServiceLifecycleTest.FakeBridge {
            volatile boolean delay,protectedFrame,holdWithoutFrost;volatile int nativeHolds,pairedHolds;
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            @Override public Bundle capture(int id){
                if(delay){entered.countDown();try{resume.await(6,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}
                if(protectedFrame){Bundle b=new Bundle();b.putString("error","Protected frame unavailable");return b;}
                return super.capture(id);
            }
            @Override public Bundle holdNative(boolean inner){nativeHolds++;holdWithoutFrost|=!RevealService.showing;return super.holdNative(inner);}
            public Bundle holdPaired(boolean inner){pairedHolds++;holdWithoutFrost|=!RevealService.showing;Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
        }
        Bridge bridge=new Bridge();BridgeConnection.bridge=bridge;
        try{
            context.startForegroundService(new Intent(context,RevealService.class).setAction("preview"));
            waitFor(()->RevealService.completed==1&&bridge.sink!=null&&!RevealService.showing);
            assertEquals(1,bridge.captures);assertEquals(0,bridge.nativeHolds);
            bridge.delay=true;
            bridge.sink.angle(0,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            bridge.sink.angle(22,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            assertTrue(bridge.entered.await(3,TimeUnit.SECONDS));
            assertEquals("No concurrent mode",0,bridge.holds);
            assertEquals("Never switch panels before the covering frame exists",0,bridge.nativeHolds);
            bridge.sink.angle(170,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            Thread.sleep(80);bridge.resume.countDown();waitFor(()->bridge.captures==2);Thread.sleep(150);
            assertFalse("A late capture cannot flash after the endpoint",RevealService.showing);
            assertEquals(0,bridge.nativeHolds);
            bridge.delay=false;bridge.protectedFrame=true;int skipped=RevealService.skipped;
            bridge.sink.angle(156,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            waitFor(()->RevealService.skipped>skipped);assertFalse(RevealService.showing);assertEquals(0,bridge.nativeHolds);
            bridge.protectedFrame=false;
            bridge.sink.angle(0,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            bridge.sink.angle(22,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            waitFor(()->bridge.pairedHolds==1);
            assertEquals("Do not turn off the outgoing screen when the animation starts",0,bridge.nativeHolds);
            assertTrue(RevealService.showing);assertFalse(bridge.holdWithoutFrost);assertEquals(0,bridge.holds);assertEquals(0,bridge.moves);
            int releases=bridge.releases;
            context.startService(new Intent(context,RevealService.class).setAction("stop"));
            waitFor(()->!RevealService.running&&bridge.sink==null&&bridge.releases>releases);
            assertFalse(RevealService.showing);assertFalse(MotionSettings.enabled(context));
            new RestartReceiver().onReceive(context,new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));Thread.sleep(150);
            assertFalse("Explicit Stop survives an app update",RevealService.running);assertFalse(MotionService.running);
        }finally{
            bridge.resume.countDown();MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,RevealService.class));
            waitFor(()->!RevealService.running);instrumentation.runOnMainSync(activity::finish);
        }
    }
}
