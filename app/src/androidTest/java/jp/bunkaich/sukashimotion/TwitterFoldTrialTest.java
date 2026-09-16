package jp.bunkaich.sukashimotion;

import android.app.*;
import android.content.*;
import android.graphics.Point;
import android.os.*;
import android.view.WindowManager;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.*;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in local experiment. No posts, likes, account changes, or saved screen images. */
public class TwitterFoldTrialTest {
    private static final String TWITTER="com.twitter.android";
    private final Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
    private final Context context=instrumentation.getTargetContext();
    private Activity setup;
    private IShellBridge bridge;
    private MotionService motion;
    private boolean continuous(){return "true".equals(InstrumentationRegistry.getArguments().getString("continuous"));}
    private interface Check { boolean ready()throws Exception; }
    private void await(String message,long ms,Check check)throws Exception{
        long deadline=SystemClock.elapsedRealtime()+ms;
        while(SystemClock.elapsedRealtime()<deadline){if(check.ready())return;Thread.sleep(50);}
        assertTrue(message,check.ready());
    }
    private Object field(String name)throws Exception{
        var field=MotionService.class.getDeclaredField(name);field.setAccessible(true);return field.get(motion);
    }
    private boolean flag(String name)throws Exception{return (boolean)field(name);}
    private void healthy()throws Exception{
        assertFalse("Phone locked; test stopped",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
        assertTrue("Trial service stopped",MotionService.running);
        assertEquals("Service recovery: "+((UiText)field("lastRecovery")).resolve(context),0,field("recoveries"));
    }
    private boolean idle()throws Exception{
        healthy();return !flag("busy")&&!flag("finishing")&&((List<?>)field("layers")).isEmpty();
    }
    private void report(String text){Bundle b=new Bundle();b.putString("stream",text+"\n");instrumentation.sendStatus(0,b);}
    private String shell(String command)throws Exception{
        try(var in=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))){
            return new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    private Point displaySize(String method)throws Exception{
        Class<?> api=Class.forName("android.view.IWindowManager");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
        Object wm=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        Point p=new Point();api.getMethod(method,int.class,Point.class).invoke(wm,0,p);return p;
    }
    private Object twitterTask()throws Exception{
        Object manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager")
            .getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,1,false,false,0);
        if(tasks.isEmpty())return null;
        Object top=tasks.get(0);ComponentName component=(ComponentName)top.getClass().getField("topActivity").get(top);
        return component!=null&&TWITTER.equals(component.getPackageName())?top:null;
    }
    private int twitterTaskId()throws Exception{
        Object task=twitterTask();assertNotNull("Twitter remains the foreground app",task);
        return task.getClass().getField("taskId").getInt(task);
    }
    private void start(boolean manual)throws Exception{
        motion=null;
        boolean probe="true".equals(InstrumentationRegistry.getArguments().getString("contentProbe"));
        instrumentation.getUiAutomation().adoptShellPermissionIdentity();
        MotionSettings.setEnabled(context,false);
        context.stopService(new Intent(context,MotionService.class));context.stopService(new Intent(context,RevealService.class));
        await("Previous animation stopped",5000,()->!MotionService.running&&!RevealService.running);
        setup=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        BridgeConnection.connect(context);await("Shizuku connected",15000,()->BridgeConnection.bridge!=null);bridge=BridgeConnection.bridge;
        if(probe){
            String base=bridge.inspect().getString("baseState");
            assertTrue("Leave the phone still at either endpoint for the content probe","OPENED".equals(base)||"CLOSED".equals(base));
            bridge.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){}});
            assertTrue("Prepare the existing cover-primary test mapping",bridge.hold(false,0).getBoolean("ok"));
            await("Cover is primary",5000,()->displaySize("getInitialDisplaySize").x<displaySize("getInitialDisplaySize").y);
        }else if(!manual){
            assertEquals("Close the phone before this test","CLOSED",bridge.inspect().getString("baseState"));
            assertFalse("Unlock the phone first",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
        }
        assertEquals("Keep existing display sizing untouched",displaySize("getInitialDisplaySize"),displaySize("getBaseDisplaySize"));
        if(manual){
          while(true){
            CountDownLatch tapped=new CountDownLatch(1);boolean[] cancelled={false};
            instrumentation.runOnMainSync(()->{
                setup.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                new AlertDialog.Builder(setup).setTitle("Twitter fold test")
                    .setMessage(continuous()?"Start while closed and wait for Twitter. Open and close three times at your normal speed. The test stays active between folds. To end it, expand Folduo Fine's notification and tap Stop. It also stops after ten minutes or if you lock the phone.":"Start while closed and wait for Twitter. Watch ONLY the OUTER screen. Slowly open halfway and hold for two seconds: the fade should hold. Reverse slightly: it should follow your hand. Then finish opening and close fully. The cover should become black around 120 degrees before Twitter expands inside, and reveal portrait Twitter again when closing. Start returns for another try; Cancel ends testing.")
                    .setPositiveButton("Start Twitter test",(d,w)->tapped.countDown())
                    .setNegativeButton("Cancel",(d,w)->{cancelled[0]=true;tapped.countDown();}).setCancelable(false).show();
            });
            report("TWITTER_WAITING_FOR_START");
            assertTrue("Start was not tapped within ten minutes",tapped.await(10,TimeUnit.MINUTES));
            org.junit.Assume.assumeFalse("User cancelled",cancelled[0]);
            if("CLOSED".equals(bridge.inspect().getString("baseState"))&&!context.getSystemService(KeyguardManager.class).isKeyguardLocked())break;
            instrumentation.runOnMainSync(()->android.widget.Toast.makeText(setup,"Close fully and unlock, then tap Start.",android.widget.Toast.LENGTH_LONG).show());
          }
        }
        context.startForegroundService(new Intent(context,MotionService.class).setAction("start"));
        Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("currentActivityThread").invoke(null);
        var services=at.getDeclaredField("mServices");services.setAccessible(true);
        await("Trial service started",5000,()->{
            for(Object service:((Map<?,?>)services.get(thread)).values())if(service instanceof MotionService found)motion=found;
            return motion!=null;
        });
        if(probe){Thread.sleep(350);angle(0);}
        await("Both screens and app mirror ready",15000,()->{healthy();return flag("layoutPrepared")&&flag("mirrorReady");});
        assertTrue("Use the accepted Duo renderer",flag("duoEffect"));
        if(probe){
            instrumentation.runOnMainSync(()->setup.setContentView(new android.view.View(setup){
                @Override protected void onDraw(android.graphics.Canvas canvas){canvas.drawColor(getWidth()>getHeight()?android.graphics.Color.BLUE:android.graphics.Color.RED);}
            }));
            Thread.sleep(500);report("CONTENT_PROBE_READY: red narrow app; blue wide app");return;
        }
        Intent launch=context.getPackageManager().getLaunchIntentForPackage(TWITTER);assertNotNull("Twitter is installed",launch);
        // Existing task is resumed. No force-stop, data clearing, or task clearing.
        launch.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(launch);
        await("Twitter visible on cover",10000,()->twitterTask()!=null&&bridge.windowState(0).getBoolean("ready"));
        Thread.sleep(1000);instrumentation.runOnMainSync(setup::finish);
        report("TWITTER_READY task="+twitterTaskId());
    }
    private void angle(float value)throws Exception{
        var accept=MotionService.class.getDeclaredMethod("accept",float.class,long.class,int.class);accept.setAccessible(true);
        instrumentation.runOnMainSync(()->{try{accept.invoke(motion,value,SystemClock.elapsedRealtime(),HardwareAngle.SOURCE);}catch(Exception e){throw new AssertionError(e);}});
    }
    private void assertLayoutBlended(boolean inner)throws Exception{
        assertTrue("The arriving app layout blends inside its existing surface",((Collection<?>)field("handoffs")).stream()
            .anyMatch(event->event.toString().contains(":destination-layout-blending:"+(inner?"inner":"cover"))));
    }
    private void stop()throws Exception{
        try{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));
            await("Service stopped",5000,()->!MotionService.running);
            await("Original app resolution restored",6000,()->displaySize("getInitialDisplaySize").equals(displaySize("getBaseDisplaySize")));
            await("Normal Samsung display control restored",6000,()->shell("dumpsys device_state").contains("mOverrideState=Optional.empty"));
            report("TWITTER_CLEANUP_OK");
        }finally{
            if(setup!=null)instrumentation.runOnMainSync(setup::finish);
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
        }
    }
    @Test public void stationaryTwitterRoundTripPreservesTaskAndRestoresDisplay()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        try{
            start(false);int task=twitterTaskId();
            for(float value:new float[]{20,60,100,140,173}){angle(value);Thread.sleep(350);}
            await("Inner Twitter revealed",10000,()->idle()&&field("navigation")!=null);
            assertLayoutBlended(true);
            assertEquals("Same Twitter task after opening",task,twitterTaskId());
            assertTrue("Live app mirrored inside",bridge.inspect().getBoolean("mirrorActive"));
            assertNotEquals("App now has inner dimensions",displaySize("getInitialDisplaySize"),displaySize("getBaseDisplaySize"));
            for(float value:new float[]{140,100,60,20,5}){angle(value);Thread.sleep(350);}
            await("Cover Twitter revealed",10000,this::idle);
            assertEquals("Same Twitter task after closing",task,twitterTaskId());
            assertEquals("Cover app dimensions restored",displaySize("getInitialDisplaySize"),displaySize("getBaseDisplaySize"));
            report("TWITTER_STATIONARY_OK: same app task through both sizes; physical blackout and feed position still need observation.");
        }finally{stop();}
    }
    @Test public void destinationCaptureContainsTheResizedAppRatherThanItsOldOverlay()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("contentProbe")));
        try{
            start(false);angle(135);
            await("Destination image prepared",10000,()->{healthy();return !flag("busy")&&((Map<?,?>)field("frozen")).get(true)!=null;});
            var excluded=MotionService.class.getDeclaredMethod("excluded");excluded.setAccessible(true);
            android.view.SurfaceControl[] layers=(android.view.SurfaceControl[])excluded.invoke(motion);
            android.graphics.Bitmap actual=bridge.captureBehind(0,layers).getParcelable("frame",android.graphics.Bitmap.class);
            assertNotNull("Direct app capture exists",actual);actual=actual.copy(android.graphics.Bitmap.Config.ARGB_8888,false);
            int app=actual.getPixel(actual.getWidth()/2,actual.getHeight()/2);
            report("CONTENT_PROBE directApp="+Integer.toHexString(app));
            assertEquals("Live resized app is blue",android.graphics.Color.BLUE,app);
            FrameTexture destination=(FrameTexture)((Map<?,?>)field("frozen")).get(true);
            for(float x:new float[]{.25f,.5f,.75f}){
                int captured=destination.sharp.getPixel((int)(destination.sharp.getWidth()*x),destination.sharp.getHeight()/2);
                report("CONTENT_PROBE destination x="+x+" pixel="+Integer.toHexString(captured));
                assertEquals("Destination must contain the new blue app, never the old red overlay",android.graphics.Color.BLUE,captured);
            }
        }finally{stop();}
    }
    @Test public void traceVisibleInnerPixelsBeforeDestinationCover()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("contentProbe")));
        boolean precover="true".equals(InstrumentationRegistry.getArguments().getString("precover"));
        boolean requireCovered="true".equals(InstrumentationRegistry.getArguments().getString("assertPrecovered"));
        WindowManager[] guardWindow={null};android.view.View[] guard={null};
        try{
            start(false);
            instrumentation.runOnMainSync(()->setup.setContentView(new android.view.View(setup){
                @Override protected void onDraw(android.graphics.Canvas canvas){
                    canvas.drawColor(getWidth()>getHeight()?android.graphics.Color.BLUE:android.graphics.Color.RED);
                    if(getWidth()<getHeight()){
                        android.graphics.Paint ink=new android.graphics.Paint();ink.setColor(android.graphics.Color.WHITE);
                        for(int x=0;x<getWidth();x+=64)canvas.drawRect(x,0,x+32,getHeight(),ink);
                    }
                }
            }));
            if(precover)instrumentation.runOnMainSync(()->{
                var display=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(1);
                Context local=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                guardWindow[0]=local.getSystemService(WindowManager.class);
                assertTrue("Native window blur must be enabled for this probe",guardWindow[0].isCrossWindowBlurEnabled());
                guard[0]=new android.view.View(local);guard[0].setBackgroundColor(android.graphics.Color.TRANSPARENT);
                var lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                    android.graphics.PixelFormat.TRANSLUCENT);
                lp.setBlurBehindRadius(128);lp.setFitInsetsTypes(0);lp.setTitle("Folduo diagnostic precover");
                guardWindow[0].addView(guard[0],lp);
            });
            Thread.sleep(350);
            for(int cycle=0;cycle<(requireCovered?2:1);cycle++){
            long began=SystemClock.elapsedRealtime();angle(135);int earlyFrames=0;boolean wideSeen=false;
            while(SystemClock.elapsedRealtime()-began<1800){
                android.graphics.Bitmap image=bridge.captureBehind(1,new android.view.SurfaceControl[0]).getParcelable("frame",android.graphics.Bitmap.class);
                if(image==null){Thread.sleep(20);continue;}
                android.graphics.Bitmap pixels=image.copy(android.graphics.Bitmap.Config.ARGB_8888,false);
                int min=255,max=0,blue=0,count=0;
                for(int x=pixels.getWidth()*3/5;x<pixels.getWidth()*4/5;x++){
                    int c=pixels.getPixel(x,pixels.getHeight()/2);int g=android.graphics.Color.green(c);
                    min=Math.min(min,g);max=Math.max(max,g);
                    if(android.graphics.Color.blue(c)>android.graphics.Color.red(c)+60)blue++;count++;
                }
                report("VISIBLE_INNER ms="+(SystemClock.elapsedRealtime()-began)+" contrast="+(max-min)+" blue="+blue+"/"+count+" stage="+field("stage"));
                wideSeen|=blue==count;
                if((precover||requireCovered)&&SystemClock.elapsedRealtime()-began<400){earlyFrames++;assertTrue("No readable narrow image before the snapshot arrives: "+(max-min),max-min<12);}
                pixels.recycle();image.recycle();Thread.sleep(20);
            }
            if(precover||requireCovered)assertTrue("Observe the early exposed interval",earlyFrames>0);
            assertTrue("The real wide app must eventually be visible",wideSeen);
            report("VISIBLE_INNER_HANDOFFS "+field("handoffs"));
            if(requireCovered){
                angle(176);await("Open endpoint reveals the live app",6000,this::idle);
                assertNull("The preparatory cover must not block the open app",((InnerWorkspace)field("workspace")).coveredSurface());
                for(float closing:new float[]{140,100,60,20,5}){angle(closing);Thread.sleep(250);}
                await("Closed endpoint restores cover app",6000,this::idle);
                assertNotNull("Next opening is covered before closing completes",((InnerWorkspace)field("workspace")).coveredSurface());
                report("PREPARED_INNER_CYCLE_OK "+(cycle+1));
            }
            }
        }finally{
            if(guard[0]!=null)instrumentation.runOnMainSync(()->guardWindow[0].removeViewImmediate(guard[0]));
            stop();
        }
    }
    @Test public void outerDarknessLeavesInnerLiveAndClearsOnClosingAndStop()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("contentProbe")));
        try{
            start(false);
            for(int cycle=0;cycle<5;cycle++){
                angle(135);
                await("Both app layouts prepared",10000,()->{healthy();return !flag("busy")&&((Map<?,?>)field("frozen")).get(true)!=null;});
                // Inspect the live inner app below its arriving snapshot. Keeping the
                // outgoing portrait used to put red frost over the blue inner mirror.
                instrumentation.runOnMainSync(()->{try{
                    for(Object layer:new ArrayList<>((List<?>)field("layers"))){
                        var viewField=layer.getClass().getDeclaredField("view");viewField.setAccessible(true);
                        if(((SnapshotView)viewField.get(layer)).inner){
                            var remove=MotionService.class.getDeclaredMethod("removeLayer",layer.getClass());remove.setAccessible(true);remove.invoke(motion,layer);
                        }
                    }
                }catch(Exception e){throw new AssertionError(e);}});
                Thread.sleep(150);
                assertPanelColor("Retained outer portrait must not cover live inner",1,android.graphics.Color.BLUE);
                angle(176);await("Open live app ready",6000,this::idle);
                assertEquals("Outer finishes black",1,bridge.inspect().getFloat("outerDarkness"),0);
                assertTrue("Native outer layer exists",bridge.inspect().getBoolean("outerShadeActive"));
                assertPhysicalCoverColor(android.graphics.Color.BLACK);
                assertFalse("Reject invalid alpha",bridge.outerDarkness(Float.NaN).getBoolean("ok"));
                assertEquals("Invalid alpha leaves the existing shade intact",1,bridge.inspect().getFloat("outerDarkness"),0);
                assertPanelColor("Outer blackout must not darken the live inner",1,android.graphics.Color.BLUE);
                java.util.concurrent.atomic.AtomicBoolean tapped=new java.util.concurrent.atomic.AtomicBoolean();
                instrumentation.runOnMainSync(()->((android.view.ViewGroup)setup.findViewById(android.R.id.content)).getChildAt(0)
                    .setOnTouchListener((v,event)->{if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP)tapped.set(true);return true;}));
                shell("input -d 1 tap 1224 924");await("Inner tap reaches the app while the outer is dark",2500,tapped::get);
                for(float closing:new float[]{140,100,60,20,5}){angle(closing);Thread.sleep(250);}
                await("Closed portrait restored",6000,this::idle);
                assertEquals("Closing clears the outer shade",0,bridge.inspect().getFloat("outerDarkness"),0);
                assertPanelColor("Cover restores the real narrow app",0,android.graphics.Color.RED);
                assertPhysicalCoverColor(android.graphics.Color.RED);
                report("OUTER_ENDPOINT_CYCLE_OK "+(cycle+1));
            }
            angle(135);await("Final open prepared",10000,()->!flag("busy"));
            angle(176);await("Final outer shade active",6000,this::idle);
        }finally{
            stop();
            if(bridge!=null){
                try{
                    assertFalse("Stop removes the native black layer",bridge.inspect().getBoolean("outerShadeActive"));
                    assertFalse("A late alpha call cannot recreate a shade after release",bridge.outerDarkness(1).getBoolean("ok"));
                }catch(DeadObjectException stoppedHelper){/* Normal disconnect can terminate the helper before inspection. */}
                await("Stop removes the compositor layer",3000,()->!shell("dumpsys SurfaceFlinger --list").contains("Folduo outer endpoint"));
            }
        }
    }
    @Test public void rapidReversalsAndEndpointRestartsKeepOuterRevealWorking()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("contentProbe")));
        try{
            start(false);
            for(int cycle=0;cycle<3;cycle++){
                angle(135);
                await("Opening blend started",6000,()->{healthy();return "destination-layout-blending:inner".equals(field("stage"));});
                angle(90);
                await("Portrait resize started before closing finishes",6000,()->{healthy();return flag("busy")&&displaySize("getInitialDisplaySize").equals(displaySize("getBaseDisplaySize"));});
                angle(135);
                await("Reversed opening ready",6000,()->{healthy();return !flag("busy");});
                angle(176);
                await("Opening endpoint cleanup started",6000,()->flag("finishing"));
                angle(140);
                await("Closing survived interrupted endpoint cleanup",6000,()->{healthy();return !flag("busy")&&flag("outerPortraitReady");});
                for(float closing:new float[]{90,45,5}){angle(closing);Thread.sleep(80);}
                await("Closing endpoint cleanup started",6000,()->flag("finishing"));
                report("INTERRUPTED_FOLD_CYCLE_OK "+(cycle+1)+" history="+field("handoffs"));
                // The next iteration opens before the closing cleanup completes.
            }
            await("Final portrait restored",6000,this::idle);
            assertEquals("Repeated interruptions must not leave the cover black",0,bridge.inspect().getFloat("outerDarkness"),0);
            assertPhysicalCoverColor(android.graphics.Color.RED);
        }finally{stop();}
    }
    @Test public void closingStartsRevealingWithinNormalFoldBudget()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("contentProbe")));
        try{
            start(false);long[] elapsed=new long[3];
            for(int cycle=0;cycle<elapsed.length;cycle++){
                angle(135);await("Inner layout prepared",6000,()->{healthy();return !flag("busy");});
                angle(176);await("Open app ready",6000,this::idle);
                long began=SystemClock.elapsedRealtime();angle(90);
                await("Cover starts revealing the portrait app",6000,()->{healthy();return flag("outerPortraitReady")&&bridge.inspect().getFloat("outerDarkness")<.9f;});
                elapsed[cycle]=SystemClock.elapsedRealtime()-began;
                assertEquals("Visible cover must have restored geometry",displaySize("getInitialDisplaySize"),displaySize("getBaseDisplaySize"));
                angle(5);await("Closed live app ready",6000,this::idle);assertPhysicalCoverColor(android.graphics.Color.RED);
            }
            report("CLOSING_REVEAL_MS "+Arrays.toString(elapsed));Arrays.sort(elapsed);
            // Fold8 hardware budget: leave visible hinge travel in a roughly one-second close.
            assertTrue("Median cover reveal must start within 650 ms, was "+elapsed[1],elapsed[1]<=650);
        }finally{stop();}
    }
    @Test public void clearingOuterShadeDoesNotRequireAnActiveDisplayRequest()throws Exception{
        try{
            setup=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            BridgeConnection.connect(context);await("Shizuku connected",15000,()->BridgeConnection.bridge!=null);bridge=BridgeConnection.bridge;
            bridge.release();
            Bundle cleared=bridge.outerDarkness(0);
            assertTrue("Clearing an owned effect must succeed after display control ends: "+cleared,cleared.getBoolean("ok"));
            assertFalse("Clearing must not create a native layer",bridge.inspect().getBoolean("outerShadeActive"));
            assertFalse("Increasing darkness still requires display ownership",bridge.outerDarkness(1).getBoolean("ok"));
        }finally{
            if(bridge!=null)bridge.release();BridgeConnection.disconnect();
            if(setup!=null)instrumentation.runOnMainSync(setup::finish);
        }
    }
    @Test public void outerIsBlackBeforeAppResizesAndRevealsOnlyPortrait()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("contentProbe")));
        try{
            start(false);Point portrait=displaySize("getInitialDisplaySize");angle(135);
            await("Wide destination prepared",10000,()->!flag("busy")&&((Map<?,?>)field("frozen")).get(true)!=null);
            assertEquals("Outer is already black while the app is wide mid-fold",1,bridge.inspect().getFloat("outerDarkness"),0);
            assertPhysicalCoverColor(android.graphics.Color.BLACK);
            angle(176);await("Open endpoint ready",6000,this::idle);
            angle(100);
            long deadline=SystemClock.elapsedRealtime()+6000;int wideSamples=0;
            while(flag("busy")&&SystemClock.elapsedRealtime()<deadline){
                Point before=displaySize("getBaseDisplaySize");float opacity=bridge.inspect().getFloat("outerDarkness");
                if(!before.equals(portrait)&&before.equals(displaySize("getBaseDisplaySize"))){
                    assertEquals("Cover stays black until portrait geometry returns",1,opacity,0);wideSamples++;
                }
                Thread.sleep(20);
            }
            assertTrue("Observed the wide-to-portrait closing interval",wideSamples>0);
            assertFalse("Closing frame exchange finishes",flag("busy"));
            assertEquals("App is portrait before cover shade clears",portrait,displaySize("getBaseDisplaySize"));
            await("Cover follows the closing angle",3000,()->Math.abs(bridge.inspect().getFloat("outerDarkness")-FoldPolicy.outerDarkness(100))<.015f);
            angle(5);await("Closed endpoint ready",6000,this::idle);assertPhysicalCoverColor(android.graphics.Color.RED);
            report("OUTER_RESIZE_MASK_OK: wide app geometry is hidden during opening and closing");
        }finally{stop();}
    }
    @Test public void outerFadeTracksHeldAnglesAndReversesWithoutExposingWideGeometry()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("contentProbe")));
        try{
            start(false);Point portrait=displaySize("getInitialDisplaySize");angle(75);
            await("Source frost committed",5000,()->((Collection<?>)field("handoffs")).stream().anyMatch(e->e.toString().contains("source-frost-committed")));
            Thread.sleep(400);
            assertEquals("A held 75-degree hinge stays partly visible",.4f,bridge.inspect().getFloat("outerDarkness"),.015f);
            Thread.sleep(700);
            assertEquals("Elapsed time cannot finish the fade",.4f,bridge.inspect().getFloat("outerDarkness"),.015f);
            assertEquals("No resize while the outer is visible",portrait,displaySize("getBaseDisplaySize"));
            angle(60);await("Reversing brightens the outer",6000,()->!flag("busy")&&Math.abs(bridge.inspect().getFloat("outerDarkness")-.2f)<.015f);
            angle(135);await("Wide frame can prepare after full outer coverage",10000,()->!flag("busy"));
            assertEquals(1,bridge.inspect().getFloat("outerDarkness"),0);assertPhysicalCoverColor(android.graphics.Color.BLACK);
            FrameTexture prepared=(FrameTexture)((Map<?,?>)field("frozen")).get(true);
            assertEquals("Reversal prepares the actual wide layout before revealing it",android.graphics.Color.BLUE,prepared.sharp.getPixel(prepared.sharp.getWidth()*3/4,prepared.sharp.getHeight()/2));
            angle(176);await("Open live app ready",6000,this::idle);assertPanelColor("Reversal still reveals the wide app",1,android.graphics.Color.BLUE);
            angle(90);await("Closing restores portrait under a held fade",10000,()->!flag("busy")&&Math.abs(bridge.inspect().getFloat("outerDarkness")-.6f)<.015f);
            assertEquals(portrait,displaySize("getBaseDisplaySize"));Thread.sleep(700);
            assertEquals("Closing pause also holds its shade",.6f,bridge.inspect().getFloat("outerDarkness"),.015f);
            angle(135);await("Reverse closing resumes the inner app under black",10000,()->!flag("busy")&&bridge.inspect().getFloat("outerDarkness")==1);
            angle(176);await("Reopened endpoint ready",6000,this::idle);assertPanelColor("Second reversal has live wide app",1,android.graphics.Color.BLUE);
            for(float value:new float[]{100,60,20,5}){angle(value);Thread.sleep(250);}
            await("Cover endpoint ready",6000,this::idle);assertPhysicalCoverColor(android.graphics.Color.RED);
            report("HINGE_OUTER_FADE_OK: pauses hold, reversals follow, visible cover remains portrait");
        }finally{stop();}
    }
    private void assertPhysicalCoverColor(int expected)throws Exception{
        // Synthetic test pixels only; decode in memory, never save app screenshots.
        var display=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0);
        String unique=(String)android.view.Display.class.getMethod("getUniqueId").invoke(display);
        long physical=Long.parseLong(unique.substring("local:".length()));
        try(var in=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("screencap -p -d "+physical))){
            android.graphics.Bitmap image=android.graphics.BitmapFactory.decodeStream(in);
            assertNotNull("Physical cover screenshot exists",image);
            try{
                for(float x:new float[]{.2f,.5f,.8f})for(float y:new float[]{.2f,.5f,.8f})
                    assertEquals("Physical cover pixels at "+x+","+y,expected,image.getPixel((int)(image.getWidth()*x),(int)(image.getHeight()*y)));
            }finally{image.recycle();}
        }
    }
    private void assertPanelColor(String message,int displayId,int expected)throws Exception{
        android.graphics.Bitmap image=bridge.captureBehind(displayId,new android.view.SurfaceControl[0]).getParcelable("frame",android.graphics.Bitmap.class);
        assertNotNull(message,image);
        android.graphics.Bitmap pixels=image.copy(android.graphics.Bitmap.Config.ARGB_8888,false);
        try{assertEquals(message,expected,pixels.getPixel(pixels.getWidth()*3/4,pixels.getHeight()/2));}
        finally{pixels.recycle();image.recycle();}
    }
    @Test public void oneUserStartedPhysicalCycle()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        int runs=!continuous()&&"true".equals(InstrumentationRegistry.getArguments().getString("repeat"))?5:1;
        for(int run=0;run<runs;run++)physicalCycle();
    }
    private void physicalCycle()throws Exception{
        try{
            start(true);int task=twitterTaskId();boolean opened=false,finished=false;int cycles=0;
            String prior="";long began=SystemClock.elapsedRealtime(),deadline=began+(continuous()?600000:180000);
            while(SystemClock.elapsedRealtime()<deadline){
                if(continuous()&&(!MotionService.running||context.getSystemService(KeyguardManager.class).isKeyguardLocked())){
                    report("TWITTER_SESSION_ENDED_BY_STOP_OR_LOCK cycles="+cycles);return;
                }
                healthy();String base=bridge.inspect().getString("baseState");
                String stage=(String)field("stage");
                if(!stage.equals(prior)){report("TWITTER_STAGE ms="+(SystemClock.elapsedRealtime()-began)+" "+stage+" base="+base+" angle="+field("target")+" outer="+bridge.inspect().getFloat("outerDarkness")+" portraitReady="+field("outerPortraitReady"));prior=stage;}
                if("OPENED".equals(base)&&idle())opened=true;
                if(opened&&"CLOSED".equals(base)&&idle()){
                    assertEquals("Same Twitter task after real fold",task,twitterTaskId());finished=true;cycles++;opened=false;
                    report("TWITTER_COMPLETED_CYCLES "+cycles);
                    if(!continuous())break;
                }
                Thread.sleep(100);
            }
            if(continuous())report("TWITTER_SESSION_TIME_LIMIT cycles="+cycles);
            else assertTrue("Complete one open/close cycle within three minutes",finished);
            report("TWITTER_PHYSICAL_CYCLE_FINISHED: user observation determines flash, visual alignment, and touch result.");
        }finally{stop();}
    }
}
