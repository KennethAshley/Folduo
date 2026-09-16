package jp.bunkaich.sukashimotion;

import android.animation.*;
import android.app.*;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import java.util.*;
import java.util.concurrent.*;
import java.io.FileDescriptor;
import java.io.PrintWriter;

/** Keeps the user-enabled monitor alive; capture is suspended while locked. */
public final class MotionService extends Service implements DisplayManager.DisplayListener,Choreographer.FrameCallback {
    static volatile boolean running;static volatile UiText status=UiText.of(R.string.stopped);
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService jobs=Executors.newSingleThreadExecutor();
    private final ExecutorService controls=Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService poller=Executors.newSingleThreadScheduledExecutor();
    private final List<Panel> panels=new ArrayList<>();private final List<Layer> layers=new ArrayList<>();
    private final Map<Boolean,FrameTexture> frozen=new HashMap<>();
    private volatile List<Anchor> anchors=List.of();
    private volatile int generation,sessionSerial;private volatile boolean stopped;private boolean paused=true,busy,frameScheduled,blockedUntilEndpoint,finishing;private String panelSignature="";
    private long angleStartedAt,angleSession,retryAt;private int recoveries;private UiText lastRecovery=UiText.raw("");private String notificationText="";
    private boolean layoutPrepared,layoutPreparing,layoutRecovering,fixedPrimaryInner;
    // The accepted GPU renderer is also used by notification, restore, and release builds.
    private final boolean duoEffect=true;
    private float blurStrength=1,responseSeconds=.024f,outerFadeStart=45;
    private float outerDarkness;private boolean outerFollowsHinge,outerPortraitReady=true;
    private int outerCommandGeneration=-1;private Runnable afterOuterBlack;
    private IShellBridge bound;private FoldPolicy policy;private float target=Float.NaN,smoothed=Float.NaN;
    private LocaleList uiLocales;private InnerNavigation navigation;private String navigationError="";
    private InnerWorkspace workspace;private boolean mirrorReady,checkingMirror;private long mirrorCheckedAt;
    private int warmingTicket=-1;private long warmedAt;
    private long measuredAt,lastFrame;private int source=-1;private DisplayManager displays;
    private final ArrayDeque<String> angleHistory=new ArrayDeque<>();
    private long layerSerial;private long acceptedAngles;private String anchorError="",stage="idle";
    private final ArrayDeque<String> handoffs=new ArrayDeque<>();
    private final BroadcastReceiver power=new BroadcastReceiver(){public void onReceive(Context c,Intent intent){
        if(Intent.ACTION_SCREEN_OFF.equals(intent.getAction())){if(!unlocked())pause();}
        else if(Intent.ACTION_USER_PRESENT.equals(intent.getAction()))resume();
    }};
    private record Panel(Display display,boolean inner,int w,int h){}
    private record Anchor(WindowManager wm,View view,WallpaperManager wallpaper){}
    private static final class Layer {
        final WindowManager wm;final SnapshotView view;final SnapshotSurface root;final int displayId;final long serial;boolean committed;
        Layer(WindowManager wm,SnapshotView view,SnapshotSurface root,int displayId,long serial){this.wm=wm;this.view=view;this.root=root;this.displayId=displayId;this.serial=serial;}
    }
    private void trace(String message){stage=message;if(handoffs.size()>=32)handoffs.removeFirst();handoffs.addLast(SystemClock.elapsedRealtime()+":"+message);}
    private SurfaceControl[] excluded(){
        ArrayList<SurfaceControl> surfaces=new ArrayList<>();
        for(Layer layer:layers){SurfaceControl surface=layer.root.getSurfaceControl();if(surface!=null&&surface.isValid())surfaces.add(surface);}
        SurfaceControl cover=workspace==null?null:workspace.coveredSurface();if(cover!=null&&cover.isValid())surfaces.add(cover);
        return surfaces.toArray(SurfaceControl[]::new);
    }
    @Override public IBinder onBind(Intent intent){return null;}
    @Override public void onCreate(){
        super.onCreate();stopService(new Intent(this,RevealService.class));uiLocales=getResources().getConfiguration().getLocales();running=true;status=UiText.of(R.string.preparing);
        NotificationManager nm=getSystemService(NotificationManager.class);nm.createNotificationChannel(new NotificationChannel("motion",getString(R.string.notification_channel),NotificationManager.IMPORTANCE_LOW));
        startForeground(7,notification(getString(R.string.starting)));
        displays=getSystemService(DisplayManager.class);displays.registerDisplayListener(this,main);
        IntentFilter filter=new IntentFilter(Intent.ACTION_SCREEN_OFF);filter.addAction(Intent.ACTION_USER_PRESENT);registerReceiver(power,filter,Context.RECEIVER_NOT_EXPORTED);
        BridgeConnection.init(this);
        poller.scheduleWithFixedDelay(()->{for(Anchor a:anchors)try{IBinder token=a.view.getWindowToken();if(token!=null)a.wallpaper.sendWallpaperCommand(token,BuildConfig.APPLICATION_ID+".READ_ANGLE",0,0,0,null);}catch(Exception ignored){}},0,16,TimeUnit.MILLISECONDS);
        main.post(health);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration config){
        super.onConfigurationChanged(config);
        if(config.getLocales().equals(uiLocales))return;
        uiLocales=config.getLocales();
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("motion",getString(R.string.notification_channel),NotificationManager.IMPORTANCE_LOW));
        notificationText="";updateNotification();
        removeNavigation();updateNavigation();
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId){
        String action=intent==null?"restore":intent.getAction();
        if("stop".equals(action)){MotionSettings.setEnabled(this,false);stopSelf();return START_NOT_STICKY;}
        if("restore".equals(action)&&!MotionSettings.enabled(this)){stopSelf();return START_NOT_STICKY;}
        MotionSettings.setEnabled(this,true);MotionSettings.recovery(this,"");
        if("restart".equals(action)){pause();recordRecovery(UiText.of(R.string.manual_restart));}
        resume();return START_STICKY;
    }
    private Notification notification(String text){
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,MotionService.class).setAction("stop"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open=PendingIntent.getActivity(this,2,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent restart=PendingIntent.getService(this,3,new Intent(this,MotionService.class).setAction("restart"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this,"motion").setSmallIcon(android.R.drawable.ic_menu_view).setContentTitle(getString(R.string.app_name)).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setOnlyAlertOnce(true).setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null,getString(R.string.resume),restart).build()).addAction(new Notification.Action.Builder(null,getString(R.string.stop),stop).build()).build();
    }
    private void updateNotification(){
        String text=(status.is(R.string.angle_status)?UiText.of(layoutPrepared?R.string.active:R.string.close_fully_to_prepare):status).resolve(this);
        if(!text.equals(notificationText)){notificationText=text;getSystemService(NotificationManager.class).notify(7,notification(text));}
    }
    private void recordRecovery(UiText reason){recoveries++;lastRecovery=reason;MotionSettings.recovery(this,reason);}
    private boolean unlocked(){return getSystemService(PowerManager.class).isInteractive()&&!getSystemService(KeyguardManager.class).isKeyguardLocked();}
    private void resume(){
        if(stopped||!unlocked()) {paused=true;status=UiText.of(R.string.waiting_unlock);return;}
        if(!paused)return;
        paused=false;++sessionSerial;layoutPrepared=layoutPreparing=layoutRecovering=false;blockedUntilEndpoint=false;rebuildPanels();Panel primary=panelById(0);fixedPrimaryInner=primary!=null&&primary.inner;policy=new FoldPolicy(fixedPrimaryInner);target=smoothed=Float.NaN;source=-1;measuredAt=lastFrame=0;status=UiText.of(R.string.preparing_connection);
    }
    private void pause(){paused=true;++angleSession;cancelSession();removeAnchors();IShellBridge b=bound;bound=null;if(b!=null)controls.execute(()->{try{BridgeConnection.stopAngles(MotionService.this,b);b.release();}catch(Exception ignored){}});status=UiText.of(R.string.waiting_unlock);}
    private final Runnable health=new Runnable(){public void run(){
        if(stopped)return;
        if(!paused&&!unlocked())pause();
        // Fold display changes can send SCREEN_OFF without a lock/unlock broadcast pair.
        if(paused&&unlocked())resume();
        if(!paused)BridgeConnection.connect(MotionService.this);
        IShellBridge available=BridgeConnection.bridge;
        if(!paused&&bound!=available){
            if(bound!=null){cancelSession();IShellBridge old=bound;controls.execute(()->{try{BridgeConnection.stopAngles(MotionService.this,old);}catch(Exception ignored){}});recordRecovery(UiText.of(R.string.helper_recovery));}
            bound=available;++angleSession;source=-1;target=smoothed=Float.NaN;measuredAt=0;
            if(available!=null)startAngles(available);
        }
        if(available==null&&!paused)status=BridgeConnection.status;
        long now=SystemClock.elapsedRealtime();
        // Wallpaper reports are continuous; a dead log reader or heartbeat expiry can
        // stop them while the binder itself remains alive. Re-register, not just wait.
        if(!paused&&bound!=null&&now-angleStartedAt>5000&&((source==1&&now-measuredAt>1500)||(source<1&&!layoutPrepared))){
            cancelSession();source=-1;target=smoothed=Float.NaN;measuredAt=0;blockedUntilEndpoint=false;
            removeAnchors();rebuildPanels();recordRecovery(UiText.of(R.string.angle_recovery));status=UiText.of(R.string.angle_retry);startAngles(bound);
        }
        if(!paused&&layoutPrepared)recoverLayoutIfNeeded();
        if(!paused&&layoutPrepared&&mirrorReady&&!checkingMirror&&now-mirrorCheckedAt>1000){
            checkingMirror=true;mirrorCheckedAt=now;IShellBridge bridge=bound;int session=sessionSerial;
            controls.execute(()->{try{Bundle state=bridge.inspect();main.post(()->{checkingMirror=false;if(session==sessionSerial&&!state.getBoolean("mirrorActive"))fail(UiText.raw("Inner workspace stopped: "+state.getString("mirrorError","")));});}catch(Exception e){main.post(()->{checkingMirror=false;if(session==sessionSerial)fail(UiText.error(e));});}});
        }
        if(!paused&&!layoutPrepared&&!layoutPreparing&&source>=0&&Float.isFinite(target))prepareIfClosed();
        if(!busy&&!finishing&&(policy==null||!policy.active))refreshTuning();
        warmInner();
        if(!paused&&layoutPrepared&&policy!=null&&policy.active&&Float.isFinite(target))handle(policy.update(target,SystemClock.elapsedRealtime(),source==HardwareAngle.SOURCE));
        updateNavigation();updateNotification();main.postDelayed(this,100);
    }};
    private void startAngles(IShellBridge bridge){
        angleStartedAt=SystemClock.elapsedRealtime();long session=++angleSession;
        IAngleSink sink=new IAngleSink.Stub(){public void angle(float value,long at,int kind){main.post(()->{if(session==angleSession&&bound==bridge)accept(value,at,kind);});}};
        // Serialize with pause/stop. Otherwise a late stopAngles from screen-off can
        // run AFTER wake-up's startAngles and silently leave a live binder with no sink.
        controls.execute(()->{try{BridgeConnection.startAngles(MotionService.this,bridge,sink,true);}catch(Exception e){main.post(()->{if(!stopped&&bound==bridge){recordRecovery(UiText.of(R.string.angle_start_failed));status=UiText.of(R.string.angle_retry_error,UiText.error(e));}});}});
    }
    private void accept(float value,long at,int kind){
        if(stopped||paused||bound==null||!Float.isFinite(value)||at>SystemClock.elapsedRealtime()+50||SystemClock.elapsedRealtime()-at>600)return;
        if(kind==0){
            if(source<1){
                status=UiText.of(R.string.coarse_angles);
                // A real CLOSED sample can arm the existing cover mapping after a
                // screen-off reset. Intermediate motion still requires a fine source.
                if(value==0&&!layoutPrepared){source=0;target=smoothed=0;measuredAt=at;prepareIfClosed();}
            }
            return;
        }
        // Direct fine sensors take priority while active; wallpaper is the fallback.
        if(kind==1&&source>=2)return;
        if(kind==source&&at<measuredAt)return;
        source=kind;measuredAt=at;target=value;
        acceptedAngles++;
        synchronized(angleHistory){if(angleHistory.size()>=160)angleHistory.removeFirst();angleHistory.addLast(at+":"+value+":"+kind);}
        if(blockedUntilEndpoint){if(SystemClock.elapsedRealtime()<retryAt||value>(kind==HardwareAngle.SOURCE?10:3)&&value<(kind==HardwareAngle.SOURCE?170:177))return;blockedUntilEndpoint=false;policy=new FoldPolicy(value>=170);}if(!Float.isFinite(smoothed))smoothed=value;
        status=UiText.of(R.string.angle_status,Math.round(value),UiText.of(kind==1?R.string.source_wallpaper:kind==2?R.string.source_samsung:kind==HardwareAngle.SOURCE?R.string.source_hardware_log:R.string.source_standard));
        if(!layoutPrepared){prepareIfClosed();return;}
        if(policy!=null)handle(policy.update(value,SystemClock.elapsedRealtime(),kind==HardwareAngle.SOURCE));scheduleFrame();
    }
    private void prepareIfClosed(){
        if(layoutPreparing||blockedUntilEndpoint)return;
        Panel primary=panelById(0);
        // The last angle can arrive before Samsung finishes activating the cover.
        // Health retries this check after the display change without inventing a sample.
        if(primary==null||primary.inner||primary.display.getState()!=Display.STATE_ON||target>(source==HardwareAngle.SOURCE?10:3)){status=UiText.of(R.string.close_to_prepare);return;}
        fixedPrimaryInner=false;prepareLayout();
    }
    private void prepareLayout(){
        int ticket=++generation;layoutPreparing=true;trace("prepare-stable-panels");IShellBridge bridge=bound;
        // Retain the currently active physical mapping. Adding the other panel does not swap
        // logical display IDs, which otherwise forces Samsung's mapper through DISPLAY_OFF.
        controls.execute(()->{try{
            if(ticket!=generation||stopped||bridge==null)return;
            Bundle result=bridge.hold(fixedPrimaryInner,MotionSettings.ownerPid(this));if(!result.getBoolean("ok"))throw new IllegalStateException(result.getString("error"));MotionSettings.ownerPid(this,result.getInt("ownerPid"));
            main.post(()->awaitLayout(ticket,0));
        }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.prepare_failed,UiText.error(e)));});}});
    }
    private void awaitLayout(int ticket,int attempt){
        if(stopped||ticket!=generation)return;rebuildPanels();Panel primary=panelById(0);
        if(findPanel(true,true)==null||findPanel(false,true)==null||primary==null||primary.inner!=fixedPrimaryInner){
            if(attempt>=35){fail(UiText.of(R.string.prepare_unconfirmed));return;}
            main.postDelayed(()->awaitLayout(ticket,attempt+1),40);return;
        }
        if(workspace!=null)return;
        IShellBridge bridge=bound;Panel inner=findPanel(true,true);int session=sessionSerial;
        controls.execute(()->{try{
            Bundle result=bridge.innerWallpaper();Bitmap wallpaper=result.getParcelable("frame",Bitmap.class);
            if(wallpaper==null)throw new IllegalStateException(result.getString("error","Inner wallpaper unavailable"));
            main.post(()->{
                if(stopped||ticket!=generation||session!=sessionSerial)return;
                try{
                    Context local=createDisplayContext(inner.display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                    workspace=new InnerWorkspace(local,wallpaper,bridge,(surface,parent)->controls.execute(()->{
                        try{
                            if(stopped||session!=sessionSerial)return;
                            Bundle started=bridge.mirror(surface,parent,inner.w,inner.h,local.getResources().getDisplayMetrics().densityDpi);
                            if(!started.getBoolean("ok"))throw new IllegalStateException(started.getString("error"));
                            main.post(()->{if(stopped||session!=sessionSerial)return;mirrorReady=true;policy=new FoldPolicy(false);
                                if(duoEffect)warmInner();else{layoutPrepared=true;layoutPreparing=false;trace("stable-mirror-ready");}
                            });
                        }catch(Exception e){main.post(()->{if(session==sessionSerial)fail(UiText.error(e));});}
                    }),error->{if(session==sessionSerial)fail(UiText.error(error));});
                }catch(Exception e){fail(UiText.error(e));}
            });
        }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.error(e));});}});
    }
    private void warmInner(){
        if(!duoEffect||stopped||paused||workspace==null||!mirrorReady||policy==null||policy.open||policy.active||busy||finishing||warmingTicket==generation)return;
        // ponytail: refresh the fully frosted, hidden inner frame at 4 fps; a live
        // texture renderer would avoid periodic captures if power measurements require it.
        long now=SystemClock.elapsedRealtime();if(now-warmedAt<250)return;warmedAt=now;
        int ticket=generation;warmingTicket=ticket;InnerWorkspace owner=workspace;IShellBridge bridge=bound;SurfaceControl[] exclude=excluded();
        jobs.execute(()->{try{
            if(stopped||ticket!=generation||bridge==null)return;
            Bundle result=bridge.captureBehind(0,exclude);Bitmap image=result.getParcelable("frame",Bitmap.class);
            if(image==null)throw new IllegalStateException(result.getString("error","@folduo/capture_failed"));
            main.post(()->{
                if(stopped||paused||ticket!=generation||workspace!=owner||policy.active||policy.open)return;
                try{owner.cover(image,()->{
                    if(stopped||ticket!=generation||workspace!=owner)return;
                    if(layoutPreparing){layoutPrepared=true;layoutPreparing=false;trace("stable-mirror-covered-ready");}
                });}catch(Exception e){fail(UiText.error(e));}
            });
        }catch(Exception e){main.post(()->{if(ticket==generation&&!stopped)fail(UiText.error(e));});}
        finally{main.post(()->{if(warmingTicket==ticket)warmingTicket=-1;});}});
    }
    private void recoverLayoutIfNeeded(){
        if(stopped||paused||!layoutPrepared||layoutRecovering||bound==null)return;
        if(findPanel(true,true)!=null&&findPanel(false,true)!=null)return;
        Panel primary=panelById(0);
        // Full closure cancels even OUTER_DEFAULT on this firmware. Re-add the inner
        // panel while the cover still owns display 0; never swap a lit primary panel.
        if(primary==null||primary.inner||primary.display.getState()!=Display.STATE_ON)return;
        layoutRecovering=true;int session=sessionSerial;IShellBridge bridge=bound;trace("rearming-after-display-release");
        controls.execute(()->{try{
            if(stopped||session!=sessionSerial)return;
            Bundle result=bridge.hold(false,MotionSettings.ownerPid(this));if(!result.getBoolean("ok"))throw new IllegalStateException(result.getString("error"));MotionSettings.ownerPid(this,result.getInt("ownerPid"));
            main.post(()->awaitRecoveredLayout(session,0));
        }catch(Exception e){main.post(()->{if(session==sessionSerial&&!stopped){layoutRecovering=false;fail(UiText.of(R.string.reprepare_failed,UiText.error(e)));}});}});
    }
    private void awaitRecoveredLayout(int session,int attempt){
        if(stopped||session!=sessionSerial)return;rebuildPanels();Panel primary=panelById(0);
        if(primary!=null&&!primary.inner&&findPanel(true,true)!=null&&findPanel(false,true)!=null){layoutRecovering=false;trace("display-request-rearmed");return;}
        if(attempt>=35){layoutRecovering=false;fail(UiText.of(R.string.relight_unconfirmed));return;}
        main.postDelayed(()->awaitRecoveredLayout(session,attempt+1),40);
    }
    private void handle(FoldPolicy.Change change){
        switch(change){case OPEN -> transition(true);case CLOSE -> transition(false);case FINISH_OPEN,FINISH_CLOSED -> finish();default -> {}}
    }
    private void transition(boolean opening){
        if(!busy&&!finishing&&layers.isEmpty())refreshTuning();
        removeNavigation();
        int ticket=++generation;busy=true;finishing=false;for(Layer layer:layers){layer.root.animate().cancel();layer.root.setAlpha(1);}
        afterOuterBlack=null;
        trace(opening?"opening-capture":"closing-capture");
        // Hide only icons (not inset sources), before taking the frozen image. A pair of
        // frames lets both SystemUI and the dismissed controls leave composition.
        IShellBridge bridge=bound;
        controls.execute(()->{try{
            if(stopped||ticket!=generation||bridge==null)return;
            Bundle result=bridge.statusIcons(true);
            if(!result.getBoolean("ok"))throw new IllegalStateException(result.getString("error"));
            main.post(()->main.postDelayed(()->captureSource(ticket,opening),34));
        }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.status_icons_failed,UiText.error(e)));});}});
    }
    private void captureSource(int ticket,boolean opening){
        if(stopped||ticket!=generation)return;
        boolean outgoingInner=!opening;Panel outgoing=findPanel(outgoingInner,false);FrameTexture cached=frozen.get(outgoingInner);
        if(outgoing==null){fail(UiText.of(R.string.source_missing));return;}
        IShellBridge bridge=bound;if(bridge==null){fail(UiText.of(R.string.bridge_missing));return;}
        int displayId=outgoing.display.getDisplayId();SurfaceControl[] exclude=excluded();float density=getResources().getDisplayMetrics().density;
        jobs.execute(()->{
            try{
                if(stopped||ticket!=generation)return;
                FrameTexture frame=cached;
                if(frame==null){Bundle result=bridge.captureBehind(displayId,exclude);Bitmap bitmap=result.getParcelable("frame",Bitmap.class);if(bitmap==null)throw new IllegalStateException(result.getString("error","@folduo/capture_failed"));frame=FrameTexture.sharp(bitmap);}
                FrameTexture ready=frame;
                if(!duoEffect&&!frame.prepared)main.post(()->{
                    if(ticket!=generation||stopped)return;
                    addLayer(outgoing,ready,true,false,ticket,()->trace("source-frame-committed"));
                });
                FrameTexture texture=duoEffect||frame.prepared?frame:FrameTexture.prepare(frame.sharp,density,()->stopped||ticket!=generation);
                main.post(()->{
                    if(ticket!=generation||stopped||texture==null)return;frozen.put(outgoingInner,texture);
                    // Duo blurs on the GPU; only the legacy renderer needs CPU levels.
                    controls.execute(()->{try{
                        if(ticket!=generation||stopped)return;
                        Bundle hidden=bridge.statusIcons(true);if(!hidden.getBoolean("ok"))throw new IllegalStateException(hidden.getString("error"));
                        main.post(()->{if(ticket==generation&&!stopped)addLayer(outgoing,texture,false,false,ticket,()->{trace("source-frost-committed");requestDisplays(ticket,opening);});});
                    }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.status_icons_failed,UiText.error(e)));});}});
                });
            }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.transition_cancelled,UiText.error(e)));});}
        });
    }
    private void requestDisplays(int ticket,boolean opening){requestDisplays(ticket,opening,0);}
    private void requestDisplays(int ticket,boolean opening,int attempt){
        if(ticket!=generation||stopped)return;IShellBridge bridge=bound;trace("source-covered-move-app");
        Panel source=findPanel(!opening,true),destination=findPanel(opening,true);
        if(source==null||destination==null){
            recoverLayoutIfNeeded();
            if(attempt>=35){fail(UiText.of(R.string.reprepare_timeout));return;}
            main.postDelayed(()->requestDisplays(ticket,opening,attempt+1),40);return;
        }
        FrameTexture cached=frozen.get(opening),outgoing=frozen.get(!opening);
        if(outgoing==null||!duoEffect&&!outgoing.prepared){fail(UiText.of(R.string.blur_unconfirmed));return;}
        if(duoEffect&&!opening&&!outerPortraitReady&&outerDarkness==1){
            // The native black layer already covers this destination. Reuse it
            // instead of building a placeholder that cannot be seen underneath.
            outerFollowsHinge=true;
            sendOuterDarkness(1,ticket,()->{
                trace("destination-black-covered-move-app");moveCoveredApp(ticket,false,source,destination);
            });
            return;
        }
        // Resizing display 0 also letterboxes its physical cover. Wait for hinge-driven
        // black coverage while preparing the arriving inner frame in parallel.
        boolean[] covered={false,!duoEffect||!opening};
        Runnable move=()->{
            if(stopped||ticket!=generation||!covered[0]||!covered[1])return;
            if(duoEffect&&opening)outerPortraitReady=false;
            if(opening&&workspace!=null)workspace.uncover();
            trace("destination-covered-move-app");moveCoveredApp(ticket,opening,source,destination);
        };
        if(duoEffect){
            outerFollowsHinge=true;
            if(opening)afterOuterBlack=()->{covered[1]=true;move.run();};
            scheduleFrame();
        }
        // Cover the destination BEFORE moving the real app. Never expose its sharp
        // resized layout during capture/blur preparation, even for a single frame.
        jobs.execute(()->{try{
            if(ticket!=generation||stopped)return;
            FrameTexture cover=cached!=null?cached:outgoing.transfer(!opening,destination.w,destination.h);
            main.post(()->{
                if(ticket!=generation||stopped)return;
                addLayer(destination,cover,false,duoEffect,ticket,()->{
                    covered[0]=true;move.run();
                });
            });
        }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.cover_failed,UiText.error(e)));});}});
    }
    private void moveCoveredApp(int ticket,boolean opening,Panel source,Panel destination){
        IShellBridge bridge=bound;
        controls.execute(()->{try{
            if(ticket!=generation||stopped||bridge==null)return;
            Bundle result=bridge.workspace(opening);
            if(!result.getBoolean("ok"))throw new IllegalStateException(result.getString("error"));
            main.postDelayed(()->{if(ticket==generation){trace("workspace-resized-without-display-swap");awaitPanels(ticket,opening,0);}},180);
        }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.handoff_failed,UiText.error(e)));});}});
    }
    private void awaitPanels(int ticket,boolean opening,int attempt){
        if(stopped||ticket!=generation)return;
        rebuildPanels();Panel outgoing=findPanel(!opening,true),incoming=findPanel(opening,true);Panel primary=panelById(0);
        if(outgoing==null||incoming==null||primary==null||primary.inner!=fixedPrimaryInner){
            if(attempt>=35){fail(UiText.of(R.string.displays_unconfirmed));return;}
            main.postDelayed(()->awaitPanels(ticket,opening,attempt+1),40);return;
        }
        trace("both-panels-ready");
        // Both panels already have an opaque, frosted cover. Capture excludes those
        // owned surfaces without hiding them. A reversal can reuse the session's frame.
        // A hinge reversal may precede the actual resize. Its source snapshot then
        // contains the old layout, so the Duo reveal must capture the resized app.
        if(!duoEffect&&frozen.get(opening)!=null){framesReady(ticket,opening);return;}
        awaitApp(ticket,incoming,()->captureDestination(ticket,opening));
    }
    private void awaitApp(int ticket,Panel panel,Runnable ready){
        IShellBridge bridge=bound;
        jobs.execute(()->{try{
            long deadline=SystemClock.elapsedRealtime()+1800;String previous="";int consecutive=0;
            while(!stopped&&ticket==generation&&SystemClock.elapsedRealtime()<deadline){
                Bundle state=bridge.windowState(0);String geometry=state.getString("geometry","");
                consecutive=state.getBoolean("ready")&&!geometry.isEmpty()?(geometry.equals(previous)?consecutive+1:1):0;previous=geometry;
                if(consecutive>=2){main.post(()->{if(ticket==generation&&!stopped){trace("app-frame-ready");ready.run();}});return;}
                Thread.sleep(32);
            }
            main.post(()->{if(ticket==generation)fail(UiText.of(R.string.app_ready_timeout));});
        }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.readiness_failed,UiText.error(e)));});}});
    }
    private void captureDestination(int ticket,boolean opening){
        Panel incoming=findPanel(opening,true);if(incoming==null){fail(UiText.of(R.string.destination_missing));return;}
        int id=incoming.display.getDisplayId();IShellBridge bridge=bound;SurfaceControl[] exclude=excluded();float density=getResources().getDisplayMetrics().density;
        jobs.execute(()->{try{
            if(stopped||ticket!=generation)return;
            // Our freeze stays visible. Exclude its owned surfaces from this capture only.
            Bundle result=bridge.captureBehind(id,exclude);Bitmap bitmap=result.getParcelable("frame",Bitmap.class);
            if(bitmap==null)throw new IllegalStateException(result.getString("error","@folduo/destination_capture_failed"));
            if(bitmap.getWidth()!=incoming.w||bitmap.getHeight()!=incoming.h)throw new IllegalStateException("@folduo/destination_resizing");
            FrameTexture texture=duoEffect?FrameTexture.sharp(bitmap):FrameTexture.prepare(bitmap,density,()->ticket!=generation||stopped);
            main.post(()->{if(ticket!=generation||stopped||texture==null)return;Panel destination=findPanel(opening,true);if(destination!=null)showDestination(destination,texture,ticket);});
        }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.of(R.string.destination_failed,UiText.error(e)));});}});
    }
    private void showDestination(Panel panel,FrameTexture texture,int ticket){
        Runnable ready=()->{frozen.put(panel.inner,texture);framesReady(ticket,panel.inner);};
        Layer layer=duoEffect?layers.stream().filter(l->l.displayId==panel.display.getDisplayId()&&l.committed)
            .max(Comparator.comparingLong(l->l.serial)).orElse(null):null;
        if(layer==null){addLayer(panel,texture,false,false,ticket,ready);return;}
        if(!panel.inner&&!outerPortraitReady&&outerDarkness==1){
            // A reversal may leave an old snapshot beneath the opaque native cover.
            // Commit the fresh portrait directly before allowing its hinge reveal.
            trace("destination-frame-covered:cover");layer.view.setFrame(texture);layer.view.setLayoutMask(0);
            layer.view.afterFrame(()->main.post(()->{if(!stopped&&ticket==generation&&layers.contains(layer))ready.run();}));
            return;
        }
        // Blend pixels inside the already-visible surface. A new SurfaceView would
        // expose the resized app for frames before its commit callback can fade it.
        trace("destination-layout-blending:"+(panel.inner?"inner":"cover"));layer.view.setLayoutBlend(texture,0);
        // ponytail: screenshot masking cannot animate an app's individual views;
        // exchange layouts under frost, then reveal the already-resized app.
        ValueAnimator blend=ValueAnimator.ofFloat(0,1);blend.setDuration(300);
        blend.addUpdateListener(animation->{
            if(stopped||ticket!=generation||!layers.contains(layer)){animation.cancel();return;}
            float progress=(float)animation.getAnimatedValue();
            layer.view.setLayoutBlend(texture,Math.min(1,progress*2));
            layer.view.setLayoutMask(1-Math.max(0,progress*2-1));
        });
        blend.addListener(new AnimatorListenerAdapter(){@Override public void onAnimationEnd(Animator animation){
            if(stopped||ticket!=generation||!layers.contains(layer))return;
            layer.view.setFrame(texture);layer.view.setLayoutMask(0);
            layer.view.afterFrame(()->main.post(()->{if(!stopped&&ticket==generation&&layers.contains(layer))ready.run();}));
        }});
        blend.start();
    }
    private void framesReady(int ticket,boolean inner){
        Runnable ready=()->{if(stopped||ticket!=generation)return;linkFrames();busy=false;trace("paired-frames-ready");scheduleFrame();};
        // Reveal the cover only after both its portrait geometry and pixels are ready.
        if(duoEffect&&!inner)outerPortraitReady=true;
        ready.run();
    }
    private void addLayer(Panel panel,FrameTexture frame,boolean sharpHold,boolean pendingLayout,int ticket,Runnable ready){
        if(frame==null)return;
        try{
            Context context=createDisplayContext(panel.display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            WindowManager wm=context.getSystemService(WindowManager.class);
            SnapshotView view=new SnapshotView(context,frame,panel.inner,false);view.duoEffect=duoEffect;view.setBlurStrength(blurStrength);view.logicalWidth=panel.w;view.setSharpHold(sharpHold);
            view.setLayoutMask(pendingLayout?1:0);
            if(!panel.inner)view.setRearFrame(frozen.get(true),false);
            Layer[] created=new Layer[1];SnapshotSurface root=new SnapshotSurface(context,view,()->main.post(()->{
                Layer layer=created[0];if(ticket!=generation||stopped||!layers.contains(layer))return;
                if(duoEffect&&!panel.inner){
                    IShellBridge bridge=bound;SurfaceControl surface=layer.root.getSurfaceControl();
                    controls.execute(()->{try{
                        if(stopped||ticket!=generation)return;
                        Bundle result=bridge.excludeFromMirror(surface);
                        if(!result.getBoolean("ok"))throw new IllegalStateException(result.getString("error"));
                        main.post(()->commitLayer(layer,ticket,ready));
                    }catch(Exception e){main.post(()->{if(ticket==generation)fail(UiText.error(e));});}});
                }else commitLayer(layer,ticket,ready);
            }));
            WindowManager.LayoutParams lp=snapshotLayout();
            Layer layer=new Layer(wm,view,root,panel.display.getDisplayId(),++layerSerial);created[0]=layer;layers.add(layer);
            wm.addView(root,lp);view.setAngle(Float.isFinite(smoothed)?smoothed:target);
            // A missing frame callback must never leave a permanent frozen screen.
            main.postDelayed(()->{if(ticket==generation&&layers.contains(layer)&&!layer.committed)fail(UiText.of(R.string.frozen_draw_failed));},1400);
        }catch(Exception e){fail(UiText.of(R.string.overlay_failed,UiText.error(e)));}
    }
    private void commitLayer(Layer layer,int ticket,Runnable ready){
        if(stopped||ticket!=generation||!layers.contains(layer)||layer.committed)return;
        // A late callback from an older frame must not remove its successor.
        if(layers.stream().anyMatch(l->l.displayId==layer.displayId&&l.serial>layer.serial&&l.committed)){removeLayer(layer);return;}
        layer.committed=true;
        for(Layer old:new ArrayList<>(layers))if(old.displayId==layer.displayId&&old.serial<layer.serial)removeLayer(old);
        ready.run();
    }
    private void linkFrames(){
        FrameTexture inner=frozen.get(true);
        if(inner!=null)for(Layer layer:layers)if(!layer.view.inner)layer.view.setRearFrame(inner,true);
    }
    static WindowManager.LayoutParams snapshotLayout(){
        WindowManager.LayoutParams lp=layout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);
        // Android caps non-touchable APPLICATION_OVERLAY windows to alpha 0.8. A
        // frozen image must instead consume touches until removal, keeping alpha 1.
        // The angle-only anchors remain non-touchable and transparent below.
        lp.alpha=1;lp.setTitle("Folduo fold snapshot");lp.preferredRefreshRate=120;lp.windowAnimations=0;return lp;
    }
    private static WindowManager.LayoutParams layout(int w,int h){
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        lp.gravity=Gravity.TOP|Gravity.LEFT;lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;lp.setFitInsetsTypes(0);return lp;
    }
    private void scheduleFrame(){if(!frameScheduled&&!stopped&&!paused){frameScheduled=true;Choreographer.getInstance().postFrameCallback(this);}}
    private void refreshTuning(){
        blurStrength=MotionSettings.blurStrength(MotionSettings.preset(this,"blur"));
        responseSeconds=MotionSettings.responseSeconds(MotionSettings.preset(this,"response"));
        outerFadeStart=MotionSettings.fadeStart(MotionSettings.preset(this,"fade"));
        if(workspace!=null)workspace.setBlurStrength(blurStrength);
    }
    @Override public void doFrame(long nanos){
        frameScheduled=false;if(stopped||paused||finishing||!Float.isFinite(target))return;
        float dt=lastFrame==0?1/80f:(nanos-lastFrame)/1e9f;lastFrame=nanos;
        smoothed=FoldPolicy.smooth(smoothed,target,dt,responseSeconds);if(Math.abs(smoothed-target)<.015f)smoothed=target;
        for(Layer layer:layers)layer.view.setAngle(smoothed);
        boolean shading=updateOuter(dt);
        if(smoothed!=target||shading)scheduleFrame();
    }
    private boolean updateOuter(float dt){
        if(!duoEffect||!outerFollowsHinge)return false;
        // The angle sets opacity; readiness may only keep the cover darker. Never
        // reveal the wide app while waiting for its portrait frame on a reversal.
        float desired=outerPortraitReady?FoldPolicy.outerDarkness(smoothed,outerFadeStart):1;
        float next=FoldPolicy.smooth(outerDarkness,desired,dt,responseSeconds);
        if(Math.abs(next-desired)<.001f)next=desired;
        Runnable ready=null;
        if(next==1&&afterOuterBlack!=null){
            ready=afterOuterBlack;afterOuterBlack=null;
            // Hold black after committing to a resize, including the interval while
            // its destination frame is still being attached.
            outerPortraitReady=false;
        }
        if(next!=outerDarkness||outerCommandGeneration!=generation||ready!=null)sendOuterDarkness(next,generation,ready);
        return next!=desired;
    }
    private void finish(){finishWhenReady(generation,0);}
    private void finishWhenReady(int expected,int attempt){
        if(stopped||expected!=generation)return;
        if(busy){if(attempt>=30){fail(UiText.of(R.string.move_timeout));return;}main.postDelayed(()->finishWhenReady(expected,attempt+1),40);return;}
        int ticket=++generation;busy=false;finishing=true;boolean inner=policy.open;float endpoint=inner?180:0;
        trace("endpoint-covering");
        for(Layer layer:layers){layer.view.setSharpHold(false);layer.view.setAngle(endpoint);}
        Layer cover=layers.stream().filter(l->l.committed&&l.view.inner==inner).findFirst().orElse(null);
        java.util.concurrent.atomic.AtomicBoolean revealed=new java.util.concurrent.atomic.AtomicBoolean();
        Runnable reveal=()->{if(!revealed.compareAndSet(false,true))return;awaitFinal(ticket,inner,0);};
        if(cover!=null)cover.view.afterFrame(()->main.post(()->{if(ticket==generation){trace("endpoint-frame-committed");reveal.run();}}));
        else reveal.run();
        main.postDelayed(()->{if(ticket==generation)reveal.run();},500);
    }
    private void awaitFinal(int ticket,boolean inner,int attempt){
        if(stopped||ticket!=generation)return;rebuildPanels();Panel destination=findPanel(inner,true);
        if(destination==null){if(attempt>=35){fail(UiText.of(R.string.app_destination_unconfirmed));return;}main.postDelayed(()->awaitFinal(ticket,inner,attempt+1),40);return;}
        awaitApp(ticket,destination,()->{
            Runnable reveal=()->{
                if(stopped||ticket!=generation)return;
                trace("handoff-with-stable-panels");
                // Keep the outgoing portrait opaque until the independent black layer
                // covers it. Fading both exposes the wide live app between the layers.
                for(Layer layer:layers)if(!duoEffect||!inner||layer.view.inner)layer.root.animate().alpha(0).setDuration(180).setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator()).start();
                boolean[] settled={false,!duoEffect};
                Runnable done=()->{if(stopped||ticket!=generation||!settled[0]||!settled[1])return;removeLayers();frozen.clear();finishing=false;smoothed=target;restoreStatusIcons();trace("idle");updateNavigation();};
                if(duoEffect)sendOuterDarkness(inner?1:0,ticket,()->{settled[1]=true;done.run();});
                main.postDelayed(()->{
                    if(stopped||ticket!=generation)return;
                    for(Layer layer:new ArrayList<>(layers))if(!duoEffect||!inner||layer.view.inner)removeLayer(layer);
                    settled[0]=true;done.run();
                },200);
            };
            FrameTexture cover=frozen.get(false);
            if(duoEffect&&!inner&&workspace!=null&&cover!=null){
                try{workspace.cover(cover.sharp,reveal);}catch(Exception e){fail(UiText.error(e));}
            }else reveal.run();
        });
    }
    private void sendOuterDarkness(float amount,int ticket,Runnable done){
        IShellBridge bridge=bound;outerDarkness=amount;outerCommandGeneration=ticket;
        controls.execute(()->{try{
            if(stopped||ticket!=generation)return;
            Bundle result=bridge.outerDarkness(amount);
            if(!result.getBoolean("ok"))throw new IllegalStateException(result.getString("error"));
            // Acknowledge composition before resizing or removing the covered frame.
            if(done!=null)main.postDelayed(()->{if(!stopped&&ticket==generation)done.run();},34);
        }catch(Exception e){main.post(()->{if(!stopped&&ticket==generation)fail(UiText.error(e));});}});
    }
    private void cancelSession(){
        ++generation;++sessionSerial;busy=false;finishing=false;layoutPrepared=layoutPreparing=layoutRecovering=false;removeNavigation();removeLayers();frozen.clear();
        afterOuterBlack=null;outerFollowsHinge=false;outerPortraitReady=true;outerDarkness=0;outerCommandGeneration=-1;
        mirrorReady=false;checkingMirror=false;if(workspace!=null){workspace.close();workspace=null;}
        if(policy!=null)policy.active=false;
        IShellBridge bridge=bound;if(bridge!=null)controls.execute(()->{try{bridge.release();}catch(Exception ignored){}});
    }
    private void restoreStatusIcons(){IShellBridge bridge=bound;if(bridge!=null)controls.execute(()->{try{bridge.statusIcons(false);}catch(Exception ignored){}});}
    private void updateNavigation(){
        Panel inner=findPanel(true,true);
        boolean show=!stopped&&!paused&&layoutPrepared&&mirrorReady&&!busy&&!finishing&&policy!=null&&!policy.active&&target>=(source==HardwareAngle.SOURCE?170:176)&&inner!=null&&inner.display.getDisplayId()==1;
        if(!show){removeNavigation();return;}
        if(navigation!=null&&navigation.width==inner.w&&navigation.height==inner.h)return;
        removeNavigation();
        try{
            Context context=createDisplayContext(inner.display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            navigation=new InnerNavigation(context,1,inner.w,inner.h,this::navigate);
        }catch(Exception e){navigationError=ShellBridge.message(e);}
    }
    private void navigate(int action,int taskId){
        IShellBridge bridge=bound;InnerNavigation owner=navigation;int session=sessionSerial;
        if(bridge==null||owner==null||busy||finishing)return;
        controls.execute(()->{try{
            if(stopped||session!=sessionSerial)return;
            Bundle result=bridge.navigate(0,action,taskId);
            if(BuildConfig.DEBUG)android.util.Log.i("FolduoNavigation","result action="+action+" ok="+result.getBoolean("ok")+" error="+result.getString("error",""));
            main.post(()->{
                if(stopped||session!=sessionSerial||navigation!=owner)return;
                if(!result.getBoolean("ok")){navigationError=result.getString("error");android.widget.Toast.makeText(this,getString(R.string.navigation_failed,UiText.raw(navigationError).resolve(this)),android.widget.Toast.LENGTH_SHORT).show();return;}
                navigationError="";
                if(action==KeyEvent.KEYCODE_APP_SWITCH){
                    ArrayList<Bundle> apps=result.getParcelableArrayList("apps",Bundle.class);
                    owner.showRecent(apps==null?List.of():apps);
                    if(apps!=null)loadRecentPreviews(owner,apps,session);
                }
            });
        }catch(Exception e){main.post(()->{navigationError=ShellBridge.message(e);});}});
    }
    private void loadRecentPreviews(InnerNavigation owner,List<Bundle> apps,int session){
        IShellBridge bridge=bound;
        controls.execute(()->{
            for(Bundle app:apps){
                if(stopped||session!=sessionSerial||navigation!=owner||!owner.showingRecents())return;
                try{
                    int task=app.getInt("taskId",-1);
                    Bundle result=bridge.navigate(0,InnerNavigation.PREVIEW,task);
                    Bitmap image=result.getParcelable("preview",Bitmap.class);
                    if(image!=null)main.post(()->{if(!stopped&&session==sessionSerial&&navigation==owner)owner.setPreview(task,image);});
                }catch(Exception ignored){} // An unavailable/protected preview leaves the app icon.
            }
        });
    }
    private void removeNavigation(){if(navigation!=null){try{navigation.close();}catch(Exception ignored){}navigation=null;}}
    private void fail(UiText reason){trace("cancelled: "+reason.resolve(this));cancelSession();blockedUntilEndpoint=true;retryAt=SystemClock.elapsedRealtime()+2000;recordRecovery(reason);status=UiText.of(R.string.failure_retry,reason);}
    private Panel panelById(int id){for(Panel p:panels)if(p.display.getDisplayId()==id)return p;return null;}
    private Panel findPanel(boolean inner,boolean on){for(Panel p:panels)if(p.inner==inner&&(!on||p.display.getState()==Display.STATE_ON))return p;return null;}
    private void rebuildPanels(){
        if(stopped||paused)return;
        List<Panel> discovered=new ArrayList<>();StringBuilder signature=new StringBuilder();
        for(Display display:displays.getDisplays()){
            if(display.getDisplayId()>1)continue; // Fold7 built-in logical displays only.
            // getRealSize can inherit the process activity's max bounds after that activity
            // moves to the secondary panel. Mode dimensions remain tied to this display.
            Display.Mode mode=display.getMode();int rotation=display.getRotation();
            boolean rotated=rotation==Surface.ROTATION_90||rotation==Surface.ROTATION_270;
            Point size=new Point(rotated?mode.getPhysicalHeight():mode.getPhysicalWidth(),rotated?mode.getPhysicalWidth():mode.getPhysicalHeight());if(size.x<=0||size.y<=0)continue;
            boolean inner=Math.min(size.x,size.y)/(float)Math.max(size.x,size.y)>.7f;
            discovered.add(new Panel(display,inner,size.x,size.y));signature.append(display.getDisplayId()).append(':').append(size.x).append(':').append(size.y).append(':').append(display.getState()).append(';');
        }
        panels.clear();panels.addAll(discovered);
        if(panelSignature.equals(signature.toString())&&!anchors.isEmpty())return;
        panelSignature=signature.toString();
        // Window contexts may stay attached to logical display IDs across a physical swap.
        removeAnchors();List<Anchor> next=new ArrayList<>();
        if(Settings.canDrawOverlays(this))for(Panel panel:panels){
            if(panel.display.getState()!=Display.STATE_ON)continue;
            try{
                Context context=createDisplayContext(panel.display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                WindowManager wm=context.getSystemService(WindowManager.class);View view=new View(context);view.setBackgroundColor(Color.TRANSPARENT);
                WindowManager.LayoutParams lp=layout(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT);lp.flags|=WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;lp.alpha=.01f;lp.setTitle("Folduo angle anchor");
                wm.addView(view,lp);next.add(new Anchor(wm,view,WallpaperManager.getInstance(context)));
            }catch(Exception e){anchorError=ShellBridge.message(e);}
        }
        anchors=List.copyOf(next);
    }
    private void removeAnchors(){List<Anchor> previous=anchors;anchors=List.of();for(Anchor a:previous)try{a.wm.removeViewImmediate(a.view);}catch(Exception ignored){}}
    private void removeLayer(Layer layer){layers.remove(layer);layer.root.animate().cancel();try{layer.wm.removeViewImmediate(layer.root);}catch(Exception ignored){}}
    private void removeLayers(){for(Layer layer:new ArrayList<>(layers))removeLayer(layer);}
    @Override protected void dump(FileDescriptor fd,PrintWriter out,String[] args){
        out.println("duoEffect="+duoEffect);
        out.println("blurStrength="+blurStrength+" responseSeconds="+responseSeconds+" outerFadeStart="+outerFadeStart);
        out.println("running="+running+" paused="+paused+" unlocked="+unlocked()+" status="+status.resolve(this));
        out.println("enabled="+MotionSettings.enabled(this)+" recoveries="+recoveries+" lastRecovery="+lastRecovery.resolve(this));
        out.println("source="+source+" target="+target+" smoothed="+smoothed+" ageMs="+(SystemClock.elapsedRealtime()-measuredAt)+" accepted="+acceptedAngles);
        out.println("busy="+busy+" blocked="+blockedUntilEndpoint+" panels="+panelSignature+" anchors="+anchors.size()+" layers="+layers.size()+" anchorError="+anchorError);
        out.println("fixedPrimaryInner="+fixedPrimaryInner+" layoutPrepared="+layoutPrepared+" layoutPreparing="+layoutPreparing+" layoutRecovering="+layoutRecovering);
        out.println("innerNavigation="+(navigation!=null)+" navigationError="+navigationError);
        out.println("mirrorReady="+mirrorReady+" workspace="+(workspace!=null));
        out.println("stage="+stage+" history="+String.join(",",handoffs));
        synchronized(angleHistory){out.println("angles="+String.join(",",angleHistory));}
        IShellBridge bridge=bound;if(bridge!=null)try{Bundle report=bridge.inspect();out.println("statusIconsHidden="+report.getBoolean("statusIconsHidden"));out.println("routingInnerLaunches="+report.getBoolean("routingInnerLaunches")+" launchRecoveries="+report.getInt("launchRecoveries")+" launchError="+report.getString("launchRecoveryError",""));out.println(MainActivity.formatReport(this,report));}catch(Exception e){out.println(ShellBridge.message(e));}
    }
    public void onDisplayAdded(int id){rebuildPanels();recoverLayoutIfNeeded();}public void onDisplayRemoved(int id){rebuildPanels();recoverLayoutIfNeeded();}public void onDisplayChanged(int id){rebuildPanels();recoverLayoutIfNeeded();}
    @Override public void onDestroy(){
        stopped=true;running=false;status=UiText.of(R.string.stopped);main.removeCallbacksAndMessages(null);Choreographer.getInstance().removeFrameCallback(this);
        displays.unregisterDisplayListener(this);unregisterReceiver(power);cancelSession();removeAnchors();poller.shutdownNow();
        IShellBridge bridge=bound;bound=null;controls.execute(()->{try{if(bridge!=null){BridgeConnection.stopAngles(MotionService.this,bridge);bridge.release();}}catch(Exception ignored){}});jobs.shutdown();controls.shutdown();BridgeConnection.disconnect();super.onDestroy();
    }
}
