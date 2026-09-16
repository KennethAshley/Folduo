package jp.bunkaich.sukashimotion;

import android.app.ActivityManager;
import android.content.Intent;
import android.os.Parcel;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

public class SecondaryLaunchListenerTest {
    private ActivityManager.RunningTaskInfo task;
    @Test public void acceptsOnlySystemEventsForARealTaskRequestedOnTheInnerDisplay(){
        var instrumentation=InstrumentationRegistry.getInstrumentation();var context=instrumentation.getTargetContext();
        MotionSettings.setEnabled(context,false);
        var activity=instrumentation.startActivitySync(new Intent(context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try{
        // Samsung requires a real WindowContainerToken when parceling a task.
        task=context.getSystemService(ActivityManager.class).getRunningTasks(1).get(0);
        assertEquals(31,read(1000,31,1,"android.app.ITaskStackListener"));
        assertEquals(-1,read(2000,31,1,"android.app.ITaskStackListener"));
        assertEquals(-1,read(10503,31,1,"android.app.ITaskStackListener"));
        assertEquals(-1,read(1000,31,0,"android.app.ITaskStackListener"));
        assertEquals(-1,read(1000,-1,1,"android.app.ITaskStackListener"));
        assertEquals(-1,read(1000,31,1,"wrong.interface"));
        Parcel empty=Parcel.obtain();try{assertEquals(-1,SecondaryLaunchListener.blockedTask(empty,1000));}finally{empty.recycle();}
        }finally{instrumentation.runOnMainSync(activity::finish);}
    }
    private int read(int caller,int taskId,int display,String descriptor){
        Parcel data=Parcel.obtain();try{
            data.writeInterfaceToken(descriptor);task.taskId=taskId;
            data.writeTypedObject(task,0);data.writeInt(display);data.setDataPosition(0);
            return SecondaryLaunchListener.blockedTask(data,caller);
        }finally{data.recycle();}
    }
}
