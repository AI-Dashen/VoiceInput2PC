package io.github.amxooo.voiceinput2pc;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.test.InstrumentationTestRunner;
import android.util.Base64;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.List;

/** Opt-in one-shot control. It only runs when explicit recipient/message/send arguments are supplied. */
public final class WeChatUnicodeAutomationTest extends InstrumentationTestCase {
    private UiAutomation automation;

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.NO_WRAP), StandardCharsets.UTF_8);
    }
    private static String nfc(CharSequence value) {
        return Normalizer.normalize(value == null ? "" : value.toString(), Normalizer.Form.NFC);
    }
    private Bundle args() {
        return ((InstrumentationTestRunner)getInstrumentation()).getArguments();
    }
    private AccessibilityNodeInfo root() {
        List<AccessibilityWindowInfo> windows=automation.getWindows();
        for(AccessibilityWindowInfo window:windows) {
            AccessibilityNodeInfo root=window.getRoot();
            if(root!=null && "com.tencent.mm".contentEquals(root.getPackageName())) return root;
            if(root!=null) root.recycle();
        }
        return null;
    }
    private AccessibilityNodeInfo find(AccessibilityNodeInfo node,String text,boolean exact,boolean editable) {
        if(node==null)return null;
        CharSequence value=node.getText();CharSequence desc=node.getContentDescription();
        String a=value==null?"":value.toString(),b=desc==null?"":desc.toString();
        boolean matches=text==null || (exact ? text.equals(a)||text.equals(b) : a.contains(text)||b.contains(text));
        if(matches && (!editable || node.isEditable()))return AccessibilityNodeInfo.obtain(node);
        for(int i=0;i<node.getChildCount();i++){
            AccessibilityNodeInfo child=node.getChild(i);AccessibilityNodeInfo found=find(child,text,exact,editable);
            if(child!=null)child.recycle();if(found!=null)return found;
        }
        return null;
    }
    private AccessibilityNodeInfo waitNode(String text,boolean exact,boolean editable,long timeout)throws Exception{
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){
            AccessibilityNodeInfo root=root();AccessibilityNodeInfo found=find(root,text,exact,editable);
            if(root!=null)root.recycle();if(found!=null)return found;SystemClock.sleep(80);
        }
        return null;
    }
    private boolean clickNode(AccessibilityNodeInfo node){
        AccessibilityNodeInfo current=node;
        for(int i=0;i<6 && current!=null;i++){
            if(current.isClickable())return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            AccessibilityNodeInfo parent=current.getParent();if(current!=node)current.recycle();current=parent;
        }
        if(current!=null && current!=node)current.recycle();
        Rect bounds=new Rect();node.getBoundsInScreen(bounds);
        try{automation.executeShellCommand("input tap "+bounds.centerX()+" "+bounds.centerY()).close();return true;}catch(Exception e){return false;}
    }
    private void ensureWechatWindow()throws Exception{
        AccessibilityNodeInfo current=waitRoot(600);
        if(current!=null){current.recycle();return;}
        Intent launch=getInstrumentation().getContext().getPackageManager().getLaunchIntentForPackage("com.tencent.mm");
        if(launch==null)throw new AssertionError("WECHAT_NOT_INSTALLED");
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);getInstrumentation().getContext().startActivity(launch);
        if(waitRoot(5000)==null)throw new AssertionError("WECHAT_WINDOW_NOT_AVAILABLE_OR_INSTANCE_CHOOSER");
    }
    private AccessibilityNodeInfo waitRoot(long timeout){
        long end=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<end){AccessibilityNodeInfo r=root();if(r!=null)return r;SystemClock.sleep(80);}return null;
    }

    private boolean isHome(){
        AccessibilityNodeInfo root=root();if(root==null)return false;
        int markers=0;String[] names={"微信","通讯录","发现","我"};
        for(String name:names){AccessibilityNodeInfo node=find(root,name,true,false);if(node!=null){markers++;node.recycle();}}
        root.recycle();return markers>=3;
    }
    private void pressBack()throws Exception{
        automation.executeShellCommand("input keyevent KEYCODE_BACK").close();SystemClock.sleep(350);
    }
    private boolean tapHomeSearch()throws Exception{
        AccessibilityNodeInfo root=root();if(root==null)return false;
        Rect bounds=new Rect();root.getBoundsInScreen(bounds);root.recycle();
        int x=bounds.left+(int)(bounds.width()*0.84f);
        int y=bounds.top+Math.max(110,(int)(bounds.height()*0.055f));
        automation.executeShellCommand("input tap "+x+" "+y).close();
        return true;
    }
    private void openRecipient(String recipient)throws Exception{
        AccessibilityNodeInfo title=waitNode(recipient,true,false,700);
        AccessibilityNodeInfo existingEditor=waitNode(null,true,true,250);
        if(title!=null&&existingEditor!=null){title.recycle();existingEditor.recycle();return;}
        if(title!=null)title.recycle();if(existingEditor!=null)existingEditor.recycle();
        for(int i=0;i<4&&!isHome();i++)pressBack();
        if(!isHome())throw new AssertionError("WECHAT_HOME_NOT_REACHED");
        AccessibilityNodeInfo search=waitNode("搜索",false,false,700);
        if(search!=null){
            if(!clickNode(search))throw new AssertionError("WECHAT_SEARCH_CLICK_REJECTED");search.recycle();
        }else if(!tapHomeSearch())throw new AssertionError("WECHAT_SEARCH_NOT_FOUND");
        AccessibilityNodeInfo editor=waitNode(null,true,true,2500);
        if(editor==null)throw new AssertionError("WECHAT_SEARCH_EDITOR_NOT_FOUND");
        Bundle set=new Bundle();set.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,recipient);
        if(!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,set))throw new AssertionError("WECHAT_SEARCH_SET_TEXT_REJECTED");editor.recycle();
        editor=waitNode(null,true,true,800);
        if(editor==null||!nfc(recipient).equals(nfc(editor.getText())))throw new AssertionError("WECHAT_SEARCH_READBACK_MISMATCH");editor.recycle();
        AccessibilityNodeInfo result=waitNode(recipient,true,false,4000);
        if(result==null)throw new AssertionError("WECHAT_RECIPIENT_NOT_FOUND");
        if(!clickNode(result))throw new AssertionError("WECHAT_RECIPIENT_CLICK_REJECTED");result.recycle();
        title=waitNode(recipient,true,false,4000);existingEditor=waitNode(null,true,true,2500);
        if(title==null||existingEditor==null)throw new AssertionError("CHAT_TITLE_OR_EDITOR_MISMATCH");
        title.recycle();existingEditor.recycle();
    }
    public void testOpenFillAndOptionallySend()throws Exception{
        Bundle arguments=args();
        if(!"true".equals(arguments.getString("live")))return;
        String recipient=decode(arguments.getString("recipient_b64"));
        String message=decode(arguments.getString("message_b64"));
        boolean send="true".equals(arguments.getString("send"));
        if(recipient.isEmpty()||message.isEmpty())throw new AssertionError("ARGUMENT_REQUIRED");
        long started=SystemClock.elapsedRealtime();
        automation=getInstrumentation().getUiAutomation();
        AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        ensureWechatWindow();openRecipient(recipient);
        AccessibilityNodeInfo editor=waitNode(null,true,true,3000);
        if(editor==null)throw new AssertionError("WECHAT_EDITOR_NOT_FOUND");
        Bundle set=new Bundle();set.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,message);
        if(!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,set))throw new AssertionError("ACTION_SET_TEXT_REJECTED");editor.recycle();
        editor=waitNode(null,true,true,1000);
        if(editor==null||!nfc(message).equals(nfc(editor.getText())))throw new AssertionError("TEXT_READBACK_MISMATCH");editor.recycle();
        if(send){
            AccessibilityNodeInfo sendButton=waitNode("发送",true,false,2000);
            if(sendButton==null)throw new AssertionError("SEND_BUTTON_NOT_FOUND");
            if(!clickNode(sendButton))throw new AssertionError("SEND_CLICK_REJECTED");sendButton.recycle();
            AccessibilityNodeInfo bubble=waitNode(message,true,false,4000);
            if(bubble==null)throw new AssertionError("SENT_BUBBLE_NOT_FOUND");bubble.recycle();
        }
        System.out.println("WECHAT_AUTOMATION_OK elapsed_ms="+(SystemClock.elapsedRealtime()-started)+" send="+send+" chars="+message.length());
    }
}
