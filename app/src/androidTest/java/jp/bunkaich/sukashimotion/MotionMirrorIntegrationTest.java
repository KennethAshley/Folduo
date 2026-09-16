package jp.bunkaich.sukashimotion;

import android.app.*;
import android.content.*;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real service/bridge/rendering, with a repeatable hinge trace on a stationary open phone. */
public class MotionMirrorIntegrationTest {
    interface Check {boolean ready()throws Exception;}
    private static void await(String message,Check check)throws Exception{long until=SystemClock.elapsedRealtime()+12000;while(!check.ready()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);assertTrue(message,check.ready());}
    @Test public void foldServiceKeepsNavigationLiveAndRestoresCoverLayout()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        Activity activity=null;IShellBridge bridge=null;
        try{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));context.stopService(new Intent(context,RevealService.class));
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            org.junit.Assume.assumeFalse(context.getSystemService(KeyguardManager.class).isKeyguardLocked());
            instrumentation.getUiAutomation().adoptShellPermissionIdentity();
            BridgeConnection.connect(context);await("Shizuku connected",()->BridgeConnection.bridge!=null);bridge=BridgeConnection.bridge;
            org.junit.Assume.assumeTrue("Leave phone fully open","OPENED".equals(bridge.inspect().getString("baseState")));
            bridge.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){}});
            assertTrue(bridge.hold(false,0).getBoolean("ok"));
            DisplayManager displays=context.getSystemService(DisplayManager.class);
            await("Cover is primary",()->{Display.Mode mode=displays.getDisplay(0).getMode();return mode.getPhysicalWidth()<mode.getPhysicalHeight()&&displays.getDisplay(1).getState()==Display.STATE_ON;});
            context.startForegroundService(new Intent(context,MotionService.class));await("Service started",()->MotionService.running);
            Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("currentActivityThread").invoke(null);var services=at.getDeclaredField("mServices");services.setAccessible(true);
            MotionService[] running={null};await("Service instance",()->{for(Object candidate:((Map<?,?>)services.get(thread)).values())if(candidate instanceof MotionService motion)running[0]=motion;return running[0]!=null;});
            MotionService service=running[0];Thread.sleep(350);angle(service,0);
            await("Production mirror prepared",()->{if((int)field(service,"recoveries")>0)fail(((UiText)field(service,"status")).resolve(context));return flag(service,"mirrorReady");});
            shell("am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.sec.android.app.popupcalculator/.Calculator -f 0x30000000");Thread.sleep(700);
            Activity setup=activity;instrumentation.runOnMainSync(setup::finish);Thread.sleep(1200);
            assertTrue("Closing the former captured setup task must not stop the mirror",bridge.inspect().getBoolean("mirrorActive"));
            Point inner=new Point();displays.getDisplay(1).getRealSize(inner);
            for(float value:new float[]{20,60,100}){angle(service,value);Thread.sleep(300);}
            WallpaperMirrorHardwareTest.navSwipe(inner,false,true);Thread.sleep(500);
            WallpaperMirrorHardwareTest.awaitPackage("com.sec.android.app.popupcalculator");
            for(float value:new float[]{140,173}){angle(service,value);Thread.sleep(300);}
            angle(service,173);
            await("Opening reveals the live workspace and controls",()->!flag(service,"busy")&&!flag(service,"finishing")&&field(service,"navigation")!=null);
            Point base=size("getBaseDisplaySize"),physical=size("getInitialDisplaySize");assertEquals(inner,base);assertFalse(base.equals(physical));
            WallpaperMirrorHardwareTest.assertMirroredApp(bridge);
            WallpaperMirrorHardwareTest.navSwipe(inner,false,false);WallpaperMirrorHardwareTest.awaitPackage("de.mm20.launcher2.release");
            for(float value:new float[]{140,100,60,20,5}){angle(service,value);Thread.sleep(300);}
            angle(service,5);
            await("Closing clears every frost layer",()->!flag(service,"busy")&&!flag(service,"finishing")&&((java.util.List<?>)field(service,"layers")).isEmpty());
            assertEquals("Cover regains its native app layout",physical,size("getBaseDisplaySize"));
            assertTrue("Mirror remains available for the next opening",bridge.inspect().getBoolean("mirrorActive"));
            Bundle report=new Bundle();report.putString("stream","FOLD_MIRROR_INTEGRATION_OK: open reveal, live Calculator, Home swipe, close reveal, native cover size.\n");instrumentation.sendStatus(0,report);
        }finally{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));await("Service stopped",()->!MotionService.running);
            if(bridge!=null)try{bridge.release();bridge.stopAngles();}catch(Exception ignored){}
            BridgeConnection.disconnect();if(activity!=null){Activity opened=activity;instrumentation.runOnMainSync(opened::finish);}
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
        }
        assertEquals("Stop restores the display size",size("getInitialDisplaySize"),size("getBaseDisplaySize"));
    }
    private static Object field(MotionService service,String name)throws Exception{var field=MotionService.class.getDeclaredField(name);field.setAccessible(true);return field.get(service);}
    private static boolean flag(MotionService service,String name)throws Exception{return (boolean)field(service,name);}
    private static void angle(MotionService service,float value)throws Exception{
        var method=MotionService.class.getDeclaredMethod("accept",float.class,long.class,int.class);method.setAccessible(true);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{method.invoke(service,value,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);}catch(Exception e){throw new AssertionError(e);}});
    }
    private static Point size(String method)throws Exception{
        Class<?> api=Class.forName("android.view.IWindowManager");IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");Object window=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        Point size=new Point();api.getMethod(method,int.class,Point.class).invoke(window,0,size);return size;
    }
    private static void shell(String command)throws Exception{try(var input=new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))){input.readAllBytes();}}
}
