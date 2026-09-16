package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.*;

/** Local Twitter trial: the same shader and tuning as DuoFold Fine, over app pixels. */
final class DuoSnapshotRenderer {
    private final RuntimeShader shader;
    private final RuntimeShader content=new RuntimeShader("uniform shader before; uniform shader after; uniform float amount; half4 main(float2 p) { if(amount<=0.0) return before.eval(p); if(amount>=1.0) return after.eval(p); return mix(before.eval(p),after.eval(p),amount); }");
    private final Paint frost=new Paint(),sharp=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final boolean inner;
    private Bitmap bound,boundNext;
    private int width,height;
    DuoSnapshotRenderer(Context context,boolean inner){
        this.inner=inner;
        try(var in=context.getResources().openRawResource(R.raw.duo_fold)){
            shader=new RuntimeShader(new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
        }catch(java.io.IOException e){throw new IllegalStateException("Duo shader unavailable",e);}
        float pxPerMm=context.getResources().getDisplayMetrics().xdpi/25.4f;
        if(!Float.isFinite(pxPerMm)||pxPerMm<=0)pxPerMm=6;
        shader.setFloatUniform("eyeDistancePx",(inner?260:240)*pxPerMm);
        shader.setFloatUniform("hingeSide",inner?1:-1);
        shader.setFloatUniform("hingeOffsetPx",0);
        shader.setFloatUniform("blurSpread",inner?.088f:.084f);
        shader.setFloatUniform("darkening",.02f*6/pxPerMm);
        shader.setFloatUniform("maxBlurRadiusPx",inner?52:56);
        shader.setFloatUniform("maxDarken",inner?.78f:.82f);
        shader.setFloatUniform("frostTint",.10f);
        shader.setFloatUniform("edgeDarken",inner?.85f:.90f);
        shader.setFloatUniform("perspective",1);
        shader.setFloatUniform("eyeHeightFraction",inner?.30f:.38f);
        frost.setShader(shader);
    }
    private BitmapShader fit(Bitmap image,int w,int h){
        BitmapShader texture=new BitmapShader(image,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        Matrix matrix=new Matrix();matrix.setScale(w/(float)image.getWidth(),h/(float)image.getHeight());texture.setLocalMatrix(matrix);
        return texture;
    }
    void draw(Canvas canvas,Bitmap image,Bitmap next,float blend,int w,int h,float angle){
        if(w<=1||h<=1)return;
        if(next==null)next=image;
        if(bound!=image||boundNext!=next||width!=w||height!=h){
            content.setInputShader("before",fit(image,w,h));content.setInputShader("after",fit(next,w,h));
            shader.setFloatUniform("resolution",inner?w*.5f:w,h);
            bound=image;boundNext=next;width=w;height=h;
        }
        content.setFloatUniform("amount",Math.max(0,Math.min(1,blend)));
        shader.setInputShader("content",content);sharp.setShader(content);
        float progress=Math.max(0,Math.min(1,angle/180f));
        float tilt=inner?(1-Math.min(1,progress/.92f))*75:progress*75;
        shader.setFloatUniform("tiltDegrees",tilt);
        if(inner){
            canvas.save();canvas.clipRect(w*.5f,0,w,h);
            canvas.drawRect(0,0,w,h,sharp);canvas.restore();
        }
        canvas.drawRect(0,0,inner?w*.5f:w,h,frost);
    }
}
