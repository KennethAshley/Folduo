package jp.bunkaich.sukashimotion;

import android.content.*;
import android.hardware.display.*;
import android.media.projection.MediaProjection;
import android.os.*;
import android.view.Surface;
import java.util.List;
import java.util.concurrent.*;

/** Debug-only Shizuku check. Follows the foreground task for at most 60 seconds. */
public final class HomeMirrorProbe extends Binder {
    static final String DESCRIPTOR="jp.bunkaich.sukashimotion.HomeMirrorProbe";
    private final ScheduledExecutorService expiry=Executors.newSingleThreadScheduledExecutor();
    private final HandlerThread callbacks=new HandlerThread("bounded-home-mirror");
    private final Context context; private final int appUid;
    private MediaProjection projection; private VirtualDisplay output;
    private Object projectionManager,projectionToken;private IBinder launchCookie;
    private int mirroredTask=-1;private ScheduledFuture<?> following;private String failure="";
    private TaskDisplayRouter router;
    public HomeMirrorProbe()throws Exception{
        Class<?> at=Class.forName("android.app.ActivityThread");Object thread=at.getMethod("currentActivityThread").invoke(null);
        if(thread==null)thread=at.getMethod("systemMain").invoke(null);
        Context system=(Context)at.getMethod("getSystemContext").invoke(thread);
        Class.forName("android.view.WindowManagerGlobal").getMethod("setWindowManagerServiceForSystemProcess",Class.forName("android.view.IWindowManager")).invoke(null,service("window","android.view.IWindowManager"));
        context=new ContextWrapper(system.createPackageContext("com.android.shell",0)){
            public String getPackageName(){return "com.android.shell";}
            public String getOpPackageName(){return "com.android.shell";}
            public AttributionSource getAttributionSource(){return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();}
        };
        appUid=context.getPackageManager().getPackageUid(BuildConfig.APPLICATION_ID,0);callbacks.start();
    }
    @Override protected boolean onTransact(int code,Parcel data,Parcel reply,int flags)throws RemoteException{
        int uid=Binder.getCallingUid();if(uid!=appUid&&uid!=android.os.Process.myUid())throw new SecurityException("Unexpected caller");
        if(code==IBinder.FIRST_CALL_TRANSACTION+16777114){close();expiry.shutdownNow();callbacks.quitSafely();System.exit(0);return true;}
        if(code==2){
            data.enforceInterface(DESCRIPTOR);int action=data.readInt(),task=data.readInt();data.enforceNoDataAvail();
            long identity=Binder.clearCallingIdentity();Bundle result;
            try{result=navigate(action,task);}catch(Exception error){result=new Bundle();result.putString("error",ShellBridge.message(error));}
            finally{Binder.restoreCallingIdentity(identity);}
            reply.writeNoException();reply.writeTypedObject(result,0);return true;
        }
        if(code!=1)return super.onTransact(code,data,reply,flags);
        data.enforceInterface(DESCRIPTOR);Surface surface=data.readTypedObject(Surface.CREATOR);int width=data.readInt(),height=data.readInt(),density=data.readInt(),timeout=data.readInt();data.enforceNoDataAvail();
        long identity=Binder.clearCallingIdentity();Bundle result=new Bundle();
        try{start(surface,width,height,density,timeout);result.putBoolean("ok",true);}
        catch(Throwable error){close();result.putString("error",ShellBridge.message(error));}
        finally{if(surface!=null)surface.release();Binder.restoreCallingIdentity(identity);}
        reply.writeNoException();reply.writeTypedObject(result,0);return true;
    }
    private synchronized void start(Surface surface,int width,int height,int density,int timeout)throws Exception{
        if(surface==null||!surface.isValid()||width<100||height<100||width>4096||height>4096||density<100||density>1000||timeout<1000||timeout>60000)throw new IllegalArgumentException("Invalid mirror surface, dimensions or timeout");
        Class<?> wm=Class.forName("android.view.IWindowManager");
        if((boolean)wm.getMethod("isKeyguardLocked").invoke(service("window",wm.getName())))throw new IllegalStateException("Phone is locked");
        Class<?> managerApi=Class.forName("android.media.projection.IMediaProjectionManager");Object manager=service("media_projection",managerApi.getName());
        if(managerApi.getMethod("getActiveProjectionInfo").invoke(manager)!=null)throw new IllegalStateException("Another capture session is active");
        Object tasks=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        List<?> running=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(tasks,1,false,false,0);
        if(running.isEmpty())throw new IllegalStateException("No foreground Home task");Object task=running.get(0);
        ComponentName top=(ComponentName)task.getClass().getField("topActivity").get(task);
        if(top==null||!top.getPackageName().equals("de.mm20.launcher2.release"))throw new IllegalStateException("Kvaesitso must be foreground");
        Class<?> api=Class.forName("android.media.projection.IMediaProjection"),cookie=Class.forName("android.app.ActivityOptions$LaunchCookie");
        Object token=managerApi.getMethod("createProjection",int.class,String.class,int.class,boolean.class,int.class).invoke(manager,android.os.Process.myUid(),"com.android.shell",0,false,0);
        mirroredTask=task.getClass().getField("taskId").getInt(task);
        api.getMethod("setTaskId",int.class).invoke(token,mirroredTask);
        Object cookieValue=cookie.getConstructor().newInstance();
        api.getMethod("setLaunchCookie",cookie).invoke(token,cookieValue);
        launchCookie=(IBinder)cookie.getField("binder").get(cookieValue);projectionManager=manager;projectionToken=token;
        projection=(MediaProjection)MediaProjection.class.getConstructor(Context.class,api).newInstance(context,token);
        projection.registerCallback(new MediaProjection.Callback(){},new Handler(callbacks.getLooper()));
        output=projection.createVirtualDisplay("Folduo bounded Home mirror",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,new Handler(callbacks.getLooper()));
        if(output==null)throw new IllegalStateException("No mirror display created");
        following=expiry.scheduleWithFixedDelay(()->{
            try{followForeground();}catch(Exception error){failure=ShellBridge.message(error);close();}
        },50,50,TimeUnit.MILLISECONDS);
        expiry.schedule(()->{close();System.exit(0);},timeout,TimeUnit.MILLISECONDS);
    }
    private boolean locked()throws Exception{
        return (boolean)Class.forName("android.view.IWindowManager").getMethod("isKeyguardLocked").invoke(service("window","android.view.IWindowManager"));
    }
    private synchronized void followForeground()throws Exception{
        if(projection==null)return;if(locked()){close();return;}
        Object manager=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(manager,1,false,false,0);
        if(tasks.isEmpty())return;Object task=tasks.get(0);int id=task.getClass().getField("taskId").getInt(task);
        if(id==mirroredTask)return;
        Class<?> session=Class.forName("android.view.ContentRecordingSession");
        Object next=session.getMethod("createTaskSession",IBinder.class,int.class).invoke(null,launchCookie,id);
        session.getMethod("setVirtualDisplayId",int.class).invoke(next,output.getDisplay().getDisplayId());
        var set=Class.forName("android.media.projection.IMediaProjectionManager").getMethod("setContentRecordingSession",session,Class.forName("android.media.projection.IMediaProjection"));
        // Samsung ignores a replacement on the same virtual display until its old session clears.
        if(!(boolean)set.invoke(projectionManager,null,projectionToken)||!(boolean)set.invoke(projectionManager,next,projectionToken))throw new IllegalStateException("Cannot follow foreground task");
        mirroredTask=id;
    }
    private synchronized Bundle navigate(int action,int task)throws Exception{
        if(projection==null||locked())throw new IllegalStateException("Mirror unavailable: "+failure);
        if(router==null)router=new TaskDisplayRouter();Bundle result=new Bundle();
        if(action==android.view.KeyEvent.KEYCODE_HOME){
            Intent home=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
            router.showHome(0,home.resolveActivity(context.getPackageManager()));
        }else if(action==android.view.KeyEvent.KEYCODE_BACK)NavigationInput.back(0);
        else if(action==android.view.KeyEvent.KEYCODE_APP_SWITCH)result.putParcelableArrayList("apps",router.recentApps(context));
        else if(action==InnerNavigation.PREVIEW)result.putParcelable("preview",router.preview(task));
        else if(action==InnerNavigation.SETTINGS)router.openSettings(context,0);
        else if(action==0&&task>=0)router.selectRecent(task,0);
        else throw new IllegalArgumentException("Unsupported navigation action");
        result.putBoolean("ok",true);return result;
    }
    private synchronized void close(){
        if(following!=null){following.cancel(false);following=null;}
        try{if(output!=null)output.release();}finally{output=null;if(projection!=null)projection.stop();projection=null;}
    }
    private static Object service(String name,String api)throws Exception{
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        return Class.forName(api+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
}
