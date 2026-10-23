package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.widget.TextView;

/** Vector-only action button; accessibility and tooltips retain the action's name. */
public final class ImeIconButton extends TextView {
    private final Paint pen=new Paint(Paint.ANTI_ALIAS_FLAG);
    private String icon="more";
    private float maxIconDp=20f;
    private boolean leafRing;
    public void setLeafRing(boolean enabled){leafRing=enabled;invalidate();}
    public ImeIconButton(Context context,String icon,String description) {
        super(context);setIcon(icon);setText("");setContentDescription(description);
        setTooltipText(description);setClickable(true);setFocusable(true);
        setMinWidth(0);setMinHeight(0);setPadding(0,0,0,0);
    }
    public void setIcon(String value){icon=value==null ? "more" : value;invalidate();}
    public String getIcon(){return icon;}
    public void setIconSizeDp(float sizeDp){maxIconDp=Math.max(8f,sizeDp);invalidate();}
    @Override protected void onDraw(Canvas canvas) {
        // Text labels are intentionally never painted by this control.
        float size=Math.min(getWidth(),getHeight());
        if(size<=0)return;
        float scale=Math.min(size,getResources().getDisplayMetrics().density*maxIconDp)/24f;
        if(leafRing) {
            canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);
            canvas.scale(getResources().getDisplayMetrics().density,getResources().getDisplayMetrics().density);
            LeafIconStyle.drawRing(canvas,pen,LeafIconStyle.LEAF,false,0,isPressed());canvas.restore();
        }
        canvas.save();canvas.translate(getWidth()/2f,getHeight()/2f);canvas.scale(scale,scale);canvas.translate(-12,-12);
        pen.setStrokeCap(Paint.Cap.ROUND);pen.setStrokeJoin(Paint.Join.ROUND);
        // Dark under-keyline and narrow light edge preserve legibility on black and white apps.
        int[] tones=leafRing?new int[]{0xb0264434,0xffedffdf,LeafIconStyle.LEAF}:new int[]{LeafIconStyle.INK};
        float[] strokes=leafRing?new float[]{3.4f,2.55f,1.7f}:new float[]{1.7f};
        for(int pass=0;pass<tones.length;pass++) {
            pen.setColor(tones[pass]);pen.setAlpha(isEnabled()?255:90);
            pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(strokes[pass]);
            drawGlyph(canvas);
        }
        if(isSelected()) {
            pen.setStyle(Paint.Style.FILL);pen.setColor(LeafIconStyle.LEAF);canvas.drawCircle(20,20,4,pen);
            pen.setStyle(Paint.Style.STROKE);pen.setStrokeWidth(1.3f);pen.setColor(0xffffffff);path(canvas,17.5f,20,19.2f,21.5f,22.5f,18.5f);
        }
        canvas.restore();
    }
    private void drawGlyph(Canvas canvas) {
        switch(icon) {
            case "copy":canvas.drawRoundRect(new RectF(7,7,21,22),2,2,pen);path(canvas,16,3,3,3,3,17);break;
            case "edit":path(canvas,4,16,16,4,21,9,9,21,3,22,4,16);line(canvas,13,7,18,12);break;
            case "chevron":path(canvas,9,5,16,12,9,19);break;
            case "collapse":path(canvas,5,15,12,8,19,15);break;
            case "keyboard":keyboard(canvas);break;
            case "mic":canvas.drawRoundRect(new RectF(9,2,15,14),3,3,pen);canvas.drawArc(new RectF(5,7,19,18),0,180,false,pen);line(canvas,12,18,12,22);line(canvas,8,22,16,22);break;
            case "stop":canvas.drawRoundRect(new RectF(5,5,19,19),2,2,pen);break;
            case "symbols":pen.setStyle(Paint.Style.FILL);pen.setTextSize(18);pen.setTextAlign(Paint.Align.CENTER);canvas.drawText("#",12,18,pen);break;
            case "numbers":pen.setStyle(Paint.Style.FILL);pen.setTextSize(11);pen.setTextAlign(Paint.Align.CENTER);canvas.drawText("123",12,16,pen);break;
            case "backspace":path(canvas,9,4,22,4,22,20,9,20,2,12,9,4);line(canvas,12,9,18,15);line(canvas,18,9,12,15);break;
            case "send":path(canvas,2,3,22,12,2,21,6,12,2,3);line(canvas,6,12,22,12);break;
            case "device":canvas.drawRoundRect(new RectF(2,3,22,17),1.5f,1.5f,pen);line(canvas,12,17,12,21);line(canvas,7,21,17,21);break;
            case "history":canvas.drawCircle(12,12,9,pen);line(canvas,12,6,12,12);line(canvas,12,12,16,14);break;
            case "stats":line(canvas,4,20,4,13);line(canvas,9,20,9,7);line(canvas,14,20,14,3);line(canvas,19,20,19,10);break;
            case "settings":
                Path gear=new Path();
                for(int i=0;i<32;i++){double a=i*Math.PI/16;float r=i%4==0||i%4==3?10:8;float x=12+(float)Math.cos(a)*r,y=12+(float)Math.sin(a)*r;if(i==0)gear.moveTo(x,y);else gear.lineTo(x,y);}gear.close();canvas.drawPath(gear,pen);canvas.drawCircle(12,12,3.5f,pen);break;
            case "switch":
                canvas.save();canvas.scale(.78f,.78f,2,2);keyboard(canvas);canvas.restore();
                canvas.drawArc(new RectF(12,12,23,23),35,250,false,pen);path(canvas,19,12,22,12,22,15);path(canvas,13,21,13,18,16,18);break;
            case "local":canvas.drawRoundRect(new RectF(6,2,18,22),2,2,pen);line(canvas,10,18.5f,14,18.5f);break;
            case "close":line(canvas,5,5,19,19);line(canvas,19,5,5,19);break;
            case "enter":path(canvas,20,5,20,14,4,14);path(canvas,9,9,4,14,9,19);break;
            case "search":canvas.drawCircle(10,10,7,pen);line(canvas,15,15,22,22);break;
            case "check":path(canvas,3,12,9,18,21,5);break;
            default:pen.setStyle(Paint.Style.FILL);canvas.drawCircle(5,12,1.5f,pen);canvas.drawCircle(12,12,1.5f,pen);canvas.drawCircle(19,12,1.5f,pen);break;
        }
    }
    private void keyboard(Canvas c) {
        c.drawRoundRect(new RectF(1.5f,4,22.5f,20),2,2,pen);
        for(int row=0;row<2;row++)for(int col=0;col<5;col++)line(c,5+col*3.5f,8+row*4,5.3f+col*3.5f,8+row*4);
        line(c,6,16,18,16);
    }
    private void line(Canvas c,float x,float y,float x2,float y2){c.drawLine(x,y,x2,y2,pen);}
    private void path(Canvas c,float...xy){Path p=new Path();p.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)p.lineTo(xy[i],xy[i+1]);c.drawPath(p,pen);}
}
