package jp.bunkaich.sukashimotion;

import android.app.Service;
import android.content.Intent;
import android.os.*;

/** Paired hinge feed. Only DuoFold can opt into a foreground display lease. */
public final class LauncherHingeService extends Service {
    static final String LAUNCHER_PACKAGE="de.mm20.launcher2.fold8";
    static final int FEED_UNAVAILABLE=ShellBridge.READER_UNAVAILABLE; // Launcher-only sentinel; degrees are ignored.
    private static final String PERMISSION="jp.bunkaich.sukashimotion.permission.LAUNCHER_HINGE";
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Object clientLock=new Object();
    private volatile RemoteCallbackList<IAngleSink> clients=clientList();
    private volatile IShellBridge bound;
    private volatile boolean reading,stopped;
    private volatile int generation;
    private final FeedState feed=new FeedState();
    // Session fields are confined to BridgeConnection.work, like reader delivery.
    private IBinder smoothOwner;
    private IShellBridge smoothBridge;
    private SmoothHomeLease smoothLease;

    static final class FeedState {
        private boolean available,publishedUnavailable;
        boolean needsInitial(){return !available;}
        void sample(){available=true;publishedUnavailable=false;}
        boolean unavailable(){boolean notify=available||!publishedUnavailable;available=false;publishedUnavailable=true;return notify;}
        void initialSent(){publishedUnavailable=true;}
    }

    private RemoteCallbackList<IAngleSink> clientList(){
        return new RemoteCallbackList<>(){
            @Override public void onCallbackDied(IAngleSink callback){
                BridgeConnection.work.execute(()->stopSmooth(callback.asBinder()));
                main.post(LauncherHingeService.this::refresh);
            }
        };
    }

    private final ILauncherHinge.Stub binder=new ILauncherHinge.Stub(){
        @Override public boolean registerListener(IAngleSink listener){
            enforceLauncher();
            if(listener==null||RevealService.running||MotionService.running)return false;
            boolean added; synchronized(clientLock){added=clients.register(listener,Binder.getCallingUid());}
            if(added){BridgeConnection.work.execute(()->initialUnavailable(listener));main.post(LauncherHingeService.this::refresh);}
            return added;
        }
        @Override public void unregisterListener(IAngleSink listener){
            enforceLauncher();
            if(listener==null)return;
            BridgeConnection.work.execute(()->stopSmooth(listener.asBinder()));
            synchronized(clientLock){clients.unregister(listener);}
            main.post(LauncherHingeService.this::refresh);
        }
        @Override public Bundle startSmoothHome(IAngleSink listener){
            int uid=enforceSmoothHome();
            return sessionCall(()->startSmooth(listener,uid));
        }
        @Override public Bundle renewSmoothHome(IAngleSink listener){
            enforceSmoothHome();
            return sessionCall(()->{
                if(listener==null||!listener.asBinder().equals(smoothOwner))return sessionResult(false,"Smooth Home has stopped.");
                if(smoothLease.expired(SystemClock.elapsedRealtime())){stopSmooth(null);return sessionResult(false,"Smooth Home paused. Start again when ready.");}
                smoothLease.renew(SystemClock.elapsedRealtime());
                checkSmooth();
                return sessionResult(smoothOwner!=null,"Smooth Home ended. Start again when ready.");
            });
        }
        @Override public void stopSmoothHome(IAngleSink listener){
            enforceSmoothHome();
            if(listener!=null)sessionCall(()->{stopSmooth(listener.asBinder());return sessionResult(false,"");});
        }
    };

    @Override public void onCreate(){super.onCreate();BridgeConnection.init(this);main.post(health);}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public boolean onUnbind(Intent intent){
        BridgeConnection.work.execute(()->stopSmooth(null));
        synchronized(clientLock){RemoteCallbackList<IAngleSink> old=clients;clients=clientList();old.kill();}
        main.post(this::refresh);return false;
    }

    private void enforceLauncher(){
        enforceCallingPermission(PERMISSION,"Caller lacks launcher hinge permission");
        if(!ownsLauncher(getPackageManager().getPackagesForUid(Binder.getCallingUid())))throw new SecurityException("Unexpected launcher hinge caller");
    }
    static boolean ownsLauncher(String[] packages){
        if(packages!=null)for(String name:packages)if(LAUNCHER_PACKAGE.equals(name)||"com.example.duofold.fine".equals(name))return true;
        return false;
    }
    private int enforceSmoothHome(){
        enforceCallingPermission(PERMISSION,"Caller lacks launcher hinge permission");
        int uid=Binder.getCallingUid();
        if(!ownsSmoothHome(getPackageManager().getPackagesForUid(uid)))throw new SecurityException("Display control is restricted to paired DuoFold");
        return uid;
    }
    static boolean ownsSmoothHome(String[] packages){
        if(packages!=null)for(String name:packages)if("com.example.duofold.fine".equals(name))return true;
        return false;
    }
    private Bundle sessionCall(java.util.concurrent.Callable<Bundle> action){
        try{return BridgeConnection.work.submit(action).get(3,java.util.concurrent.TimeUnit.SECONDS);}
        catch(Exception e){BridgeConnection.work.execute(()->stopSmooth(null));return sessionResult(false,"Display helper unavailable. Check Shizuku and try again.");}
    }
    private static Bundle sessionResult(boolean active,String error){
        Bundle result=new Bundle();result.putBoolean("active",active);if(!active)result.putString("error",error);return result;
    }
    private boolean registered(IAngleSink listener,int uid){
        if(listener==null||!listener.asBinder().isBinderAlive())return false;
        synchronized(clientLock){
            for(int i=0;i<clients.getRegisteredCallbackCount();i++)
                if(listener.asBinder().equals(clients.getRegisteredCallbackItem(i).asBinder())&&Integer.valueOf(uid).equals(clients.getRegisteredCallbackCookie(i)))return true;
        }
        return false;
    }
    private boolean unlocked(){return !getSystemService(android.app.KeyguardManager.class).isKeyguardLocked();}
    private Bundle startSmooth(IAngleSink listener,int uid)throws Exception{
        if(!registered(listener,uid))return sessionResult(false,"Hinge reader is connecting. Try again.");
        if(smoothOwner!=null)return sessionResult(false,"A Smooth Home session is already active.");
        synchronized(BridgeConnection.class){
            IShellBridge b=bound;
            if(stopped||!reading||b==null||!BridgeConnection.ownsAngles(this)||!available(RevealService.running,MotionService.running))
                return sessionResult(false,"Hinge reader is not ready. Check Shizuku and keep other Folduo modes off.");
            Bundle state=b.inspect();
            if(!unlocked())return sessionResult(false,"Unlock the phone first.");
            if(!"CLOSED".equals(state.getString("baseState"))||state.getInt("currentState",-1)!=state.getInt("closedState",-2))
                return sessionResult(false,"Close the phone fully before starting.");
            smoothOwner=listener.asBinder();smoothBridge=b;smoothLease=new SmoothHomeLease(SystemClock.elapsedRealtime());
            Bundle held=b.hold(false,0);
            if(!held.getBoolean("ok")){stopSmooth(null);return sessionResult(false,"Samsung could not start both screens. Try again.");}
            android.util.Log.i("SmoothHome","Started by DuoFold");
            return sessionResult(true,"");
        }
    }
    private void checkSmooth(){
        if(smoothOwner==null)return;
        synchronized(BridgeConnection.class){
            try{
                if(stopped||!unlocked()||smoothLease.expired(SystemClock.elapsedRealtime())||!smoothOwner.isBinderAlive()
                    ||smoothBridge!=bound||!reading||!BridgeConnection.ownsAngles(this)||!available(RevealService.running,MotionService.running)){
                    stopSmooth(null);return;
                }
                Bundle state=smoothBridge.inspect();
                int action=smoothLease.observe("CLOSED".equals(state.getString("baseState")),
                    state.getInt("currentState",-1)==state.getInt("coverState",-2),SystemClock.elapsedRealtime());
                if(action==SmoothHomeLease.STOP)stopSmooth(null);
                else if(action==SmoothHomeLease.REARM){
                    if(!smoothBridge.hold(false,0).getBoolean("ok"))stopSmooth(null);
                    else android.util.Log.i("SmoothHome","Rearmed after physical close");
                }
            }catch(Exception e){stopSmooth(null);}
        }
    }
    private void stopSmooth(IBinder owner){
        if(smoothOwner==null||(owner!=null&&!owner.equals(smoothOwner)))return;
        IShellBridge previous=smoothBridge;smoothOwner=null;smoothBridge=null;smoothLease=null;
        synchronized(BridgeConnection.class){
            // An interactive Folduo service may have taken over. Never cancel its request.
            if(BridgeConnection.ownsAngles(this))try{previous.release();}catch(Exception ignored){}
        }
        android.util.Log.i("SmoothHome","Released display lease");
    }
    static boolean available(boolean revealRunning,boolean motionRunning){return !revealRunning&&!motionRunning;}
    static boolean isReaderTermination(float degrees,int source){return source==FEED_UNAVAILABLE&&!Float.isFinite(degrees);}

    private boolean hasClients(){synchronized(clientLock){return clients.getRegisteredCallbackCount()>0;}}
    private final Runnable health=new Runnable(){@Override public void run(){
        refresh();BridgeConnection.work.execute(LauncherHingeService.this::checkSmooth);if(!stopped)main.postDelayed(this,500);
    }};
    private void refresh(){
        if(stopped)return;
        if(!hasClients()||!available(RevealService.running,MotionService.running)){detach();return;}
        BridgeConnection.connect(this);IShellBridge next=BridgeConnection.bridge;
        if(next!=bound){detach();bound=next;}
        if(next!=null&&!reading)start(next);
    }
    private void start(IShellBridge bridge){
        reading=true;int ticket=++generation;
        IAngleSink relay=new IAngleSink.Stub(){@Override public void angle(float degrees,long measuredAt,int source){
            BridgeConnection.work.execute(()->forward(ticket,degrees,measuredAt,source));
        }};
        BridgeConnection.work.execute(()->{
            try{
                boolean started=!stopped&&ticket==generation&&bound==bridge&&hasClients()&&available(RevealService.running,MotionService.running)
                    &&BridgeConnection.startAngles(LauncherHingeService.this,bridge,relay,false);
                if(!started){unavailable();main.post(()->{if(ticket==generation)reading=false;});}
            }catch(Exception ignored){unavailable();main.post(()->{if(ticket==generation){reading=false;bound=null;}});}
        });
    }
    private void forward(int ticket,float degrees,long measuredAt,int source){
        if(stopped||ticket!=generation||!reading||!BridgeConnection.ownsAngles(this))return;
        if(isReaderTermination(degrees,source)){
            // The health loop also changes reader state; serialize invalidation with it.
            main.post(()->{
                if(stopped||ticket!=generation||!reading)return;
                ++generation;reading=false;
                BridgeConnection.work.execute(()->{stopSmooth(null);unavailable();});
            });
            return;
        }
        feed.sample();broadcast(degrees,measuredAt,source);
    }
    private void initialUnavailable(IAngleSink listener){
        if(!feed.needsInitial())return;
        try{listener.angle(0,SystemClock.elapsedRealtime(),FEED_UNAVAILABLE);}catch(RemoteException ignored){}finally{feed.initialSent();}
    }
    private void unavailable(){
        if(feed.unavailable())broadcast(0,SystemClock.elapsedRealtime(),FEED_UNAVAILABLE);
    }
    private void broadcast(float degrees,long measuredAt,int source){
        RemoteCallbackList<IAngleSink> list=clients;int count=list.beginBroadcast();
        try{for(int i=0;i<count;i++)try{list.getBroadcastItem(i).angle(degrees,measuredAt,source);}catch(RemoteException ignored){}}
        finally{list.finishBroadcast();}
    }
    private void detach(){
        IShellBridge previous=bound;if(previous==null&&!reading)return;
        bound=null;reading=false;++generation;
        BridgeConnection.work.execute(()->{stopSmooth(null);unavailable();if(previous!=null)try{BridgeConnection.stopAngles(LauncherHingeService.this,previous);}catch(Exception ignored){}});
    }
    @Override public void onDestroy(){
        stopped=true;main.removeCallbacksAndMessages(null);detach();
        BridgeConnection.work.execute(()->stopSmooth(null));
        synchronized(clientLock){clients.kill();}
        super.onDestroy();
    }
}
