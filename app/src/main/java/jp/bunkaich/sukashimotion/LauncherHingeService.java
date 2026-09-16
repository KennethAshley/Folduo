package jp.bunkaich.sukashimotion;

import android.app.Service;
import android.content.Intent;
import android.os.*;

/** Signature-protected, read-only hinge feed for the paired launcher. */
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

    static final class FeedState {
        private boolean available,publishedUnavailable;
        boolean needsInitial(){return !available;}
        void sample(){available=true;publishedUnavailable=false;}
        boolean unavailable(){boolean notify=available||!publishedUnavailable;available=false;publishedUnavailable=true;return notify;}
        void initialSent(){publishedUnavailable=true;}
    }

    private RemoteCallbackList<IAngleSink> clientList(){
        return new RemoteCallbackList<>(){
            @Override public void onCallbackDied(IAngleSink callback){main.post(LauncherHingeService.this::refresh);}
        };
    }

    private final ILauncherHinge.Stub binder=new ILauncherHinge.Stub(){
        @Override public boolean registerListener(IAngleSink listener){
            enforceLauncher();
            if(listener==null||RevealService.running||MotionService.running)return false;
            boolean added; synchronized(clientLock){added=clients.register(listener);}
            if(added){BridgeConnection.work.execute(()->initialUnavailable(listener));main.post(LauncherHingeService.this::refresh);}
            return added;
        }
        @Override public void unregisterListener(IAngleSink listener){
            enforceLauncher();
            if(listener==null)return;
            synchronized(clientLock){clients.unregister(listener);}
            main.post(LauncherHingeService.this::refresh);
        }
    };

    @Override public void onCreate(){super.onCreate();BridgeConnection.init(this);main.post(health);}
    @Override public IBinder onBind(Intent intent){return binder;}
    @Override public boolean onUnbind(Intent intent){
        synchronized(clientLock){RemoteCallbackList<IAngleSink> old=clients;clients=clientList();old.kill();}
        main.post(this::refresh);return false;
    }

    private void enforceLauncher(){
        enforceCallingPermission(PERMISSION,"Caller lacks launcher hinge permission");
        if(!ownsLauncher(getPackageManager().getPackagesForUid(Binder.getCallingUid())))throw new SecurityException("Unexpected launcher hinge caller");
    }
    static boolean ownsLauncher(String[] packages){
        if(packages!=null)for(String name:packages)if(LAUNCHER_PACKAGE.equals(name))return true;
        return false;
    }
    static boolean available(boolean revealRunning,boolean motionRunning){return !revealRunning&&!motionRunning;}
    static boolean isReaderTermination(float degrees,int source){return source==FEED_UNAVAILABLE&&!Float.isFinite(degrees);}

    private boolean hasClients(){synchronized(clientLock){return clients.getRegisteredCallbackCount()>0;}}
    private final Runnable health=new Runnable(){@Override public void run(){
        refresh();if(!stopped)main.postDelayed(this,500);
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
                BridgeConnection.work.execute(this::unavailable);
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
        BridgeConnection.work.execute(()->{unavailable();if(previous!=null)try{BridgeConnection.stopAngles(LauncherHingeService.this,previous);}catch(Exception ignored){}});
    }
    @Override public void onDestroy(){
        stopped=true;main.removeCallbacksAndMessages(null);detach();
        synchronized(clientLock){clients.kill();}
        super.onDestroy();
    }
}
