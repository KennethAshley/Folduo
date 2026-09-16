package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.view.View;

final class SnapshotView extends View {
    final RuntimeShader shader=new RuntimeShader(FoldShader.CODE);final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    FrameTexture frame;boolean inner;final boolean leftOnly;
    int logicalWidth;boolean flatProjection,duoEffect; private float angle;private FrameTexture rearFrame;private long rearSince;private boolean sharpHold;
    private float blurFloor,layoutBlend=-1,layoutMask,blurStrength=1;
    private RenderNode layoutNode;
    private final Paint holdPaint=new Paint(Paint.FILTER_BITMAP_FLAG);
    private DuoSnapshotRenderer duoRenderer;
    SnapshotView(Context context,FrameTexture frame,boolean inner,boolean leftOnly){
        super(context);this.frame=frame;this.inner=inner;this.leftOnly=leftOnly;angle=inner?180:0;paint.setShader(shader);
        setContentDescription(context.getString(R.string.snapshot_description));setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Do not operate an unseen live app through its temporary frozen image.
        setOnTouchListener((v,event)->true);
    }
    private BitmapShader bitmap(Bitmap bitmap,float sx,float sy,float tx){
        BitmapShader shader=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        Matrix matrix=new Matrix();matrix.setScale(sx,sy);matrix.postTranslate(tx,0);shader.setLocalMatrix(matrix);return shader;
    }
    @Override protected void onSizeChanged(int w,int h,int oldW,int oldH){bindTextures();}
    private void bindTextures(){
        int w=logicalWidth>0?logicalWidth:getWidth(),h=getHeight();if(w==0||h==0)return;
        shader.setInputShader("content",bitmap(frame.sharp,w/(float)frame.sharp.getWidth(),h/(float)frame.sharp.getHeight(),0));
        for(int i=0;i<BlurCache.LEVELS.length;i++)shader.setInputShader("rest"+(int)BlurCache.LEVELS[i],bitmap(frame.levels[i],1,1,0));
        shader.setFloatUniform("cacheScale",frame.levels[0].getWidth()/(float)w,frame.levels[0].getHeight()/(float)h);
        // Cover sees the SAME right half of the inner snapshot, not an unrelated wallpaper.
        FrameTexture linked=rearFrame==null?frame:rearFrame;boolean crop=rearFrame!=null&&layoutBlend<0;
        shader.setInputShader("rear",bitmap(linked.sharp,w*(crop?2f:1f)/linked.sharp.getWidth(),h/(float)linked.sharp.getHeight(),crop?-w:0));
        for(int i=0;i<BlurCache.LEVELS.length;i++)shader.setInputShader("rear"+(int)BlurCache.LEVELS[i],bitmap(linked.levels[i],1,1,crop?-linked.levels[i].getWidth()*.5f:0));
        shader.setFloatUniform("rearCacheScale",linked.levels[0].getWidth()*(crop?.5f:1f)/w,linked.levels[0].getHeight()/(float)h);
        shader.setFloatUniform("size",w,h);shader.setFloatUniform("inner",inner?1:0);
        shader.setFloatUniform("pixelsPerDp",getResources().getDisplayMetrics().density);shader.setFloatUniform("radiusDp",28);
    }
    void setFrame(FrameTexture next){frame=next;if(duoEffect){rearFrame=null;layoutBlend=-1;}bindTextures();invalidate();}
    void setBlurFloor(float radius){blurFloor=Math.max(0,Math.min(60,radius));invalidate();}
    void setBlurStrength(float strength){
        float next=Float.isFinite(strength)?Math.max(.5f,Math.min(1.5f,strength)):1;
        if(blurStrength==next)return;blurStrength=next;
        if(duoRenderer!=null)duoRenderer.setBlurStrength(next);invalidate();
    }
    void setLayoutMask(float amount){
        float next=Math.max(0,Math.min(1,amount));if(layoutMask==next)return;layoutMask=next;
        if(layoutNode==null)layoutNode=new RenderNode("destination preparation frost");
        // Local visual calibration: conceal the temporary layout until the resized frame arrives.
        float radius=48*getResources().getDisplayMetrics().density*layoutMask;
        layoutNode.setRenderEffect(radius>0?RenderEffect.createBlurEffect(radius,radius,Shader.TileMode.CLAMP):null);invalidate();
    }
    void setLayoutBlend(FrameTexture next,float progress){
        boolean changed=rearFrame!=next||layoutBlend<0;
        rearFrame=next;layoutBlend=Math.max(0,Math.min(1,progress));
        if(changed)bindTextures();invalidate();
    }
    void setPanel(FrameTexture next,boolean inside){inner=inside;setFrame(next);}
    void setSharpHold(boolean value){sharpHold=value;invalidate();}
    void afterFrame(Runnable committed){
        getViewTreeObserver().registerFrameCommitCallback(committed);invalidate();
    }
    void setRearFrame(FrameTexture rear,boolean animate){
        if(inner||rear==rearFrame)return;
        rearFrame=rear;rearSince=animate?SystemClock.uptimeMillis():0;bindTextures();invalidate();
    }
    void setAngle(float value){
        if(!Float.isFinite(value))return;float next=GlassProjection.clamp(value);
        if(Math.abs(angle-next)<.00001f)return;angle=next;invalidate();
    }
    @Override protected void onDraw(Canvas canvas){
        if(sharpHold||!frame.prepared&&!duoEffect){canvas.drawColor(Color.BLACK);canvas.drawBitmap(frame.sharp,null,new Rect(0,0,getWidth(),getHeight()),holdPaint);return;}
        if(duoEffect){
            if(duoRenderer==null){duoRenderer=new DuoSnapshotRenderer(getContext(),inner);duoRenderer.setBlurStrength(blurStrength);}
            Canvas target=canvas;
            if(layoutMask>0){layoutNode.setPosition(0,0,getWidth(),getHeight());target=layoutNode.beginRecording();}
            duoRenderer.draw(target,frame.sharp,layoutBlend>=0&&rearFrame!=null?rearFrame.sharp:null,layoutBlend,getWidth(),getHeight(),angle);
            if(layoutMask>0){layoutNode.endRecording();canvas.drawRenderNode(layoutNode);}return;
        }
        GlassProjection.Pose pose=GlassProjection.coverPose(angle);
        shader.setFloatUniform("pose",flatProjection?0:pose.expansion(),flatProjection?0:pose.taper());
        GlassProjection.Plane plane=GlassProjection.innerPlane(angle);
        shader.setFloatUniform("innerDepth",flatProjection?0:plane.depth());
        shader.setFloatUniform("amount",FoldPolicy.blur(angle,inner));
        shader.setFloatUniform("blurFloor",blurFloor);
        float ready=rearSince==0?1:Math.min(1,(SystemClock.uptimeMillis()-rearSince)/160f);ready=ready*ready*(3-2*ready);
        shader.setFloatUniform("rearBlend",layoutBlend>=0?layoutBlend:rearFrame==null?0:GlassProjection.rearWeight(angle)*ready);
        canvas.drawRect(0,0,getWidth(),getHeight(),paint);if(ready<1)postInvalidateOnAnimation();
    }
}
