package jp.bunkaich.sukashimotion;

import android.content.*;
import android.graphics.*;
import android.hardware.display.*;
import android.media.projection.MediaProjection;
import android.os.*;
import android.view.*;
import java.util.List;
import java.util.concurrent.*;

/** Home-only task capture plus a live display mirror for apps. Input stays on display 0. */
final class WorkspaceMirror implements AutoCloseable {
    private final Object window,manager;private final Class<?> wmApi,managerApi;
    private final Context context;private final Handler handler;private final ScheduledExecutorService life;
    private MediaProjection projection;private VirtualDisplay output;private SurfaceControl apps;
    private ScheduledFuture<?> following;private int task=-1,homeTask=-1,width,height;private boolean resized;
    private final Point physical=new Point();private String error="";
    WorkspaceMirror(Context shell,Handler handler,ScheduledExecutorService life)throws Exception{
        this.handler=handler;this.life=life;
        context=new ContextWrapper(shell){
            public String getPackageName(){return "com.android.shell";}
            public String getOpPackageName(){return "com.android.shell";}
            public AttributionSource getAttributionSource(){return new AttributionSource.Builder(android.os.Process.myUid()).setPackageName("com.android.shell").build();}
        };
        wmApi=Class.forName("android.view.IWindowManager");window=service("window",wmApi.getName());
        Class.forName("android.view.WindowManagerGlobal").getMethod("setWindowManagerServiceForSystemProcess",wmApi).invoke(null,window);
        managerApi=Class.forName("android.media.projection.IMediaProjectionManager");manager=service("media_projection",managerApi.getName());
    }
    synchronized void start(Surface surface,SurfaceControl parent,int width,int height,int density)throws Exception{
        if(surface==null||!surface.isValid()||parent==null||!parent.isValid()||width<100||height<100||width>4096||height>4096||density<100||density>1000)throw new IllegalArgumentException("Invalid mirror surface or dimensions");
        if(locked())throw new IllegalStateException("Phone is locked");
        if(managerApi.getMethod("getActiveProjectionInfo").invoke(manager)!=null)throw new IllegalStateException("Another capture session is active");
        wmApi.getMethod("getInitialDisplaySize",int.class,Point.class).invoke(window,0,physical);
        Point base=new Point();wmApi.getMethod("getBaseDisplaySize",int.class,Point.class).invoke(window,0,base);
        if(!base.equals(physical)||Math.min(physical.x,physical.y)/(float)Math.max(physical.x,physical.y)>.7f)throw new IllegalStateException("Cover must be primary, without a custom display size");
        this.width=width;this.height=height;
        Class<?> api=Class.forName("android.media.projection.IMediaProjection"),launchCookie=Class.forName("android.app.ActivityOptions$LaunchCookie");
        homeTask=homeTask();
        Object token=managerApi.getMethod("createProjection",int.class,String.class,int.class,boolean.class,int.class).invoke(manager,android.os.Process.myUid(),"com.android.shell",0,false,0);
        api.getMethod("setTaskId",int.class).invoke(token,homeTask);
        Object value=launchCookie.getConstructor().newInstance();
        api.getMethod("setLaunchCookie",launchCookie).invoke(token,value);
        projection=(MediaProjection)MediaProjection.class.getConstructor(Context.class,api).newInstance(context,token);
        projection.registerCallback(new MediaProjection.Callback(){@Override public void onStop(){synchronized(WorkspaceMirror.this){if(projection!=null){error="Home capture stopped";close();}}}},handler);
        output=projection.createVirtualDisplay("Folduo inner workspace",width,height,density,DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,surface,null,handler);
        if(output==null)throw new IllegalStateException("No mirror display created");
        // Samsung retains old task listeners when a recording session is retargeted.
        // Keep capture attached to Home; the display mirror follows all other apps without retargeting.
        apps=SurfaceControl.class.getConstructor().newInstance();
        if(!(boolean)wmApi.getMethod("mirrorDisplay",int.class,SurfaceControl.class).invoke(window,0,apps))throw new IllegalStateException("Cannot mirror apps");
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){tx.reparent(apps,parent).setLayer(apps,1).setVisibility(apps,false).apply();}
        fitApps();follow();
        // ponytail: check Home/app visibility every 50 ms; use task callbacks if battery profiling warrants it.
        following=life.scheduleWithFixedDelay(()->{try{follow();}catch(Exception e){error=ShellBridge.message(e);close();}},50,50,TimeUnit.MILLISECONDS);
    }
    synchronized boolean active(){return projection!=null&&output!=null&&apps!=null&&apps.isValid();}
    synchronized String error(){return error;}
    synchronized void workspace(boolean inner)throws Exception{
        if(!active()||locked())throw new IllegalStateException("Mirror is unavailable");
        if(inner==resized)return;
        if(inner){resized=true;wmApi.getMethod("setForcedDisplaySize",int.class,int.class,int.class).invoke(window,0,width,height);}
        else restoreSize();
        fitApps();
    }
    synchronized void touch(MotionEvent event,int viewWidth,int viewHeight)throws Exception{
        if(!active()||!resized||locked())return;
        if(viewWidth<100||viewHeight<100||viewWidth>4096||viewHeight>4096||event.getPointerCount()>10||(int)InputEvent.class.getMethod("getDisplayId").invoke(event)!=1)throw new IllegalArgumentException("Invalid inner touch");
        MotionEvent copy=forwardedEvent(event,viewWidth,viewHeight,physical);
        try{
            InputEvent.class.getMethod("setDisplayId",int.class).invoke(copy,0);
            Class<?> api=Class.forName("android.hardware.input.InputManagerGlobal");Object input=api.getMethod("getInstance").invoke(null);
            if(!(boolean)api.getMethod("injectInputEvent",InputEvent.class,int.class).invoke(input,copy,0))throw new IllegalStateException("Forwarded touch rejected");
        }finally{copy.recycle();}
    }
    static MotionEvent forwardedEvent(MotionEvent event,int width,int height,Point physical)throws Exception{
        MotionEvent copy=MotionEvent.obtain(event);Matrix scale=new Matrix();
        scale.setRectToRect(new RectF(0,0,width,height),new RectF(0,0,physical.x,physical.y),Matrix.ScaleToFit.CENTER);
        try{MotionEvent.class.getMethod("applyTransform",Matrix.class).invoke(copy,scale);return copy;}
        catch(Exception e){copy.recycle();throw e;}
    }
    private boolean locked()throws Exception{return (boolean)wmApi.getMethod("isKeyguardLocked").invoke(window);}
    private int foreground()throws Exception{
        Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,1,false,false,0);
        if(tasks.isEmpty())return -1;return tasks.get(0).getClass().getField("taskId").getInt(tasks.get(0));
    }
    private int homeTask()throws Exception{
        ComponentName preferred=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).resolveActivity(context.getPackageManager());
        Object service=Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        List<?> tasks=(List<?>)Class.forName("android.app.IActivityTaskManager").getMethod("getTasks",int.class,boolean.class,boolean.class,int.class).invoke(service,64,false,false,0);
        for(Object candidate:tasks)if(preferred!=null&&preferred.equals(candidate.getClass().getField("baseActivity").get(candidate)))return candidate.getClass().getField("taskId").getInt(candidate);
        throw new IllegalStateException("Open your launcher once before starting Folduo");
    }
    private synchronized void follow()throws Exception{
        if(!active())return;if(locked()){close();return;}
        int nextTask=foreground();if(nextTask<0||nextTask==task)return;
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){tx.setVisibility(apps,nextTask!=homeTask).apply();}task=nextTask;
    }
    private void fitApps()throws Exception{
        Point base=new Point();wmApi.getMethod("getBaseDisplaySize",int.class,Point.class).invoke(window,0,base);
        float scale=Math.min(width/(float)base.x,height/(float)base.y);
        try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){tx.setScale(apps,scale,scale).setPosition(apps,(width-base.x*scale)/2,(height-base.y*scale)/2).apply();}
    }
    private void restoreSize()throws Exception{
        if(!resized)return;
        Point base=new Point();wmApi.getMethod("getBaseDisplaySize",int.class,Point.class).invoke(window,0,base);
        // Never overwrite a display size changed externally during our session.
        if(base.x==width&&base.y==height)wmApi.getMethod("clearForcedDisplaySize",int.class).invoke(window,0);
        resized=false;
    }
    @Override public synchronized void close(){
        if(following!=null){following.cancel(false);following=null;}
        MediaProjection previous=projection;projection=null;
        if(apps!=null){try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){tx.reparent(apps,null).apply();}catch(Exception e){error=ShellBridge.message(e);}finally{apps.release();apps=null;}}
        try{if(output!=null)output.release();}catch(Exception e){error=ShellBridge.message(e);}finally{output=null;}
        try{if(previous!=null)previous.stop();}catch(Exception e){error=ShellBridge.message(e);}
        try{restoreSize();}catch(Exception e){error=ShellBridge.message(e);}
    }
    private static Object service(String name,String api)throws Exception{
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,name);
        return Class.forName(api+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
    }
}
