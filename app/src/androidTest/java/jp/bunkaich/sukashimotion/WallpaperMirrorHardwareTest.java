package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.app.KeyguardManager;
import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.lang.reflect.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Stationary, opt-in proof: original inner wallpaper + transparent Home + forwarded touch. */
public class WallpaperMirrorHardwareTest {
    @Test public void samsungInnerWallpaperThumbnailIsReadable() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        Activity activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
            BridgeConnection.connect(context);long until=SystemClock.elapsedRealtime()+10000;
            while(BridgeConnection.bridge==null&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
            assertNotNull(BridgeConnection.bridge);Bundle result=BridgeConnection.bridge.innerWallpaper();
            assertTrue(result.toString(),result.getBoolean("ok"));Bitmap bitmap=result.getParcelable("frame",Bitmap.class);assertNotNull(bitmap);
            try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"inner-wallpaper-thumbnail.png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
        }finally{BridgeConnection.disconnect();instrumentation.runOnMainSync(activity::finish);}
    }
    @Test public void forwardedEventUsesPhysicalCoordinates() {
        MotionEvent original=MotionEvent.obtain(1,2,MotionEvent.ACTION_DOWN,1224,1478.4f,0);
        MotionEvent copy;try{copy=WorkspaceMirror.forwardedEvent(original,2448,1848,new Point(1248,1972));}catch(Exception e){throw new AssertionError(e);}
        try{
            assertEquals(624,copy.getX(),.1f);
            assertEquals("Input injection reads raw coordinates",copy.getX(),copy.getRawX(),.1f);
            assertEquals(copy.getY(),copy.getRawY(),.1f);
        }finally{original.recycle();copy.recycle();}
    }
    private static MotionEvent forwardedEvent(MotionEvent event,int width,int height,Point physical){
        MotionEvent copy=MotionEvent.obtain(event);Matrix scale=new Matrix();
        scale.setRectToRect(new RectF(0,0,width,height),new RectF(0,0,physical.x,physical.y),Matrix.ScaleToFit.CENTER);
        // transform() changes view coordinates but leaves raw coordinates untouched.
        // InputDispatcher consumes the raw points, so use the native whole-event transform.
        try{MotionEvent.class.getMethod("applyTransform",Matrix.class).invoke(copy,scale);return copy;}
        catch(ReflectiveOperationException error){copy.recycle();throw new IllegalStateException(error);}
    }
    @Test public void innerHomeKeepsItsWallpaperAndReceivesSwipe() throws Exception {
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")) && DeviceSupport.supports(Build.MODEL));
        var instrumentation=InstrumentationRegistry.getInstrumentation(); Context context=instrumentation.getTargetContext();
        boolean manual="true".equals(InstrumentationRegistry.getArguments().getString("manualSwipe"));
        boolean navigation="true".equals(InstrumentationRegistry.getArguments().getString("navigation"));
        boolean production="true".equals(InstrumentationRegistry.getArguments().getString("productionMirror"));
        Activity activity=null; IShellBridge bridge=null; Object wm=null; boolean resized=false;
        Bitmap wallpaper=null;
        AtomicReference<IBinder> mirrorBinder=new AtomicReference<>();CountDownLatch mirrorConnected=new CountDownLatch(1);
        var mirrorArgs=new rikka.shizuku.Shizuku.UserServiceArgs(new ComponentName(context,HomeMirrorProbe.class)).daemon(false).processNameSuffix("home_mirror_probe").debuggable(true).version(BuildConfig.VERSION_CODE);
        ServiceConnection mirrorConnection=new ServiceConnection(){public void onServiceConnected(ComponentName name,IBinder binder){mirrorBinder.set(binder);mirrorConnected.countDown();}public void onServiceDisconnected(ComponentName name){mirrorBinder.set(null);}};
        WindowManager[] windows={null}; FrameLayout[] roots={null}; SurfaceView[] surfaces={null};
        AtomicInteger forwarded=new AtomicInteger(); AtomicReference<Throwable> inputError=new AtomicReference<>();
        Class<?> wmApi=Class.forName("android.view.IWindowManager");
        try {
            MotionSettings.setEnabled(context,false);
            context.stopService(new Intent(context,MotionService.class)); context.stopService(new Intent(context,RevealService.class));
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            BridgeConnection.connect(context); long deadline=SystemClock.elapsedRealtime()+10000;
            while(BridgeConnection.bridge==null && SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
            bridge=BridgeConnection.bridge; assertNotNull(bridge);
            org.junit.Assume.assumeTrue("Phone must be unfolded","OPENED".equals(bridge.inspect().getString("baseState")));
            org.junit.Assume.assumeFalse("Phone must be unlocked",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
            instrumentation.getUiAutomation().adoptShellPermissionIdentity();
            wm=service("window",wmApi.getName());
            Point initial=new Point(),base=new Point();
            wmApi.getMethod("getInitialDisplaySize",int.class,Point.class).invoke(wm,0,initial);
            wmApi.getMethod("getBaseDisplaySize",int.class,Point.class).invoke(wm,0,base);
            org.junit.Assume.assumeTrue("Leave existing custom display overrides untouched",initial.equals(base));
            Class<?> projectionManagerApi=Class.forName("android.media.projection.IMediaProjectionManager");
            Object projectionManager=service("media_projection",projectionManagerApi.getName());
            org.junit.Assume.assumeTrue("Leave another capture session untouched",projectionManagerApi.getMethod("getActiveProjectionInfo").invoke(projectionManager)==null);
            if("true".equals(InstrumentationRegistry.getArguments().getString("manualHome"))||"true".equals(InstrumentationRegistry.getArguments().getString("manualNavigation")))waitForManualStart(activity,bridge);
            shell("input -d 0 keyevent 3"); Thread.sleep(600);
            if(production){Bundle result=bridge.innerWallpaper();assertTrue(result.toString(),result.getBoolean("ok"));wallpaper=result.getParcelable("frame",Bitmap.class);}
            else wallpaper=(Bitmap)wmApi.getMethod("screenshotWallpaper").invoke(wm);
            assertNotNull("Capture the selected inner wallpaper before changing display mapping",wallpaper);
            DisplayManager displays=context.getSystemService(DisplayManager.class);
            Point inner=new Point(); displays.getDisplay(0).getRealSize(inner);
            Method uniqueId=Display.class.getMethod("getUniqueId");
            Object innerId=uniqueId.invoke(displays.getDisplay(0));
            bridge.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){}});
            Bundle held=bridge.hold(false,0); assertTrue(held.toString(),held.getBoolean("ok"));
            deadline=SystemClock.elapsedRealtime()+5000;
            // Both panels turn ON before Samsung finishes assigning their logical IDs.
            // Resize only once display 0 has become the physical cover.
            int stable=0;
            while(stable<3 && SystemClock.elapsedRealtime()<deadline){
                stable=displays.getDisplay(1).getState()==Display.STATE_ON
                        && innerId.equals(uniqueId.invoke(displays.getDisplay(1)))
                        && !innerId.equals(uniqueId.invoke(displays.getDisplay(0)))?stable+1:0;
                Thread.sleep(50);
            }
            assertEquals("Physical panel mapping must settle before resizing",3,stable);
            assertEquals(Display.STATE_ON,displays.getDisplay(1).getState());
            Point coverPixels=new Point();wmApi.getMethod("getInitialDisplaySize",int.class,Point.class).invoke(wm,0,coverPixels);
            if(!production){resized=true; wmApi.getMethod("setForcedDisplaySize",int.class,int.class,int.class).invoke(wm,0,inner.x,inner.y);}
            Thread.sleep(700); shell("input -d 0 keyevent 3"); Thread.sleep(500);
            Point primary=new Point(); deadline=SystemClock.elapsedRealtime()+3000;
            do{displays.getDisplay(0).getRealSize(primary);if(inner.equals(primary))break;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<deadline);
            if(!production)assertEquals("Primary app layout must match the inner panel",inner,primary);
            homeTask();
            CountDownLatch ready=new CountDownLatch(1); Bitmap background=wallpaper;IShellBridge connected=bridge;
            Class<?> inputApi=Class.forName("android.hardware.input.InputManagerGlobal");
            Object input=inputApi.getMethod("getInstance").invoke(null);
            Method inject=inputApi.getMethod("injectInputEvent",InputEvent.class,int.class);
            Method setDisplay=InputEvent.class.getMethod("setDisplayId",int.class);
            instrumentation.runOnMainSync(()->{
                Context panel=context.createDisplayContext(displays.getDisplay(1)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                WindowManager manager=panel.getSystemService(WindowManager.class); FrameLayout root=new FrameLayout(panel);
                ImageView image=new ImageView(panel); image.setImageBitmap(background); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
                root.addView(image,new FrameLayout.LayoutParams(-1,-1));
                SurfaceView surface=new SurfaceView(panel); surface.setZOrderOnTop(true); surface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
                surface.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder holder){ready.countDown();}public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){}public void surfaceDestroyed(SurfaceHolder holder){}});
                surface.setOnTouchListener((view,event)->{
                    if(event.getActionMasked()!=MotionEvent.ACTION_MOVE)android.util.Log.i("FolduoMirrorInput","event="+event.getActionMasked()+" device="+event.getDeviceId()+" x="+event.getRawX()+" y="+event.getRawY());
                    if(context.getSystemService(KeyguardManager.class).isKeyguardLocked())return true;
                    if(production){try{connected.mirrorTouch(event,view.getWidth(),view.getHeight());forwarded.incrementAndGet();}catch(Exception e){inputError.compareAndSet(null,e);}return true;}
                    MotionEvent copy=forwardedEvent(event,view.getWidth(),view.getHeight(),coverPixels);
                    try {
                        setDisplay.invoke(copy,0);
                        if(!(boolean)inject.invoke(input,copy,0))throw new IllegalStateException("Forwarded input rejected");
                        forwarded.incrementAndGet();
                    }catch(Throwable failure){inputError.compareAndSet(null,failure);}finally{copy.recycle();}
                    return true;
                });
                root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
                WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
                lp.setFitInsetsTypes(0); lp.setTitle("Folduo bounded wallpaper mirror");
                windows[0]=manager;roots[0]=root;surfaces[0]=surface;manager.addView(root,lp);
            });
            assertTrue(ready.await(5,TimeUnit.SECONDS));
            if(production){
                Bundle result=bridge.mirror(surfaces[0].getHolder().getSurface(),surfaces[0].getSurfaceControl(),inner.x,inner.y,context.getResources().getDisplayMetrics().densityDpi);assertTrue(result.toString(),result.getBoolean("ok"));
                result=bridge.workspace(true);assertTrue(result.toString(),result.getBoolean("ok"));mirrorBinder.set(bridge.asBinder());Thread.sleep(700);
            }else{
            rikka.shizuku.Shizuku.bindUserService(mirrorArgs,mirrorConnection);assertTrue(mirrorConnected.await(10,TimeUnit.SECONDS));
            Parcel request=Parcel.obtain(),response=Parcel.obtain();
            try{
                request.writeInterfaceToken(HomeMirrorProbe.DESCRIPTOR);request.writeTypedObject(surfaces[0].getHolder().getSurface(),0);
                request.writeInt(inner.x);request.writeInt(inner.y);request.writeInt(context.getResources().getDisplayMetrics().densityDpi);
                request.writeInt(manual||navigation?60000:15000);
                assertTrue(mirrorBinder.get().transact(1,request,response,0));response.readException();Bundle result=response.readTypedObject(Bundle.CREATOR);
                assertNotNull(result);assertTrue(result.toString(),result.getBoolean("ok"));
            }finally{request.recycle();response.recycle();}
            }
            Thread.sleep(800);
            saveFrame(context,bridge,1,"wallpaper-mirror-inner-home.png"); saveFrame(context,bridge,0,"wallpaper-mirror-cover-home.png");
            if(navigation){checkNavigation(context,bridge,mirrorBinder.get(),displays.getDisplay(1),inner);return;}
            var accessibility=instrumentation.getUiAutomation().getServiceInfo();
            accessibility.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            instrumentation.getUiAutomation().setServiceInfo(accessibility);
            assertFalse("Begin on Home, with its search panel closed",searchVisible());
            float x=surfaces[0].getWidth()/2f,from=surfaces[0].getHeight()*.8f,to=surfaces[0].getHeight()*.25f;
            if(manual){
                Bundle readyForFinger=new Bundle();readyForFinger.putString("stream","WALLPAPER_FINGER_READY: swipe up on the inner wallpaper once; 45 seconds.\n");instrumentation.sendStatus(0,readyForFinger);
                long until=SystemClock.elapsedRealtime()+45000;
                while(!searchVisible()&&SystemClock.elapsedRealtime()<until){
                    assertFalse("Keep phone unlocked",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
                    assertEquals("Keep phone unfolded","OPENED",bridge.inspect().getString("baseState"));Thread.sleep(100);
                }
            }else{
                // Android's normal synthetic finger includes the same tool type as physical touch.
                shell("input -d 1 swipe "+(int)x+" "+(int)from+" "+(int)x+" "+(int)to+" 350");
            }
            Thread.sleep(600); assertNull("Input forwarding must succeed",inputError.get()); assertTrue("Real display-1 input must reach the mirror",forwarded.get()>=2);
            saveFrame(context,bridge,1,"wallpaper-mirror-inner-drawer.png");
            saveFrame(context,bridge,0,"wallpaper-mirror-cover-drawer.png");
            assertTrue("Forwarded swipe must reveal Kvaesitso's search field",searchVisible());
            if(manual)Thread.sleep(3000);
            for(int attempt=0;attempt<2&&searchVisible();attempt++){shell("input -d 0 keyevent 4");Thread.sleep(350);}
            saveFrame(context,bridge,1,"wallpaper-mirror-inner-back.png");
            saveFrame(context,bridge,0,"wallpaper-mirror-cover-back.png");
            assertFalse("Native Back must dismiss search",searchVisible());
            Bundle report=new Bundle(); report.putString("stream","WALLPAPER_MIRROR_SWIPE_OK forwarded="+forwarded.get()+"; inspect both wallpapers and Home/Drawer/Back frames. No production service changed.\n");instrumentation.sendStatus(0,report);
        }finally{
            try{if(!production)rikka.shizuku.Shizuku.unbindUserService(mirrorArgs,mirrorConnection,true);}
            finally{
                instrumentation.runOnMainSync(()->{if(roots[0]!=null)windows[0].removeViewImmediate(roots[0]);});
                try{if(resized)wmApi.getMethod("clearForcedDisplaySize",int.class).invoke(wm,0);}
                finally{
                    if(bridge!=null)try{bridge.release();bridge.stopAngles();}finally{BridgeConnection.disconnect();}
                    instrumentation.getUiAutomation().dropShellPermissionIdentity();
                    if(wallpaper!=null)wallpaper.recycle();
                    if(activity!=null){Activity source=activity;instrumentation.runOnMainSync(source::finish);}
                    MotionSettings.setEnabled(context,false);
                }
            }
        }
    }
    private static void waitForManualStart(Activity activity,IShellBridge bridge)throws Exception{
        waitForManualStart(activity,bridge,"Navigation swipe check","Tap Start when you are ready. Calculator will appear after about 8 seconds. Follow the instruction at the top of the phone screen.");
        assertEquals("Leave the phone unfolded for the navigation check","OPENED",bridge.inspect().getString("baseState"));
    }
    static void waitForManualStart(Activity activity,IShellBridge bridge,String title,String message)throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        CountDownLatch start=new CountDownLatch(1);AtomicBoolean cancelled=new AtomicBoolean();
        android.app.AlertDialog[] dialog={null};
        try{
            instrumentation.runOnMainSync(()->{
                dialog[0]=new android.app.AlertDialog.Builder(activity).setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("Start test",(d,w)->start.countDown())
                    .setNegativeButton("Cancel",(d,w)->{cancelled.set(true);start.countDown();}).setCancelable(false).create();
                dialog[0].show();dialog[0].getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
            Bundle ready=new Bundle();ready.putString("stream","MIRROR_WAITING_FOR_START: tap Start test on the phone; waiting up to three minutes.\n");instrumentation.sendStatus(0,ready);
            long until=SystemClock.elapsedRealtime()+180000;
            while(start.getCount()!=0&&SystemClock.elapsedRealtime()<until){
                org.junit.Assume.assumeFalse("Phone locked before Start",activity.getSystemService(KeyguardManager.class).isKeyguardLocked());
                start.await(100,TimeUnit.MILLISECONDS);
            }
            org.junit.Assume.assumeTrue("No physical swipe trial started",start.getCount()==0&&!cancelled.get());
        }finally{instrumentation.runOnMainSync(()->{if(dialog[0]!=null)dialog[0].dismiss();});}
    }
    private static Bundle navigate(IBinder mirror,int action,int task)throws Exception{
        if("jp.bunkaich.sukashimotion.IShellBridge".equals(mirror.getInterfaceDescriptor())){Bundle result=IShellBridge.Stub.asInterface(mirror).navigate(0,action,task);assertTrue(result.toString(),result.getBoolean("ok"));return result;}
        Parcel data=Parcel.obtain(),reply=Parcel.obtain();
        try{
            data.writeInterfaceToken(HomeMirrorProbe.DESCRIPTOR);data.writeInt(action);data.writeInt(task);
            assertTrue("Mirror must connect navigation actions",mirror.transact(2,data,reply,0));
            reply.readException();Bundle result=reply.readTypedObject(Bundle.CREATOR);
            assertNotNull(result);assertTrue(result.toString(),result.getBoolean("ok"));return result;
        }finally{data.recycle();reply.recycle();}
    }
    private static void checkNavigation(Context context,IShellBridge bridge,IBinder mirror,Display display,Point size)throws Exception{
        navigate(mirror,KeyEvent.KEYCODE_HOME,-1);
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        InnerNavigation[] navigation={null};AtomicReference<Throwable> failure=new AtomicReference<>();
        TextView[] prompt={null};
        AtomicInteger completed=new AtomicInteger();
        ExecutorService actions=Executors.newSingleThreadExecutor();
        try{
            instrumentation.runOnMainSync(()->{
                Context local=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                navigation[0]=new InnerNavigation(local,1,size.x,size.y,(action,task)->actions.execute(()->{
                    try{
                        Bundle result=navigate(mirror,action,task);
                        if(action==KeyEvent.KEYCODE_APP_SWITCH)instrumentation.runOnMainSync(()->navigation[0].showRecent(result.getParcelableArrayList("apps",Bundle.class)));
                        int bit=action==KeyEvent.KEYCODE_HOME?1:action==KeyEvent.KEYCODE_APP_SWITCH?2:action==0&&task>=0?4:action==KeyEvent.KEYCODE_BACK?8:0;
                        completed.getAndUpdate(previous->previous|bit);
                    }catch(Throwable error){failure.compareAndSet(null,error);}
                }));
            });
            shell("am start --display 0 -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.sec.android.app.popupcalculator/.Calculator -f 0x30000000");
            awaitPackage("com.sec.android.app.popupcalculator");Thread.sleep(900);
            assertMirroredApp(bridge);saveFrame(context,bridge,1,"wallpaper-mirror-calculator.png");
            int calculatorDepth=calculatorDepth();
            boolean manualHome="true".equals(InstrumentationRegistry.getArguments().getString("manualHome"));
            if(manualHome||"true".equals(InstrumentationRegistry.getArguments().getString("manualNavigation"))){
                instrumentation.runOnMainSync(()->{
                    Context local=context.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                    TextView message=new TextView(local);message.setText("Swipe up from the white line at the bottom now");message.setTextSize(20);message.setTextColor(Color.WHITE);message.setBackgroundColor(0xff205944);message.setPadding(30,20,30,20);message.setGravity(Gravity.CENTER);
                    WindowManager.LayoutParams lp=new WindowManager.LayoutParams(size.x-120,-2,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,PixelFormat.TRANSLUCENT);
                    lp.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;lp.y=80;lp.setTitle("Folduo swipe instruction");
                    local.getSystemService(WindowManager.class).addView(message,lp);prompt[0]=message;
                });
                int expected=manualHome?1:15;
                Bundle ready=new Bundle();ready.putString("stream",manualHome?"MIRROR_HOME_READY: 45 seconds. Swipe upward from the bottom white line once.\n":"MIRROR_NAVIGATION_READY: 45 seconds. Swipe Home, hold for Recents, select Calculator, swipe Back.\n");instrumentation.sendStatus(0,ready);
                long until=SystemClock.elapsedRealtime()+45000;int previousStep=0;
                while((completed.get()&expected)!=expected&&failure.get()==null&&SystemClock.elapsedRealtime()<until){
                    int step=completed.get();
                    if(!manualHome&&step!=previousStep){
                        String instruction=(step&1)==0?"Swipe up from the white line at the bottom":(step&2)==0?"Swipe up from the white line and hold for Recent apps":(step&4)==0?"Tap the Calculator card in Recent apps":"Swipe inward from the left or right edge for Back";
                        instrumentation.runOnMainSync(()->prompt[0].setText(instruction));previousStep=step;
                    }
                    assertFalse("Keep phone unlocked",context.getSystemService(KeyguardManager.class).isKeyguardLocked());
                    assertEquals("Keep phone open","OPENED",bridge.inspect().getString("baseState"));Thread.sleep(100);
                }
                assertNull(failure.get());assertEquals("Physical controls completed: Home=1 Recents=2 Select=4 Back=8",expected,completed.get()&expected);
                if(manualHome)awaitPackage("de.mm20.launcher2.release");else awaitBack(calculatorDepth);Thread.sleep(500);
                saveFrame(context,bridge,1,"wallpaper-mirror-manual-navigation.png");
                Bundle report=new Bundle();report.putString("stream",manualHome?"MIRROR_PHYSICAL_HOME_OK: finger swipe returned to Kvaesitso.\n":"MIRROR_PHYSICAL_NAVIGATION_OK: Home, Recents, Calculator selection and Back.\n");instrumentation.sendStatus(0,report);return;
            }
            navSwipe(size,false,false);awaitPackage("de.mm20.launcher2.release");Thread.sleep(600);
            assertNull(failure.get());saveFrame(context,bridge,1,"wallpaper-mirror-navigation-home.png");
            navSwipe(size,true,false);long until=SystemClock.elapsedRealtime()+3000;
            while(!navigation[0].showingRecents()&&failure.get()==null&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
            assertNull(failure.get());assertTrue("Up-and-hold must show Recents",navigation[0].showingRecents());
            saveFrame(context,bridge,1,"wallpaper-mirror-navigation-recents.png");
            String calculator=context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo("com.sec.android.app.popupcalculator",0)).toString();
            var rootField=InnerNavigation.class.getDeclaredField("root");rootField.setAccessible(true);
            View card=findDescription((View)rootField.get(navigation[0]),calculator);assertNotNull("Calculator must be selectable in Recents",card);
            instrumentation.runOnMainSync(card::performClick);awaitPackage("com.sec.android.app.popupcalculator");Thread.sleep(700);
            assertFalse(navigation[0].showingRecents());assertNull(failure.get());assertMirroredApp(bridge);
            navSwipe(size,false,true);awaitBack(calculatorDepth);Thread.sleep(500);assertNull(failure.get());
            saveFrame(context,bridge,1,"wallpaper-mirror-navigation-back.png");
            Bundle report=new Bundle();report.putString("stream","MIRROR_NAVIGATION_OK: Calculator mirrored; Home swipe; Recents hold; Calculator selection; Back swipe.\n");instrumentation.sendStatus(0,report);
        }finally{
            actions.shutdown();actions.awaitTermination(5,TimeUnit.SECONDS);
            instrumentation.runOnMainSync(()->{if(prompt[0]!=null)prompt[0].getContext().getSystemService(WindowManager.class).removeViewImmediate(prompt[0]);if(navigation[0]!=null)navigation[0].close();});
        }
    }
    private static View findDescription(View view,String description){
        if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;
        if(view instanceof android.view.ViewGroup group)for(int i=0;i<group.getChildCount();i++){View match=findDescription(group.getChildAt(i),description);if(match!=null)return match;}
        return null;
    }
    static void navSwipe(Point size,boolean hold,boolean back)throws Exception{
        float x=back?5:size.x/2f,y=back?size.y/2f:size.y-8,dx=back?350:0,dy=back?0:-350;
        long down=SystemClock.uptimeMillis();
        for(int action:new int[]{MotionEvent.ACTION_DOWN,MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP}){
            MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x+(action==0?0:dx),y+(action==0?0:dy),0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);InputEvent.class.getMethod("setDisplayId",int.class).invoke(event,1);
            try{assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event,true));}finally{event.recycle();}
            Thread.sleep(action==MotionEvent.ACTION_MOVE&&hold?450:60);
        }
    }
    static void awaitPackage(String expected)throws Exception{
        long until=SystemClock.elapsedRealtime()+4000;String actual="";
        do{
            Object manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
            List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,1,false,false,0);
            if(!tasks.isEmpty()){Object task=tasks.get(0);ComponentName top=(ComponentName)task.getClass().getField("topActivity").get(task);actual=top==null?"":top.getPackageName();if(expected.equals(actual))return;}
            Thread.sleep(50);
        }while(SystemClock.elapsedRealtime()<until);
        assertEquals("Navigation must change the actual foreground app",expected,actual);
    }
    private static int calculatorDepth()throws Exception{
        Object manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,1,false,false,0);
        assertFalse(tasks.isEmpty());Object task=tasks.get(0);ComponentName top=(ComponentName)task.getClass().getField("topActivity").get(task);
        if(top!=null&&"de.mm20.launcher2.release".equals(top.getPackageName()))return 0;
        assertNotNull(top);assertEquals("com.sec.android.app.popupcalculator",top.getPackageName());
        return task.getClass().getField("numActivities").getInt(task);
    }
    private static void awaitBack(int before)throws Exception{
        long until=SystemClock.elapsedRealtime()+3000;int after;
        do{after=calculatorDepth();if(after<before)return;Thread.sleep(50);}while(SystemClock.elapsedRealtime()<until);
        fail("Back must remove one Calculator screen or return Home; before="+before+" after="+after);
    }
    static void assertMirroredApp(IShellBridge bridge)throws Exception{
        Bitmap source=bridge.capture(0).getParcelable("frame",Bitmap.class),target=bridge.capture(1).getParcelable("frame",Bitmap.class);
        assertNotNull(source);assertNotNull(target);
        try{
            long difference=0;int samples=0;
            for(int y=2;y<18;y++)for(int x=2;x<18;x++){
                int a=source.getPixel(source.getWidth()*x/20,source.getHeight()*y/20),b=target.getPixel(target.getWidth()*x/20,target.getHeight()*y/20);
                difference+=Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b));samples+=3;
            }
            assertTrue("Inner mirror must show the selected Calculator, mean pixel difference="+(difference/(float)samples),difference/(float)samples<10);
        }finally{source.recycle();target.recycle();}
    }
    private static Object service(String name,String api)throws Exception{
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        return Class.forName(api+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
    private static Object homeTask()throws Exception{
        Object manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,1,false,false,0);
        assertFalse(tasks.isEmpty());Object task=tasks.get(0);ComponentName top=(ComponentName)task.getClass().getField("topActivity").get(task);
        assertNotNull(top);assertEquals("de.mm20.launcher2.release",top.getPackageName());return task;
    }
    private static void shell(String command)throws Exception{
        try(var input=new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))){input.readAllBytes();}
    }
    private static boolean searchVisible(){
        var automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();
        automation.clearCache(); // Mirrored Compose nodes can outlive the panel they describe.
        var all=automation.getWindowsOnAllDisplays();
        var windows=all.get(0);if(windows==null)return false;
        for(var window:windows){
            var root=window.getRoot();if(root==null||!android.text.TextUtils.equals("de.mm20.launcher2.release",root.getPackageName()))continue;
            java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> pending=new java.util.ArrayDeque<>();pending.add(root);
            while(!pending.isEmpty()){
                var node=pending.removeFirst();
                if(node.isEditable()&&node.isVisibleToUser())return true;
                for(int i=0;i<node.getChildCount();i++){var child=node.getChild(i);if(child!=null)pending.add(child);}
            }
        }
        return false;
    }
    private static void saveFrame(Context context,IShellBridge bridge,int display,String name)throws Exception{
        assertFalse(context.getSystemService(KeyguardManager.class).isKeyguardLocked());
        Bundle result=bridge.capture(display);Bitmap frame=result.getParcelable("frame",Bitmap.class);assertNotNull(result.toString(),frame);
        try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name))){assertTrue(frame.compress(Bitmap.CompressFormat.PNG,100,out));}finally{frame.recycle();}
    }
}
