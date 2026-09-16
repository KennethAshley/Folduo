package jp.bunkaich.sukashimotion;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** One physical fold with optional frost; no mirroring, task migration, or custom navigation. */
public class NativeHandoffTest {
    private static final String CALCULATOR="com.sec.android.app.popupcalculator";
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final UiAutomation automation=instrumentation.getUiAutomation();
    private volatile float angle=Float.NaN;
    private volatile long angleAt;
    private PrintWriter trace;
    private DualDisplayControl nativeControl;
    private SnapshotView frost;
    private WindowManager frostWindows;
    private android.animation.ValueAnimator frostAngle;
    private Object displayPower;
    private java.lang.reflect.Method overridePower;
    private final IBinder[] powerTokens={new Binder(),new Binder()};
    private final java.util.ArrayList<WindowManager> colorWindows=new java.util.ArrayList<>();
    private final java.util.ArrayList<View> colorViews=new java.util.ArrayList<>();
    private interface Check {boolean ready()throws Exception;}
    private void await(String name,long timeout,Check check)throws Exception{
        long until=SystemClock.elapsedRealtime()+timeout;
        while(!check.ready()&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
        assertTrue(name,check.ready());
    }
    @Test public void onePhysicalHandoff()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        boolean service="true".equals(InstrumentationRegistry.getArguments().getString("service"));
        boolean powerLock="true".equals(InstrumentationRegistry.getArguments().getString("powerLock"));
        boolean singleScreen="true".equals(InstrumentationRegistry.getArguments().getString("singleScreen"));
        boolean blur=service||"true".equals(InstrumentationRegistry.getArguments().getString("blur"));
        assertTrue("Blur check requires the single-screen handoff",!blur||singleScreen||service);
        boolean early=!service&&(powerLock||singleScreen||"true".equals(InstrumentationRegistry.getArguments().getString("early")));
        boolean destinationPrimary=singleScreen||"destination".equals(InstrumentationRegistry.getArguments().getString("primary"));
        boolean closing=!"open".equals(InstrumentationRegistry.getArguments().getString("direction"));
        boolean watchOutgoing="outgoing".equals(InstrumentationRegistry.getArguments().getString("watch"));
        String watchScreen=closing==watchOutgoing?"inner":"cover";
        String label=(powerLock?"power-lock-dual":service?"service":(blur?"frost-": "")+(singleScreen?"single-screen-early":early?(destinationPrimary?"destination-early":"early"):"native"))+(closing?"-close":"-open")+(watchOutgoing?"-outgoing":"-incoming");
        Context context=instrumentation.getTargetContext();Activity activity=null;IShellBridge bridge=null;
        DisplayManager displays=context.getSystemService(DisplayManager.class);
        DisplayManager.DisplayListener listener=new DisplayManager.DisplayListener(){
            public void onDisplayAdded(int id){event("display-added "+id+" "+panels(displays));}
            public void onDisplayRemoved(int id){event("display-removed "+id+" "+panels(displays));}
            public void onDisplayChanged(int id){event("display-changed "+id+" "+panels(displays));}
        };
        try{
            trace=new PrintWriter(new File(context.getExternalFilesDir(null),"handoff-"+label+".txt"));
            automation.adoptShellPermissionIdentity();
            AccessibilityServiceInfo info=automation.getServiceInfo();info.flags|=AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;automation.setServiceInfo(info);
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));context.stopService(new Intent(context,RevealService.class));
            await("Folduo services stopped",5000,()->!MotionService.running&&!RevealService.running);
            assertEquals("Start in the requested physical position",closing?"OPENED":"CLOSED",base());
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            if(early||service){BridgeConnection.connect(context);await("Shizuku connected",10000,()->BridgeConnection.bridge!=null);bridge=BridgeConnection.bridge;}
            CountDownLatch start=new CountDownLatch(1);boolean[] cancelled={false};Activity setup=activity;
            instrumentation.runOnMainSync(()->{
                setup.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                new AlertDialog.Builder(setup).setTitle(service?"Paired blur check":destinationPrimary?"Early app handoff check":early?"Early-screen handoff check":"Normal Samsung handoff check")
                    .setMessage("Tap Start test. When Calculator shows 1,234, "+(closing?"close":"open")+" the phone once and leave it "+(closing?"closed":"open")+". Watch ONLY "+(service&&watchOutgoing&&closing?"the LEFT HALF of the inner screen":"the "+watchScreen+" screen")+". "+(powerLock?"Test colors appear, then Calculator returns. Watch for a black gap between them.":service&&watchOutgoing?"The image should become blurred during the fold, then switch off once at the end.":blur?"The blur should reveal Calculator without a black flash.":"A flash means it goes black and lights back up during the fold. No swipes or animation."))
                    .setPositiveButton("Start test",(d,w)->start.countDown())
                    .setNegativeButton("Cancel",(d,w)->{cancelled[0]=true;start.countDown();}).setCancelable(false).show();
            });
            report("WAITING_FOR_START "+label+": unlock and tap Start test on the phone.");
            assertTrue("Start was not tapped within three minutes",start.await(180,TimeUnit.SECONDS));
            org.junit.Assume.assumeFalse("User cancelled",cancelled[0]);
            assertFalse("Unlock before Start",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
            assertEquals("Position changed before Start",closing?"OPENED":"CLOSED",base());
            if(early)bridge.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){if(source==HardwareAngle.SOURCE){angle=value;angleAt=at;event("hinge="+value+" measuredAt="+at);}}});
            if(service){
                context.startForegroundService(new Intent(context,RevealService.class).setAction("start"));
                await("Native reveal service connected",8000,()->RevealService.running&&BridgeConnection.bridge!=null&&BridgeConnection.bridge.inspect().getBoolean("running"));
            }
            displays.registerDisplayListener(listener,new Handler(Looper.getMainLooper()));
            shell("am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "+CALCULATOR+"/.Calculator -f 0x30000000");
            instrumentation.runOnMainSync(setup::finish);
            await("Calculator appeared",5000,()->node("calc_keypad_btn_clear")!=null);
            for(String button:new String[]{"clear","01","02","03","04"}){
                AccessibilityNodeInfo node=node("calc_keypad_btn_"+button);assertNotNull(node);assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));Thread.sleep(80);
            }
            await("Test number entered",2000,()->formula().replaceAll("[^0-9]","").equals("1234"));
            Object before=task();int taskId=before.getClass().getField("taskId").getInt(before);
            event("before task="+taskId+" formula="+formula()+" "+panels(displays));
            FrameTexture frozen=null;
            if(blur&&!service){
                Bitmap image=bridge.capture(0).getParcelable("frame",Bitmap.class);assertNotNull("Source screenshot",image);
                frozen=FrameTexture.prepare(image,context.getResources().getDisplayMetrics().density,()->false);assertNotNull("Prepared frost",frozen);
            }
            report("READY "+label+": "+(closing?"close":"open")+" once now.");
            boolean held=false,released=false,stagedReady=false;long heldAt=0,until=SystemClock.elapsedRealtime()+60000;
            float lastFrostAngle=Float.NaN;
            while(SystemClock.elapsedRealtime()<until){
                assertFalse("Phone locked before handoff",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
                String state=base();
                if(early&&!held&&SystemClock.elapsedRealtime()-angleAt<600&&(closing?angle<165&&angle>15:angle>15&&angle<165)){
                    if(blur){showFrost(context,displays.getDisplay(0),frozen,!closing,angle);lastFrostAngle=angle;}
                    if(powerLock){powerOverride(0,Display.STATE_ON,35000);powerOverride(1,Display.STATE_ON,35000);event("power-locked");}
                    if(singleScreen)requestNative(!closing);
                    else{Bundle result=bridge.hold(destinationPrimary?!closing:closing,0);assertTrue(result.toString(),result.getBoolean("ok"));}
                    held=true;heldAt=SystemClock.elapsedRealtime();event("early-hold primary="+(destinationPrimary?"destination":"source")+" singleScreen="+singleScreen+" angle="+angle+" "+panels(displays));
                }
                if(blur&&!service&&held&&angle!=lastFrostAngle){float current=angle;instrumentation.runOnMainSync(()->animateFrost(current));lastFrostAngle=angle;}
                Display.Mode primaryMode=displays.getDisplay(0).getMode();
                boolean primaryMatches=!destinationPrimary||(primaryMode.getPhysicalWidth()>primaryMode.getPhysicalHeight())!=closing;
                if(held&&!stagedReady&&primaryMatches&&displays.getDisplay(0).getState()==Display.STATE_ON&&displays.getDisplay(1).getState()==(singleScreen?Display.STATE_OFF:Display.STATE_ON)){
                    stagedReady=true;event((singleScreen?"single-screen-before-endpoint":"both-on-before-handoff")+" "+panels(displays));
                    if(powerLock)showColors(context,displays);
                }
                // A reversed fold can finish before another starts. Wait for the current overlay too.
                boolean endpoint=(closing?"CLOSED".equals(state):"OPENED".equals(state)&&(!early||angle>=170&&SystemClock.elapsedRealtime()-angleAt<600))&&(!service||RevealService.completed>0&&!RevealService.showing);
                if(endpoint){
                    event("physical-endpoint "+state+" angle="+angle+" "+panels(displays));
                    if(early){assertTrue("Early activation never ran",held);if(singleScreen)releaseNative();else bridge.release();released=true;event("early-release");assertTrue("Requested layout was never ready before endpoint",stagedReady);}
                    break;
                }
                assertTrue("Early activation exceeded thirty seconds",!held||SystemClock.elapsedRealtime()-heldAt<30000);
                Thread.sleep(25);
            }
            assertEquals("Physical fold was not completed",closing?"CLOSED":"OPENED",base());
            if(early)assertTrue("Early display control was released",released);
            if(service){assertTrue("At least one service transition completed",RevealService.completed>0);assertEquals("No service transition skipped",0,RevealService.skipped);assertFalse("Frost removed",RevealService.showing);}
            await("Native destination display active",4000,()->{
                Display display=displays.getDisplay(0);Display.Mode mode=display.getMode();
                return display.getState()==Display.STATE_ON&&((mode.getPhysicalWidth()>mode.getPhysicalHeight())!=closing);
            });
            if(powerLock){
                IShellBridge connected=bridge;await("App drawn under colors",4000,()->connected.windowState(0).getBoolean("ready"));
                clearPowerOverrides();event("power-unlocked "+panels(displays));removeColors();
            }
            if(blur&&!service){
                IShellBridge connected=bridge;await("Destination app drawn beneath frost",4000,()->connected.windowState(0).getBoolean("ready"));
                CountDownLatch revealed=new CountDownLatch(1);
                instrumentation.runOnMainSync(()->{if(frostAngle!=null)frostAngle.cancel();frost.animate().alpha(0).setDuration(220).withEndAction(revealed::countDown).start();});
                assertTrue("Frost revealed the live app",revealed.await(2,TimeUnit.SECONDS));removeFrost();event("frost-removed");
            }
            await("Calculator state retained",5000,()->formula().replaceAll("[^0-9]","").equals("1234"));
            Object after=task();assertEquals("Same app task",taskId,after.getClass().getField("taskId").getInt(after));
            assertEquals(CALCULATOR,((ComponentName)after.getClass().getField("topActivity").get(after)).getPackageName());
            event("after task="+taskId+" formula="+formula()+" "+panels(displays));
            // Display.STATE_ON can precede the physical panel's first visible frame.
            Bitmap image=readableFrame();event("nonblack-frame "+panels(displays));
            try(FileOutputStream out=new FileOutputStream(new File(context.getExternalFilesDir(null),"handoff-"+label+".png"))){image.compress(Bitmap.CompressFormat.PNG,100,out);}finally{image.recycle();}
            report("HANDOFF_STATE_OK "+label+": same Calculator task and 1,234 on native destination. Visible blackout still requires the user's observation.");
        }finally{
            try{removeColors();removeFrost();}finally{try{try{clearPowerOverrides();}finally{releaseNative();}}finally{
                try{if(bridge!=null)try{bridge.release();bridge.stopAngles();}finally{BridgeConnection.disconnect();}}
                finally{
                    displays.unregisterDisplayListener(listener);MotionSettings.setEnabled(context,false);
                    context.stopService(new Intent(context,MotionService.class));context.stopService(new Intent(context,RevealService.class));
                    await("Services stopped after test",5000,()->!MotionService.running&&!RevealService.running);
                    if(activity!=null){Activity setup=activity;instrumentation.runOnMainSync(setup::finish);}
                    automation.dropShellPermissionIdentity();if(trace!=null){trace.close();trace=null;}
                }
            }}
        }
    }
    private void showFrost(Context context,Display display,FrameTexture frame,boolean inner,float angle)throws Exception{
        CountDownLatch drawn=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->{
            Context panel=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            frostWindows=panel.getSystemService(WindowManager.class);frost=new SnapshotView(panel,frame,inner,false);
            frost.setAngle(frostAngle(angle));
            WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.OPAQUE);
            params.setFitInsetsTypes(0);params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            params.setTitle("Folduo native handoff frost check");params.windowAnimations=0;
            frostWindows.addView(frost,params);frost.afterFrame(drawn::countDown);
        });
        assertTrue("Frost frame committed before native handoff",drawn.await(2,TimeUnit.SECONDS));event("frost-frame-committed");
    }
    private void showColors(Context context,DisplayManager displays)throws Exception{
        CountDownLatch drawn=new CountDownLatch(2);
        instrumentation.runOnMainSync(()->{
            for(int id:new int[]{0,1}){
                Display display=displays.getDisplay(id);Display.Mode mode=display.getMode();
                boolean inner=Math.min(mode.getPhysicalWidth(),mode.getPhysicalHeight())/(float)Math.max(mode.getPhysicalWidth(),mode.getPhysicalHeight())>.7f;
                Context panel=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                WindowManager manager=panel.getSystemService(WindowManager.class);View view=new View(panel);
                view.setBackgroundColor(inner?0xff146342:0xff1549a4);view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
                params.setFitInsetsTypes(0);params.setTitle("Folduo power continuity check");params.windowAnimations=0;
                manager.addView(view,params);colorWindows.add(manager);colorViews.add(view);
                view.getViewTreeObserver().registerFrameCommitCallback(drawn::countDown);view.invalidate();
            }
        });
        assertTrue("Both color frames committed",drawn.await(2,TimeUnit.SECONDS));event("both-color-frames-committed");
    }
    private void removeColors(){instrumentation.runOnMainSync(()->{
        for(int i=0;i<colorViews.size();i++)colorWindows.get(i).removeViewImmediate(colorViews.get(i));
        colorViews.clear();colorWindows.clear();
    });}
    private float frostAngle(float angle){return frost.inner?Math.min(angle,145):Math.max(angle,30);}
    private void animateFrost(float angle){
        if(frost==null)return;if(frostAngle!=null)frostAngle.cancel();
        float target=frostAngle(angle);float from=frostAngle==null?target:(float)frostAngle.getAnimatedValue();
        frostAngle=android.animation.ValueAnimator.ofFloat(from,target);frostAngle.setDuration(100);
        frostAngle.setInterpolator(new android.view.animation.LinearInterpolator());
        frostAngle.addUpdateListener(a->{if(frost!=null)frost.setAngle((float)a.getAnimatedValue());});frostAngle.start();
    }
    private void removeFrost(){instrumentation.runOnMainSync(()->{if(frostAngle!=null){frostAngle.cancel();frostAngle=null;}if(frost!=null){frost.animate().cancel();frostWindows.removeViewImmediate(frost);frost=null;frostWindows=null;}});}
    @Test public void nativeStateRequestCapability()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        automation.adoptShellPermissionIdentity();
        try{
            String original=base();org.junit.Assume.assumeTrue("OPENED".equals(original)||"CLOSED".equals(original));
            requestNative("OPENED".equals(original));Thread.sleep(300);
            Object state=nativeControl.read.invoke(nativeControl.service);
            assertEquals("Current native layout retained",nativeControl.id(state,"baseState"),nativeControl.id(state,"currentState"));
            report("NATIVE_STATE_REQUEST_SUPPORTED: current single-screen state requested and released without changing panel mapping.");
        }finally{releaseNative();automation.dropShellPermissionIdentity();}
    }
    @Test public void shizukuNativeStateRequestCapability()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        Context context=instrumentation.getTargetContext();MotionSettings.setEnabled(context,false);
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        IShellBridge bridge=null;
        try{
            BridgeConnection.connect(context);await("Shizuku connected",10000,()->BridgeConnection.bridge!=null);bridge=BridgeConnection.bridge;
            String original=bridge.inspect().getString("baseState");assertTrue("Start at a native endpoint","OPENED".equals(original)||"CLOSED".equals(original));
            bridge.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){}});
            Bundle result=bridge.holdNative("OPENED".equals(original));assertTrue(result.toString(),result.getBoolean("ok"));
            Thread.sleep(300);assertEquals(original,bridge.inspect().getString("baseState"));
            report("SHIZUKU_NATIVE_REQUEST_OK: native endpoint requested through the production helper without adopted shell permissions.");
        }finally{if(bridge!=null){bridge.release();bridge.stopAngles();}BridgeConnection.disconnect();instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void nativeScreenPowerCapability()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        Context context=instrumentation.getTargetContext();
        automation.adoptShellPermissionIdentity();
        try{
            Display display=context.getSystemService(DisplayManager.class).getDisplay(0);
            assertEquals("The active screen is already ON",Display.STATE_ON,display.getState());
            powerOverride(0,Display.STATE_ON,1500);Thread.sleep(100);
            assertEquals("Power lock retains active screen",Display.STATE_ON,display.getState());
            report("POWER_LOCK_SUPPORTED: timed Samsung ON request accepted for the already-active screen.");
        }finally{try{clearPowerOverrides();}finally{automation.dropShellPermissionIdentity();}}
    }
    @Test public void pairedMotionKeepsPanelRolesUntilEndpoint()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        Context context=instrumentation.getTargetContext();MotionSettings.setEnabled(context,false);
        context.stopService(new Intent(context,RevealService.class));
        await("Previous service stopped",5000,()->!RevealService.running);
        BridgeConnection.work.submit(()->{}).get(5,TimeUnit.SECONDS);
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        automation.adoptShellPermissionIdentity();
        DualDisplayControl control=new DualDisplayControl();
        boolean sourceInner="OPENED".equals(base());String sourceBase=base();
        DisplayManager displays=context.getSystemService(DisplayManager.class);
        class Bridge extends ServiceLifecycleTest.FakeBridge {
            volatile int paired,nativeRequests;
            public Bundle inspect(){Bundle b=new Bundle();b.putString("baseState",sourceBase);return b;}
            public Bundle holdPaired(boolean inner){
                paired++;try{control.holdPaired(inner);}catch(Exception e){throw new RuntimeException(e);}
                return super.holdPaired(inner);
            }
            public Bundle holdNative(boolean inner){
                nativeRequests++;try{control.holdNative(inner);}catch(Exception e){throw new RuntimeException(e);}
                return super.holdNative(inner);
            }
            public void release(){control.close();super.release();}
        }
        Bridge bridge=new Bridge();BridgeConnection.bridge=bridge;
        try{
            assertFalse("Phone unlocked",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
            context.startForegroundService(new Intent(context,RevealService.class).setAction("start"));
            await("Synthetic hinge connected",5000,()->bridge.sink!=null);
            bridge.sink.angle(sourceInner?180:0,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            bridge.sink.angle(sourceInner?155:25,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            await("Both real panels on",5000,()->bridge.paired>0&&displays.getDisplay(0).getState()==Display.STATE_ON&&displays.getDisplay(1).getState()==Display.STATE_ON);
            for(float value:new float[]{60,100,80,45}){
                bridge.sink.angle(value,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);Thread.sleep(200);
            }
            assertEquals("Keep the same physical screen mapping throughout motion and reversal",1,bridge.paired);
            assertEquals("No native handoff before endpoint",0,bridge.nativeRequests);
            assertEquals("No early destination capture",1,bridge.captures);
            assertTrue("Both frost frames remain active",RevealService.showing);
            Display.Mode mode=displays.getDisplay(0).getMode();
            assertEquals(sourceInner,Math.min(mode.getPhysicalWidth(),mode.getPhysicalHeight())/(float)Math.max(mode.getPhysicalWidth(),mode.getPhysicalHeight())>.7f);
            bridge.sink.angle(sourceInner?180:0,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
            await("Reversed fold restores native screen",5000,()->RevealService.completed==1&&!RevealService.showing);
            assertEquals(1,bridge.nativeRequests);assertFalse(control.isPowerPinned());assertFalse(control.isOwned());
            report("PAIRED_ROLES_OK: real panels stayed mapped throughout synthetic motion/reversal; native endpoint released both pins.");
        }finally{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,RevealService.class));
            try{await("Stationary check stopped",5000,()->!RevealService.running);}
            finally{control.close();automation.dropShellPermissionIdentity();instrumentation.runOnMainSync(activity::finish);}
        }
    }
    private void powerOverride(int id,int state,int timeout)throws Exception{
        if(displayPower==null){
            Class<?> api=Class.forName("android.hardware.display.IDisplayManager");
            displayPower=service("display",api.getName());
            overridePower=api.getMethod("setDisplayStateOverrideWithDisplayId",IBinder.class,int.class,int.class,int.class);
        }
        overridePower.invoke(displayPower,powerTokens[id],state,id,timeout);
    }
    @Test public void closingCapturesNativeCoverAfterHandoffAndAlwaysReleases()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        Context context=instrumentation.getTargetContext();MotionSettings.setEnabled(context,false);
        context.stopService(new Intent(context,RevealService.class));await("Previous service stopped",5000,()->!RevealService.running);
        BridgeConnection.work.submit(()->{}).get(5,TimeUnit.SECONDS);
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        instrumentation.runOnMainSync(()->activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
        automation.adoptShellPermissionIdentity();DualDisplayControl control=new DualDisplayControl();
        DisplayManager displays=context.getSystemService(DisplayManager.class);
        class Bridge extends ServiceLifecycleTest.FakeBridge {
            volatile int nativeRequests,paired;int mode;
            CountDownLatch entered=new CountDownLatch(1),resume=new CountDownLatch(1);
            public Bundle inspect(){Bundle b=new Bundle();b.putString("baseState","CLOSED");return b;}
            public Bundle holdPaired(boolean inner){paired++;try{control.holdPaired(inner);}catch(Exception e){throw new RuntimeException(e);}return super.holdPaired(inner);}
            public Bundle holdNative(boolean inner){nativeRequests++;try{control.holdNative(inner);}catch(Exception e){throw new RuntimeException(e);}return super.holdNative(inner);}
            public Bundle captureBehind(int id,SurfaceControl[] exclude){
                assertEquals("Capture follows the native handoff",1,nativeRequests);assertEquals(0,id);
                assertTrue("Keep the visible freeze excluded from capture",exclude.length>0&&exclude[0].isValid());
                entered.countDown();try{resume.await(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}
                Bundle b=new Bundle();if(mode==1){b.putString("error","Protected frame unavailable");return b;}
                Point size=new Point();displays.getDisplay(0).getRealSize(size);
                Bitmap image=Bitmap.createBitmap(size.x,size.y,Bitmap.Config.ARGB_8888);image.eraseColor(Color.BLUE);b.putParcelable("frame",image);return b;
            }
            public void release(){control.close();super.release();}
        }
        Bridge bridge=null;
        try{
            assertEquals("Leave the phone closed for this stationary check","CLOSED",base());
            for(int mode=0;mode<3;mode++){
                control.holdPaired(true);
                await("Synthetic source is inner",5000,()->{Display.Mode m=displays.getDisplay(0).getMode();return Math.min(m.getPhysicalWidth(),m.getPhysicalHeight())/(float)Math.max(m.getPhysicalWidth(),m.getPhysicalHeight())>.7f&&displays.getDisplay(0).getState()==Display.STATE_ON;});
                Bridge current=new Bridge();bridge=current;current.mode=mode;BridgeConnection.bridge=current;
                context.startForegroundService(new Intent(context,RevealService.class).setAction("start"));
                long connectUntil=SystemClock.elapsedRealtime()+5000;
                while(current.sink==null&&SystemClock.elapsedRealtime()<connectUntil)Thread.sleep(25);
                assertNotNull("Angle check connected: running="+RevealService.running+" sameBridge="+(BridgeConnection.bridge==current)+" interactive="+context.getSystemService(PowerManager.class).isInteractive()+" locked="+context.getSystemService(KeyguardManager.class).isKeyguardLocked(),current.sink);
                current.sink.angle(180,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);current.sink.angle(155,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
                await("Source freeze held",5000,()->current.paired==1&&RevealService.showing);
                assertEquals("No layout capture while folding",1,current.entered.getCount());
                current.sink.angle(0,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);
                assertTrue("Capture the real-sized cover before removing the frost",current.entered.await(6,TimeUnit.SECONDS));
                assertTrue(RevealService.showing);assertTrue(control.isPowerPinned());assertEquals(1,current.paired);
                if(mode==2){
                    context.startService(new Intent(context,RevealService.class).setAction("stop"));await("Stop removes pending cover blend",5000,()->!RevealService.running);
                    current.resume.countDown();Thread.sleep(200);assertFalse("Late cover capture cannot restore an overlay",RevealService.showing);
                }else{
                    current.resume.countDown();await("Native cover revealed even if capture is protected",5000,()->RevealService.completed==1&&!RevealService.showing);
                    context.stopService(new Intent(context,RevealService.class));await("Check stopped",5000,()->!RevealService.running);
                }
                BridgeConnection.work.submit(()->{}).get(5,TimeUnit.SECONDS);
                assertFalse(control.isPowerPinned());assertFalse(control.isOwned());
            }
            report("CLOSING_BLEND_OK: native-sized capture after endpoint, no early mapping swap, protected-frame fallback, and stop during capture all release the screen.");
        }finally{
            if(bridge!=null)bridge.resume.countDown();MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,RevealService.class));
            try{await("Closing check stopped",5000,()->!RevealService.running);}
            finally{control.close();automation.dropShellPermissionIdentity();instrumentation.runOnMainSync(activity::finish);}
        }
    }
    private void clearPowerOverrides()throws Exception{
        if(displayPower==null)return;
        try{powerOverride(0,Display.STATE_UNKNOWN,0);}finally{powerOverride(1,Display.STATE_UNKNOWN,0);}
    }
    @Test public void nativeHomeGestureWhileEnabled()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        Context context=instrumentation.getTargetContext();MotionSettings.setEnabled(context,false);
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            assertFalse("Unlock for native navigation check",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
            // This checks the legacy native service, which is no longer the app default.
            context.startForegroundService(new Intent(context,RevealService.class).setAction("start"));
            await("Legacy native service starts",8000,()->RevealService.running&&BridgeConnection.bridge!=null&&BridgeConnection.bridge.inspect().getBoolean("running"));
            assertFalse("Legacy service stays stopped",MotionService.running);
            shell("am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "+CALCULATOR+"/.Calculator -f 0x30000000");
            await("Calculator ready",5000,()->node("calc_keypad_btn_clear")!=null);
            Bitmap screen=automation.takeScreenshot();assertNotNull(screen);int w=screen.getWidth(),h=screen.getHeight();screen.recycle();
            shell("input -d 0 swipe "+(w/2)+" "+(h-2)+" "+(w/2)+" "+(h*3/5)+" 200");
            await("Native Home swipe reaches Kvaesitso",5000,()->{
                automation.clearCache();AccessibilityNodeInfo home=automation.getRootInActiveWindow();
                return home!=null&&"de.mm20.launcher2.release".contentEquals(home.getPackageName());
            });
            assertTrue("Native reveal remains enabled",RevealService.running&&MotionSettings.enabled(context));
            assertFalse(RevealService.showing);assertEquals(0,RevealService.completed);
            report("NATIVE_HOME_OK: Legacy native service starts and a real bottom-edge swipe reaches Kvaesitso while enabled.");
        }finally{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,RevealService.class));
            await("Navigation check stopped",5000,()->!RevealService.running);instrumentation.runOnMainSync(activity::finish);
        }
    }
    private void requestNative(boolean inner)throws Exception{
        nativeControl=new DualDisplayControl();String name=inner?"OPENED":"CLOSED";int id=-1;
        for(Object state:(List<?>)nativeControl.manager.getClass().getMethod("getSupportedDeviceStates").invoke(nativeControl.manager)){
            if(name.equals(state.getClass().getMethod("getName").invoke(state)))id=(int)state.getClass().getMethod("getIdentifier").invoke(state);
        }
        assertTrue("Native state advertised: "+name,id>=0);
        Object builder=nativeControl.requestType.getMethod("newBuilder",int.class).invoke(null,id);
        Object request=builder.getClass().getMethod("build").invoke(builder);
        Object callback=java.lang.reflect.Proxy.newProxyInstance(nativeControl.callbackType.getClassLoader(),new Class<?>[]{nativeControl.callbackType},(proxy,method,args)->switch(method.getName()){
            case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];case "toString"->"Native handoff diagnostic";default->null;
        });
        nativeControl.request.invoke(nativeControl.manager,request,(Executor)Runnable::run,callback);
    }
    private void releaseNative()throws Exception{if(nativeControl!=null){nativeControl.cancel.invoke(nativeControl.manager);nativeControl=null;}}
    private synchronized void event(String message){if(trace!=null){trace.println(SystemClock.elapsedRealtime()+" "+message);trace.flush();}}
    private void report(String message){event(message);Bundle result=new Bundle();result.putString("stream",message+"\n");instrumentation.sendStatus(0,result);}
    private static String panels(DisplayManager displays){StringBuilder s=new StringBuilder();for(int id:new int[]{0,1}){Display d=displays.getDisplay(id);if(d!=null){Display.Mode m=d.getMode();s.append(" d").append(id).append('=').append(d.getState()).append(':').append(m.getPhysicalWidth()).append('x').append(m.getPhysicalHeight());}}return s.toString();}
    private AccessibilityNodeInfo node(String id){automation.clearCache();AccessibilityNodeInfo root=automation.getRootInActiveWindow();if(root==null)return null;List<AccessibilityNodeInfo> nodes=root.findAccessibilityNodeInfosByViewId(CALCULATOR+":id/"+id);return nodes.isEmpty()?null:nodes.get(0);}
    private String formula(){AccessibilityNodeInfo node=node("calc_edt_formula");return node==null?"":String.valueOf(node.getText());}
    private Bitmap readableFrame()throws Exception{
        long until=SystemClock.elapsedRealtime()+3000;
        do{
            Bitmap image=automation.takeScreenshot();
            if(image!=null){for(int y=10;y<image.getHeight();y+=30)for(int x=10;x<image.getWidth();x+=30)if((image.getPixel(x,y)&0xffffff)>0x404040)return image;image.recycle();}
            Thread.sleep(100);
        }while(SystemClock.elapsedRealtime()<until);
        throw new AssertionError("No nonblack destination frame within three seconds");
    }
    private static Object service(String name,String type)throws Exception{IBinder b=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);return Class.forName(type+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,b);}
    private static String base()throws Exception{Class<?> api=Class.forName("android.hardware.devicestate.IDeviceStateManager");Object info=api.getMethod("getDeviceStateInfo").invoke(service("device_state",api.getName()));Object state=info.getClass().getField("baseState").get(info);return (String)state.getClass().getMethod("getName").invoke(state);}
    private static Object task()throws Exception{Class<?> api=Class.forName("android.app.IActivityTaskManager");List<?> tasks=(List<?>)api.getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service("activity_task",api.getName()),1,false,false,0);assertFalse(tasks.isEmpty());return tasks.get(0);}
    private void shell(String command)throws Exception{try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))){in.readAllBytes();}}
}
