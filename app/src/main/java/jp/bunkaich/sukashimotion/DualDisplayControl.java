package jp.bunkaich.sukashimotion;

import android.os.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.Executor;

/** Validates Samsung's advertised states rather than relying on numeric IDs. */
final class DualDisplayControl implements AutoCloseable {
    final Object manager,service;final Class<?> requestType,callbackType;final Method request,cancel,read;
    final int innerState,outerState,nativeInnerState,nativeOuterState;private Object owned;
    private Object displayPower;private Method overridePower;private boolean powerPinned;
    private final IBinder[] powerTokens={new Binder(),new Binder()};
    DualDisplayControl()throws Exception{
        if(!DeviceSupport.supports(Build.MODEL))throw new UnsupportedOperationException("@folduo/err_wrong_model");
        Class<?> type=Class.forName("android.hardware.devicestate.DeviceStateManager");
        manager=type.getConstructor().newInstance();int inner=-1,outer=-1,opened=-1,closed=-1;
        for(Object state:(List<?>)type.getMethod("getSupportedDeviceStates").invoke(manager)){
            Class<?> s=state.getClass();String name=(String)s.getMethod("getName").invoke(state);int id=(int)s.getMethod("getIdentifier").invoke(state);
            if("OPENED".equals(name))opened=id;
            if("CLOSED".equals(name))closed=id;
            if(!(boolean)s.getMethod("hasProperty",int.class).invoke(state,10))continue;
            if("CONCURRENT_INNER_DEFAULT".equals(name)&&(boolean)s.getMethod("hasProperty",int.class).invoke(state,12))inner=id;
            if("CONCURRENT_OUTER_DEFAULT".equals(name)&&(boolean)s.getMethod("hasProperty",int.class).invoke(state,11))outer=id;
        }
        if(inner<0||outer<0)throw new UnsupportedOperationException("@folduo/err_states_unavailable");
        innerState=inner;outerState=outer;nativeInnerState=opened;nativeOuterState=closed;
        requestType=Class.forName("android.hardware.devicestate.DeviceStateRequest");callbackType=Class.forName("android.hardware.devicestate.DeviceStateRequest$Callback");
        request=type.getMethod("requestState",requestType,Executor.class,callbackType);cancel=type.getMethod("cancelStateRequest");
        IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"device_state");
        service=Class.forName("android.hardware.devicestate.IDeviceStateManager$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        read=Class.forName("android.hardware.devicestate.IDeviceStateManager").getMethod("getDeviceStateInfo");
    }
    int id(Object info,String field)throws Exception {Object state=info.getClass().getField(field).get(info);return (int)state.getClass().getMethod("getIdentifier").invoke(state);}
    String describe()throws Exception{Object info=read.invoke(service);return "inner="+innerState+" / cover="+outerState+" / current="+id(info,"currentState")+" / base="+id(info,"baseState");}
    String baseName()throws Exception{Object info=read.invoke(service),state=info.getClass().getField("baseState").get(info);return (String)state.getClass().getMethod("getName").invoke(state);}
    synchronized boolean isOwned(){return owned!=null;}
    synchronized void hold(boolean inner,int previousOwner)throws Exception{
        holdState(inner?innerState:outerState,previousOwner);
    }
    synchronized void holdNative(boolean inner)throws Exception{
        // Samsung can cancel a concurrent request itself at the physical endpoint.
        // Keep the power pins while that already-pending native layout settles.
        if(powerPinned&&owned==null&&id(read.invoke(service),"baseState")== (inner?nativeInnerState:nativeOuterState))return;
        holdState(inner?nativeInnerState:nativeOuterState,0);
    }
    synchronized boolean isPowerPinned(){return powerPinned;}
    synchronized void holdPaired(boolean inner)throws Exception{
        try{
            powerPinned=true;powerOverride(0,2,35000);powerOverride(1,2,35000);
            hold(inner,0);
        }catch(Exception e){clearPowerPins();throw e;}
    }
    private void powerOverride(int displayId,int state,int timeout)throws Exception{
        if(displayPower==null){
            Class<?> api=Class.forName("android.hardware.display.IDisplayManager");
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"display");
            displayPower=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            overridePower=api.getMethod("setDisplayStateOverrideWithDisplayId",IBinder.class,int.class,int.class,int.class);
        }
        overridePower.invoke(displayPower,powerTokens[displayId],state,displayId,timeout);
    }
    private void clearPowerPins(){
        if(!powerPinned)return;
        try{try{powerOverride(0,0,0);}finally{powerOverride(1,0,0);}powerPinned=false;}
        catch(Exception ignored){/* Watchdog retries; the OS timeout and Binder death also release. */}
    }
    private void holdState(int desired,int previousOwner)throws Exception{
        if(desired<0)throw new UnsupportedOperationException("@folduo/err_states_unavailable");
        Object info=read.invoke(service);
        if(owned==null&&id(info,"currentState")!=id(info,"baseState")&&!recoverable(previousOwner,desired))throw new IllegalStateException("@folduo/err_display_conflict");
        Object builder=requestType.getMethod("newBuilder",int.class).invoke(null,desired);
        Object next=builder.getClass().getMethod("build").invoke(builder);
        Object callback=Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,m,args)->{
            switch(m.getName()){
                case "hashCode":return System.identityHashCode(proxy);
                case "equals":return proxy==args[0];
                case "toString":return "FolduoDisplayCallback";
                case "onRequestCanceled":synchronized(this){if(owned==next)owned=null;}
            }return null;
        });
        Object previous=owned;owned=next;
        try{request.invoke(manager,next,(Executor)Runnable::run,callback);}catch(Exception e){owned=previous;throw e;}
    }
    private boolean recoverable(int previousOwner,int desired)throws Exception{
        if(previousOwner<=0)return false;
        boolean alive=true;
        try{android.system.Os.kill(previousOwner,0);}catch(android.system.ErrnoException e){alive=e.errno!=android.system.OsConstants.ESRCH;}
        if(alive)return false;
        java.lang.Process probe=new ProcessBuilder("dumpsys","device_state").start();
        try{
            if(!probe.waitFor(1500,java.util.concurrent.TimeUnit.MILLISECONDS))return false;
            String dump=new String(probe.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            return DisplayRequestOwner.canRecover(dump,previousOwner,desired,false);
        }finally{probe.destroy();}
    }
    @Override public synchronized void close(){
        try{if(owned!=null){cancel.invoke(manager);owned=null;}}
        catch(Exception ignored){/* Watchdog retries without cancelling another process's request. */}
        finally{clearPowerPins();}
    }
}
