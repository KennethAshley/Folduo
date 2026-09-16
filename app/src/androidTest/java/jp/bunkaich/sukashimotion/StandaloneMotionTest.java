package jp.bunkaich.sukashimotion;

import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.Button;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Real app controls and Shizuku; never adopts shell permissions or supplies a trial flag. */
public class StandaloneMotionTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private Activity activity;
    private interface Check {boolean ready()throws Exception;}
    private void await(String message,Check check)throws Exception{
        long until=SystemClock.elapsedRealtime()+15000;
        while(SystemClock.elapsedRealtime()<until){if(check.ready())return;Thread.sleep(100);}
        assertTrue(message,check.ready());
    }
    private String shell(String command)throws Exception{
        try(var in=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))){
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private String dump()throws Exception{return shell("dumpsys activity service "+context.getPackageName()+"/.MotionService");}
    private Button findButton(View view,String text){
        if(view instanceof Button button&&text.contentEquals(button.getText()))return button;
        if(view instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){Button found=findButton(group.getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
    private void tap(int label){
        instrumentation.runOnMainSync(()->{
            Button button=findButton(activity.getWindow().getDecorView(),context.getString(label));
            assertNotNull("App control exists: "+context.getString(label),button);assertTrue(button.isEnabled());button.performClick();
        });
    }
    private void ready()throws Exception{
        await("Normal Start must run the accepted motion service",()->MotionService.running);
        assertFalse("Only one animation service may run",RevealService.running);
        await("Both panels and the prepared inner frame are ready",()->dump().contains("layoutPrepared=true"));
        String report=dump();
        assertTrue("Accepted renderer must be active without a debug extra",report.contains("duoEffect=true"));
        assertTrue("The live app mirror is ready",report.contains("mirrorReady=true"));
        assertTrue("Start choice is persisted",MotionSettings.enabled(context));
    }
    private void stopped()throws Exception{
        await("Stop removes both services",()->!MotionService.running&&!RevealService.running);
        await("Stop restores native display dimensions",()->!shell("wm size").contains("Override size"));
        await("Stop releases Samsung display control",()->shell("dumpsys device_state").contains("mOverrideState=Optional.empty"));
        assertFalse("Stop choice persists",MotionSettings.enabled(context));
    }
    @Test public void appAndNotificationControlsUseAcceptedModeWithoutTestPermissions()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        try{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));context.stopService(new Intent(context,RevealService.class));
            await("Previous services stopped",()->!MotionService.running&&!RevealService.running);
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            assertFalse("Leave the phone unlocked",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
            assertTrue("Overlay access is required",Settings.canDrawOverlays(context));
            await("Shizuku permission is available",BridgeConnection::permitted);
            BridgeConnection.connect(context);await("Shizuku connected",()->BridgeConnection.bridge!=null);
            assertEquals("Leave the phone closed","CLOSED",BridgeConnection.bridge.inspect().getString("baseState"));
            tap(R.string.enable_animation);ready();
            Notification notification=null;
            for(var entry:context.getSystemService(NotificationManager.class).getActiveNotifications())if(entry.getId()==7)notification=entry.getNotification();
            assertNotNull("Foreground notification is present",notification);
            notification.actions[0].actionIntent.send();
            await("Notification Resume was processed",()->dump().contains("recoveries=1"));ready();
            notification.actions[1].actionIntent.send();stopped();
            instrumentation.runOnMainSync(()->new RestartReceiver().onReceive(context,new Intent(Intent.ACTION_MY_PACKAGE_REPLACED)));
            Thread.sleep(500);assertFalse("An app update must not undo Stop",MotionService.running||RevealService.running);
            MotionSettings.setEnabled(context,true);
            instrumentation.runOnMainSync(()->new RestartReceiver().onReceive(context,new Intent(Intent.ACTION_MY_PACKAGE_REPLACED)));
            ready();tap(R.string.stop);stopped();
            Bundle result=new Bundle();result.putString("stream","STANDALONE_CONTROLS_OK: app Start, notification Resume/Stop, enabled update restore, app Stop, and native display cleanup; no adopted shell permissions.\n");instrumentation.sendStatus(0,result);
        }finally{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));context.stopService(new Intent(context,RevealService.class));
            await("Cleanup stopped the services",()->!MotionService.running&&!RevealService.running);
            if(activity!=null)instrumentation.runOnMainSync(activity::finish);
        }
    }
}
