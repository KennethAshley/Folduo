package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in, three-minute physical test of the existing fixed-screen service. */
public class FixedScreenTest {
    @Test public void tryFixedScreenMode() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")) && DeviceSupport.supports(Build.MODEL));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        Context context=instrumentation.getTargetContext();
        Activity activity=null;
        java.io.File finish=new java.io.File(context.getExternalFilesDir(null),"finish-fold-test");finish.delete();
        try {
            MotionSettings.setEnabled(context,false);
            context.stopService(new Intent(context,RevealService.class));
            context.stopService(new Intent(context,MotionService.class));
            Thread.sleep(500);
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            BridgeConnection.connect(context);
            long deadline=SystemClock.elapsedRealtime()+10000;
            while(BridgeConnection.bridge==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
            assertNotNull("Real Shizuku connection",BridgeConnection.bridge);
            context.startForegroundService(new Intent(context,MotionService.class));
            report("FIXED_SCREEN_WAITING: fully close the phone once, then leave it closed until ready.");
            deadline=SystemClock.elapsedRealtime()+90000;
            String state="";
            while(SystemClock.elapsedRealtime()<deadline){
                state=dump(instrumentation);
                if(state.contains("layoutPrepared=true"))break;
                Thread.sleep(250);
            }
            assertTrue("Fixed cover-primary layout must arm: "+state,state.contains("fixedPrimaryInner=false layoutPrepared=true"));
            assertTrue("Live workspace must be connected",state.contains("mirrorReady=true"));
            WallpaperMirrorHardwareTest.waitForManualStart(activity,BridgeConnection.bridge,"Fold animation check","Tap Start while closed. Calculator will appear. Open fully, swipe up from the bottom white line to Home, then close fully. Watch whether the blur clears into each screen without a black flash. This test ends after three minutes.");
            Activity opened=activity;instrumentation.runOnMainSync(opened::finish);
            try(var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.sec.android.app.popupcalculator/.Calculator -f 0x30000000"))){input.readAllBytes();}
            report("FIXED_SCREEN_READY: open and close normally; test ends automatically in three minutes.\n"+state);
            deadline=SystemClock.elapsedRealtime()+180000;
            long nextReport=SystemClock.elapsedRealtime()+10000;
            long nextCheck=SystemClock.elapsedRealtime()+1000;
            while(SystemClock.elapsedRealtime()<deadline&&!finish.exists()&&MotionService.running&&!context.getSystemService(KeyguardManager.class).isKeyguardLocked()){
                if(SystemClock.elapsedRealtime()>=nextCheck){
                    if(!BridgeConnection.bridge.inspect().getBoolean("mirrorActive"))fail("Live mirror stopped during the physical test: "+dump(instrumentation));
                    nextCheck+=1000;
                }
                if(SystemClock.elapsedRealtime()>=nextReport){report(dump(instrumentation));nextReport+=10000;}
                Thread.sleep(250);
            }
            report("FIXED_SCREEN_FINISHED\n"+dump(instrumentation));
        } finally {
            MotionSettings.setEnabled(context,false);
            context.stopService(new Intent(context,MotionService.class));
            long deadline=SystemClock.elapsedRealtime()+5000;
            while(MotionService.running&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
            if(activity!=null){Activity opened=activity;instrumentation.runOnMainSync(opened::finish);}
            IShellBridge bridge=BridgeConnection.bridge;
            if(bridge!=null)try{bridge.release();bridge.stopAngles();}finally{BridgeConnection.disconnect();}
        }
    }
    private static String dump(android.app.Instrumentation instrumentation)throws Exception{
        try(var input=new android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("dumpsys activity service jp.bunkaich.sukashimotion/.MotionService"))){return new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
    }
    private static void report(String message){Bundle result=new Bundle();result.putString("stream",message+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(0,result);}
}
