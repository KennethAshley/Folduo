package jp.bunkaich.sukashimotion;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import rikka.shizuku.Shizuku;

public final class MainActivity extends Activity {
    private final Handler handler=new Handler();private TextView state;
    private final Shizuku.OnRequestPermissionResultListener permission=(code,result)->{if(result==0)BridgeConnection.connect(this);};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);BridgeConnection.init(this);Shizuku.addRequestPermissionResultListener(permission);
        getWindow().setNavigationBarColor(Color.rgb(16,23,20));
        ScrollView scroll=new ScrollView(this);LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(24),dp(28),dp(24),dp(40));scroll.addView(page);
        scroll.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets i=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());v.setPadding(i.left,i.top,i.right,i.bottom);return insets;});
        label(page,getString(R.string.app_name),30,Color.WHITE);
        Button language=new Button(this);language.setId(R.id.language_button);language.setAllCaps(false);language.setText(getString(R.string.language_current,languageName()));language.setOnClickListener(v->chooseLanguage());page.addView(language,new LinearLayout.LayoutParams(-1,-2));
        label(page,getString(R.string.tagline),17,0xffb3eed4);
        label(page,getString(R.string.intro),15,0xffc5d3cd);
        state=label(page,"",15,0xffb3eed4);
        label(page,getString(R.string.inner_controls_title),21,Color.WHITE);
        label(page,getString(R.string.inner_controls_body),14,0xffc5d3cd);
        label(page,getString(R.string.setup_title),21,Color.WHITE);
        label(page,getString(R.string.setup_body),14,0xffc5d3cd);
        button(page,getString(R.string.open_shizuku),()->{Intent launch=getPackageManager().getLaunchIntentForPackage("moe.shizuku.privileged.api");if(launch!=null)startActivity(launch);else startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")));});
        button(page,getString(R.string.connect_shizuku),()->{
            if(!Shizuku.pingBinder()){new AlertDialog.Builder(this).setMessage(getString(R.string.shizuku_not_running)).setPositiveButton(getString(R.string.official_guide),(d,w)->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://shizuku.rikka.app/guide/setup/")))).setNegativeButton(getString(R.string.close),null).show();return;}
            if(BridgeConnection.permitted())BridgeConnection.connect(this);else Shizuku.requestPermission(7);
        });
        button(page,getString(R.string.allow_overlay),()->startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:"+getPackageName()))));
        label(page,getString(R.string.screen_access_title),21,Color.WHITE);
        label(page,getString(R.string.screen_access_body),14,0xffc5d3cd);
        label(page,getString(R.string.power_body),14,0xffc5d3cd);
        button(page,MotionSettings.enabled(this)?getString(R.string.resume_animation):getString(R.string.enable_animation),()->startMotion("start"));
        button(page,getString(R.string.preview),()->startActivity(new Intent(this,PreviewActivity.class)));
        button(page,getString(R.string.stop),()->{MotionSettings.setEnabled(this,false);stopService(new Intent(this,MotionService.class));stopService(new Intent(this,RevealService.class));if(!MotionService.running&&!RevealService.running)BridgeConnection.disconnect();});
        label(page,getString(R.string.recovery_title),21,Color.WHITE);
        label(page,getString(R.string.recovery_body),14,0xffc5d3cd);
        label(page,getString(R.string.battery_body),14,0xffc5d3cd);
        button(page,getString(R.string.battery_settings),()->startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))));
        setContentView(scroll);handler.post(refresh);
    }
    private void startMotion(String action){
        if(!Settings.canDrawOverlays(this)){Toast.makeText(this,getString(R.string.need_overlay),Toast.LENGTH_LONG).show();return;}
        if(!BridgeConnection.permitted()){Toast.makeText(this,getString(R.string.need_shizuku),Toast.LENGTH_LONG).show();return;}
        if(!DeviceSupport.supports(Build.MODEL)){Toast.makeText(this,getString(R.string.unsupported_device),Toast.LENGTH_LONG).show();return;}
        if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},8);
        stopService(new Intent(this,MotionService.class));
        BridgeConnection.connect(this);MotionSettings.setEnabled(this,true);startForegroundService(new Intent(this,RevealService.class).setAction(action));
    }
    static String formatReport(Context c,Bundle b){
        StringBuilder text=new StringBuilder(c.getString(R.string.probe_permissions,b.getInt("uid"),c.getString(b.getBoolean("samsungPermission")?R.string.yes:R.string.no)));
        ArrayList<Bundle> rows=b.getParcelableArrayList("sensors",Bundle.class);boolean gyro=false,sub=false;
        if(rows!=null)for(Bundle r:rows){
            long count=r.getLong("events");int type=r.getInt("type");
            if(type==4&&count>0)gyro=true;if((type==65689||type==65690)&&count>0)sub=true;
            text.append('\n').append(c.getString(R.string.probe_row,r.getString("name"),type,c.getString(r.getBoolean("registered")?R.string.success:R.string.unavailable),count));
            if(type==36||type==65686)text.append(c.getString(R.string.resolution,java.text.NumberFormat.getNumberInstance(c.getResources().getConfiguration().getLocales().get(0)).format(r.getFloat("resolution"))));
            if(r.containsKey("error"))text.append('\n').append(UiText.raw(r.getString("error")).resolve(c));text.append('\n');
        }
        text.append('\n').append(c.getString(R.string.gyro_result,c.getString(gyro&&sub?R.string.both_gyros:R.string.missing_gyro)));
        text.append("\n\n").append(c.getString(R.string.display_result,UiText.raw(b.getString("display")).resolve(c)));
        if(!b.getString("error","").isEmpty())text.append('\n').append(UiText.raw(b.getString("error")).resolve(c));return text.toString();
    }
    private String languageName(){
        LocaleList locales=getSystemService(LocaleManager.class).getApplicationLocales();
        if(locales.isEmpty())return getString(R.string.language_system);
        return getString("ja".equals(locales.get(0).getLanguage())?R.string.language_japanese:R.string.language_english);
    }
    private void chooseLanguage(){
        LocaleManager manager=getSystemService(LocaleManager.class);LocaleList locales=manager.getApplicationLocales();
        int selected=locales.isEmpty()?0:("ja".equals(locales.get(0).getLanguage())?2:1);
        String[] names={getString(R.string.language_system),getString(R.string.language_english),getString(R.string.language_japanese)};
        new AlertDialog.Builder(this).setTitle(R.string.language_title).setSingleChoiceItems(names,selected,(dialog,index)->{
            dialog.dismiss();String tags=new String[]{"","en","ja"}[index];
            if(!manager.getApplicationLocales().toLanguageTags().equals(tags))manager.setApplicationLocales(LocaleList.forLanguageTags(tags));
        }).setNegativeButton(R.string.close,null).show();
    }
    private final Runnable refresh=new Runnable(){public void run(){
        String status=RevealService.running?RevealService.status.resolve(MainActivity.this):MotionService.running?MotionService.status.resolve(MainActivity.this):getString(R.string.state_stopped,BridgeConnection.status.resolve(MainActivity.this));
        state.setText(getString(R.string.state_details,status,getString(MotionSettings.enabled(MainActivity.this)?R.string.on:R.string.off),getString(Settings.canDrawOverlays(MainActivity.this)?R.string.allowed:R.string.not_allowed)));
        handler.postDelayed(this,400);
    }};
    @Override protected void onResume(){super.onResume();if(MotionSettings.enabled(this)&&!RevealService.running&&!MotionService.running&&Settings.canDrawOverlays(this))startForegroundService(new Intent(this,RevealService.class).setAction("restore"));}
    private int dp(int x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView label(LinearLayout parent,String text,int size,int color){TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(10),0,dp(10));v.setLineSpacing(dp(3),1);parent.addView(v);return v;}
    private void button(LinearLayout parent,String title,Runnable action){Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,-2));}
    @Override protected void onDestroy(){handler.removeCallbacks(refresh);Shizuku.removeRequestPermissionResultListener(permission);super.onDestroy();}
}
