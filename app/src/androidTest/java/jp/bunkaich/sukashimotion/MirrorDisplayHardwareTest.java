package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.hardware.display.DisplayManager;
import android.os.*;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in capability check. Owns no launcher or app tasks; removes the mirror in finally. */
public class MirrorDisplayHardwareTest {
    @Test public void primaryHomeCanBeMirroredToInner()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware"))&&DeviceSupport.supports(Build.MODEL));
        var instrumentation=InstrumentationRegistry.getInstrumentation();Context context=instrumentation.getTargetContext();
        Activity activity=null;IShellBridge bridge=null;WindowManager[] windows={null};SurfaceView[] views={null};SurfaceControl mirror=null;
        try{
            MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));context.stopService(new Intent(context,RevealService.class));
            activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            BridgeConnection.connect(context);long deadline=SystemClock.elapsedRealtime()+10000;
            while(BridgeConnection.bridge==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
            bridge=BridgeConnection.bridge;assertNotNull(bridge);
            org.junit.Assume.assumeTrue("Phone must be unfolded","OPENED".equals(bridge.inspect().getString("baseState")));
            org.junit.Assume.assumeFalse("Phone must be unlocked",context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
            bridge.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){}});
            Bundle held=bridge.hold(false,0);assertTrue(held.getString("error",held.toString()),held.getBoolean("ok"));
            DisplayManager displays=context.getSystemService(DisplayManager.class);
            deadline=SystemClock.elapsedRealtime()+5000;
            while(displays.getDisplay(1).getState()!=Display.STATE_ON&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
            assertEquals(Display.STATE_ON,displays.getDisplay(1).getState());
            Activity source=activity;
            instrumentation.runOnMainSync(()->source.startActivity(TaskDisplayRouter.launchIntent(new ComponentName("com.sec.android.app.popupcalculator","com.sec.android.app.popupcalculator.Calculator"))));
            Thread.sleep(1000);
            CountDownLatch surfaceReady=new CountDownLatch(1);
            instrumentation.runOnMainSync(()->{
                Context panel=context.createDisplayContext(displays.getDisplay(1)).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
                WindowManager wm=panel.getSystemService(WindowManager.class);SurfaceView view=new SurfaceView(panel);
                view.getHolder().addCallback(new SurfaceHolder.Callback(){public void surfaceCreated(SurfaceHolder h){surfaceReady.countDown();}public void surfaceChanged(SurfaceHolder h,int f,int w,int x){}public void surfaceDestroyed(SurfaceHolder h){}});
                WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
                lp.setFitInsetsTypes(0);lp.setTitle("Folduo bounded mirror test");windows[0]=wm;views[0]=view;wm.addView(view,lp);
            });
            assertTrue(surfaceReady.await(5,TimeUnit.SECONDS));
            instrumentation.getUiAutomation().adoptShellPermissionIdentity();
            Class<?> api=Class.forName("android.view.IWindowManager");
            IBinder binder=(IBinder)Class.forName("android.os.ServiceManager").getMethod("getService",String.class).invoke(null,"window");
            Object wm=Class.forName(api.getName()+"$Stub").getMethod("asInterface",IBinder.class).invoke(null,binder);
            mirror=SurfaceControl.class.getConstructor().newInstance();
            assertTrue("Firmware must grant a real display mirror",(boolean)api.getMethod("mirrorDisplay",int.class,SurfaceControl.class).invoke(wm,0,mirror));
            assertTrue(mirror.isValid());
            Point primary=new Point();displays.getDisplay(0).getRealSize(primary);
            int width=views[0].getWidth(),height=views[0].getHeight();float scale=Math.min(width/(float)primary.x,height/(float)primary.y);
            try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){
                tx.reparent(mirror,views[0].getSurfaceControl()).setLayer(mirror,1).setScale(mirror,scale,scale).setPosition(mirror,(width-primary.x*scale)/2,(height-primary.y*scale)/2).setVisibility(mirror,true).apply();
            }
            Thread.sleep(500);saveFrame(context,bridge,"mirrored-calculator.png");
            try(var input=new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand("input -d 0 keyevent 3"))){input.readAllBytes();}
            Thread.sleep(1000);saveFrame(context,bridge,"mirrored-home.png");
            Bundle report=new Bundle();report.putString("stream","MIRROR_API_ACCEPTED: inspect saved Calculator and Home frames; touch forwarding is not implemented.\n");instrumentation.sendStatus(0,report);
        }finally{
            if(mirror!=null){try(SurfaceControl.Transaction tx=new SurfaceControl.Transaction()){tx.reparent(mirror,null).apply();}mirror.release();}
            instrumentation.runOnMainSync(()->{if(views[0]!=null)windows[0].removeViewImmediate(views[0]);});
            instrumentation.getUiAutomation().dropShellPermissionIdentity();
            if(bridge!=null)try{bridge.release();bridge.stopAngles();}finally{BridgeConnection.disconnect();}
            if(activity!=null){Activity source=activity;instrumentation.runOnMainSync(source::finish);}
            MotionSettings.setEnabled(context,false);
        }
    }
    private static void saveFrame(Context context,IShellBridge bridge,String name)throws Exception{
        assertFalse(context.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
        Bundle result=bridge.capture(1);Bitmap bitmap=result.getParcelable("frame",Bitmap.class);assertNotNull(result.toString(),bitmap);
        try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name))){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}
    }
}
