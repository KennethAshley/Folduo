package jp.bunkaich.sukashimotion;

import android.content.*;

/** Only restores a previously enabled monitor after boot or an app update. */
public final class RestartReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        String action=intent.getAction();
        if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        if(!MotionSettings.enabled(context))return;
        try{context.startForegroundService(new Intent(context,MotionService.class).setAction("restore"));}
        catch(RuntimeException e){MotionSettings.recovery(context,UiText.of(R.string.restore_deferred));}
    }
}
