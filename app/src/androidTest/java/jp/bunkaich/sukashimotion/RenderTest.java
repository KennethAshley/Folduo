package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RenderTest {
 private Bitmap render(boolean inner,float angle)throws Exception{
  return render(inner,angle,PreviewActivity.sample(640,720),null);
 }
 private Bitmap render(boolean inner,float angle,Bitmap source,Bitmap linked)throws Exception{
  return render(inner,angle,source,linked,false);
 }
 private Bitmap render(boolean inner,float angle,Bitmap source,Bitmap linked,boolean physical)throws Exception{
  return render(inner,angle,source,linked,physical,false);
 }
 private Bitmap render(boolean inner,float angle,Bitmap source,Bitmap linked,boolean physical,boolean flat)throws Exception{
  return render(inner,angle,source,linked,physical,flat,view->{});
 }
 private Bitmap render(boolean inner,float angle,Bitmap source,Bitmap linked,boolean physical,boolean flat,java.util.function.Consumer<SnapshotView> configure)throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  android.content.res.Configuration config=new android.content.res.Configuration(context.getResources().getConfiguration());config.densityDpi=160;
  Context renderContext=context.createConfigurationContext(config);
  FrameTexture frame=FrameTexture.prepare(source,1,()->false);
  FrameTexture rear=linked==null?null:FrameTexture.prepare(linked,1,()->false);
  android.media.ImageReader reader=android.media.ImageReader.newInstance(640,720,PixelFormat.RGBA_8888,2,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
  HardwareRenderer renderer=new HardwareRenderer();renderer.setSurface(reader.getSurface());
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
   SnapshotView view=new SnapshotView(renderContext,frame,inner,false);view.flatProjection=flat;view.layout(0,0,640,720);if(rear!=null)view.setRearFrame(rear,false);view.setAngle(angle);
   configure.accept(view);
   RenderNode node=new RenderNode("fold-test");node.setPosition(0,0,640,720);Canvas c=node.beginRecording();
   if(physical){
    c.drawColor(Color.BLACK);c.save();c.clipRect(320,0,640,720);view.draw(c);c.restore();
    // Independent physical panel projection, applied AFTER the actual GPU shader.
    double beta=Math.toRadians(180-angle),depth=320*Math.sin(beta),eyeDistance=5120,scale=eyeDistance/(eyeDistance-depth);
    float x=(float)(320-320*Math.cos(beta)*scale),top=(float)(360-360*scale),bottom=720-top;
    Matrix transform=new Matrix();transform.setPolyToPoly(new float[]{0,0,320,0,320,720,0,720},0,new float[]{x,top,320,0,320,720,x,bottom},0,4);
    c.save();c.concat(transform);c.clipRect(0,0,320,720);view.draw(c);c.restore();
   }else view.draw(c);
   node.endRecording();
   renderer.setContentRoot(node);renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
  });
  android.media.Image image=null;
  for(int i=0;i<50&&image==null;i++){image=reader.acquireLatestImage();if(image==null)Thread.sleep(20);}
  assertNotNull("GPU frame available",image);
  android.hardware.HardwareBuffer buffer=image.getHardwareBuffer();
  Bitmap result=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB)).copy(Bitmap.Config.ARGB_8888,false);
  buffer.close();image.close();renderer.destroy();reader.close();return result;
 }
 @Test public void pairedBlurKeepsBothAxesAligned()throws Exception{
  Bitmap gradient=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  for(int x=0;x<640;x++)for(int y=0;y<720;y++)gradient.setPixel(x,y,Color.rgb(Math.round(x*255f/640),Math.round(y*255f/720),80));
  for(boolean inner:new boolean[]{false,true})for(float angle:new float[]{30,90,150}){
   Bitmap result=render(inner,angle,gradient,null,false,true);
   for(int x=100;x<540;x+=40)for(int y=140;y<580;y+=40){
    assertEquals("Paired image keeps x at "+angle,x*255f/640,Color.red(result.getPixel(x,y)),3);
    assertEquals("Paired image keeps y at "+angle,y*255f/720,Color.green(result.getPixel(x,y)),3);
   }
   result.recycle();
  }
  for(boolean inner:new boolean[]{false,true}){
   Bitmap blurred=render(inner,90,horizontalEdge(),null,false,true);
   assertTrue("Flattening perspective must retain visible blur",edgeSigma(blurred,inner?40:480)>10);
   blurred.recycle();
  }
 }
 @Test public void rightPaneRemainsPixelIdentical()throws Exception{
  Bitmap open=render(true,180),folded=render(true,95);long difference=0;
  for(int y=5;y<715;y+=5)for(int x=324;x<635;x+=5)difference+=distance(open.getPixel(x,y),folded.getPixel(x,y));
  assertEquals("Right pane must not blur or slide",0,difference);
 }
 @Test public void leftPaneBlursWithoutReplacingContent()throws Exception{
  Bitmap open=render(true,180),folded=render(true,95);long difference=0;
  for(int y=40;y<680;y+=4)for(int x=20;x<270;x+=4)difference+=distance(open.getPixel(x,y),folded.getPixel(x,y));
  assertTrue("Left pane blur must be visible",difference>10000);
  assertTrue("The original green background must remain",Color.green(folded.getPixel(20,500))>Color.red(folded.getPixel(20,500)));
 }
 @Test public void innerSeamHasNoBlackCorner()throws Exception{
  Bitmap folded=render(true,90);for(int y:new int[]{0,1,10,710,719})assertTrue("No artificial corner on seam",Color.green(folded.getPixel(319,y))>20);
 }
 @Test public void coverShapeHasBlackOutsideAndVisibleCenter()throws Exception{
  Bitmap folded=render(false,70);assertTrue("Far outside remains black",Color.green(folded.getPixel(620,2))<5);assertTrue(Color.green(folded.getPixel(320,360))>20);
 }
 @Test public void innerHorizontalGradientStaysInPlaceAtEveryAngle()throws Exception{
  Bitmap gradient=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  for(int x=0;x<640;x++)for(int y=0;y<720;y++)gradient.setPixel(x,y,Color.rgb(Math.round(x*255f/640),128,80));
  for(float angle:new float[]{180,174,150,120,100,91,90,89}){
   Bitmap result=render(true,angle,gradient,null);
   for(int x=40;x<320;x+=20)assertEquals("No horizontal stretch at "+angle+", x="+x,x*255f/640,Color.red(result.getPixel(x,360)),3);
   result.recycle();
  }
 }
 @Test public void iconEdgesBlurWithoutSlidingOrStretching()throws Exception{
  Bitmap bars=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bars);canvas.drawColor(Color.BLACK);
  Paint paint=new Paint();paint.setColor(Color.WHITE);
  // Two icon-sized rectangles, kept away from the physical image silhouette.
  canvas.drawRect(64,200,112,520,paint);canvas.drawRect(224,200,272,520,paint);
  StringBuilder measurements=new StringBuilder("angle,far_left,far_right,near_left,near_right\n");
  for(float angle:new float[]{180,174,160,140,120,100,91,90}){
   Bitmap result=render(true,angle,bars,null);measurements.append(angle);
   for(int edge:new int[]{64,112,224,272}){
    int crossing=-1;boolean rising=edge==64||edge==224;
    for(int x=edge-16;x<=edge+16;x++){
     int a=Color.red(result.getPixel(x,360)),b=Color.red(result.getPixel(x+1,360));
     if(rising?a<128&&b>=128:a>=128&&b<128){crossing=x;break;}
    }
    assertTrue("Icon edge remains present at "+angle+", edge="+edge,crossing>=0);
    assertEquals("Blur softens an edge without moving it at "+angle,edge-1,crossing,3);
    measurements.append(',').append(crossing);
   }
   measurements.append('\n');result.recycle();
  }
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"inner-horizontal-stability.csv"))){out.write(measurements.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
 @Test public void physicalPaneRotationKeepsTheAcceptedVerticalCompensation()throws Exception{
  Bitmap gradient=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  for(int x=0;x<640;x++)for(int y=0;y<720;y++)gradient.setPixel(x,y,Color.rgb(Math.round(x*255f/640),Math.round(y*255f/720),80));
  for(float angle:new float[]{179,170,150,120,100}){
   Bitmap result=render(true,angle,gradient,null,true);
   double beta=Math.toRadians(180-angle);int left=(int)Math.ceil(320-320*Math.cos(beta)/(1-Math.sin(beta)/16));
   for(int x=Math.max(40,left+20);x<310;x+=7)for(int y=250;y<470;y+=17){
    int pixel=result.getPixel(x,y);
    assertEquals("Vertical image position at "+angle+" degrees, y="+y,y*255f/720,Color.green(pixel),3);
   }
  }
 }
 @Test public void innerBlackBoundaryFollowsThePlaneAndStaysOpaque()throws Exception{
  Bitmap white=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);white.eraseColor(Color.WHITE);
  Bitmap result=render(true,120,white,null);int previous=-1,largestStep=0,middle=0;
  for(int y=0;y<190;y++){
   int value=Color.red(result.getPixel(20,y));if(value>10&&value<245)middle++;
   if(previous>=0)largestStep=Math.max(largestStep,Math.abs(value-previous));previous=value;
  }
  // The smaller inset is narrower than the frost radius, so the black surround
  // is intentionally softened even at the physical top edge, never an alpha hole.
  assertTrue("Outside is darker than the middle of the optical boundary",Color.red(result.getPixel(20,0))<128);
  assertTrue("Image height is retained away from the soft edge",Color.red(result.getPixel(20,90))>245);
  assertTrue("The optical edge is frosted, not cut out",middle>=30&&largestStep<=10);
  for(int x=0;x<640;x+=3)for(int y=0;y<720;y+=3)assertEquals(255,Color.alpha(result.getPixel(x,y)));
 }
 @Test public void coverUsesInnerRightThenReturnsToOwnFrame()throws Exception{
  Bitmap cover=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);cover.eraseColor(Color.BLUE);
  Bitmap inside=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(inside);c.drawColor(Color.RED);Paint p=new Paint();p.setColor(Color.GREEN);c.drawRect(320,0,640,720,p);
  Bitmap folded=render(false,80,cover,inside),closed=render(false,0,cover,inside);
  int middle=folded.getPixel(400,360);
  assertTrue("Cover samples the paired INNER RIGHT image",Color.green(middle)>180&&Color.red(middle)<60&&Color.blue(middle)<10);
  assertEquals("Closed endpoint is exactly the actual cover image",Color.BLUE,closed.getPixel(400,360));
 }
 @Test public void frostedBoundaryHasWideFalloffAndNeverRevealsTheUnderlyingApp()throws Exception{
  Bitmap white=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);white.eraseColor(Color.WHITE);
  Bitmap folded=render(false,70,white,null);int intermediate=0,largestStep=0,previous=-1;
  for(int y=0;y<140;y++){
   int c=folded.getPixel(610,y),value=Color.red(c);
   if(value>10&&value<245)intermediate++;
   if(previous>=0)largestStep=Math.max(largestStep,Math.abs(value-previous));previous=value;
  }
  assertTrue("Frost extends into the black edge",intermediate>=30);
  assertTrue("No razor-sharp silhouette step",largestStep<=10);
  for(int y=0;y<720;y+=3)for(int x=0;x<640;x+=3)assertEquals("No clear holes exposing the real app",255,Color.alpha(folded.getPixel(x,y)));
 }
 @Test public void exchangingLayoutsDoesNotRetainSharpGhostLines()throws Exception{
  Bitmap cover=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888),inside=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  for(int y=0;y<720;y++)for(int x=0;x<640;x++){
   cover.setPixel(x,y,(x/8%2==0)?Color.WHITE:Color.BLACK);
   inside.setPixel(x,y,(y/8%2==0)?Color.BLACK:Color.WHITE);
  }
  for(float a:new float[]{26,35,50,65}){
   Bitmap folded=render(false,a,cover,inside);int low=255,high=0,rearLow=255,rearHigh=0;
   for(int x=100;x<540;x++){int value=Color.red(folded.getPixel(x,360));low=Math.min(low,value);high=Math.max(high,value);}
   for(int y=260;y<460;y++){int value=Color.red(folded.getPixel(320,y));rearLow=Math.min(rearLow,value);rearHigh=Math.max(rearHigh,value);}
   // Early in a gradual fold the ORIGINAL may still be readable. Distinct horizontal
   // and vertical details detect the regression we actually need to prevent: two
   // simultaneously readable layouts, not the presence of any remaining detail.
   assertTrue("No two readable layouts at "+a+" degrees: source="+(high-low)+" rear="+(rearHigh-rearLow),Math.min(high-low,rearHigh-rearLow)<=18);
  }
 }
 @Test public void temporaryDestinationContainsOnlyTheCurrentFrameAndPreparedFrost(){
  Bitmap source=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(source);c.drawColor(Color.RED);Paint p=new Paint();p.setColor(Color.BLUE);c.drawRect(320,0,640,720,p);
  FrameTexture original=FrameTexture.prepare(source,1,()->false);
  FrameTexture cover=original.transfer(true,320,720),inner=cover.transfer(false,640,720);
  assertTrue(cover.prepared&&inner.prepared);
  assertEquals(Color.BLUE,cover.sharp.getPixel(160,360));assertEquals(Color.BLUE,inner.sharp.getPixel(100,360));assertEquals(Color.BLUE,inner.sharp.getPixel(540,360));
  for(Bitmap level:inner.levels){int pixel=level.getPixel(level.getWidth()/4,level.getHeight()/2);assertTrue("Cropped right image remains blue after blur",Color.blue(pixel)>240&&Color.red(pixel)<15);}
  FrameTexture pairedCover=original.transfer(true,320,720,true);
  assertEquals("Paired cover follows the inner LEFT pane",Color.RED,pairedCover.sharp.getPixel(160,360));
  for(Bitmap level:pairedCover.levels){int pixel=level.getPixel(level.getWidth()/2,level.getHeight()/2);assertTrue(Color.red(pixel)>240&&Color.blue(pixel)<15);}
 }
 @Test public void closingCoverPreservesProportionsWithoutEmptyBorders(){
  Bitmap source=Bitmap.createBitmap(800,600,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(source);canvas.drawColor(Color.BLUE);
  Paint paint=new Paint();paint.setColor(Color.WHITE);canvas.drawCircle(200,300,60,paint);
  FrameTexture cover=FrameTexture.prepare(source,1,()->false).transfer(true,200,600,true);
  int horizontal=0,vertical=0;
  for(int x=0;x<200;x++)if(Color.red(cover.sharp.getPixel(x,300))>128)horizontal++;
  for(int y=0;y<600;y++)if(Color.red(cover.sharp.getPixel(100,y))>128)vertical++;
  assertEquals("A circle stays round when mapped onto the narrow cover",vertical,horizontal,2);
  for(int y=0;y<600;y+=20)for(int x:new int[]{0,199})assertEquals("No empty black/transparent border",Color.BLUE,cover.sharp.getPixel(x,y));
 }
 @Test public void closingLayoutBlendsUnderBlurThenRevealsTheCorrectImage()throws Exception{
  Bitmap old=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888),next=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  old.eraseColor(Color.RED);next.eraseColor(Color.BLUE);FrameTexture destination=FrameTexture.prepare(next,1,()->false);
  for(float mix:new float[]{0,.25f,.5f,.75f,1}){
   Bitmap image=render(false,0,old,null,false,true,view->{view.setBlurFloor(28);view.setLayoutBlend(destination,mix);});
   int color=image.getPixel(320,360);assertEquals(255*(1-mix),Color.red(color),2);assertEquals(255*mix,Color.blue(color),2);assertEquals(255,Color.alpha(color));image.recycle();
  }
  for(int y=0;y<720;y++)for(int x=0;x<640;x++){old.setPixel(x,y,x/8%2==0?Color.WHITE:Color.BLACK);next.setPixel(x,y,y/8%2==0?Color.WHITE:Color.BLACK);}
  FrameTexture detailed=FrameTexture.prepare(next,1,()->false);
  Bitmap masked=render(false,0,old,null,false,true,view->{view.setBlurFloor(28);view.setLayoutBlend(detailed,.5f);});
  int low=255,high=0;for(int x=100;x<540;x+=3)for(int y=140;y<580;y+=3){int color=masked.getPixel(x,y);low=Math.min(low,Color.red(color));high=Math.max(high,Color.red(color));assertEquals(255,Color.alpha(color));}
  assertTrue("Both layouts lose sharp detail during the exchange",high-low<20);masked.recycle();
  Bitmap clear=render(false,0,old,null,false,true,view->{view.setBlurFloor(0);view.setLayoutBlend(detailed,1);});
  for(int x=100;x<540;x+=13)for(int y=140;y<580;y+=13)assertTrue("Final frame is the full-width destination",distance(next.getPixel(x,y),clear.getPixel(x,y))<=6);clear.recycle();
 }
 @Test public void saveCalibratedRenderingSamples()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  for(boolean inner:new boolean[]{false,true})for(int angle:new int[]{0,26,60,90,120,160,180}){
   if(inner&&angle<90||!inner&&angle>90)continue;
   Bitmap bitmap=render(inner,angle);
   try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"calibrated-"+(inner?"inner":"cover")+"-"+angle+".png"))){bitmap.compress(Bitmap.CompressFormat.PNG,100,out);}
  }
 }
 @Test public void cancellingTexturePreparationReturnsNoTexture(){Bitmap b=PreviewActivity.sample(320,360);assertNull(FrameTexture.prepare(b,1,()->true));}
 private Bitmap horizontalEdge(){
  Bitmap edge=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(edge);canvas.drawColor(Color.BLACK);
  Paint p=new Paint();p.setColor(Color.WHITE);canvas.drawRect(0,360,640,720,p);return edge;
 }
 @Test public void linkingRearImageCannotChangeBlurAtTheSameAngle()throws Exception{
  // Horizontal detail is identical in the full-width and cropped rear image.
  // This catches a blur floor driven by rearBlend, including late capture completion.
  Bitmap edge=horizontalEdge();
  for(float angle:new float[]{20,21,23,26,35,50,77,80}){
   Bitmap unlinked=render(false,angle,edge,null),linked=render(false,angle,edge,edge);int largest=0;
   for(int x=80;x<600;x+=13)for(int y=270;y<450;y++)largest=Math.max(largest,distance(unlinked.getPixel(x,y),linked.getPixel(x,y)));
   assertTrue("Pairing must not change optical strength at "+angle+" degrees: "+largest,largest<=3);
  }
 }
 private double edgeSigma(Bitmap image,int x){
  double total=0,first=0,second=0;
  for(int y=180;y<540;y++){
   double weight=Math.max(0,Color.red(image.getPixel(x,y+1))-Color.red(image.getPixel(x,y)));
   total+=weight;first+=weight*y;second+=weight*y*y;
  }
  assertTrue("Edge remains present",total>240);
  return Math.sqrt(Math.max(0,second/total-Math.pow(first/total,2)));
 }
 @Test public void slowOpeningHasNoBlurCliffAroundImageExchange()throws Exception{
  Bitmap edge=horizontalEdge();double previous=-1;StringBuilder samples=new StringBuilder("angle,sigma_pixels\n");
  for(int angle=18;angle<=32;angle++){
   double sigma=edgeSigma(render(false,angle,edge,edge),480);samples.append(angle).append(',').append(sigma).append('\n');
   if(previous>=0){assertTrue("No sudden blur increase at "+angle+": "+previous+" -> "+sigma,sigma-previous<.9);assertTrue("Opening must not sharpen at "+angle,sigma>=previous-.2);}
   previous=sigma;
  }
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"blur-continuity.csv"))){out.write(samples.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
 @Test public void innerBlurGrowsContinuouslyWithAngleAndHingeDistance()throws Exception{
  Bitmap edge=horizontalEdge();double[] previous={-1,-1,-1};int[] columns={40,160,280};
  StringBuilder samples=new StringBuilder("angle,far_sigma,middle_sigma,hinge_sigma\n");
  for(int angle=180;angle>=90;angle-=2){
   Bitmap result=render(true,angle,edge,null);samples.append(angle);double priorColumn=Double.POSITIVE_INFINITY;
   for(int i=0;i<columns.length;i++){
    double sigma=edgeSigma(result,columns[i]);samples.append(',').append(sigma);
    if(previous[i]>=0){
     assertTrue("No inner blur cliff at "+angle+", x="+columns[i]+": "+previous[i]+" -> "+sigma,sigma-previous[i]<1.6);
     assertTrue("Closing should not visibly sharpen at "+angle,sigma>=previous[i]-.35);
    }
    assertTrue("Blur decreases continuously towards the hinge",sigma<=priorColumn+.35);priorColumn=sigma;previous[i]=sigma;
   }
   samples.append('\n');result.recycle();
  }
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"inner-blur-continuity.csv"))){out.write(samples.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
 @Test public void innerGlassRetainsDetailNearHingeAndFrostsTheFarEdge()throws Exception{
  Bitmap result=render(true,120,horizontalEdge(),null);
  double far=edgeSigma(result,40),middle=edgeSigma(result,160),hinge=edgeSigma(result,280);
  assertTrue("Detail by the hinge should stay readable, sigma="+hinge,hinge<1.5);
  assertTrue("The middle should be lightly frosted, sigma="+middle,middle>2&&middle<7);
  assertTrue("The far edge should remain frosted without the former heavy haze, sigma="+far,far>10&&far<20);
  assertTrue("Frost grows progressively with distance",far>middle*2&&middle>hinge*3);
 }
 @Test public void homeRoleOpensTheInteractiveHomeInsteadOfSettings()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  android.content.Intent intent=new android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).setPackage(context.getPackageName());
  java.util.List<android.content.pm.ResolveInfo> homes=context.getPackageManager().queryIntentActivities(intent,0);
  assertEquals(1,homes.size());
  assertEquals(HomeActivity.class.getName(),homes.get(0).activityInfo.name);
 }
 @Test public void blurStrengthChangesFrostButKeepsTheLiveEndpointsAndRightPane()throws Exception{
  Bitmap pattern=PreviewActivity.sample(640,720);
  Bitmap light=render(true,125,pattern,null,false,false,v->{v.duoEffect=true;v.setBlurStrength(.65f);});
  Bitmap strong=render(true,125,pattern,null,false,false,v->{v.duoEffect=true;v.setBlurStrength(1.35f);});
  long changed=0;
  for(int x=15;x<300;x+=7)for(int y=15;y<705;y+=13)changed+=distance(light.getPixel(x,y),strong.getPixel(x,y));
  assertTrue("Blur strength must affect actual GPU pixels",changed>1000);
  for(float strength:new float[]{.65f,1.35f})for(boolean inside:new boolean[]{true,false}){
   Bitmap clear=render(inside,inside?180:0,pattern,null,false,false,v->{v.duoEffect=true;v.setBlurStrength(strength);});
   for(int x=15;x<630;x+=19)for(int y=15;y<705;y+=23){
    assertTrue("Tuning must leave endpoint content clear",distance(pattern.getPixel(x,y),clear.getPixel(x,y))<=3);
    if(x>325)assertEquals("Blur strength must leave the inner right pane unchanged",light.getPixel(x,y),strong.getPixel(x,y));
   }
   clear.recycle();
  }
  light.recycle();strong.recycle();pattern.recycle();
 }
 @Test public void glassFrostSoftensFinePatternsWithoutSpeckles()throws Exception{
  Bitmap stripes=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  for(int y=0;y<720;y++)for(int x=0;x<640;x++)stripes.setPixel(x,y,(y/2)%2==0?Color.WHITE:Color.BLACK);
  for(boolean inside:new boolean[]{true,false})for(float angle:new float[]{60,90,120}){
   Bitmap result=render(inside,angle,stripes,null,false,false,v->v.duoEffect=true);
   int x=inside?40:560,low=255,high=0;
   for(int y=220;y<500;y++){int value=Color.red(result.getPixel(x,y));low=Math.min(low,value);high=Math.max(high,value);}
   assertTrue("Fine detail must soften evenly through glass, inner="+inside+", angle="+angle+", contrast="+(high-low),high-low<=8);
   result.recycle();
  }
  stripes.recycle();
 }
 @Test public void glassReversalsReuseTheSameFrameAtFullPanelSize()throws Exception{
  Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  int w=2448,h=1848;Bitmap source=PreviewActivity.sample(w,h);
  var reader=android.media.ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE|android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
  HardwareRenderer renderer=new HardwareRenderer();renderer.setSurface(reader.getSurface());
  RenderNode node=new RenderNode("glass-reversal");node.setPosition(0,0,w,h);renderer.setContentRoot(node);
  java.util.Map<String,int[]> seen=new java.util.HashMap<>();StringBuilder timing=new StringBuilder("inner,cycle,angle,submit_and_present_ms\n");
  try{
   for(boolean inside:new boolean[]{true,false}){
    DuoSnapshotRenderer glass=new DuoSnapshotRenderer(context,inside);
    for(int cycle=0;cycle<2;cycle++)for(int angle:new int[]{0,30,60,90,120,150,180,150,120,90,60,30,0}){
     long start=System.nanoTime();
     InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
      Canvas canvas=node.beginRecording();glass.draw(canvas,source,null,0,w,h,angle);node.endRecording();
      renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
     });
     timing.append(inside).append(',').append(cycle).append(',').append(angle).append(',').append((System.nanoTime()-start)/1e6).append('\n');
     android.media.Image image=null;for(int i=0;i<50&&image==null;i++){image=reader.acquireLatestImage();if(image==null)Thread.sleep(10);}
     assertNotNull(image);var buffer=image.getHardwareBuffer();
     Bitmap frame=Bitmap.wrapHardwareBuffer(buffer,ColorSpace.get(ColorSpace.Named.SRGB)).copy(Bitmap.Config.ARGB_8888,false);
     buffer.close();image.close();
     int[] samples=new int[200];int n=0;
     for(int y=50;y<h&&n<samples.length;y+=150)for(int x=50;x<w&&n<samples.length;x+=150)samples[n++]=frame.getPixel(x,y);
     String key=inside+"-"+angle;
     if(seen.containsKey(key))assertArrayEquals("Reversing the hinge must return to the same pixels: "+key,seen.get(key),samples);else seen.put(key,samples);
     if(cycle==0&&(angle==60||angle==120||angle==180))try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"glass-"+key+".png"))){frame.compress(Bitmap.CompressFormat.PNG,100,out);}
     frame.recycle();
    }
   }
  }finally{renderer.destroy();reader.close();source.recycle();}
  try(var out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),"glass-frame-times.csv"))){out.write(timing.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
 }
 @Test public void duoAppSnapshotKeepsEndpointsAndRightPaneSharpWithOppositeDepth()throws Exception{
  Bitmap white=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);white.eraseColor(Color.WHITE);
  Bitmap cover=render(false,90,white,null,false,false,v->v.duoEffect=true);
  int near=Color.red(cover.getPixel(80,360)),far=Color.red(cover.getPixel(500,360));
  assertTrue("Duo depth must shade progressively from the cover hinge: "+near+", "+far,near>far+30&&near<250&&near>150);
  Bitmap inner=render(true,90,white,null,false,false,v->v.duoEffect=true);
  assertTrue("Inner depth runs towards the left edge",Color.red(inner.getPixel(40,360))+30<Color.red(inner.getPixel(280,360)));
  Bitmap pattern=PreviewActivity.sample(640,720);
  Bitmap folded=render(true,90,pattern,null,false,false,v->v.duoEffect=true);
  for(boolean inside:new boolean[]{false,true}){
   Bitmap clear=render(inside,inside?180:0,pattern,null,false,false,v->v.duoEffect=true);
   for(int y=10;y<710;y+=17)for(int x=10;x<630;x+=17){
    assertTrue("Endpoint must reveal the actual app pixels",distance(pattern.getPixel(x,y),clear.getPixel(x,y))<=3);
    if(inside&&x>325)assertEquals("Inner right half remains sharp",clear.getPixel(x,y),folded.getPixel(x,y));
   }
   clear.recycle();
  }
  cover.recycle();inner.recycle();folded.recycle();white.recycle();pattern.recycle();
 }
 @Test public void duoLayoutExchangeBlendsBothPanesWithoutChangingTheFoldEffect()throws Exception{
  Bitmap old=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888),next=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  old.eraseColor(Color.RED);next.eraseColor(Color.BLUE);FrameTexture destination=FrameTexture.prepare(next,1,()->false);
  for(boolean inside:new boolean[]{false,true})for(float angle:new float[]{90,inside?180:0}){
   Bitmap before=render(inside,angle,old,null,false,false,v->v.duoEffect=true);
   Bitmap after=render(inside,angle,next,null,false,false,v->v.duoEffect=true);
   for(float mix:new float[]{0,.5f,1}){
    Bitmap result=render(inside,angle,old,null,false,false,v->{v.duoEffect=true;v.setLayoutBlend(destination,mix);});
    for(int x:new int[]{80,280,480}){
     int a=before.getPixel(x,360),b=after.getPixel(x,360),actual=result.getPixel(x,360);
     assertEquals("Content fades without changing hinge shading",Color.red(a)*(1-mix)+Color.red(b)*mix,Color.red(actual),3);
     assertEquals("The resized layout appears progressively",Color.blue(a)*(1-mix)+Color.blue(b)*mix,Color.blue(actual),3);
     assertEquals("No transparent hole during the exchange",255,Color.alpha(actual));
    }
    result.recycle();
   }
   Bitmap committed=render(inside,angle,old,null,false,false,v->{v.duoEffect=true;v.setLayoutBlend(destination,.5f);v.setFrame(destination);});
   assertEquals("Committing the new layout clears the old blend",after.getPixel(480,360),committed.getPixel(480,360));
   committed.recycle();before.recycle();after.recycle();
  }
  old.recycle();next.recycle();
 }
 @Test public void duoHardwareFramesTransferAndRenderWithoutChangingPixels()throws Exception{
  Bitmap source=PreviewActivity.sample(640,720),hardware=source.copy(Bitmap.Config.HARDWARE,false);
  assertNotNull("Hardware capture available",hardware);
  FrameTexture prepared=FrameTexture.prepare(source,1,()->false);
  for(boolean inside:new boolean[]{false,true}){
   FrameTexture expected=prepared.transfer(!inside,640,720);
   FrameTexture direct=FrameTexture.sharp(hardware).transfer(!inside,640,720);
   for(float angle:new float[]{90,inside?180:0}){
    Bitmap before=render(inside,angle,source,null,false,false,v->{v.duoEffect=true;v.setFrame(expected);});
    Bitmap after=render(inside,angle,source,null,false,false,v->{v.duoEffect=true;v.setFrame(direct);});
    for(int x=0;x<640;x+=19)for(int y=0;y<720;y+=23)
     assertTrue("Skipping unused CPU blur must preserve fold pixels",distance(before.getPixel(x,y),after.getPixel(x,y))<=3);
    before.recycle();after.recycle();
   }
  }
  hardware.recycle();source.recycle();
 }
 @Test public void pendingLayoutHidesReadablePlaceholderAndClearsWithoutChangingFoldPixels()throws Exception{
  Bitmap stripes=Bitmap.createBitmap(640,720,Bitmap.Config.ARGB_8888);
  for(int x=0;x<640;x++)for(int y=0;y<720;y++)stripes.setPixel(x,y,(x/12)%2==0?Color.WHITE:Color.BLACK);
  for(boolean inside:new boolean[]{false,true})for(float angle:new float[]{90,inside?180:0}){
   Bitmap normal=render(inside,angle,stripes,null,false,false,v->v.duoEffect=true);
   Bitmap hidden=render(inside,angle,stripes,null,false,false,v->{v.duoEffect=true;v.setLayoutMask(1);});
   int contrast=Math.abs(Color.red(hidden.getPixel(486,360))-Color.red(hidden.getPixel(498,360)));
   assertTrue("The temporary layout must not expose readable sharp stripes: "+contrast,contrast<10);
   for(int x:new int[]{0,80,320,639})for(int y:new int[]{0,360,719})assertEquals("Mask must remain opaque at edges",255,Color.alpha(hidden.getPixel(x,y)));
   Bitmap cleared=render(inside,angle,stripes,null,false,false,v->{v.duoEffect=true;v.setLayoutMask(1);v.setLayoutMask(0);});
   for(int x=0;x<640;x+=19)for(int y=0;y<720;y+=23)assertEquals("Clearing preparation frost preserves the existing fold",normal.getPixel(x,y),cleared.getPixel(x,y));
   normal.recycle();hidden.recycle();cleared.recycle();
  }
  stripes.recycle();
 }
 private int distance(int a,int b){return Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b));}
}
