package jp.bunkaich.sukashimotion;

import android.app.Activity;
import android.content.*;
import android.os.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.*;
import org.junit.runner.RunWith;
import java.io.*;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ServiceLifecycleTest {
 Context context;Activity activity;FakeBridge bridge;
 static class FakeBridge extends IShellBridge.Stub {
  volatile IAngleSink sink;volatile int captures,holds,releases,moves,starts;CountDownLatch holdEntered,allowHold,stopEntered,allowStop;
  public Bundle inspect(){return new Bundle();}
  public Bundle capture(int id){captures++;Bundle b=new Bundle();b.putParcelable("frame",PreviewActivity.sample(400,500));return b;}
  public Bundle captureBehind(int id,android.view.SurfaceControl[] exclude){return capture(id);}
  public Bundle windowState(int id){Bundle b=new Bundle();b.putBoolean("ready",true);b.putString("geometry","test");return b;}
  public Bundle moveApp(int source,int target,boolean idle){moves++;Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle statusIcons(boolean hidden){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle navigate(int displayId,int action,int taskId){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle launchApp(int displayId,String component){Bundle b=new Bundle();b.putBoolean("ok",true);b.putBoolean("handled",true);return b;}
  public Bundle routeInnerLaunches(boolean enabled){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle mirror(android.view.Surface surface,android.view.SurfaceControl parent,int width,int height,int density){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle workspace(boolean inner){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public void mirrorTouch(android.view.MotionEvent event,int width,int height){}
  public Bundle innerWallpaper(){Bundle b=new Bundle();b.putParcelable("frame",PreviewActivity.sample(400,500));b.putBoolean("ok",true);return b;}
  public Bundle hold(boolean inner,int previousOwner){holds++;if(holdEntered!=null){holdEntered.countDown();try{allowHold.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle holdNative(boolean inner){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle holdPaired(boolean inner){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle excludeFromMirror(android.view.SurfaceControl surface){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public Bundle outerDarkness(float amount){Bundle b=new Bundle();b.putBoolean("ok",true);return b;}
  public void release(){releases++;}public void heartbeat(){}public void startAngles(IAngleSink sink){this.sink=sink;starts++;}public void stopAngles(){if(stopEntered!=null){stopEntered.countDown();try{allowStop.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}sink=null;}public void destroy(){}
 }
 interface Check { boolean ok(); }
 void waitFor(Check check)throws Exception{long end=SystemClock.elapsedRealtime()+5000;while(!check.ok()&&SystemClock.elapsedRealtime()<end)Thread.sleep(20);assertTrue("Condition reached before timeout",check.ok());}
 @Before public void start()throws Exception{
  context=InstrumentationRegistry.getInstrumentation().getTargetContext();
  MotionSettings.setEnabled(context,false);
  activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
  bridge=new FakeBridge();BridgeConnection.bridge=bridge;
  context.startForegroundService(new Intent(context,MotionService.class));waitFor(()->MotionService.running&&bridge.sink!=null);
 }
 @After public void stop()throws Exception{
  if(bridge.allowHold!=null)bridge.allowHold.countDown();if(bridge.allowStop!=null)bridge.allowStop.countDown();MotionSettings.setEnabled(context,false);context.stopService(new Intent(context,MotionService.class));waitFor(()->!MotionService.running);waitFor(()->bridge.sink==null);
  InstrumentationRegistry.getInstrumentation().runOnMainSync(()->activity.finish());Thread.sleep(100);
 }
 @Test public void coarseAnglesDoNotCaptureOrInventIntermediateValues()throws Exception{
  for(float angle:new float[]{180,90,0,90,180})bridge.sink.angle(angle,SystemClock.elapsedRealtime(),0);
  Thread.sleep(250);assertEquals(0,bridge.captures);assertEquals(0,bridge.moves);
  // A coarse CLOSED reading may prepare an already-active cover, but must never animate.
  assertTrue(bridge.holds<=1);
 }
 @Test public void closedCoverPreparesAfterAngleReaderRestarts()throws Exception{
  var mode=context.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(0).getMode();
  org.junit.Assume.assumeTrue(Math.min(mode.getPhysicalWidth(),mode.getPhysicalHeight())/(float)Math.max(mode.getPhysicalWidth(),mode.getPhysicalHeight())<.7f);
  bridge.sink.angle(0,SystemClock.elapsedRealtime(),0);
  waitFor(()->bridge.holds==1);
  assertEquals("Coarse state prepares the mapping without a made-up animation",0,bridge.captures);
  assertEquals(0,bridge.moves);
 }
 @Test public void stopWhileWaitingForClosureDoesNotMoveApps()throws Exception{
  bridge.sink.angle(120,SystemClock.elapsedRealtime(),3);Thread.sleep(200);
  context.stopService(new Intent(context,MotionService.class));waitFor(()->!MotionService.running);waitFor(()->bridge.releases>0);
  assertEquals(0,bridge.captures);assertEquals(0,bridge.moves);assertEquals(0,bridge.holds);
 }
 @Test public void innerDisplayNeverArmsFromAngleAlone()throws Exception{
  // This test device has an inner-shaped physical mode. Even an angle of zero must
  // not arm OUTER_DEFAULT until the normal cover mapping really exists.
  for(float angle:new float[]{180,120,0,0,90,180})bridge.sink.angle(angle,SystemClock.elapsedRealtime(),3);
  Thread.sleep(250);assertEquals(0,bridge.captures);assertEquals(0,bridge.moves);assertEquals(0,bridge.holds);
  assertTrue(MotionService.status.is(R.string.close_to_prepare));
 }
 @Test public void transparentAnchorBecomesWallpaperTargetBehindOpaqueApp()throws Exception{
  Thread.sleep(150);
  ParcelFileDescriptor fd=InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand("dumpsys window");
  String dump;try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(fd)){dump=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
  boolean target=dump.lines().anyMatch(line->line.contains("mWallpaperTarget")&&line.contains("Folduo angle anchor"));
  assertTrue("Wallpaper must stay active while a normal opaque activity is in front",target);
 }
 @Test public void screenOffSuspendsAnglesAndWakeRestartsThem()throws Exception{
  try{
   shell("input keyevent KEYCODE_SLEEP");waitFor(()->bridge.sink==null);
   shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");waitFor(()->bridge.sink!=null);
  }finally{shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");}
 }
 @Test public void newBridgeAutomaticallyRestartsAngleSubscription()throws Exception{
  FakeBridge previous=bridge;IAngleSink oldSink=previous.sink;
  bridge=new FakeBridge();BridgeConnection.bridge=bridge;
  waitFor(()->bridge.sink!=null&&previous.sink==null);
  oldSink.angle(99,SystemClock.elapsedRealtime(),1);Thread.sleep(100);
  assertFalse("Old callbacks must not feed the new connection",MotionService.status.resolve(context).contains("99°"));
  bridge.sink.angle(120,SystemClock.elapsedRealtime(),1);waitFor(()->MotionService.status.is(R.string.close_to_prepare));
 }
 @Test public void stoppedAngleReaderRecoversWithoutRestartingApplication()throws Exception{
  int initial=bridge.starts;bridge.sink.angle(120,SystemClock.elapsedRealtime(),1);
  waitFor(()->MotionService.status.is(R.string.close_to_prepare));
  long deadline=SystemClock.elapsedRealtime()+8000;
  while(bridge.starts==initial&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);
  assertTrue("A live binder with a stalled reader must be re-subscribed",bridge.starts>initial);
  assertTrue(MotionService.running);assertTrue(MotionSettings.recovery(context).equals(context.getString(R.string.angle_recovery)));
 }
 @Test public void slowScreenOffCleanupCannotStopNewWakeSubscription()throws Exception{
  bridge.stopEntered=new CountDownLatch(1);bridge.allowStop=new CountDownLatch(1);
  try{
   shell("input keyevent KEYCODE_SLEEP");assertTrue(bridge.stopEntered.await(2,TimeUnit.SECONDS));
   int before=bridge.starts;shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");Thread.sleep(250);
   bridge.allowStop.countDown();waitFor(()->bridge.starts>before&&bridge.sink!=null);Thread.sleep(200);assertNotNull(bridge.sink);
  }finally{bridge.allowStop.countDown();shell("input keyevent KEYCODE_WAKEUP");shell("wm dismiss-keyguard");}
 }
 @Test public void explicitStopDisablesAutomaticRestore()throws Exception{
  assertTrue(MotionSettings.enabled(context));
  context.startService(new Intent(context,MotionService.class).setAction("stop"));waitFor(()->!MotionService.running);
  assertFalse(MotionSettings.enabled(context));
  new RestartReceiver().onReceive(context,new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));Thread.sleep(200);
  assertFalse("A user stop must never resurrect itself",MotionService.running);
 }
 @Test public void packageUpdateRestoresNativeRevealService()throws Exception{
  context.stopService(new Intent(context,MotionService.class));waitFor(()->!MotionService.running);waitFor(()->bridge.sink==null);Thread.sleep(150);
  assertTrue(MotionSettings.enabled(context));BridgeConnection.bridge=bridge;
  int starts=bridge.starts;
  try{
   new RestartReceiver().onReceive(context,new Intent(Intent.ACTION_MY_PACKAGE_REPLACED));waitFor(()->RevealService.running);
   waitFor(()->bridge.starts>starts);
   assertFalse(MotionService.running);
  }finally{context.stopService(new Intent(context,RevealService.class));context.stopService(new Intent(context,MotionService.class));waitFor(()->!RevealService.running&&!MotionService.running);}
 }
 private void shell(String command)throws Exception{
  try(InputStream in=new ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(command))){in.readAllBytes();}
 }
}
