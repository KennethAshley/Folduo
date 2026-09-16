package jp.bunkaich.sukashimotion;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import java.io.*;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** Paired hinge frost, with temporary power pins across the final native app handoff. */
public final class RevealService extends Service implements DisplayManager.DisplayListener,Choreographer.FrameCallback {
    private static final long MAX_CAPTURE_AGE_MS=700, MAX_HOLD_MS=30000;
    static volatile boolean running, showing;
    static volatile int completed, skipped;
    static volatile UiText status=UiText.of(R.string.stopped);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService captures=Executors.newSingleThreadExecutor();
    private final HingeTrigger hinge=new HingeTrigger();
    private final ArrayDeque<String> events=new ArrayDeque<>();
    private DisplayManager displays;
    private IShellBridge bound, controlled;
    private WindowManager windows;
    private SnapshotView frost;
    private WindowManager otherWindows;
    private SnapshotView otherFrost;
    private SnapshotSurface frostRoot,otherRoot;
    private FrameTexture innerFrame,coverFrame,handoffFrame;
    private boolean frameScheduled;
    private long frameAt,handoffAt;
    private float targetAngle;
    private volatile int generation;
    private volatile boolean stopped;
    private boolean motion, finishing, sourceInner, destinationInner, requested, otherCommitted;
    private float lastAngle=Float.NaN, drawnAngle;
    private long hardwareAt, startedAt, captureMs;
    private String lastError="", stage="idle", notificationText="";
    private final BroadcastReceiver power=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            cancelEffect("power:"+intent.getAction());hinge.reset();
            if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction()))detachBridge();
        }
    };
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
        super.onCreate();running=true;completed=skipped=0;status=UiText.of(R.string.starting);
        NotificationManager notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("reveal",getString(R.string.notification_channel),NotificationManager.IMPORTANCE_LOW));
        startForeground(8,notification());displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,main);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(power,filter,Context.RECEIVER_NOT_EXPORTED);
        BridgeConnection.init(this);main.post(health);
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        String action=intent==null?"restore":intent.getAction();
        if("stop".equals(action)||"restore".equals(action)&&!MotionSettings.enabled(this)){
            MotionSettings.setEnabled(this,false);stopSelf();return START_NOT_STICKY;
        }
        MotionSettings.setEnabled(this,true);BridgeConnection.connect(this);
        if("preview".equals(action))begin(true);
        return START_STICKY;
    }
    private Notification notification(){
        PendingIntent stop=PendingIntent.getService(this,11,new Intent(this,RevealService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,12,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"reveal").setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle(getString(R.string.app_name)).setContentText(status.resolve(this)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).addAction(new Notification.Action.Builder(null,getString(R.string.stop),stop).build()).build();
    }
    private boolean unlocked(){return getSystemService(PowerManager.class).isInteractive()&&!getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private static boolean isInner(Display display){
        Display.Mode mode=display.getMode();return Math.min(mode.getPhysicalWidth(),mode.getPhysicalHeight())/(float)Math.max(mode.getPhysicalWidth(),mode.getPhysicalHeight())>.7f;
    }
    private final Runnable health=new Runnable(){public void run(){
        if(stopped)return;
        if(!unlocked()){cancelEffect("locked");detachBridge();}
        else{
            BridgeConnection.connect(RevealService.this);IShellBridge next=BridgeConnection.bridge;
            if(next!=bound){
                detachBridge();bound=next;hinge.reset();hardwareAt=0;
                // Arm from the current native panel; no animation starts until a fresh angle arrives.
                Display display=displays.getDisplay(0);if(display!=null)hinge.update(isInner(display)?180:0);
                if(next!=null)BridgeConnection.work.execute(()->{
                    try{BridgeConnection.startAngles(RevealService.this,next,new IAngleSink.Stub(){public void angle(float value,long at,int source){
                        main.post(()->onHinge(next,value,at,source));
                    }},true);}catch(Exception e){main.post(()->{if(bound==next){lastError=ShellBridge.message(e);detachBridge();}});}
                });
            }
            if(motion&&SystemClock.uptimeMillis()-startedAt>=MAX_HOLD_MS)fail("Fold exceeded 30 seconds");
        }
        status=!unlocked()?UiText.of(R.string.waiting_unlock):bound==null?BridgeConnection.status:UiText.of(R.string.reveal_ready);
        String text=status.resolve(RevealService.this);if(!text.equals(notificationText)){notificationText=text;getSystemService(NotificationManager.class).notify(8,notification());}
        main.postDelayed(this,500);
    }};
    private void detachBridge(){
        IShellBridge previous=bound;bound=null;hinge.reset();hardwareAt=0;
        if(previous==null)return;cancelEffect("bridge-detached");
        BridgeConnection.work.execute(()->{try{previous.release();}catch(Exception ignored){}try{BridgeConnection.stopAngles(RevealService.this,previous);}catch(Exception ignored){}});
    }
    private void onHinge(IShellBridge sender,float value,long at,int source){
        long age=SystemClock.elapsedRealtime()-at;
        if(stopped||sender!=bound||!unlocked()||age<0||age>600||!Float.isFinite(value)||value<0||value>180)return;
        if(source!=HardwareAngle.SOURCE){
            // Coarse posture can arm a stationary endpoint, never create a fold animation.
            if(source==0&&!motion&&(value<=10||value>=170))hinge.update(value);
            return;
        }
        if(at<hardwareAt)return;hardwareAt=at;lastAngle=value;
        int event=hinge.update(value);
        if(event==HingeTrigger.START)begin(false);
        else if(event==HingeTrigger.FINISH&&motion)finishMotion(value>=170);
        if(motion&&showing&&!finishing)animateAngle(value);
    }
    private void begin(boolean preview){
        cancelEffect("new-effect");
        if(!unlocked()||!Settings.canDrawOverlays(this)||!ValueAnimator.areAnimatorsEnabled())return;
        IShellBridge bridge=preview?BridgeConnection.bridge:bound;
        Display display=displays.getDisplay(0);
        if(bridge==null||display==null||display.getState()!=Display.STATE_ON)return;
        lastError="";sourceInner=isInner(display);destinationInner=preview?sourceInner:!sourceInner;
        motion=true;startedAt=SystemClock.uptimeMillis();stage="capturing";int ticket=generation;
        Point otherSize=new Point();Display other=displays.getDisplay(1);
        if(!preview&&other==null){fail("Second panel unavailable");return;}
        if(other!=null)other.getRealSize(otherSize);
        trace("capture-start");
        captures.execute(()->{
            FrameTexture frame=null,paired=null;String error="";
            try{
                Bundle result=bridge.capture(0);Bitmap image=result.getParcelable("frame",Bitmap.class);
                if(image==null)error=result.getString("error","Capture unavailable");
                else{
                    frame=FrameTexture.prepare(image,getResources().getDisplayMetrics().density,()->stopped||ticket!=generation);
                    if(frame!=null&&!preview&&ticket==generation)paired=frame.transfer(sourceInner,otherSize.x,otherSize.y,true);
                }
            }catch(Exception e){error=ShellBridge.message(e);}
            FrameTexture ready=frame,otherReady=paired;String failure=error;
            main.post(()->{
                if(stopped||ticket!=generation||!unlocked())return;
                captureMs=SystemClock.uptimeMillis()-startedAt;
                if(ready==null||!preview&&otherReady==null){fail(failure.isEmpty()?"Capture cancelled":failure);return;}
                Display current=displays.getDisplay(0);
                if(captureMs>MAX_CAPTURE_AGE_MS||current==null||current.getState()!=Display.STATE_ON||isInner(current)!=sourceInner){fail("Late capture skipped");return;}
                innerFrame=sourceInner?ready:otherReady;coverFrame=sourceInner?otherReady:ready;
                show(ready,otherReady,bridge,ticket,preview);
            });
        });
    }
    private SnapshotView addFrost(int displayId,FrameTexture frame,boolean inner,Runnable committed){
        Context panel=createDisplayContext(displays.getDisplay(displayId)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
        WindowManager manager=panel.getSystemService(WindowManager.class);
        if(displayId==0)windows=manager;else otherWindows=manager;
        SnapshotView view=new SnapshotView(panel,frame,inner,false);view.flatProjection=true;
        SnapshotSurface root=new SnapshotSurface(panel,view,committed);
        if(displayId==0)frostRoot=root;else otherRoot=root;
        WindowManager.LayoutParams params=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,PixelFormat.TRANSLUCENT);
        params.setFitInsetsTypes(0);params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("Folduo paired frost "+displayId);params.windowAnimations=0;
        manager.addView(root,params);return view;
    }
    private void show(FrameTexture frame,FrameTexture paired,IShellBridge bridge,int ticket,boolean preview){
        try{
            frost=addFrost(0,frame,sourceInner,()->{
                if(ticket!=generation||stopped||!unlocked()||!"drawing".equals(stage))return;trace("frost-frame-committed");
                if(preview){reveal(ticket);return;}
                controlled=bridge;stage="requesting";
                BridgeConnection.work.execute(()->{
                    if(stopped||ticket!=generation)return;
                    try{
                        Bundle result=bridge.holdPaired(sourceInner);
                        main.post(()->{if(ticket!=generation||stopped)return;if(!result.getBoolean("ok")){fail(result.getString("error","Native request unavailable"));return;}
                            requested=true;stage="warming";trace("both-panels-held");awaitBothFrames(ticket);});
                    }catch(Exception e){main.post(()->{if(ticket==generation)fail(ShellBridge.message(e));});}
                });
            });
            if(!preview)otherFrost=addFrost(1,paired,!sourceInner,()->{if(ticket==generation){otherCommitted=true;trace("both-frost-frames-committed");}});
            drawnAngle=targetAngle=preview?90:lastAngle;applyAngle();showing=true;stage="drawing";
            main.postDelayed(()->{if(ticket==generation&&"drawing".equals(stage))fail("Frost frame did not commit");},1000);
        }catch(Exception e){fail(ShellBridge.message(e));}
    }
    private void awaitBothFrames(int ticket){
        if(stopped||ticket!=generation||finishing)return;
        Display first=displays.getDisplay(0),second=displays.getDisplay(1);
        if(!otherCommitted||first==null||second==null||first.getState()!=Display.STATE_ON||second.getState()!=Display.STATE_ON||isInner(first)!=sourceInner){
            main.postDelayed(()->awaitBothFrames(ticket),32);return;
        }
        // Switching between concurrent layouts blanks both layer stacks in Samsung's
        // compositor, even with physical power pinned ON. Keep this mapping until
        // the real endpoint; the live app resizes beneath the frost during handoff.
        stage="folding";trace("both-ready-source-retained");
    }
    private void applyAngle(){applyAngle(frost);applyAngle(otherFrost);}
    private void applyAngle(SnapshotView view){
        if(view==null)return;
        if(handoffFrame!=null&&view==frost)return;
        float angle=drawnAngle;
        // Keep a little destination frost until its newly-sized live app is ready.
        if(view.inner==destinationInner)angle=view.inner?Math.min(angle,145):Math.max(angle,30);
        view.setAngle(angle);
        // Preserve the normal hinge detail through most of closing. Conceal the
        // layout exchange only in the last 35–10 degrees, then clear the native image.
        float closingMask=Math.max(0,Math.min(1,(35-drawnAngle)/25));
        view.setBlurFloor(sourceInner&&!view.inner?28*closingMask*closingMask*(3-2*closingMask):0);
    }
    private void animateAngle(float angle){
        targetAngle=angle;scheduleFrame();
    }
    private void scheduleFrame(){
        if(!frameScheduled){frameScheduled=true;Choreographer.getInstance().postFrameCallback(this);}
    }
    @Override public void doFrame(long nanos){
        frameScheduled=false;if(stopped||!motion||!showing||"revealing".equals(stage)||"releasing".equals(stage)){frameAt=0;return;}
        if("blending-cover".equals(stage)){
            if(handoffAt==0)handoffAt=nanos;
            float elapsed=(nanos-handoffAt)/1e6f;
            float blend=Math.min(1,elapsed/160),clear=Math.max(0,Math.min(1,(elapsed-160)/200));
            frost.setAngle(0);frost.setLayoutBlend(handoffFrame,blend*blend*(3-2*blend));
            frost.setBlurFloor(28*(1-clear*clear*(3-2*clear)));
            if(elapsed<360)scheduleFrame();
            else{int ticket=generation;frost.afterFrame(()->main.post(()->{if(ticket==generation&&!stopped)releaseAndReveal(ticket,controlled);}));}
            return;
        }
        float dt=frameAt==0?1/60f:(nanos-frameAt)/1e9f;frameAt=nanos;
        // Physical hinge motion uses elapsed time, independent of Samsung's animator speed.
        // ponytail: 70 ms filters coarse angle steps; retune if the hardware feed gets finer.
        drawnAngle=FoldPolicy.smooth(drawnAngle,targetAngle,dt,.07f);
        if(Math.abs(drawnAngle-targetAngle)<.015f)drawnAngle=targetAngle;
        applyAngle();if(drawnAngle!=targetAngle)scheduleFrame();else frameAt=0;
    }
    private void stopAngleFrames(){
        Choreographer.getInstance().removeFrameCallback(this);frameScheduled=false;frameAt=0;
    }
    private void finishMotion(boolean inner){
        if(finishing)return;
        if(!showing||!requested){cancelEffect("endpoint-before-ready");return;}
        finishing=true;destinationInner=inner;animateAngle(inner?180:0);stage="waiting-native";trace(stage);
        int ticket=generation;awaitEndpoint(ticket,controlled);
    }
    private void awaitEndpoint(int ticket,IShellBridge bridge){
        if(stopped||ticket!=generation)return;
        BridgeConnection.work.execute(()->{
            if(stopped||ticket!=generation)return;
            try{
                if(!HingeTrigger.nativeEndpoint(destinationInner,bridge.inspect().getString("baseState"))){main.postDelayed(()->awaitEndpoint(ticket,bridge),50);return;}
                Bundle result=bridge.holdNative(destinationInner);
                if(!result.getBoolean("ok"))throw new IllegalStateException(result.getString("error","Native handoff unavailable"));
                main.post(()->{
                    if(ticket!=generation||stopped)return;stage="waiting-app";trace("native-requested-with-power-pins");remapPanels();
                    long deadline=SystemClock.uptimeMillis()+4000;awaitApp(ticket,bridge,deadline);
                });
            }catch(Exception e){main.post(()->{if(ticket==generation)fail(ShellBridge.message(e));});}
        });
    }
    private void awaitApp(int ticket,IShellBridge bridge,long deadline){
        if(stopped||ticket!=generation)return;
        if(SystemClock.uptimeMillis()>deadline){fail("Destination app did not draw");return;}
        Display display=displays.getDisplay(0);
        if(display==null||display.getState()!=Display.STATE_ON||isInner(display)!=destinationInner){main.postDelayed(()->awaitApp(ticket,bridge,deadline),32);return;}
        BridgeConnection.work.execute(()->{
            if(stopped||ticket!=generation)return;
            try{
                boolean ready=bridge.windowState(0).getBoolean("ready");
                main.post(()->{
                    if(stopped||ticket!=generation)return;
                    if(ready){
                        if(sourceInner&&!destinationInner)captureCover(ticket,bridge);
                        else releaseAndReveal(ticket,bridge);
                    }else main.postDelayed(()->awaitApp(ticket,bridge,deadline),32);
                });
            }catch(Exception e){main.post(()->{if(ticket==generation)fail(ShellBridge.message(e));});}
        });
    }
    private void captureCover(int ticket,IShellBridge bridge){
        stopAngleFrames();remapPanels();stage="capturing-cover";trace(stage);
        Point size=new Point();displays.getDisplay(0).getRealSize(size);
        SurfaceControl primary=frostRoot==null?null:frostRoot.getSurfaceControl();
        if(primary==null||!primary.isValid()){releaseAndReveal(ticket,bridge);return;}
        SurfaceControl[] exclude=java.util.stream.Stream.of(frostRoot,otherRoot).filter(root->root!=null)
            .map(SnapshotSurface::getSurfaceControl).filter(surface->surface!=null&&surface.isValid()).toArray(SurfaceControl[]::new);
        float density=frost.getResources().getDisplayMetrics().density;
        // A capture failure must never leave the user's live app behind an overlay.
        main.postDelayed(()->{
            if(ticket==generation&&!stopped&&"capturing-cover".equals(stage)){trace("cover-capture-timeout");releaseAndReveal(ticket,bridge);}
        },1200);
        captures.execute(()->{
            FrameTexture ready=null;String error="";
            try{
                if(stopped||ticket!=generation)return;
                Bundle result=bridge.captureBehind(0,exclude);Bitmap bitmap=result.getParcelable("frame",Bitmap.class);
                if(bitmap==null)throw new IllegalStateException(result.getString("error","Cover capture unavailable"));
                if(bitmap.getWidth()!=size.x||bitmap.getHeight()!=size.y)throw new IllegalStateException("Cover layout is still resizing");
                ready=FrameTexture.prepare(bitmap,density,()->stopped||ticket!=generation);
            }catch(Exception e){error=ShellBridge.message(e);}
            FrameTexture frame=ready;String failure=error;
            main.post(()->{
                if(stopped||ticket!=generation||!"capturing-cover".equals(stage))return;
                if(frame==null){trace("cover-capture-unavailable:"+failure);releaseAndReveal(ticket,bridge);return;}
                handoffFrame=frame;handoffAt=0;stage="blending-cover";trace("native-cover-frame-ready");scheduleFrame();
            });
        });
    }
    private void releaseAndReveal(int ticket,IShellBridge bridge){
        if(stopped||ticket!=generation||"releasing".equals(stage)||"revealing".equals(stage))return;
        stopAngleFrames();stage="releasing";
        BridgeConnection.work.execute(()->{
            if(stopped||ticket!=generation)return;
            try{
                bridge.release();
                main.post(()->{
                    if(stopped||ticket!=generation)return;
                    controlled=null;
                    // Keep the fully clear destination image until the overlay fades.
                    if(handoffFrame!=null){coverFrame=handoffFrame;frost.setAngle(0);frost.setBlurFloor(0);}
                    else remapPanels();
                    trace("power-released");frost.afterFrame(()->main.post(()->{if(ticket==generation&&!stopped)reveal(ticket);}));
                });
            }catch(Exception e){main.post(()->{if(ticket==generation)fail(ShellBridge.message(e));});}
        });
    }
    private void reveal(int ticket){
        stopAngleFrames();
        finishing=true;stage="revealing";trace(stage);
        if(otherFrost!=null)otherFrost.animate().alpha(0).setDuration(220).start();
        frost.animate().alpha(0).setDuration(220).withEndAction(()->{if(ticket==generation){completed++;cancelEffect("revealed");}}).start();
    }
    private void fail(String error){skipped++;lastError=error;cancelEffect("error:"+error);}
    private void cancelEffect(String reason){
        if(motion||showing)trace("cancel:"+reason);
        generation++;motion=finishing=requested=otherCommitted=false;stage="idle";
        stopAngleFrames();
        if(frost!=null){frost.animate().cancel();try{windows.removeViewImmediate(frostRoot);}catch(IllegalArgumentException ignored){}frost=null;frostRoot=null;windows=null;}
        if(otherFrost!=null){otherFrost.animate().cancel();try{otherWindows.removeViewImmediate(otherRoot);}catch(IllegalArgumentException ignored){}otherFrost=null;otherRoot=null;otherWindows=null;}
        innerFrame=coverFrame=handoffFrame=null;handoffAt=0;
        // RenderThread may still reference the immutable textures. Let GC reclaim them after detach.
        showing=false;IShellBridge previous=controlled;controlled=null;
        if(previous!=null)BridgeConnection.work.execute(()->{try{previous.release();}catch(Exception ignored){}});
    }
    private void trace(String event){
        if(!BuildConfig.DEBUG)return;
        String line=SystemClock.elapsedRealtime()+" "+event+" angle="+lastAngle+" stage="+stage+" captureMs="+captureMs;
        if(events.size()>=128)events.removeFirst();events.addLast(line);android.util.Log.i("FolduoReveal",line);
    }
    private void remapPanels(){
        remapPanel(frost,0);remapPanel(otherFrost,1);applyAngle();
    }
    private void remapPanel(SnapshotView view,int id){
        Display display=displays.getDisplay(id);if(view==null||display==null)return;
        boolean inner=isInner(display);FrameTexture frame=inner?innerFrame:coverFrame;
        if(frame!=null&&view.inner!=inner)view.setPanel(frame,inner);
    }
    public void onDisplayAdded(int id){if(id<=1)remapPanels();}
    public void onDisplayRemoved(int id){if(id<=1&&motion)cancelEffect("display-removed");}
    public void onDisplayChanged(int id){if(id<=1)remapPanels();}
    @Override protected void dump(FileDescriptor fd,PrintWriter out,String[] args){
        out.println("running="+running+" showing="+showing+" motion="+motion+" unlocked="+unlocked());
        out.println("completed="+completed+" skipped="+skipped+" hinge="+lastAngle+" stage="+stage+" captureMs="+captureMs+" lastError="+lastError);
        for(String event:events)out.println("event="+event);
    }
    @Override public void onDestroy(){
        stopped=true;cancelEffect("service-stopped");detachBridge();main.removeCallbacksAndMessages(null);
        unregisterReceiver(power);displays.unregisterDisplayListener(this);captures.shutdown();BridgeConnection.disconnect();running=showing=false;
        status=UiText.of(R.string.stopped);super.onDestroy();
    }
}
