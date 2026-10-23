package io.github.amxooo.voiceinput2pc;

public final class CompactDockGeometryTest {
    private static int scenarios;
    public static void main(String[] args) {
        centeredToggle(); edgesNeverJump(); tinyWindows(); nanAndInfinity();
        rotationAndPersistence(); oddAndLargeSlots(); invalidSlot(); sweepBounds();
        System.out.println("CompactDockGeometryTest: " + scenarios + " PASS");
    }
    private static CompactDockGeometry.Placement at(int w,int h,int slot,float x,float y,boolean expanded) {
        return CompactDockGeometry.layout(w,h,slot,x,y,expanded);
    }
    private static void centeredToggle() {
        CompactDockGeometry.Placement small=at(400,800,48,.5f,.5f,false), large=at(400,800,48,.5f,.5f,true);
        eq(176,small.left);eq(376,small.top);eq(48,small.height);
        eq(176,large.left);eq(328,large.top);eq(144,large.height);eq(48,large.micTop);
        sameCenter(small,large);yes(large.canExpand&&large.expanded);scenarios++;
    }
    private static void edgesNeverJump() {
        for(float x:new float[]{-2,0,.5f,1,2})for(float y:new float[]{-2,0,.5f,1,2}) {
            CompactDockGeometry.Placement small=at(320,640,48,x,y,false), large=at(320,640,48,x,y,true);
            sameCenter(small,large);inside(small,320,640);inside(large,320,640);
        }
        eq(48,at(320,640,48,0,0,false).top);
        eq(0,at(320,640,48,0,0,true).top);
        eq(640,at(320,640,48,1,1,true).top+144);scenarios++;
    }
    private static void tinyWindows() {
        for(int w:new int[]{0,1,47,48,100})for(int h:new int[]{0,1,47,48,100,143,144}) {
            CompactDockGeometry.Placement a=at(w,h,48,.9f,.75f,false), b=at(w,h,48,.9f,.75f,true);
            sameCenter(a,b);inside(a,w,h);inside(b,w,h);
            yes(b.expanded==(w>=48&&h>=144));
        }
        eq(0,at(-1,100,48,1,1,true).height);
        eq(0,at(100,-1,48,1,1,true).width);scenarios++;
    }
    private static void nanAndInfinity() {
        CompactDockGeometry.Placement a=at(400,800,48,Float.NaN,Float.NaN,true),
                b=at(400,800,48,CompactDockGeometry.DEFAULT_X_FRACTION,CompactDockGeometry.DEFAULT_Y_FRACTION,true);
        eq(a.left,b.left);eq(a.top,b.top);
        eq(352,at(400,800,48,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,true).left);
        eq(656,at(400,800,48,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,true).top);
        eq(0,at(400,800,48,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,true).left);
        eq(0,at(400,800,48,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,true).top);scenarios++;
    }
    private static void rotationAndPersistence() {
        CompactDockGeometry.Placement p=at(400,800,48,.7f,.6f,true);
        CompactDockGeometry.Placement roundTrip=at(400,800,48,p.xFraction,p.yFraction,false);
        sameCenter(p,roundTrip);
        CompactDockGeometry.Placement landscape=at(800,400,48,p.xFraction,p.yFraction,true);
        inside(landscape,800,400);sameCenter(landscape,at(800,400,48,p.xFraction,p.yFraction,false));
        close(.7f,CompactDockGeometry.fractionX(280,400));
        close(.6f,CompactDockGeometry.fractionY(480,800));
        close(1,CompactDockGeometry.fractionX(Float.POSITIVE_INFINITY,400));
        close(CompactDockGeometry.DEFAULT_Y_FRACTION,CompactDockGeometry.fractionY(Float.NaN,400));
        close(CompactDockGeometry.DEFAULT_X_FRACTION,CompactDockGeometry.fractionX(3,0));scenarios++;
    }
    private static void oddAndLargeSlots() {
        CompactDockGeometry.Placement a=at(401,803,49,.5f,.5f,false), b=at(401,803,49,.5f,.5f,true);
        sameCenter(a,b);eq(147,b.height);inside(b,401,803);
        CompactDockGeometry.Placement large=at(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,1,1,true);
        yes(!large.expanded);eq(Integer.MAX_VALUE,large.width);eq(0,large.left);
        CompactDockGeometry.Placement huge=at(Integer.MAX_VALUE,Integer.MAX_VALUE,48,1,1,true);
        inside(huge,Integer.MAX_VALUE,Integer.MAX_VALUE);scenarios++;
    }
    private static void invalidSlot() {
        for(int slot:new int[]{0,-1,Integer.MIN_VALUE}) {
            try {at(100,200,slot,0,0,false);throw new AssertionError("invalid slot accepted");}
            catch(IllegalArgumentException expected) {}
        }
        scenarios++;
    }
    private static void sweepBounds() {
        for(int w=1;w<=200;w+=11)for(int h=1;h<=400;h+=13)for(int slot:new int[]{1,17,48,73}) {
            CompactDockGeometry.Placement a=at(w,h,slot,.98f,.02f,false),b=at(w,h,slot,.98f,.02f,true);
            sameCenter(a,b);inside(a,w,h);inside(b,w,h);
        }
        scenarios++;
    }
    private static void inside(CompactDockGeometry.Placement p,int w,int h) {
        yes(p.left>=0&&p.top>=0&&p.width>=0&&p.height>=0);
        yes((long)p.left+p.width<=w&&(long)p.top+p.height<=h);
        yes(p.micTop>=0&&(long)p.micTop+p.micHeight<=p.height);
    }
    private static void sameCenter(CompactDockGeometry.Placement a,CompactDockGeometry.Placement b) {
        close(a.micCenterX,b.micCenterX);close(a.micCenterY,b.micCenterY);
        eq(a.left,b.left);eq(a.top+a.micTop,b.top+b.micTop);
    }
    private static void close(float a,float b) {if(Math.abs(a-b)>.00001f)throw new AssertionError(a+" != "+b);}
    private static void eq(int a,int b) {if(a!=b)throw new AssertionError(a+" != "+b);}
    private static void yes(boolean value) {if(!value)throw new AssertionError("condition failed");}
}
