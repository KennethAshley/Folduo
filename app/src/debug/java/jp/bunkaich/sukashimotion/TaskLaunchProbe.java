package jp.bunkaich.sukashimotion;

import android.app.ActivityManager;
import android.os.*;
import java.lang.reflect.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded read-only shell probe of Samsung's task-launch callbacks. */
public final class TaskLaunchProbe {
    public static void main(String[] args)throws Exception {
        Looper.prepareMainLooper();
        Class<?> api=Class.forName("android.app.IActivityTaskManager");
        Object manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        Class<?> listenerType=Class.forName("android.app.ITaskStackListener");
        Class<?> stub=Class.forName("android.app.ITaskStackListener$Stub");
        Map<Integer,String> names=new ConcurrentHashMap<>();
        for(Field field:stub.getDeclaredFields())if(field.getName().startsWith("TRANSACTION_")){
            field.setAccessible(true);names.put(field.getInt(null),field.getName().substring(12));
        }
        Map<String,Integer> counts=new ConcurrentHashMap<>();
        Binder transport=new Binder(){
            @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
                if(code==INTERFACE_TRANSACTION){if(reply!=null)reply.writeString("android.app.ITaskStackListener");return true;}
                if(Binder.getCallingUid()!=android.os.Process.SYSTEM_UID)return false;
                data.enforceInterface("android.app.ITaskStackListener");
                String name=names.getOrDefault(code,"unknown"+code);counts.merge(name,1,Integer::sum);
                if(name.equals("onActivityLaunchOnSecondaryDisplayFailed")||name.equals("onActivityLaunchOnSecondaryDisplayRerouted")){
                    ActivityManager.RunningTaskInfo task=data.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
                    int requested=data.readInt();
                    System.out.println("LAUNCH_EVENT "+name+" task="+(task==null?-1:task.taskId)+" requested="+requested);
                }
                if(name.equals("onTaskMovedToFront")){
                    ActivityManager.RunningTaskInfo task=data.readTypedObject(ActivityManager.RunningTaskInfo.CREATOR);
                    if(task!=null)try{
                        android.content.ComponentName top=(android.content.ComponentName)task.getClass().getField("topActivity").get(task);
                        if(top!=null&&(top.getPackageName().equals("com.sec.android.app.popupcalculator")||top.getPackageName().equals("de.mm20.launcher2.release")))
                            System.out.println("TASK_FRONT at="+SystemClock.elapsedRealtime()+" task="+task.taskId+" display="+task.getClass().getField("displayId").getInt(task)+" app="+top.getPackageName());
                    }catch(ReflectiveOperationException error){System.out.println("TASK_FRONT_READ_ERROR "+error.getClass().getSimpleName());}
                }
                return true;
            }
        };
        Object listener=stub.getMethod("asInterface",IBinder.class).invoke(null,transport);
        api.getMethod("registerTaskStackListener",listenerType).invoke(manager,listener);
        System.out.println("LISTENING_FOR_LAUNCH_CALLBACKS "+names);
        try{Thread.sleep(120000);}
        finally{api.getMethod("unregisterTaskStackListener",listenerType).invoke(manager,listener);System.out.println("CALLBACK_COUNTS "+counts);}
        System.exit(0);
    }
}
