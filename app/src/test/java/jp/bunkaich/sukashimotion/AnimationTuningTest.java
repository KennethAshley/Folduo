package jp.bunkaich.sukashimotion;

import org.junit.Test;
import static org.junit.Assert.*;

public class AnimationTuningTest {
    @Test public void defaultAndInvalidPresetsKeepTheAcceptedAnimation(){
        for(int preset:new int[]{1,-1,3,Integer.MAX_VALUE}){
            assertEquals(1,MotionSettings.blurStrength(preset),0);
            assertEquals(.024f,MotionSettings.responseSeconds(preset),0);
            assertEquals(.4f,FoldPolicy.outerDarkness(75,MotionSettings.fadeStart(preset)),.0001f);
        }
    }
    @Test public void fasterResponseTracksSoonerWithoutOvershoot(){
        float quick=FoldPolicy.smooth(0,90,.016f,MotionSettings.responseSeconds(0));
        float normal=FoldPolicy.smooth(0,90,.016f,MotionSettings.responseSeconds(1));
        float soft=FoldPolicy.smooth(0,90,.016f,MotionSettings.responseSeconds(2));
        assertTrue(quick>normal&&normal>soft);assertTrue(quick<90&&soft>0);
        for(int preset=0;preset<3;preset++){
            float next=FoldPolicy.smooth(90,0,.016f,MotionSettings.responseSeconds(preset));
            assertTrue("A reversal stays inside the measured angles",next>0&&next<90);
        }
    }
    @Test public void fadeChoicesShiftTimingButAlwaysCoverBeforeFullOpen(){
        assertEquals(.6f,FoldPolicy.outerDarkness(70,MotionSettings.fadeStart(0)),.0001f);
        assertEquals(1/3f,FoldPolicy.outerDarkness(70,MotionSettings.fadeStart(1)),.0001f);
        assertEquals(1/15f,FoldPolicy.outerDarkness(70,MotionSettings.fadeStart(2)),.0001f);
        for(int preset=0;preset<3;preset++){
            float start=MotionSettings.fadeStart(preset),last=0;
            for(int angle=0;angle<=180;angle++){
                float darkness=FoldPolicy.outerDarkness(angle,start);
                assertTrue(darkness>=last&&darkness<=1);last=darkness;
            }
            assertEquals(0,FoldPolicy.outerDarkness(0,start),0);
            assertEquals("Resize remains covered before the hardware open endpoint",1,FoldPolicy.outerDarkness(150,start),0);
        }
    }
}
