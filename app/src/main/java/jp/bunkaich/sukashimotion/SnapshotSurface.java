package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.view.*;

/** Own surface lets capture exclude only our pixels, without ever hiding the visible freeze. */
final class SnapshotSurface extends SurfaceView implements SurfaceHolder.Callback {
    final SnapshotView image;private SurfaceControlViewHost host;private final Runnable committed;
    SnapshotSurface(Context context,SnapshotView image,Runnable committed){
        super(context);this.image=image;this.committed=committed;
        // Logical OFF during a panel swap must not discard the still-visible frozen buffer.
        if(android.os.Build.VERSION.SDK_INT>=34)setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT);
        setZOrderOnTop(true);getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        getHolder().addCallback(this);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setOnTouchListener((v,event)->true);
    }
    @Override public void surfaceCreated(SurfaceHolder holder){
        host=new SurfaceControlViewHost(getContext(),getDisplay(),getHostToken());
        image.logicalWidth=getWidth();host.setView(image,getWidth(),getHeight());
        SurfaceControlViewHost.SurfacePackage surface=host.getSurfacePackage();
        if(surface!=null)setChildSurfacePackage(surface);
        // GPU completion can precede attaching the embedded surface to its parent transaction.
        // Keep the source app visible until both the child and the parent have been submitted.
        image.afterFrame(()->post(()->postOnAnimation(()->postOnAnimation(committed))));
    }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height){
        if(host!=null){image.logicalWidth=width;host.relayout(width,height);}
    }
    @Override public void surfaceDestroyed(SurfaceHolder holder){if(host!=null){host.release();host=null;}}
}
