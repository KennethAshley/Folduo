package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.*;

/** Original glass projection over native Gaussian blur; all captured pixels stay on the GPU. */
final class DuoSnapshotRenderer {
    // Blur radii relative to the moving pane, so frost scales with the actual image.
    private static final float[] RADII={0,.00625f,.025f,.075f,.36f};
    private static final String GLASS="""
        uniform shader image;
        uniform float2 size;
        uniform float inner, lift, strength, verticalDepth;
        uniform float3 band;
        half4 main(float2 p) {
            float page=size.x*mix(1.0,.5,inner);
            float u=clamp(mix(p.x/page,1.0-p.x/page,inner),0.0,1.0);
            float depth=lift*u;
            float radius=.22*page*strength*lift*u*u;
            // Adjacent native blur levels partition the image by Gaussian variance.
            // No sparse sample pattern can shimmer as the hinge changes direction.
            float r2=radius*radius;
            float lo=band.x*band.x, mid=band.y*band.y, hi=band.z*band.z;
            float weight=radius<band.y ? (r2-lo)/max(.001,mid-lo)
                                      : (hi-r2)/max(.001,hi-mid);
            weight=clamp(weight,0.0,1.0);
            if(weight<=0.0)return half4(0);
            // Preserve content width. A cosine projection stretches a narrow source
            // strip across the pane when the viewer is not at the assumed eye point.
            float denominator=1.0-verticalDepth*u;
            float2 q=float2(p.x,
                           size.y*.5+(p.y-size.y*.5)/denominator);
            half3 color=image.eval(clamp(q,float2(.5),size-float2(.5))).rgb;
            float transmission=exp(-2.2*lift*u);
            float reflection=.045*lift*pow(1.0-u,8.0);
            float feather=max(1.0,radius*2.0);
            float outside=max(-q.y,q.y-size.y);
            float coverage=mix(1.0,1.0-smoothstep(-feather,feather,outside),smoothstep(0.0,.02,depth));
            color=(color*half(transmission)+half3(reflection))*half(coverage);
            return half4(color,1)*half(weight);
        }
        """;
    private final RuntimeShader content=new RuntimeShader("uniform shader before; uniform shader after; uniform float amount; half4 main(float2 p) { if(amount<=0.0) return before.eval(p); if(amount>=1.0) return after.eval(p); return mix(before.eval(p),after.eval(p),amount); }");
    private final RuntimeShader[] glass=new RuntimeShader[RADII.length];
    private final RenderEffect[] blur=new RenderEffect[RADII.length];
    private final RenderNode pane=new RenderNode("Folduo glass");
    private final Paint sharp=new Paint(Paint.FILTER_BITMAP_FLAG);
    private final boolean inner;
    private float strength=1,appliedLift=-1;
    private Bitmap bound,boundNext;
    private int width,height;
    DuoSnapshotRenderer(Context context,boolean inner){
        this.inner=inner;
        for(int i=0;i<RADII.length;i++){
            glass[i]=new RuntimeShader(GLASS);
            glass[i].setFloatUniform("inner",inner?1:0);
        }
        sharp.setShader(content);
    }
    void setBlurStrength(float value){strength=value;appliedLift=-1;}
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
            float page=inner?w*.5f:w;
            for(int i=0;i<glass.length;i++){
                glass[i].setFloatUniform("size",w,h);
                glass[i].setFloatUniform("band",RADII[Math.max(0,i-1)]*page,RADII[i]*page,RADII[Math.min(RADII.length-1,i+1)]*page);
                if(i>0)blur[i]=RenderEffect.createBlurEffect(RADII[i]*page,RADII[i]*page,Shader.TileMode.CLAMP);
            }
            bound=image;boundNext=next;width=w;height=h;appliedLift=-1;
        }
        content.setFloatUniform("amount",Math.max(0,Math.min(1,blend)));
        float progress=Math.max(0,Math.min(1,angle/180f));
        float lift=inner?1-Math.min(1,progress/.92f):progress;
        if(inner){canvas.save();canvas.clipRect(w*.5f,0,w,h);canvas.drawRect(0,0,w,h,sharp);canvas.restore();}
        if(lift<=0){canvas.save();canvas.clipRect(0,0,inner?w*.5f:w,h);canvas.drawRect(0,0,w,h,sharp);canvas.restore();return;}
        if(lift!=appliedLift){
            RenderEffect combined=null;
            float depth=GlassProjection.innerPlane(180-180*lift).depth();
            float frost=(float)Math.sin(lift*Math.PI*.5);
            for(int i=0;i<glass.length;i++){
                glass[i].setFloatUniform("lift",frost);glass[i].setFloatUniform("strength",strength);
                glass[i].setFloatUniform("verticalDepth",depth);
                RenderEffect level=RenderEffect.createRuntimeShaderEffect(glass[i],"image");
                if(blur[i]!=null)level=RenderEffect.createChainEffect(level,blur[i]);
                combined=combined==null?level:RenderEffect.createBlendModeEffect(combined,level,BlendMode.PLUS);
            }
            pane.setRenderEffect(combined);appliedLift=lift;
        }
        pane.setPosition(0,0,w,h);Canvas pixels=pane.beginRecording();
        pixels.drawRect(0,0,w,h,sharp);pane.endRecording();
        canvas.save();canvas.clipRect(0,0,inner?w*.5f:w,h);
        // Keep the overlay opaque even if half-float weight addition rounds below one.
        canvas.drawColor(Color.BLACK);canvas.drawRenderNode(pane);canvas.restore();
    }
}
