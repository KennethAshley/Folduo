package jp.bunkaich.sukashimotion;

import android.app.WallpaperManager;
import android.content.Context;
import android.os.*;
import java.lang.reflect.Method;

/** Lists restoration APIs and wallpaper configuration; never changes a wallpaper. */
public final class WallpaperProbe {
    public static void main(String[] args) throws Exception {
        Looper.prepareMainLooper();
        Class<?> at = Class.forName("android.app.ActivityThread");
        Object thread = at.getMethod("systemMain").invoke(null);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        WallpaperManager manager = WallpaperManager.getInstance(system.createPackageContext("com.android.shell", 0));
        for (Method method : WallpaperManager.class.getMethods())
            if (method.getName().matches("(?i).*(snapshot|backup|description).*$"))
                System.out.println(method);
        for (int which : new int[]{5, 17}) {
            System.out.println("HOME " + which + " info=" + WallpaperManager.class.getMethod("getWallpaperInfo", int.class, int.class).invoke(manager, which, 0));
            Bundle extras = (Bundle) WallpaperManager.class.getMethod("getWallpaperExtras", int.class, int.class).invoke(manager, which, 0);
            System.out.println("EXTRAS " + which + " " + extras);
            if (extras != null) for (String key : extras.keySet()) System.out.println(key + "=" + extras.get(key));
        }
        System.exit(0);
    }
}
