package jp.bunkaich.sukashimotion;

import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import static org.junit.Assert.*;

/** Opt-in regression for registration racing the first real sensor callback. */
public class SensorReaderHardwareTest {
    @Test public void repeatedSensorRegistrationKeepsHelperAlive()throws Exception{
        org.junit.Assume.assumeTrue("true".equals(InstrumentationRegistry.getArguments().getString("folduoHardware")));
        var instrumentation=InstrumentationRegistry.getInstrumentation();
        var context=instrumentation.getTargetContext();
        var activity=instrumentation.startActivitySync(new android.content.Intent(context,MainActivity.class)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        IShellBridge bridge=null;
        try{
            BridgeConnection.connect(context);
            long until=SystemClock.elapsedRealtime()+15000;
            while(BridgeConnection.bridge==null&&SystemClock.elapsedRealtime()<until)Thread.sleep(50);
            bridge=BridgeConnection.bridge;assertNotNull("Real Shizuku helper",bridge);
            var sink=new IAngleSink.Stub(){public void angle(float a,long at,int source){}};
            int sampled=0;
            for(int attempt=0;attempt<60;attempt++){
                bridge.startAngles(sink);
                Thread.sleep(60);
                Bundle status=bridge.inspect();
                assertTrue("Reader remains active at restart "+attempt,status.getBoolean("running"));
                var rows=status.getParcelableArrayList("sensors",Bundle.class);
                assertNotNull(rows);
                if(rows.stream().anyMatch(row->row.getBoolean("registered")&&row.getLong("events")>0))sampled++;
            }
            assertTrue("Real sensor callbacks were exercised",sampled>0);
            Bundle result=new Bundle();result.putString("stream","SENSOR_RESTARTS_OK: 60 restarts; "+sampled+" rounds received hardware samples.\n");
            instrumentation.sendStatus(0,result);
        }finally{
            try{if(bridge!=null&&bridge.asBinder().isBinderAlive())bridge.stopAngles();}
            catch(android.os.DeadObjectException ignored){}
            finally{instrumentation.runOnMainSync(activity::finish);}
        }
    }
}
