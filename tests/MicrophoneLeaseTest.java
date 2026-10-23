package io.github.amxooo.voiceinput2pc;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
public final class MicrophoneLeaseTest {
    private static int checks;
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
    public static void main(String[] args)throws Exception {
        check(MicrophoneLease.tryAcquire("ime-voice"));
        check(!MicrophoneLease.tryAcquire("ime-voice"));
        check(!MicrophoneLease.tryAcquire("long-recorder"));
        MicrophoneLease.release("long-recorder");check(MicrophoneLease.isOwnedBy("ime-voice"));
        MicrophoneLease.release("ime-voice");check(MicrophoneLease.tryAcquire("long-recorder"));
        check(MicrophoneLease.isOwnedBy("long-recorder"));MicrophoneLease.release("long-recorder");
        for(String bad:new String[]{null,""}){boolean denied=false;try{MicrophoneLease.tryAcquire(bad);}catch(IllegalArgumentException expected){denied=true;}check(denied);}
        CountDownLatch ready=new CountDownLatch(12),go=new CountDownLatch(1),done=new CountDownLatch(12);
        AtomicInteger won=new AtomicInteger();
        for(int i=0;i<12;i++){final String owner="race-"+i;new Thread(()->{ready.countDown();try{go.await();if(MicrophoneLease.tryAcquire(owner))won.incrementAndGet();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{done.countDown();}}).start();}
        ready.await();go.countDown();done.await();check(won.get()==1);
        for(int i=0;i<12;i++)MicrophoneLease.release("race-"+i);
        check(MicrophoneLease.tryAcquire("ime-voice"));MicrophoneLease.release("ime-voice");
        System.out.println(checks+" microphone lease checks passed");
    }
}
