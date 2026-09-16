package jp.bunkaich.sukashimotion;

import android.content.*;
import android.os.*;
import java.util.concurrent.*;
import rikka.shizuku.Shizuku;

final class BridgeConnection {
    static volatile IShellBridge bridge;
    static volatile UiText status=UiText.of(R.string.bridge_waiting);
    static final ExecutorService work=Executors.newSingleThreadExecutor();
    private static final ScheduledExecutorService pulse=Executors.newSingleThreadScheduledExecutor();
    private static volatile boolean binding;private static boolean initialized;
    private static Object angleOwner;
    private static long bindingAt,nextAttempt;private static int failures;
    private static Shizuku.UserServiceArgs args;
    private static final ServiceConnection connection=new ServiceConnection(){
        public void onServiceConnected(ComponentName name,IBinder binder){synchronized(BridgeConnection.class){bridge=IShellBridge.Stub.asInterface(binder);status=UiText.of(R.string.bridge_connected);binding=false;failures=0;nextAttempt=0;}}
        public void onServiceDisconnected(ComponentName name){synchronized(BridgeConnection.class){bridge=null;angleOwner=null;status=UiText.of(R.string.bridge_reconnecting);binding=false;nextAttempt=0;}}
    };
    static synchronized void init(Context context){
        if(initialized)return;initialized=true;
        args=new Shizuku.UserServiceArgs(new ComponentName(context,ShellBridge.class)).daemon(false).processNameSuffix("motion_bridge").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderDeadListener(()->{synchronized(BridgeConnection.class){bridge=null;angleOwner=null;binding=false;nextAttempt=0;status=UiText.of(R.string.bridge_start_shizuku);}});
        pulse.scheduleWithFixedDelay(()->{IShellBridge b=bridge;if(b!=null)try{b.heartbeat();}catch(Exception e){synchronized(BridgeConnection.class){if(bridge==b){bridge=null;angleOwner=null;binding=false;status=UiText.of(R.string.bridge_reconnecting);}}}},0,1,TimeUnit.SECONDS);
    }
    static boolean permitted(){try{return Shizuku.pingBinder()&&Shizuku.checkSelfPermission()==0;}catch(Exception e){return false;}}
    static synchronized void connect(Context context){
        init(context);if(bridge!=null)return;long now=SystemClock.elapsedRealtime();
        if(binding&&now-bindingAt<10000)return;
        if(binding){try{Shizuku.unbindUserService(args,connection,false);}catch(Exception ignored){}binding=false;}
        if(now<nextAttempt)return;
        nextAttempt=now+Math.min(15000,1000L<<Math.min(failures++,4));
        if(!permitted()){status=UiText.of(R.string.bridge_permission);return;}
        try{binding=true;bindingAt=now;status=UiText.of(R.string.bridge_connecting);Shizuku.bindUserService(args,connection);}catch(Exception e){binding=false;status=UiText.error(e);}
    }
    static void disconnect(){
        work.execute(()->{synchronized(BridgeConnection.class){try{if(args!=null)Shizuku.unbindUserService(args,connection,true);}catch(Exception ignored){}finally{bridge=null;angleOwner=null;binding=false;nextAttempt=0;failures=0;}}});
    }
    static synchronized boolean startAngles(Object owner,IShellBridge target,IAngleSink sink,boolean replace)throws RemoteException{
        if(!replace&&angleOwner!=null&&angleOwner!=owner)return false;
        angleOwner=owner;
        try{target.startAngles(sink);return true;}catch(RemoteException e){if(angleOwner==owner)angleOwner=null;throw e;}
    }
    static synchronized void stopAngles(Object owner,IShellBridge target)throws RemoteException{
        if(angleOwner!=owner)return;
        try{target.stopAngles();}finally{angleOwner=null;}
    }
    static synchronized boolean ownsAngles(Object owner){return angleOwner==owner;}
}
