package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.view.*;
import android.widget.*;
import java.util.function.Consumer;
import java.util.function.BiConsumer;

/** Transparent task content above the selected inner Home wallpaper. */
final class InnerWorkspace implements AutoCloseable {
    private final WindowManager window;private final FrameLayout root;
    private SnapshotSurface cover;
    private boolean closed;
    private float blurStrength=1;
    InnerWorkspace(Context context,Bitmap wallpaper,IShellBridge bridge,BiConsumer<Surface,SurfaceControl> ready,Consumer<Exception> failed){
        window=context.getSystemService(WindowManager.class);root=new FrameLayout(context);
        ImageView background=new ImageView(context);background.setImageBitmap(wallpaper);background.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(background,new FrameLayout.LayoutParams(-1,-1));
        SurfaceView surface=new SurfaceView(context);surface.setZOrderOnTop(true);surface.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        surface.getHolder().addCallback(new SurfaceHolder.Callback(){
            public void surfaceCreated(SurfaceHolder holder){if(!closed)ready.accept(holder.getSurface(),surface.getSurfaceControl());}
            public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){}
            public void surfaceDestroyed(SurfaceHolder holder){}
        });
        surface.setOnTouchListener((view,event)->{try{bridge.mirrorTouch(event,view.getWidth(),view.getHeight());}catch(Exception e){failed.accept(e);}return true;});
        root.addView(surface,new FrameLayout.LayoutParams(-1,-1));
        WindowManager.LayoutParams lp=new WindowManager.LayoutParams(-1,-1,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        lp.setFitInsetsTypes(0);lp.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;lp.setTitle("Folduo inner workspace");
        try{window.addView(root,lp);}catch(RuntimeException e){close();throw e;}
    }
    void cover(Bitmap frame,Runnable ready){
        if(closed)return;
        if(cover!=null){cover.image.setFrame(FrameTexture.sharp(frame));cover.image.afterFrame(ready);return;}
        Context local=root.getContext();
        SnapshotView image=new SnapshotView(local,FrameTexture.sharp(frame),true,false);
        image.duoEffect=true;image.setBlurStrength(blurStrength);image.setLayoutMask(1);image.setAngle(0);
        SnapshotSurface next=new SnapshotSurface(local,image,()->{if(!closed&&cover!=null&&cover.image==image)ready.run();});
        WindowManager.LayoutParams lp=MotionService.snapshotLayout();lp.setTitle("Folduo prepared inner frame");
        window.addView(next,lp);cover=next;
    }
    SurfaceControl coveredSurface(){return cover==null?null:cover.getSurfaceControl();}
    void setBlurStrength(float strength){blurStrength=strength;if(cover!=null)cover.image.setBlurStrength(strength);}
    void uncover(){if(cover!=null){SnapshotSurface old=cover;cover=null;try{window.removeViewImmediate(old);}catch(IllegalArgumentException alreadyRemoved){/* Display teardown already removed this window. */}}}
    @Override public void close(){closed=true;uncover();if(root.isAttachedToWindow())window.removeViewImmediate(root);}
}
