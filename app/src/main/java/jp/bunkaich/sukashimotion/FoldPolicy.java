package jp.bunkaich.sukashimotion;

/** Pure state machine. Direction hysteresis prevents tiny sensor jitter swapping screens. */
final class FoldPolicy {
    // Physical calibration: leave early blur visible, finish coverage before resizing.
    static final float OUTER_FADE_START=45,OUTER_FADE_END=120;
    static float outerDarkness(float angle){return Math.max(0,Math.min(1,(angle-OUTER_FADE_START)/(OUTER_FADE_END-OUTER_FADE_START)));}
    enum Change { NONE, OPEN, CLOSE, FINISH_OPEN, FINISH_CLOSED }
    boolean open,active;float extreme;long endpointSince=-1;
    FoldPolicy(boolean initiallyInner){open=initiallyInner;extreme=initiallyInner?180:0;}
    Change update(float angle,long now){return update(angle,now,false);}
    Change update(float angle,long now,boolean stepped){
        if(!Float.isFinite(angle)||angle<0||angle>180)return Change.NONE;
        // Samsung's tested hardware log can stop at 8/173 degrees at a physical endpoint.
        float closed=stepped?10:3,opening=stepped?170:174,opened=stepped?170:176;
        if(!active){
            if(!open&&angle>closed&&angle<opening){active=true;open=true;extreme=angle;return Change.OPEN;}
            if(open&&angle<opening&&angle>closed){active=true;open=false;extreme=angle;return Change.CLOSE;}
            // Public state may have skipped the entire motion. Do not synthesize an animation.
            if(angle>=opened)open=true;if(angle<=closed)open=false;return Change.NONE;
        }
        boolean atEnd=angle>=opened||angle<=(stepped?closed:1);
        if(atEnd){
            if(endpointSince<0)endpointSince=now;
            if(now-endpointSince>=120){active=false;open=angle>=opened;endpointSince=-1;return open?Change.FINISH_OPEN:Change.FINISH_CLOSED;}
        }else endpointSince=-1;
        if(open){extreme=Math.max(extreme,angle);if(extreme-angle>=12){open=false;extreme=angle;return Change.CLOSE;}}
        else{extreme=Math.min(extreme,angle);if(angle-extreme>=12){open=true;extreme=angle;return Change.OPEN;}}
        return Change.NONE;
    }
    static float blur(float angle,boolean inner){
        float x=inner?(176-angle)/86:(angle-1.5f)/88.5f;
        // Optical strength follows hinge travel, independently of image handoff.
        // Easing this value accelerates the blur in the middle of a slow fold.
        return Math.max(0,Math.min(1,x));
    }
    static float smooth(float value,float target,float seconds){return smooth(value,target,seconds,.024f);}
    static float smooth(float value,float target,float seconds,float response){return target+(value-target)*(float)Math.exp(-Math.min(.1,Math.max(0,seconds))/response);}
}
