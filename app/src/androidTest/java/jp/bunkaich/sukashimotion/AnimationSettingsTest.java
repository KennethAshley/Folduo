package jp.bunkaich.sukashimotion;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class AnimationSettingsTest {
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private Activity activity;
    private int resource(String name,String type){return context.getResources().getIdentifier(name,type,context.getPackageName());}
    private void tap(String id){
        instrumentation.runOnMainSync(()->{
            View control=activity.findViewById(resource(id,"id"));
            assertNotNull("Animation setting must be available: "+id,control);assertTrue(control.performClick());
        });
    }
    private void choose(String name)throws Exception{
        int id=resource(name,"string");assertNotEquals("Choice exists",0,id);
        var automation=instrumentation.getUiAutomation();long until=SystemClock.elapsedRealtime()+3000;
        while(SystemClock.elapsedRealtime()<until){
            automation.clearCache();var root=automation.getRootInActiveWindow();
            if(root!=null)for(var node:root.findAccessibilityNodeInfosByText(context.getString(id))){
                if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;
            }
            Thread.sleep(50);
        }
        fail("Setting choice is clickable: "+name);
    }
    private Activity open(){
        Activity result=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->result.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));return result;
    }
    private void applied(String expected)throws Exception{
        if(!MotionService.running)return;
        long until=SystemClock.elapsedRealtime()+3000;
        while(SystemClock.elapsedRealtime()<until){
            try(var input=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("dumpsys activity service "+context.getPackageName()+"/.MotionService"))){
                if(new String(input.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).contains(expected))return;
            }
            Thread.sleep(100);
        }
        fail("Idle service applies saved animation settings: "+expected);
    }
    @Test public void choicesPersistAcrossReopeningAndResetPreservesStartStopChoice()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        var prefs=context.getSharedPreferences("motion",0);boolean enabled=MotionSettings.enabled(context);
        try{
            activity=open();
            tap("blur_setting");choose("blur_strong");
            tap("response_setting");choose("response_quick");
            tap("fade_setting");choose("fade_later");
            assertEquals(2,prefs.getInt("blur",1));assertEquals(0,prefs.getInt("response",1));assertEquals(2,prefs.getInt("fade",1));
            applied("blurStrength=1.35 responseSeconds=0.012 outerFadeStart=65.0");
            instrumentation.runOnMainSync(activity::finish);activity=open();
            assertEquals(2,prefs.getInt("blur",1));assertEquals(0,prefs.getInt("response",1));assertEquals(2,prefs.getInt("fade",1));
            tap("reset_animation_settings");
            assertEquals(1,prefs.getInt("blur",1));assertEquals(1,prefs.getInt("response",1));assertEquals(1,prefs.getInt("fade",1));
            assertEquals("Reset changes animation tuning only",enabled,MotionSettings.enabled(context));
            applied("blurStrength=1.0 responseSeconds=0.024 outerFadeStart=45.0");
        }finally{
            prefs.edit().remove("blur").remove("response").remove("fade").commit();
            if(activity!=null)instrumentation.runOnMainSync(activity::finish);
        }
    }
}
