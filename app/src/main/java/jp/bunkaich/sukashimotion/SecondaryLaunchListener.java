package jp.bunkaich.sukashimotion;

import android.app.ActivityManager;
import android.os.*;
import java.lang.reflect.Field;
import java.util.function.IntConsumer;

/** Receives only the system's explicit failed/rerouted secondary-display launches. */
final class SecondaryLaunchListener implements AutoCloseable {
    private final Object manager,listener;
    private final Class<?> api,listenerType;
    private volatile boolean closed;
    SecondaryLaunchListener(IntConsumer blocked)throws Exception{
        api=Class.forName("android.app.IActivityTaskManager");
        manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        listenerType=Class.forName("android.app.ITaskStackListener");
        Class<?> stub=Class.forName("android.app.ITaskStackListener$Stub");
        int failed=transaction(stub,"onActivityLaunchOnSecondaryDisplayFailed");
        int rerouted=transaction(stub,"onActivityLaunchOnSecondaryDisplayRerouted");
        Binder transport=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags){
                if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString("android.app.ITaskStackListener");return true;}
                if(closed||Binder.getCallingUid()!=android.os.Process.SYSTEM_UID)return false;
                if(code==failed||code==rerouted){int task=blockedTask(data,Binder.getCallingUid());if(task>=0)blocked.accept(task);}
                return true;
            }
        };
        listener=stub.getMethod("asInterface",IBinder.class).invoke(null,transport);
        api.getMethod("registerTaskStackListener",listenerType).invoke(manager,listener);
    }
    private static int transaction(Class<?> stub,String name)throws Exception{
        Field field=stub.getDeclaredField("TRANSACTION_"+name);field.setAccessible(true);return field.getInt(null);
    }
    static int blockedTask(Parcel data,int caller){
        if(caller!=android.os.Process.SYSTEM_UID)return -1;
        try{
            data.enforceInterface("android.app.ITaskStackListener");
            ActivityManager.RunningTaskInfo task=data.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
            int requested=data.readInt();data.enforceNoDataAvail();
            return requested==1&&task!=null&&task.taskId>=0?task.taskId:-1;
        }catch(RuntimeException malformed){return -1;}
    }
    @Override public void close()throws Exception{
        closed=true;api.getMethod("unregisterTaskStackListener",listenerType).invoke(manager,listener);
    }
}
