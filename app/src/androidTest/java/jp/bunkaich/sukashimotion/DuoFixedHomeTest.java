package jp.bunkaich.sukashimotion;

import android.app.KeyguardManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Throwaway, opt-in Home experiment. The request dies with this process. */
public class DuoFixedHomeTest {
    private static final String APP="com.example.duofold.fine";
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final android.app.UiAutomation automation=instrumentation.getUiAutomation();

    private String shell(String command)throws Exception {
        try(var in=new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))){
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private void report(String message){
        Bundle b=new Bundle();b.putString("stream",message+"\n");instrumentation.sendStatus(0,b);
    }
    private AccessibilityNodeInfo findExit(AccessibilityNodeInfo node){
        if(node==null)return null;
        if("Exit trial".equals(String.valueOf(node.getText())))return node;
        for(int i=0;i<node.getChildCount();i++){
            var found=findExit(node.getChild(i));if(found!=null)return found;
        }
        return null;
    }
    private AccessibilityNodeInfo exitButton(int display){
        var windows=automation.getWindowsOnAllDisplays();
        for(int i=0;i<windows.size();i++)if(windows.keyAt(i)==display)for(var window:windows.valueAt(i)){
            var root=window.getRoot();if(root==null)continue;
            if(APP.equals(String.valueOf(root.getPackageName()))){
                var found=findExit(root);if(found!=null)return found;
            }
        }
        return null;
    }
    private void holdCover(IShellBridge control)throws Exception{
        Bundle held=control.hold(false,0);
        assertTrue(held.toString(),held.getBoolean("ok"));
        long until=SystemClock.elapsedRealtime()+5000;
        while(!control.inspect().getString("display").contains("current=5 /")&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
        assertTrue("Cover must remain primary",control.inspect().getString("display").contains("current=5 /"));
    }
    @Test public void fixedHomeKeepsBothLayoutsAndRestoresOnExit()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        org.junit.Assume.assumeTrue("SM-F971U".equals(android.os.Build.MODEL));
        boolean physical="true".equals(InstrumentationRegistry.getArguments().getString("physical"));
        var context=instrumentation.getTargetContext();
        var keyguard=context.getSystemService(KeyguardManager.class);
        automation.adoptShellPermissionIdentity();
        var info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        IShellBridge control=null;
        android.app.Activity setup=null;
        try{
            assertFalse("Unlock before the trial",keyguard.isKeyguardLocked());
            shell("am force-stop "+APP);
            setup=instrumentation.startActivitySync(new android.content.Intent(context,MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            BridgeConnection.connect(context);
            long connectUntil=SystemClock.elapsedRealtime()+15000;
            while(BridgeConnection.bridge==null&&SystemClock.elapsedRealtime()<connectUntil)Thread.sleep(50);
            control=BridgeConnection.bridge;
            assertNotNull("Existing Shizuku helper must connect",control);
            if(physical)WallpaperMirrorHardwareTest.waitForManualStart(setup,control,"Fold check ready",
                "Keep the phone closed and unlocked. Tap Start test when ready. When the app grid says Fold check active: open fully, close fully, and pause two seconds. Repeat once. Then open and tap Exit trial on the inner screen. The active test lasts two minutes.");
            assertFalse("Unlock before Start",keyguard.isKeyguardLocked());
            assertEquals("Start fully closed", "CLOSED",control.inspect().getString("baseState"));
            control.startAngles(new IAngleSink.Stub(){public void angle(float a,long t,int source){}});
            holdCover(control);
            report(shell("am start -W -n "+APP+"/com.example.duofold.MainActivity --ez fixed_trial true"));
            android.app.Activity launchScreen=setup;
            instrumentation.runOnMainSync(launchScreen::finish);
            long deadline=SystemClock.elapsedRealtime()+8000;
            while(SystemClock.elapsedRealtime()<deadline &&
                (exitButton(0)==null||exitButton(1)==null))Thread.sleep(150);
            assertNotNull("Cover needs a reachable Exit trial control",exitButton(0));
            assertNotNull("Inner needs a reachable Exit trial control",exitButton(1));
            assertTrue("The app must not replace the fixed display request",control.inspect().getString("display").contains("current=5 /"));
            if("true".equals(InstrumentationRegistry.getArguments().getString("rearm"))){
                control.release();
                deadline=SystemClock.elapsedRealtime()+3000;
                while(!control.inspect().getString("display").contains("current=0 /")&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
                assertTrue("Simulated endpoint reaches closed state",control.inspect().getString("display").contains("current=0 /"));
                Thread.sleep(750); // Let display-removal callbacks run before rearming.
                assertNotNull("Cover must survive inner display removal",exitButton(0));
                holdCover(control);
                deadline=SystemClock.elapsedRealtime()+4000;
                while(exitButton(1)==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
                assertNotNull("Cover controls survive rearming",exitButton(0));
                assertNotNull("Inner controls survive rearming",exitButton(1));
                report("FIXED_DUO_REARM_CHECK_PASSED");
            }
            report("FIXED_DUO_READY: two open/close cycles with two-second pauses, then open and tap inner Exit trial. Two-minute limit.");
            if(physical){
                deadline=SystemClock.elapsedRealtime()+120000;
                boolean foldedAway=false;
                while(SystemClock.elapsedRealtime()<deadline&&!keyguard.isKeyguardLocked()){
                    String activities=shell("dumpsys activity activities");
                    boolean top=activities.lines().anyMatch(l->l.contains("topResumedActivity=")&&l.contains(APP));
                    if(!top)break;
                    Bundle state=control.inspect();
                    String display=state.getString("display");
                    if(!"CLOSED".equals(state.getString("baseState")))foldedAway=true;
                    // Samsung cancels state 5 at full closure. Only rearm once
                    // after a real fold; repeated/unrelated cancellations fail.
                    if(foldedAway&&"CLOSED".equals(state.getString("baseState"))&&display.contains("current=0 /")){
                        holdCover(control);foldedAway=false;
                        report("FIXED_DUO_REARMED_AFTER_CLOSE");
                        display=control.inspect().getString("display");
                    }
                    assertTrue("Screen assignments changed: "+display,display.contains("current=5 /"));
                    Thread.sleep(250);
                }
            }else{
                if("home".equals(InstrumentationRegistry.getArguments().getString("exit"))){
                    shell("input keyevent KEYCODE_HOME");
                }else{
                    var exit=exitButton(1);
                    while(exit!=null&&!exit.isClickable())exit=exit.getParent();
                    assertNotNull("Inner Exit has a clickable action",exit);
                    assertTrue("Inner Exit responds",exit.performAction(AccessibilityNodeInfo.ACTION_CLICK));
                }
                deadline=SystemClock.elapsedRealtime()+4000;
                while(exitButton(1)!=null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(100);
                assertNull("Exit removes the inner presentation",exitButton(1));
            }
        }finally{
            try{shell("am force-stop "+APP);}finally{
                try{
                    if(control!=null){
                        control.release();control.stopAngles();
                        long until=SystemClock.elapsedRealtime()+3000;
                        while(control.inspect().getString("display").contains("current=5 /")&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
                        report("FIXED_DUO_RELEASED: "+control.inspect().getString("display"));
                    }
                }catch(android.os.DeadObjectException e){
                    report("FIXED_DUO_HELPER_DIED: verify native display state before retrying.");
                }finally{
                    automation.dropShellPermissionIdentity();
                    if(setup!=null){android.app.Activity launchScreen=setup;instrumentation.runOnMainSync(launchScreen::finish);}
                }
            }
        }
    }
}
