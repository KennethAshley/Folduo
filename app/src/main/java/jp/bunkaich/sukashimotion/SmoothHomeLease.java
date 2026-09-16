package jp.bunkaich.sukashimotion;

/** One recovery per physical closure, with bounded request and client waits. */
final class SmoothHomeLease {
    static final int KEEP=0,REARM=1,STOP=2;
    private long expiresAt,settlingUntil;
    private boolean foldedAway;
    SmoothHomeLease(long now){renew(now);settlingUntil=now+1500;}
    void renew(long now){expiresAt=now+3000;}
    boolean expired(long now){return now>=expiresAt;}
    int observe(boolean closed,boolean held,long now){
        if(!closed)foldedAway=true;
        if(held){settlingUntil=0;return KEEP;}
        if(settlingUntil!=0)return now<settlingUntil?KEEP:STOP;
        if(closed&&foldedAway){foldedAway=false;settlingUntil=now+1500;return REARM;}
        return STOP;
    }
}
