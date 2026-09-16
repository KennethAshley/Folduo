package jp.bunkaich.sukashimotion;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Display-local controls. An overlay never creates or changes the app's system insets. */
final class InnerNavigation {
    static final int SETTINGS=1000, PREVIEW=1001;
    interface Actions {void run(int action,int taskId);}
    private final Context context;private final WindowManager wm;private final Actions actions;
    private View root;private volatile boolean recents;
    private final List<View> edges=new ArrayList<>();
    private final Map<Integer,ImageView> previews=new HashMap<>();
    final int displayId,width,height;
    InnerNavigation(Context c,int display,int width,int height,Actions actions){context=c;displayId=display;this.width=width;this.height=height;this.actions=actions;wm=c.getSystemService(WindowManager.class);try{collapse();}catch(RuntimeException e){close();throw e;}}
    private int dp(float n){return Math.round(n*context.getResources().getDisplayMetrics().density);}
    private GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private WindowManager.LayoutParams layout(int w,int h,int gravity){
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(w,h,WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.TRANSLUCENT);
        p.gravity=gravity;p.setFitInsetsTypes(0);
        p.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        p.setTitle("Folduo inner navigation");p.windowAnimations=0;
        return p;
    }
    private void replace(View view,int w,int h){
        removeWindows();root=view;
        WindowManager.LayoutParams p=layout(w,h,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);p.y=recents?dp(8):0;wm.addView(root,p);
    }
    private void perform(int key){
        if(BuildConfig.DEBUG)android.util.Log.i("FolduoNavigation","action="+key+" display="+displayId+" recents="+recents);
        if(key==KeyEvent.KEYCODE_BACK&&recents){collapse();return;}
        if(recents&&key!=KeyEvent.KEYCODE_APP_SWITCH)collapse();
        actions.run(key,-1);
    }
    private LinearLayout buttons(){
        LinearLayout row=new LinearLayout(context);row.setGravity(Gravity.CENTER);row.setPadding(dp(8),dp(4),dp(8),dp(4));row.setBackground(background(0xee22252c,26));
        int[] keys={KeyEvent.KEYCODE_APP_SWITCH,KeyEvent.KEYCODE_HOME,KeyEvent.KEYCODE_BACK,SETTINGS};
        String[] labels={context.getString(R.string.nav_recents),context.getString(R.string.nav_home),context.getString(R.string.nav_back),context.getString(R.string.nav_settings)};
        for(int i=0;i<keys.length;i++){
            int key=keys[i];NavButton button=new NavButton(context,key);button.setContentDescription(labels[i]);
            button.setOnClickListener(v->perform(key));row.addView(button,new LinearLayout.LayoutParams(0,dp(44),1));
        }
        return row;
    }
    @android.annotation.SuppressLint("RtlHardcoded") // Swipe direction follows the physical edge in every language.
    private void collapse(){
        recents=false;previews.clear();replace(new SwipeStrip(0),WindowManager.LayoutParams.MATCH_PARENT,dp(24));
        for(int side:new int[]{1,-1}){
            View edge=new SwipeStrip(side);edges.add(edge);
            wm.addView(edge,layout(dp(18),Math.max(dp(48),height-dp(192)),Gravity.CENTER_VERTICAL|(side==1?Gravity.LEFT:Gravity.RIGHT)));
        }
    }
    boolean showingRecents(){return recents;}
    void showRecent(List<Bundle> apps){
        recents=true;previews.clear();
        LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(14),dp(14),dp(14),dp(8));panel.setBackground(background(0xfa191c22,26));
        LinearLayout heading=new LinearLayout(context);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=new TextView(context);title.setText(context.getString(R.string.nav_recents));title.setTextSize(19);title.setTextColor(Color.WHITE);heading.addView(title,new LinearLayout.LayoutParams(0,dp(40),1));
        Button close=new Button(context);close.setText(context.getString(R.string.close));close.setOnClickListener(v->collapse());heading.addView(close);panel.addView(heading);
        ScrollView scroll=new ScrollView(context);LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        int panelWidth=Math.min(width-dp(32),dp(680));int cardWidth=(panelWidth-dp(44))/2;
        if(apps.isEmpty()){TextView empty=new TextView(context);empty.setText(context.getString(R.string.no_recent_apps));empty.setTextColor(Color.WHITE);empty.setPadding(dp(12),dp(32),dp(12),dp(32));list.addView(empty);}
        LinearLayout row=null;
        for(int i=0;i<apps.size();i++){
            Bundle app=apps.get(i);int task=app.getInt("taskId",-1);String label=app.getString("label","");
            if(i%2==0){row=new LinearLayout(context);list.addView(row);}
            LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(8),dp(8),dp(8),dp(8));card.setBackground(background(0xff2d323b,16));
            ImageView preview=new ImageView(context);preview.setImageBitmap(app.getParcelable("icon",Bitmap.class));
            String packageName=app.getString("package");
            if(packageName!=null)try{preview.setImageDrawable(context.getPackageManager().getApplicationIcon(packageName));}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
            preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);preview.setBackgroundColor(0xff252a32);card.addView(preview,new LinearLayout.LayoutParams(-1,dp(160)));previews.put(task,preview);
            TextView name=new TextView(context);name.setText(label);name.setTextSize(15);name.setTextColor(Color.WHITE);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(dp(4),dp(10),dp(4),dp(4));card.addView(name);
            card.setContentDescription(label);card.setClickable(true);card.setFocusable(true);card.setOnClickListener(v->{collapse();actions.run(0,task);});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(cardWidth,dp(214));lp.setMargins(dp(3),dp(4),dp(3),dp(4));row.addView(card,lp);
        }
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));panel.addView(buttons(),new LinearLayout.LayoutParams(-1,dp(52)));
        replace(panel,panelWidth,Math.min(height-dp(100),dp(610)));
    }
    void setPreview(int task,Bitmap bitmap){ImageView view=previews.get(task);if(!recents||view==null)return;view.setScaleType(ImageView.ScaleType.FIT_CENTER);view.setImageBitmap(bitmap);}
    private void removeWindows(){
        for(View edge:edges)if(edge.isAttachedToWindow())wm.removeViewImmediate(edge);edges.clear();
        if(root!=null&&root.isAttachedToWindow())wm.removeViewImmediate(root);root=null;
    }
    void close(){recents=false;previews.clear();removeWindows();}
    private final class SwipeStrip extends View {
        final int side;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        float startX,startY,lastX,lastY;boolean tracking,valid;
        final Runnable held=()->{if(tracking&&valid){tracking=false;performHapticFeedback(HapticFeedbackConstants.CONFIRM);perform(KeyEvent.KEYCODE_APP_SWITCH);}};
        SwipeStrip(int side){
            super(context);this.side=side;setClickable(true);setFocusable(true);
            setContentDescription(context.getString(side==0?R.string.nav_home:R.string.nav_back));
            setStateDescription(context.getString(side==0?R.string.nav_swipe_bottom:R.string.nav_swipe_side));
            setOnClickListener(v->perform(side==0?KeyEvent.KEYCODE_HOME:KeyEvent.KEYCODE_BACK));
            if(side==0)setOnLongClickListener(v->{perform(KeyEvent.KEYCODE_APP_SWITCH);return true;});
        }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info){
            super.onInitializeAccessibilityNodeInfo(info);
            if(side==0)info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK,context.getString(R.string.nav_recents)));
        }
        @Override public boolean performClick(){super.performClick();return true;}
        @Override protected void onDraw(Canvas canvas){
            if(side!=0)return;
            float x=getWidth()/2f,y=getHeight()-dp(7);paint.setColor(0xaa000000);canvas.drawRoundRect(x-dp(29),y-dp(3),x+dp(29),y+dp(3),dp(3),dp(3),paint);
            paint.setColor(0xffeeeeee);canvas.drawRoundRect(x-dp(27),y-dp(2),x+dp(27),y+dp(2),dp(2),dp(2),paint);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            if(BuildConfig.DEBUG&&event.getActionMasked()!=MotionEvent.ACTION_MOVE)android.util.Log.i("FolduoNavigation","touch side="+side+" event="+event.getActionMasked()+" device="+event.getDeviceId()+" x="+event.getRawX()+" y="+event.getRawY()+" tracking="+tracking+" valid="+valid);
            if(event.getPointerCount()!=1){cancel();return true;}
            float x=event.getRawX(),y=event.getRawY();
            switch(event.getActionMasked()){
                case MotionEvent.ACTION_DOWN -> {cancel();tracking=true;startX=lastX=x;startY=lastY=y;}
                case MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP -> {
                    if(!tracking)return true;
                    float dx=x-startX,dy=y-startY;
                    boolean wasValid=valid;
                    valid=side==0?-dy>=dp(64)&&-dy>Math.abs(dx):dx*side>=dp(48)&&Math.abs(dx)>Math.abs(dy);
                    // A hold starts after the finger slows down above the threshold.
                    // Distance uses dp so the same controls work after rotation/density changes.
                    if(side==0&&(!valid||!wasValid||Math.hypot(x-lastX,y-lastY)>dp(8))){
                        removeCallbacks(held);if(valid)postDelayed(held,350);lastX=x;lastY=y;
                    }
                    if(event.getActionMasked()==MotionEvent.ACTION_UP){boolean fire=valid;cancel();if(fire){performHapticFeedback(HapticFeedbackConstants.CONFIRM);performClick();}}
                }
                case MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN -> cancel();
            }
            return true;
        }
        private void cancel(){tracking=valid=false;removeCallbacks(held);}
        @Override protected void onDetachedFromWindow(){cancel();super.onDetachedFromWindow();}
    }
    private static final class NavButton extends View {
        final int key;final Paint p=new Paint(3);final float density;
        NavButton(Context c,int key){super(c);this.key=key;density=c.getResources().getDisplayMetrics().density;setClickable(true);setFocusable(true);}
        protected void onDraw(Canvas canvas){super.onDraw(canvas);float cx=getWidth()/2f,cy=getHeight()/2f,r=8*density;p.setColor(Color.WHITE);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.7f*density);p.setStrokeCap(Paint.Cap.ROUND);
            if(key==KeyEvent.KEYCODE_HOME)canvas.drawRoundRect(cx-r,cy-r,cx+r,cy+r,2*density,2*density,p);
            else if(key==KeyEvent.KEYCODE_BACK){canvas.drawLine(cx+r/2,cy-r,cx-r/2,cy,p);canvas.drawLine(cx-r/2,cy,cx+r/2,cy+r,p);}
            else if(key==SETTINGS){canvas.drawCircle(cx,cy,r*.8f,p);canvas.drawCircle(cx,cy,r*.28f,p);for(int i=0;i<8;i++){double a=i*Math.PI/4;canvas.drawLine(cx+(float)Math.cos(a)*r*.8f,cy+(float)Math.sin(a)*r*.8f,cx+(float)Math.cos(a)*r*1.16f,cy+(float)Math.sin(a)*r*1.16f,p);}}
            else {canvas.drawRoundRect(cx-r,cy-r,cx+r*.45f,cy+r*.7f,2*density,2*density,p);canvas.drawLine(cx+r,cy-r*.55f,cx+r,cy+r,p);}
        }
    }
}
