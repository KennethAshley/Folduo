package jp.bunkaich.sukashimotion;

import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** UI-only ownership check: the harness never requests or releases a display. */
public class DuoStandaloneHomeTest {
    private static final String APP="com.example.duofold.fine";
    private final android.app.Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final android.app.UiAutomation automation=instrumentation.getUiAutomation();
    private String shell(String command)throws Exception{
        try(var in=new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))){
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text){
        if(node==null)return null;
        if(text.equals(String.valueOf(node.getText())))return node;
        for(int i=0;i<node.getChildCount();i++){var found=find(node.getChild(i),text);if(found!=null)return found;}
        return null;
    }
    private AccessibilityNodeInfo button(int display,String text){
        var windows=automation.getWindowsOnAllDisplays();
        for(int i=0;i<windows.size();i++)if(windows.keyAt(i)==display)for(var window:windows.valueAt(i)){
            var root=window.getRoot();
            if(root!=null&&APP.equals(String.valueOf(root.getPackageName()))){var result=find(root,text);if(result!=null)return result;}
        }
        return null;
    }
    private void click(int display,String text)throws Exception{
        long until=SystemClock.elapsedRealtime()+10000;
        while(SystemClock.elapsedRealtime()<until){
            var node=button(display,text);
            while(node!=null&&!node.isClickable())node=node.getParent();
            if(node!=null&&node.isEnabled()&&node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;
            Thread.sleep(100);
        }
        fail("No enabled "+text+" on display "+display);
    }
    private void awaitReleased()throws Exception{
        String state="";long until=SystemClock.elapsedRealtime()+6000;
        do{
            state=shell("dumpsys device_state");
            if(state.contains("mOverrideState=Optional.empty")&&state.contains("mCommittedState=Optional[DeviceState{identifier=0,"))return;
            Thread.sleep(100);
        }while(SystemClock.elapsedRealtime()<until);
        fail("App must release the display without harness assistance: "+state);
    }
    private void awaitActivityFinished()throws Exception{
        long until=SystemClock.elapsedRealtime()+5000;
        do{
            if(shell("dumpsys activity activities").lines().noneMatch(line->line.contains("Hist #")&&line.contains(APP)))return;
            Thread.sleep(100);
        }while(SystemClock.elapsedRealtime()<until);
        fail("Leaving Smooth Home must finish its activity before relaunch");
    }
    private void start()throws Exception{
        shell("am start -W -n "+APP+"/com.example.duofold.MainActivity");
        awaitReleased();
        long readerDeadline=SystemClock.elapsedRealtime()+15000;
        boolean readerReady=false;
        while(SystemClock.elapsedRealtime()<readerDeadline){
            IShellBridge bridge=BridgeConnection.bridge;
            if(bridge!=null&&bridge.inspect().getBoolean("running")){readerReady=true;break;}
            Thread.sleep(100);
        }
        assertTrue("Existing Shizuku reader must connect before Start",readerReady);
        click(0,"Start smooth Home");
        long until=SystemClock.elapsedRealtime()+7000;
        while(SystemClock.elapsedRealtime()<until&&(button(0,"Stop smooth Home")==null||button(1,"Stop smooth Home")==null))Thread.sleep(100);
        assertNotNull("Cover Stop is reachable",button(0,"Stop smooth Home"));
        assertNotNull("Inner Stop is reachable",button(1,"Stop smooth Home"));
        assertTrue("App owns cover-primary state",shell("dumpsys device_state").contains("mCommittedState=Optional[DeviceState{identifier=5,"));
        String pid=shell("pidof "+APP).trim();
        assertTrue("Cover shader must be prepared while still closed, before the first hinge movement",
            shell("logcat -d --pid="+pid+" -s FoldEffect:D '*:S'").contains("Prepared fold shader: tilt=0.0"));
    }
    @Test public void startStopHomeAndProcessDeathRestoreSamsungWithoutHarnessControl()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        org.junit.Assume.assumeTrue("SM-F971U".equals(android.os.Build.MODEL));
        automation.adoptShellPermissionIdentity();
        var info=automation.getServiceInfo();info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        try{
            assertFalse("Unlock the phone first",instrumentation.getTargetContext().getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
            assertTrue("Leave the phone physically closed",shell("dumpsys device_state").contains("mBaseState=Optional[DeviceState{identifier=0,"));
            shell("am force-stop "+APP);
            start();click(1,"Stop smooth Home");awaitReleased();awaitActivityFinished();
            start();shell("input keyevent KEYCODE_HOME");awaitReleased();awaitActivityFinished();
            start();shell("am force-stop "+APP);awaitReleased();
        }finally{
            shell("am force-stop "+APP);
            automation.dropShellPermissionIdentity();
        }
    }
}
