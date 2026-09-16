package jp.bunkaich.sukashimotion;
import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import java.util.List;
import static org.junit.Assert.*;
public class InnerNavigationTest {
 Activity activity;InnerNavigation nav;volatile int action=-1,task=-1;
 void ui(Runnable task){InstrumentationRegistry.getInstrumentation().runOnMainSync(task);}
 View root()throws Exception{var f=InnerNavigation.class.getDeclaredField("root");f.setAccessible(true);return (View)f.get(nav);}
 View find(View v,String label){if(label.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return v;if(v instanceof ViewGroup group)for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),label);if(found!=null)return found;}return null;}
 @Before public void start(){Context c=InstrumentationRegistry.getInstrumentation().getTargetContext();MotionSettings.setEnabled(c,false);activity=InstrumentationRegistry.getInstrumentation().startActivitySync(new Intent(c,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));ui(()->{activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);nav=new InnerNavigation(activity.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null),0,1968,2184,(a,t)->{action=a;task=t;});});}
 @After public void stop(){ui(()->{nav.close();activity.finish();});}
 @Test public void systemInputReachesThePhysicalInnerSwipeAreas()throws Exception{
  org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
  var instrumentation=InstrumentationRegistry.getInstrumentation();Context c=instrumentation.getTargetContext();IShellBridge real=null;
  try{
   BridgeConnection.connect(c);long deadline=SystemClock.elapsedRealtime()+10000;while(BridgeConnection.bridge==null&&SystemClock.elapsedRealtime()<deadline)Thread.sleep(50);assertNotNull(BridgeConnection.bridge);real=BridgeConnection.bridge;
   org.junit.Assume.assumeTrue("Phone stays open and unlocked","OPENED".equals(real.inspect().getString("baseState"))&&!c.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked());
   real.startAngles(new IAngleSink.Stub(){public void angle(float value,long at,int source){}});
   Bundle held=real.hold(false,0);assertTrue(held.toString(),held.getBoolean("ok"));Thread.sleep(400);
   Bundle moved=real.moveApp(0,1,false);assertTrue(moved.toString(),moved.getBoolean("ok"));Thread.sleep(400);
   ui(()->{
    nav.close();Display display=c.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(1);
    Context local=c.createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,null);
    android.graphics.Rect bounds=local.getSystemService(WindowManager.class).getMaximumWindowMetrics().getBounds();
    nav=new InnerNavigation(local,1,bounds.width(),bounds.height(),(a,t)->{action=a;task=t;});
   });instrumentation.waitForIdleSync();Thread.sleep(250);
   View bottom=root();float d=bottom.getResources().getDisplayMetrics().density;
   injectSwipe(bottom,0,-100*d,false);assertEquals("Real inner-display touch swipe must reach Home",KeyEvent.KEYCODE_HOME,action);action=-1;
   injectSwipe(bottom,0,-100*d,true);assertEquals("Real inner-display hold must reach Recents",KeyEvent.KEYCODE_APP_SWITCH,action);action=-1;
   var field=InnerNavigation.class.getDeclaredField("edges");field.setAccessible(true);var edges=List.copyOf((List<?>)field.get(nav));
   for(int i=0;i<2;i++){injectSwipe((View)edges.get(i),(i==0?100:-100)*d,0,false);assertEquals("Real side swipe must reach Back",KeyEvent.KEYCODE_BACK,action);action=-1;}
   if("true".equals(InstrumentationRegistry.getArguments().getString("manualSwipe"))){
    IShellBridge shell=real;var result=new java.util.concurrent.atomic.AtomicReference<Bundle>();
    var controls=java.util.concurrent.Executors.newSingleThreadExecutor();
    try{
     ui(()->{Context local=bottom.getContext();int w=nav.width,h=nav.height;nav.close();nav=new InnerNavigation(local,1,w,h,(a,t)->controls.execute(()->{
      try{Bundle response=shell.navigate(1,a,t);response.putInt("action",a);result.set(response);}catch(Exception e){Bundle response=new Bundle();response.putString("error",e.toString());result.set(response);}
     }));});
     Bundle ready=new Bundle();ready.putString("stream","MANUAL_SWIPE_READY: swipe upward starting on the small white line at the bottom; 90 seconds.\n");instrumentation.sendStatus(0,ready);
     long until=SystemClock.elapsedRealtime()+90000;
     while(result.get()==null&&SystemClock.elapsedRealtime()<until&&!c.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked())Thread.sleep(100);
     assertNotNull("A finger swipe must reach the inner navigation",result.get());assertTrue(result.get().toString(),result.get().getBoolean("ok"));
     Bundle report=new Bundle();report.putString("stream","MANUAL_NAVIGATION_RESULT action="+result.get().getInt("action")+" ok="+result.get().getBoolean("ok")+"\n");instrumentation.sendStatus(0,report);Thread.sleep(5000);
    }finally{controls.shutdown();controls.awaitTermination(5,java.util.concurrent.TimeUnit.SECONDS);}
   }
  }finally{ui(()->nav.close());if(real!=null)try{real.release();real.stopAngles();}finally{BridgeConnection.disconnect();}MotionSettings.setEnabled(c,false);}
 }
 private void injectSwipe(View view,float dx,float dy,boolean hold)throws Exception{
  int[] xy=new int[2];ui(()->view.getLocationOnScreen(xy));float x=xy[0]+view.getWidth()/2f,y=xy[1]+view.getHeight()/2f;
  Bundle report=new Bundle();report.putString("stream","SWIPE_WINDOW display="+view.getDisplay().getDisplayId()+" x="+xy[0]+" y="+xy[1]+" size="+view.getWidth()+"x"+view.getHeight()+" delta="+dx+","+dy+"\n");InstrumentationRegistry.getInstrumentation().sendStatus(0,report);
  long down=SystemClock.uptimeMillis();inject(down,MotionEvent.ACTION_DOWN,x,y);Thread.sleep(40);inject(down,MotionEvent.ACTION_MOVE,x+dx,y+dy);if(hold)Thread.sleep(450);else Thread.sleep(60);inject(down,MotionEvent.ACTION_UP,x+dx,y+dy);InstrumentationRegistry.getInstrumentation().waitForIdleSync();Thread.sleep(100);
 }
 private void inject(long down,int kind,float x,float y)throws Exception{
  MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),kind,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);
  InputEvent.class.getMethod("setDisplayId",int.class).invoke(e,1);
  try{assertTrue("Input injection accepted",InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(e,true));}finally{e.recycle();}
 }
 @Test public void swipesNavigateWithoutTriggeringOnTapsOrCancelledTouches()throws Exception{
  View bottom=root();float d=activity.getResources().getDisplayMetrics().density;
  touch(bottom,MotionEvent.ACTION_DOWN,100,20);touch(bottom,MotionEvent.ACTION_UP,100,20);assertEquals(-1,action);
  touch(bottom,MotionEvent.ACTION_DOWN,100,20);touch(bottom,MotionEvent.ACTION_MOVE,100,20-100*d);touch(bottom,MotionEvent.ACTION_UP,100,20-100*d);
  assertEquals("An upward swipe must go Home",KeyEvent.KEYCODE_HOME,action);action=-1;
  bottom=root();touch(bottom,MotionEvent.ACTION_DOWN,100,20);touch(bottom,MotionEvent.ACTION_MOVE,100,20-100*d);Thread.sleep(450);
  assertEquals("Holding an upward swipe must open Recents",KeyEvent.KEYCODE_APP_SWITCH,action);touch(bottom,MotionEvent.ACTION_UP,100,20-100*d);assertEquals(KeyEvent.KEYCODE_APP_SWITCH,action);action=-1;
  bottom=root();touch(bottom,MotionEvent.ACTION_DOWN,100,20);touch(bottom,MotionEvent.ACTION_MOVE,100,20-100*d);touch(bottom,MotionEvent.ACTION_CANCEL,100,20-100*d);Thread.sleep(450);assertEquals(-1,action);
  var field=InnerNavigation.class.getDeclaredField("edges");field.setAccessible(true);var edges=(List<?>)field.get(nav);assertEquals(2,edges.size());
  for(int i=0;i<2;i++){View edge=(View)edges.get(i);float dx=(i==0?100:-100)*d;touch(edge,MotionEvent.ACTION_DOWN,10,100);touch(edge,MotionEvent.ACTION_MOVE,10+dx,100);touch(edge,MotionEvent.ACTION_UP,10+dx,100);assertEquals(KeyEvent.KEYCODE_BACK,action);action=-1;}
  bottom=root();touch(bottom,MotionEvent.ACTION_DOWN,100,20);touch(bottom,MotionEvent.ACTION_MOVE,100,20-100*d);ui(nav::close);Thread.sleep(450);assertEquals("Removal cancels a pending hold",-1,action);
 }
 void touch(View view,int kind,float x,float y){ui(()->{long t=SystemClock.uptimeMillis();MotionEvent e=MotionEvent.obtain(t,t,kind,x,y,0);view.dispatchTouchEvent(e);e.recycle();});}
 @Test public void accessibleHomeAndRecentsKeepSettingsAvailable()throws Exception{
  View bottom=root();ui(bottom::performClick);assertEquals(KeyEvent.KEYCODE_HOME,action);
  bottom=root();View held=bottom;ui(held::performLongClick);assertEquals(KeyEvent.KEYCODE_APP_SWITCH,action);
  ui(()->nav.showRecent(List.of()));View row=root();ui(()->find(row,activity.getString(R.string.nav_settings)).performClick());assertEquals(InnerNavigation.SETTINGS,action);
 }
 @Test public void cardSelectsExistingTaskAndClosesOnlyThePanel()throws Exception{
  Bundle app=new Bundle();app.putInt("taskId",27);app.putString("label","電卓");ui(()->nav.showRecent(List.of(app)));View row=root();ui(()->find(row,"電卓").performClick());assertEquals(0,action);assertEquals(27,task);assertFalse(nav.showingRecents());assertNotNull(find(root(),activity.getString(R.string.nav_home)));
 }
 @Test public void backInRecentsDoesNotCloseUnderlyingApp()throws Exception{
  ui(()->nav.showRecent(List.of()));View row=root();ui(()->find(row,activity.getString(R.string.nav_back)).performClick());assertEquals(-1,action);assertFalse(nav.showingRecents());
 }
 @Test public void controlsNeverRequestAppResizingOrKeyboardFocus()throws Exception{
  WindowManager.LayoutParams p=(WindowManager.LayoutParams)root().getLayoutParams();assertTrue((p.flags&WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)!=0);assertEquals(0,p.getFitInsetsTypes());assertEquals(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,p.type);
 }
 @Test public void latePreviewCannotReopenDismissedPanel()throws Exception{
  ui(()->nav.showRecent(List.of()));View row=root();ui(()->find(row,activity.getString(R.string.nav_back)).performClick());ui(()->nav.setPreview(4,Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888)));assertFalse(nav.showingRecents());
 }
 @Test public void closingRemovesTouchableWindow()throws Exception{View before=root();var field=InnerNavigation.class.getDeclaredField("edges");field.setAccessible(true);var edges=List.copyOf((List<?>)field.get(nav));ui(()->nav.close());assertFalse(before.isAttachedToWindow());assertNull(root());for(Object edge:edges)assertFalse(((View)edge).isAttachedToWindow());}
}
