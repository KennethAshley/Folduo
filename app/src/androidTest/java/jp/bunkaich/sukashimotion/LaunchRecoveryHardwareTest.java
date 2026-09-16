package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.Display;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

/** Ordinary app launch from Samsung's rear display, using the real system callback. */
public class LaunchRecoveryHardwareTest {
    interface Check {boolean ready()throws Exception;}
    private static void waitFor(Check check)throws Exception{
        long end=SystemClock.elapsedRealtime()+10000;int stable=0;
        while(SystemClock.elapsedRealtime()<end){stable=check.ready()?stable+1:0;if(stable>=3)return;Thread.sleep(50);}
        fail("Launch recovery must settle within ten seconds");
    }
    @Test public void ordinaryAppLaunchIsRecoveredOnTheInnerScreen()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware"))&&DeviceSupport.supports(Build.MODEL));
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        Activity activity=null;IShellBridge bridge=null;
        try{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,RevealService.class));context.stopService(new Intent(context,MotionService.class));
            waitFor(()->!RevealService.running&&!MotionService.running);
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            BridgeConnection.connect(context);waitFor(()->BridgeConnection.bridge!=null);bridge=BridgeConnection.bridge;
            org.junit.Assume.assumeTrue("Phone must be unfolded", "OPENED".equals(bridge.inspect().getString("baseState")));
            org.junit.Assume.assumeFalse("Phone must be unlocked",context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
            bridge.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){}});
            Bundle held=bridge.hold(false,0);assertTrue(held.toString(),held.getBoolean("ok"));
            DisplayManager displays=context.getSystemService(DisplayManager.class);
            waitFor(()->displays.getDisplay(1)!=null&&displays.getDisplay(1).getState()==Display.STATE_ON);
            Bundle moved=bridge.moveApp(0,1,false);assertTrue(moved.toString(),moved.getBoolean("ok"));
            Activity source=activity;
            waitFor(()->{AtomicInteger display=new AtomicInteger(-1);instrumentation.runOnMainSync(()->display.set(source.getDisplay().getDisplayId()));return display.get()==1;});
            Bundle enabled=bridge.routeInnerLaunches(true);assertTrue(enabled.toString(),enabled.getBoolean("ok"));
            int before=bridge.inspect().getInt("launchRecoveries");IShellBridge real=bridge;
            // This is an ordinary unprivileged activity launch, like a launcher tap.
            instrumentation.runOnMainSync(()->source.startActivity(TaskDisplayRouter.launchIntent(new ComponentName("com.sec.android.app.popupcalculator","com.sec.android.app.popupcalculator.Calculator"))));
            waitFor(()->real.inspect().getInt("launchRecoveries")>before&&calculatorOnInner()&&real.windowState(1).getBoolean("ready"));
            assertEquals("Duplicate failure callbacks recover the task only once",before+1,bridge.inspect().getInt("launchRecoveries"));
            assertTrue(bridge.inspect().getString("launchRecoveryError","").isEmpty());
            if("true".equals(InstrumentationRegistry.getArguments().getString("checkHome"))){
                saveInnerFrame(context,bridge,"inner-calculator.png");
                reportHomeState("BEFORE_HOME");
                ComponentName home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).resolveActivity(context.getPackageManager());assertNotNull(home);
                Bundle response=bridge.navigate(1,android.view.KeyEvent.KEYCODE_HOME,-1);
                assertTrue(response.getString("error",response.toString()),response.getBoolean("ok"));
                try{waitFor(()->focusedOnInner(home.flattenToShortString())&&real.windowState(1).getBoolean("ready"));}
                finally{
                    reportHomeState("AFTER_HOME");saveInnerFrame(context,bridge,"inner-home.png");
                    try(var input=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("dumpsys SurfaceFlinger"));var output=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"inner-home-surfaces.txt"))){input.transferTo(output);}
                }
                Bundle report=new Bundle();report.putString("stream","HOME_FOCUSED_ON_INNER: inspect saved frame for visible content.\n");instrumentation.sendStatus(0,report);
            }
            if("true".equals(InstrumentationRegistry.getArguments().getString("manualTap"))){
                Bundle cover=bridge.moveApp(1,0,false);assertTrue(cover.toString(),cover.getBoolean("ok"));
                Bundle home=bridge.navigate(1,android.view.KeyEvent.KEYCODE_HOME,-1);assertTrue(home.toString(),home.getBoolean("ok"));
                waitFor(()->real.windowState(1).getBoolean("ready"));
                Bundle ready=new Bundle();ready.putString("stream","KVAESITSO_READY: open the app list and tap Calculator once; keep the phone unfolded.\n");instrumentation.sendStatus(0,ready);
                long deadline=SystemClock.elapsedRealtime()+90000;
                while(real.inspect().getInt("launchRecoveries")==before+1&&SystemClock.elapsedRealtime()<deadline){
                    assertEquals("Keep phone unfolded during the tap check","OPENED",real.inspect().getString("baseState"));
                    assertFalse("Phone must stay unlocked",context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
                    Thread.sleep(100);
                }
                waitFor(()->real.inspect().getInt("launchRecoveries")>before+1&&calculatorOnInner()&&real.windowState(1).getBoolean("ready"));
                assertEquals(before+2,real.inspect().getInt("launchRecoveries"));
                assertTrue(real.inspect().getString("launchRecoveryError","").isEmpty());
                Bundle result=new Bundle();result.putString("stream","KVAESITSO_CALCULATOR_INNER_OK\n");instrumentation.sendStatus(0,result);
                Thread.sleep(2000); // Leave the confirmed Calculator visible before restoration.
            }
            bridge.routeInnerLaunches(false);assertFalse(bridge.inspect().getBoolean("routingInnerLaunches"));
        }finally{
            if(bridge!=null)try{Bundle state=bridge.inspect(),report=new Bundle();report.putString("stream","LAUNCH_RESULT base="+state.getString("baseState")+" routing="+state.getBoolean("routingInnerLaunches")+" recoveries="+state.getInt("launchRecoveries")+" error="+state.getString("launchRecoveryError")+" calculatorOnInner="+calculatorOnInner()+" windowReady="+bridge.windowState(1).getBoolean("ready")+"\n");instrumentation.sendStatus(0,report);}catch(Exception diagnosticFailure){android.util.Log.w("FolduoTest","Launch diagnostics unavailable",diagnosticFailure);}
            if(bridge!=null)try{bridge.release();bridge.stopAngles();}finally{BridgeConnection.disconnect();}
            if(activity!=null){Activity source=activity;instrumentation.runOnMainSync(source::finish);}
            MotionSettings.setEnabled(context,false);
        }
    }
    private static boolean calculatorOnInner()throws Exception{
        return focusedOnInner("com.sec.android.app.popupcalculator/.Calculator");
    }
    private static void saveInnerFrame(Context context,IShellBridge bridge,String name)throws Exception{
        assertFalse("Capture only while unlocked",context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
        Bundle result=bridge.capture(1);android.graphics.Bitmap frame=result.getParcelable("frame",android.graphics.Bitmap.class);assertNotNull(result.toString(),frame);
        try(var output=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name))){assertTrue(frame.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output));}finally{frame.recycle();}
    }
    private static void reportHomeState(String label)throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        try(var input=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("dumpsys activity activities"))){
            String dump=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);Bundle report=new Bundle();
            if(label.equals("AFTER_HOME"))java.nio.file.Files.writeString(new java.io.File(instrumentation.getTargetContext().getExternalFilesDir(null),"inner-home-activities.txt").toPath(),dump);
            StringBuilder selected=new StringBuilder();boolean home=false;
            for(String line:dump.split("\n")){
                if(line.contains("* Task{"))home=line.contains("de.mm20.launcher2.release");
                if(line.startsWith("Display #")||line.contains("mFocusedApp=")||(home&&(line.contains("* Task{")||line.contains("isSleeping=")||line.contains("state=")||line.contains("mVisibleRequested=")||line.contains("mAppStopped=")||line.contains("windows="))))selected.append(line).append('\n');
            }
            report.putString("stream",label+"\n"+selected);instrumentation.sendStatus(0,report);
        }
        if(label.equals("AFTER_HOME"))try(var input=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("dumpsys window windows"))){
            StringBuilder selected=new StringBuilder();boolean launcher=false;
            for(String line:new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).split("\n")){
                if(line.startsWith("  Window #"))launcher=line.contains("de.mm20.launcher2.release");
                if(launcher)selected.append(line).append('\n');
            }
            Bundle report=new Bundle();report.putString("stream",selected.toString());instrumentation.sendStatus(0,report);
        }
    }
    private static boolean focusedOnInner(String component)throws Exception{
        try(var input=new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("dumpsys window displays"))){
            String dump=new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            int start=dump.indexOf("Display: mDisplayId=1 ");if(start<0)return false;
            String section=dump.substring(start);int end=section.indexOf("Display: mDisplayId=",10);if(end>=0)section=section.substring(0,end);
            return section.lines().anyMatch(line->line.contains("mFocusedApp=")&&line.contains(component));
        }
    }
}
