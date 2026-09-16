package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.Intent;
import android.graphics.*;
import android.os.*;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SnapshotSurfaceTest {
    Activity activity;SnapshotSurface root;SnapshotView view;WindowManager overlayWindows;
    @Test public void attachedFreezeSurvivesTemporaryInvisibility()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bitmap original=Bitmap.createBitmap(480,600,Bitmap.Config.ARGB_8888);original.eraseColor(Color.GREEN);
        FrameTexture frame=FrameTexture.sharp(original);CountDownLatch committed=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->{
            view=new SnapshotView(activity,frame,true,false);
            root=new SnapshotSurface(activity,view,committed::countDown);activity.setContentView(root);
        });
        assertTrue(committed.await(3,TimeUnit.SECONDS));SurfaceControl before=root.getSurfaceControl();
        instrumentation.runOnMainSync(()->root.setVisibility(View.INVISIBLE));instrumentation.waitForIdleSync();
        assertSame("A temporary display visibility change must not destroy the frozen buffer",before,root.getSurfaceControl());
        assertTrue(before.isValid());
        CountDownLatch restored=new CountDownLatch(1);
        // The child GPU callback precedes its parent's visibility transaction, just
        // as on first attachment in SnapshotSurface. Wait for that submission too.
        instrumentation.runOnMainSync(()->{root.setVisibility(View.VISIBLE);view.afterFrame(()->root.post(()->root.postOnAnimation(()->root.postOnAnimation(restored::countDown))));});
        assertTrue(restored.await(3,TimeUnit.SECONDS));
        Bitmap screen=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(screen);
        assertTrue("The retained frame is still green",Color.green(screen.getPixel(screen.getWidth()/2,screen.getHeight()/2))>200);
    }
    @After public void close(){InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{if(overlayWindows!=null&&root!=null)overlayWindows.removeViewImmediate(root);if(activity!=null)activity.finish();});}
    @Test public void applicationOverlayDoesNotGetDimmedToEightyPercent()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bitmap source=Bitmap.createBitmap(480,1000,Bitmap.Config.ARGB_8888);source.eraseColor(Color.BLUE);
        FrameTexture frame=FrameTexture.prepare(source,activity.getResources().getDisplayMetrics().density,()->false);
        CountDownLatch committed=new CountDownLatch(1);java.util.concurrent.atomic.AtomicInteger backgroundTouches=new java.util.concurrent.atomic.AtomicInteger();
        instrumentation.runOnMainSync(()->{
            View background=new View(activity);background.setBackgroundColor(Color.MAGENTA);background.setOnTouchListener((v,event)->{backgroundTouches.incrementAndGet();return true;});activity.setContentView(background);
            android.content.Context context=activity.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
            overlayWindows=context.getSystemService(WindowManager.class);view=new SnapshotView(context,frame,false,false);view.setAngle(60);
            root=new SnapshotSurface(context,view,committed::countDown);overlayWindows.addView(root,MotionService.snapshotLayout());
        });
        assertTrue(committed.await(3,TimeUnit.SECONDS));instrumentation.waitForIdleSync();Thread.sleep(250);
        Bitmap screen=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(screen);
        for(int y=screen.getHeight()/4;y<screen.getHeight()*3/4;y+=11)for(int x=screen.getWidth()/4;x<screen.getWidth()*3/4;x+=11)
            assertTrue("TYPE_APPLICATION_OVERLAY must be fully opaque, not 80 percent: "+Integer.toHexString(screen.getPixel(x,y)),Color.red(screen.getPixel(x,y))<=1);
        injectTap(screen.getWidth()/2,screen.getHeight()/2);assertEquals("Frozen app must not receive hidden taps",0,backgroundTouches.get());
        instrumentation.runOnMainSync(()->{overlayWindows.removeViewImmediate(root);overlayWindows=null;});instrumentation.waitForIdleSync();Thread.sleep(100);
        injectTap(screen.getWidth()/2,screen.getHeight()/2);assertTrue("Removing the transition restores app touch",backgroundTouches.get()>0);
    }
    private void injectTap(float x,float y){
        var automation=InstrumentationRegistry.getInstrumentation().getUiAutomation();long time=SystemClock.uptimeMillis();
        MotionEvent down=MotionEvent.obtain(time,time,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(time,time+50,MotionEvent.ACTION_UP,x,y,0);
        automation.injectInputEvent(down,true);automation.injectInputEvent(up,true);down.recycle();up.recycle();InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }
    @Test public void frostedSurfaceCompletelyCoversTheLiveAppBehindIt()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Bitmap original=Bitmap.createBitmap(480,1000,Bitmap.Config.ARGB_8888);original.eraseColor(Color.BLUE);
        FrameTexture frame=FrameTexture.prepare(original,activity.getResources().getDisplayMetrics().density,()->false);
        CountDownLatch committed=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->{
            android.widget.FrameLayout behind=new android.widget.FrameLayout(activity);behind.setBackgroundColor(Color.MAGENTA);
            view=new SnapshotView(activity,frame,false,false);view.setAngle(70);
            root=new SnapshotSurface(activity,view,committed::countDown);behind.addView(root,new android.widget.FrameLayout.LayoutParams(-1,-1));activity.setContentView(behind);
        });
        assertTrue(committed.await(3,TimeUnit.SECONDS));instrumentation.waitForIdleSync();Thread.sleep(400);
        int[] location=new int[2];instrumentation.runOnMainSync(()->root.getLocationOnScreen(location));
        Bitmap screen=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(screen);
        try(var out=new java.io.FileOutputStream(new java.io.File(activity.getExternalFilesDir(null),"surface-occlusion.png"))){screen.compress(Bitmap.CompressFormat.PNG,100,out);}
        // System clock/navigation glyphs are drawn above application surfaces. Restrict
        // this assertion to the actual app area, whose background is uniquely magenta.
        Insets[] insets=new Insets[1];instrumentation.runOnMainSync(()->insets[0]=root.getRootWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars()));
        for(int y=insets[0].top+4;y<root.getHeight()-insets[0].bottom-4;y+=9)for(int x=insets[0].left+4;x<root.getWidth()-insets[0].right-4;x+=9){
            int pixel=screen.getPixel(location[0]+x,location[1]+y);
            assertTrue("Underlying app or unexpected surface at "+x+","+y+" color="+Integer.toHexString(pixel)+" origin="+location[0]+","+location[1]+" size="+root.getWidth()+","+root.getHeight(),Color.red(pixel)<=1);
        }
    }
    @Test public void frozenFrameCommitsAndRetainsCapturableSurfaceAcrossUpdates()throws Exception{
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        activity=instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        CountDownLatch committed=new CountDownLatch(1);Bitmap original=Bitmap.createBitmap(480,600,Bitmap.Config.ARGB_8888);original.eraseColor(Color.GREEN);
        FrameTexture frame=FrameTexture.sharp(original);
        instrumentation.runOnMainSync(()->{
            view=new SnapshotView(activity,frame,true,false);view.setSharpHold(true);
            root=new SnapshotSurface(activity,view,committed::countDown);activity.setContentView(root);
        });
        assertTrue("Freeze was submitted to the GPU before switching",committed.await(3,TimeUnit.SECONDS));
        assertTrue(root.getSurfaceControl().isValid());
        // Round trip the exclusion handle, as used by the Shizuku Binder call.
        Parcel parcel=Parcel.obtain();root.getSurfaceControl().writeToParcel(parcel,0);parcel.setDataPosition(0);
        SurfaceControl copy=SurfaceControl.CREATOR.createFromParcel(parcel);assertTrue(copy.isValid());copy.release();parcel.recycle();
        Bitmap screen=instrumentation.getUiAutomation().takeScreenshot();assertNotNull(screen);
        assertTrue("Committed freeze is visibly green: "+Integer.toHexString(screen.getPixel(screen.getWidth()/2,screen.getHeight()/2)),Color.green(screen.getPixel(screen.getWidth()/2,screen.getHeight()/2))>200);
        CountDownLatch endpoint=new CountDownLatch(1);
        instrumentation.runOnMainSync(()->{view.setSharpHold(false);view.setAngle(180);view.afterFrame(endpoint::countDown);});
        assertTrue("Endpoint redraw commits before handoff",endpoint.await(3,TimeUnit.SECONDS));
    }
}
