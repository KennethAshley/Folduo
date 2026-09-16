package jp.bunkaich.sukashimotion;

import android.os.*;
import java.lang.reflect.*;

/** Read-only discovery of this firmware's per-display navigation controls. */
public final class WindowPolicyProbe {
    public static void main(String[] args)throws Exception{
        Looper.prepareMainLooper();
        Class<?> api=Class.forName("android.view.IWindowManager");
        Object binder=Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
        Object service=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
        for(Method method:api.getMethods())if(method.getName().matches(".*(SystemDecor|NavigationBar|DisplayEngagement|DisplayContentMode|HomeSupported).*"))System.out.println("API "+method);
        for(String name:new String[]{"android.view.IWindowManager","android.hardware.display.IDisplayManager"})for(Method method:Class.forName(name).getMethods())
            if(name.endsWith("IDisplayManager")||method.getName().matches(".*(ContentMode|DisplayWindowSettings|WindowingMode|Decor|DisplayTopology|DisplayConfiguration|DisplayPower).*"))System.out.println("DISPLAY_API "+method);
        for(Field field:Class.forName("android.hardware.display.DisplayManager").getDeclaredFields())
            if(Modifier.isStatic(field.getModifiers())&&field.getType()==int.class&&field.getName().matches(".*(CONTENT_MODE|DECOR).*")){field.setAccessible(true);System.out.println("DM_CONSTANT "+field.getName()+"="+field.getInt(null));}
        for(String name:new String[]{"android.view.WindowManager","android.view.Display","android.view.DisplayInfo"})for(Field field:Class.forName(name).getFields())
            if(Modifier.isStatic(field.getModifiers())&&field.getType()==int.class&&field.getName().matches(".*(ENGAGEMENT|CONTENT_MODE|DECOR).*"))System.out.println("CONSTANT "+name+"."+field.getName()+"="+field.getInt(null));
        for(int display=0;display<=1;display++)for(String name:new String[]{"shouldShowSystemDecors","hasNavigationBar","getDisplayEngagementMode","getWindowingMode","getDisplayImePolicy"}){
            try{System.out.println("DISPLAY "+display+" "+name+"="+api.getMethod(name,int.class).invoke(service,display));}
            catch(Exception error){System.out.println("DISPLAY "+display+" "+name+" "+ShellBridge.message(error));}
        }
        System.exit(0);
    }
}
