package com.semime.nasepub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.text.Html;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * AYN Thor two-screen plain-text novel reader.
 *
 * Main display: current text page.
 * Presentation display: immediately following text page.
 * Next advances past BOTH; previous returns to a saved pair start.
 *
 * Text layout uses each display's actual measured width and height and its own
 * font-size setting, so the smaller bottom panel can display fewer characters
 * without shrinking text. Rich EPUB typography, illustrations and RTL/vertical
 * writing are intentionally deferred to later versions.
 *
 * Separate app ID and preferences ensure the v0.4.6 stable app stays untouched.
 */
public final class ThorDualReader {
    public static final class Cursor {
        final int chapter;
        final int offset;
        Cursor(int chapter,int offset){
            this.chapter=chapter;
            this.offset=offset;
        }
        boolean same(Cursor other){
            return other!=null && chapter==other.chapter && offset==other.offset;
        }
    }
    private static final class Segment {
        final Cursor start,end;
        final String text;
        Segment(Cursor start,Cursor end,String text){
            this.start=start;this.end=end;this.text=text;
        }
    }

    private final MainActivity host;
    private final File bookDir;
    private final JSONObject book;
    private final Runnable exit;
    private final SharedPreferences prefs;
    private final Map<Integer,String> chapterCache=new HashMap<>();
    private final ArrayDeque<Cursor> history=new ArrayDeque<>();
    private final JSONArray chapters;
    private final String id;
    private DisplayManager displayManager;
    private Display presentationDisplay;
    private Presentation presentation;
    private TextView topText,bottomText,topStatus,bottomStatus,topMark;
    private LinearLayout topLayout;
    private Cursor cursor,nextCursor;
    private boolean shown;
    private boolean closing;
    private boolean drawing;
    private int topFont,bottomFont,theme;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private DisplayManager.DisplayListener listener;
    private long lastDisplayCheck;

    public ThorDualReader(MainActivity host,File bookDir,JSONObject book,Runnable exit){
        this.host=host;
        this.bookDir=bookDir;
        this.book=book;
        this.exit=exit;
        this.chapters=book.optJSONArray("chapters");
        this.id=book.optString("id","book");
        this.prefs=host.getSharedPreferences("thor_mode_a_v1",Context.MODE_PRIVATE);
        this.topFont=bound(prefs.getInt("top_font",20),14,26);
        this.bottomFont=bound(prefs.getInt("bottom_font",18),14,26);
        this.theme=bound(prefs.getInt("theme",0),0,2);
        this.cursor=new Cursor(prefs.getInt("ch_"+id,0),prefs.getInt("offset_"+id,0));
        this.nextCursor=cursor;
        try{
            JSONArray old=new JSONArray(prefs.getString("history_"+id,"[]"));
            int beginning=Math.max(0,old.length()-60);
            for(int n=beginning;n<old.length();n++){
                JSONObject point=old.optJSONObject(n);
                if(point!=null)history.add(new Cursor(point.optInt("ch",0),
                    point.optInt("offset",0)));
            }
        }catch(Exception ignored){}
    }
    private static int bound(int value,int min,int max){
        return Math.max(min,Math.min(max,value));
    }
    private int dp(float units){
        return Math.round(units*host.getResources().getDisplayMetrics().density);
    }
    private GradientDrawable rounded(int color,int radius){
        GradientDrawable background=new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        return background;
    }
    private int paper(){
        return theme==2?Color.rgb(23,31,32):theme==1
            ?Color.rgb(245,236,213):Color.rgb(250,251,249);
    }
    private int ink(){
        return theme==2?Color.rgb(229,233,228):theme==1
            ?Color.rgb(66,55,43):Color.rgb(39,51,44);
    }
    private int muted(){
        return theme==2?Color.rgb(163,181,169):Color.rgb(102,119,108);
    }
    private int tile(){
        return theme==2?Color.rgb(51,66,62):Color.rgb(230,238,232);
    }
    private TextView label(Context ctx,String value,int size,boolean bold){
        TextView text=new TextView(ctx);
        text.setText(value);
        text.setTextColor(ink());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP,size);
        if(bold)text.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return text;
    }
    private TextView button(Context ctx,String text,View.OnClickListener listener){
        TextView view=label(ctx,text,14,true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(8),dp(9),dp(8),dp(9));
        view.setBackground(rounded(tile(),12));
        view.setOnClickListener(listener);
        return view;
    }

    public static Display findBottom(Activity activity){
        DisplayManager dm=(DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
        if(dm==null)return null;
        Display primary=activity.getDisplay();
        int primaryId=primary==null?Display.DEFAULT_DISPLAY:primary.getDisplayId();
        Display[] available=dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        Display preferred=null;
        long smallest=Long.MAX_VALUE;
        for(Display d:available){
            if(d==null||d.getDisplayId()==primaryId||!d.isValid())continue;
            DisplayMetrics m=new DisplayMetrics();
            d.getRealMetrics(m);
            long pixels=(long)m.widthPixels*m.heightPixels;
            if(pixels<smallest){preferred=d;smallest=pixels;}
        }
        return preferred;
    }
    public static String displays(Activity activity){
        DisplayManager dm=(DisplayManager)activity.getSystemService(Context.DISPLAY_SERVICE);
        if(dm==null)return "DisplayManagerを取得できません";
        Display primary=activity.getDisplay();
        int primaryId=primary==null?0:primary.getDisplayId();
        StringBuilder text=new StringBuilder("現在のアクティビティ: "+primaryId+"\n");
        for(Display display:dm.getDisplays()){
            DisplayMetrics metrics=new DisplayMetrics();
            display.getRealMetrics(metrics);
            text.append("displayId=").append(display.getDisplayId())
                .append(", ").append(metrics.widthPixels).append("x").append(metrics.heightPixels)
                .append(", valid=").append(display.isValid())
                .append("\n");
        }
        Display bottom=findBottom(activity);
        text.append("\n下画面候補: ")
            .append(bottom==null?"未検出":("displayId="+bottom.getDisplayId()));
        return text.toString();
    }

    public View createTopView(){
        topLayout=new LinearLayout(host);
        topLayout.setOrientation(LinearLayout.VERTICAL);
        topLayout.setBackgroundColor(paper());
        topLayout.setPadding(dp(12),dp(9),dp(12),dp(10));
        LinearLayout header=new LinearLayout(host);
        header.setGravity(Gravity.CENTER_VERTICAL);
        topLayout.addView(header,new LinearLayout.LayoutParams(-1,dp(46)));
        TextView back=button(host,"‹ 本棚",v->closeAndExit());
        header.addView(back,new LinearLayout.LayoutParams(dp(82),-1));
        TextView title=label(host,book.optString("title","2画面読書"),16,true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setPadding(dp(11),0,dp(9),0);
        header.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        topMark=button(host,"◇",v->toggleMark());
        header.addView(topMark,new LinearLayout.LayoutParams(dp(43),-1));
        TextView settings=button(host,"Aa",v->showSettings());
        LinearLayout.LayoutParams settingsParam=new LinearLayout.LayoutParams(dp(45),-1);
        settingsParam.leftMargin=dp(5);
        header.addView(settings,settingsParam);

        TextView hint=label(host,"上画面  ·  先に読む",11,false);
        hint.setTextColor(muted());
        hint.setPadding(dp(4),dp(12),0,dp(5));
        topLayout.addView(hint);

        topText=makePage(host,topFont);
        LinearLayout.LayoutParams body=new LinearLayout.LayoutParams(-1,0,1);
        body.topMargin=dp(2);
        body.bottomMargin=dp(5);
        topLayout.addView(topText,body);
        topText.setOnClickListener(v->forward());

        LinearLayout footer=new LinearLayout(host);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        topLayout.addView(footer,new LinearLayout.LayoutParams(-1,dp(46)));
        TextView previous=button(host,"‹ 前",v->back());
        footer.addView(previous,new LinearLayout.LayoutParams(dp(76),-1));
        topStatus=label(host,"上下2ページ",12,false);
        topStatus.setGravity(Gravity.CENTER);
        topStatus.setTextColor(muted());
        footer.addView(topStatus,new LinearLayout.LayoutParams(0,-1,1));
        TextView next=button(host,"次 ›",v->forward());
        footer.addView(next,new LinearLayout.LayoutParams(dp(76),-1));
        return topLayout;
    }
    private TextView makePage(Context ctx,int sp){
        TextView t=label(ctx,"",sp,false);
        t.setTypeface(Typeface.SERIF);
        t.setTextColor(ink());
        t.setIncludeFontPadding(false);
        t.setLineSpacing(0,1.31f);
        t.setGravity(Gravity.TOP|Gravity.START);
        t.setPadding(dp(10),dp(9),dp(10),dp(7));
        t.setTextIsSelectable(false);
        t.setBackground(rounded(paper(),12));
        return t;
    }
    private void makeBottom(){
        Context ctx=presentation.getContext();
        LinearLayout root=new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12),dp(11),dp(12),dp(12));
        root.setBackgroundColor(paper());

        LinearLayout titleBar=new LinearLayout(ctx);
        titleBar.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(titleBar,new LinearLayout.LayoutParams(-1,dp(42)));
        TextView title=label(ctx,"下画面  ·  続きを読む",14,true);
        title.setTextColor(muted());
        titleBar.addView(title,new LinearLayout.LayoutParams(0,-1,1));
        TextView mark=button(ctx,"しおり",v->showMarks());
        titleBar.addView(mark,new LinearLayout.LayoutParams(dp(83),-1));

        bottomText=makePage(ctx,bottomFont);
        LinearLayout.LayoutParams pageLayout=new LinearLayout.LayoutParams(-1,0,1);
        pageLayout.topMargin=dp(6);
        pageLayout.bottomMargin=dp(6);
        root.addView(bottomText,pageLayout);
        bottomText.setOnClickListener(v->forward());

        LinearLayout footer=new LinearLayout(ctx);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(footer,new LinearLayout.LayoutParams(-1,dp(49)));
        TextView previous=button(ctx,"‹ 前の2ページ",v->back());
        footer.addView(previous,new LinearLayout.LayoutParams(0,-1,1));
        bottomStatus=label(ctx,"2 / 2",12,true);
        bottomStatus.setGravity(Gravity.CENTER);
        bottomStatus.setTextColor(muted());
        footer.addView(bottomStatus,new LinearLayout.LayoutParams(0,-1,1));
        TextView next=button(ctx,"次の2ページ ›",v->forward());
        footer.addView(next,new LinearLayout.LayoutParams(0,-1,1));
        presentation.setContentView(root);
    }

    public boolean start(){
        if(chapters==null||chapters.length()==0)return false;
        presentationDisplay=findBottom(host);
        if(presentationDisplay==null)return false;
        displayManager=(DisplayManager)host.getSystemService(Context.DISPLAY_SERVICE);
        if(displayManager==null)return false;
        presentation=new Presentation(host,presentationDisplay);
        makeBottom();
        try{
            presentation.show();
        }catch(RuntimeException e){
            presentation=null;
            return false;
        }
        shown=true;
        closing=false;
        if(listener==null){
            listener=new DisplayManager.DisplayListener(){
                public void onDisplayAdded(int id){}
                public void onDisplayChanged(int id){
                    if(presentationDisplay!=null
                       && id==presentationDisplay.getDisplayId())scheduleDraw();
                }
                public void onDisplayRemoved(int id){
                    if(presentationDisplay!=null && id==presentationDisplay.getDisplayId())
                        host.runOnUiThread(()->{
                            if(shown) {
                                Toast.makeText(host,"下画面が切断されました。通常の本棚に戻ります",
                                    Toast.LENGTH_LONG).show();
                                closeAndExit();
                            }
                        });
                }
            };
            displayManager.registerDisplayListener(listener,handler);
        }
        scheduleDraw();
        return true;
    }
    private void scheduleDraw(){
        handler.postDelayed(()->drawPages(),130);
    }
    private String chapterText(int index)throws Exception{
        if(index<0||index>=chapters.length())return "";
        if(chapterCache.containsKey(index))return chapterCache.get(index);
        String relative=chapters.optString(index,"");
        File path=new File(bookDir,relative).getCanonicalFile();
        String prefix=bookDir.getCanonicalPath()+File.separator;
        if(!path.getPath().startsWith(prefix)||!path.isFile())
            throw new IllegalArgumentException("EPUBの本文ファイルが見つかりません");
        if(path.length()>4L*1024*1024)
            throw new IllegalArgumentException("大きすぎる章ファイルには未対応です");
        try(FileInputStream in=new FileInputStream(path);
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){
                if(out.size()+n>4L*1024*1024)
                    throw new IllegalArgumentException("大きすぎる章ファイルには未対応です");
                out.write(buf,0,n);
            }
            String html=new String(out.toByteArray(),StandardCharsets.UTF_8);
            String plain=Html.fromHtml(html,Html.FROM_HTML_MODE_COMPACT)
                .toString().replace("\r","").replace("\u00a0"," ");
            // Keep breaks and Japanese punctuation, drop unhelpful runs of blanks.
            plain=plain.replaceAll("[ \t]{3,}","  ").replaceAll("\\n{4,}","\n\n\n").trim();
            if(chapterCache.size()>7)chapterCache.clear();
            chapterCache.put(index,plain);
            return plain;
        }
    }
    private Cursor normal(Cursor input)throws Exception{
        int ch=Math.max(0,input.chapter),ofs=Math.max(0,input.offset);
        while(ch<chapters.length()){
            String text=chapterText(ch);
            if(ofs<text.length())return new Cursor(ch,ofs);
            ch++;ofs=0;
        }
        return new Cursor(chapters.length(),0);
    }
    private Segment page(Cursor from,TextView destination,int fontSp)throws Exception{
        Cursor start=normal(from);
        if(start.chapter>=chapters.length())return new Segment(start,start,"");
        String text=chapterText(start.chapter);
        int position=start.offset;
        int width=destination.getWidth()-destination.getPaddingLeft()-destination.getPaddingRight();
        int height=destination.getHeight()-destination.getPaddingTop()-destination.getPaddingBottom();
        if(width<60||height<90)
            throw new IllegalStateException("画面の描画サイズがまだ取得できません");

        // StaticLayout's line breaking follows Android TextView rules. Bound
        // work per page even for very large XHTML chapter files.
        int trial=Math.min(text.length(),position+9000);
        String remainder=text.substring(position,trial);
        TextPaint paint=new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.SERIF);
        DisplayMetrics metrics=destination.getResources().getDisplayMetrics();
        paint.setTextSize(fontSp*metrics.scaledDensity);

        StaticLayout layout=StaticLayout.Builder
            .obtain(remainder,0,remainder.length(),paint,width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setLineSpacing(0,1.31f)
            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
            .build();
        int end=0;
        for(int i=0;i<layout.getLineCount();i++){
            if(layout.getLineBottom(i)>height)break;
            end=layout.getLineEnd(i);
        }
        if(end==0)end=Math.min(1,remainder.length());
        // Don't split high+low UTF-16 surrogate pairs.
        if(end>0&&end<remainder.length()
            &&Character.isHighSurrogate(remainder.charAt(end-1))
            &&Character.isLowSurrogate(remainder.charAt(end)))end--;
        end=Math.max(1,end);
        Cursor after=normal(new Cursor(start.chapter,position+end));
        return new Segment(start,after,remainder.substring(0,end));
    }
    private void drawPages(){
        if(!shown||closing||drawing||topText==null||bottomText==null)return;
        if(topText.getWidth()<70||topText.getHeight()<90
            ||bottomText.getWidth()<70||bottomText.getHeight()<90){
            handler.postDelayed(()->drawPages(),230);
            return;
        }
        drawing=true;
        try{
            cursor=normal(cursor);
            Segment top=page(cursor,topText,topFont);
            Segment bottom=page(top.end,bottomText,bottomFont);
            topText.setText(top.text.isEmpty()?"（本文の終わり）":top.text);
            bottomText.setText(bottom.text.isEmpty()?"この本の終わりです":bottom.text);
            nextCursor=bottom.end;
            int count=chapters.length();
            topStatus.setText(String.format(Locale.JAPAN,"上 → 下  ·  %d / %d 章",
                Math.min(count,cursor.chapter+1),count));
            bottomStatus.setText("次のページ");
            topMark.setText(isMarked(cursor)?"◆":"◇");
            save();
        }catch(Exception e){
            topText.setText("この章を表示できませんでした。\n"+e.getMessage()
                +"\n\n通常の1画面モードでの閲覧をお試しください。");
            bottomText.setText("表示できません");
        }finally{drawing=false;}
    }
    private void save(){
        JSONArray old=new JSONArray();
        for(Cursor h:history){
            JSONObject point=new JSONObject();
            try{point.put("ch",h.chapter);point.put("offset",h.offset);old.put(point);}
            catch(Exception ignored){}
        }
        prefs.edit().putInt("ch_"+id,cursor.chapter)
            .putInt("offset_"+id,cursor.offset)
            .putString("history_"+id,old.toString()).apply();
    }
    private void forward(){
        if(!shown||nextCursor==null||nextCursor.same(cursor))return;
        if(nextCursor.chapter>=chapters.length())return;
        history.addLast(cursor);
        while(history.size()>70)history.removeFirst();
        cursor=nextCursor;
        save();
        drawPages();
    }
    private void back(){
        if(!shown||history.isEmpty())return;
        cursor=history.removeLast();
        save();
        drawPages();
    }
    private String marksKey(){return "marks_"+id;}
    private JSONArray marks(){
        try{return new JSONArray(prefs.getString(marksKey(),"[]"));}
        catch(Exception e){return new JSONArray();}
    }
    private boolean isMarked(Cursor p){
        JSONArray marks=marks();
        for(int i=0;i<marks.length();i++){
            JSONObject o=marks.optJSONObject(i);
            if(o!=null&&o.optInt("ch",-1)==p.chapter&&o.optInt("ofs",-1)==p.offset)
                return true;
        }
        return false;
    }
    private void toggleMark(){
        JSONArray current=marks();
        JSONArray result=new JSONArray();
        boolean removed=false;
        for(int i=0;i<current.length();i++){
            JSONObject point=current.optJSONObject(i);
            if(point==null)continue;
            if(point.optInt("ch",-1)==cursor.chapter
                &&point.optInt("ofs",-1)==cursor.offset){
                removed=true;continue;
            }
            result.put(point);
        }
        if(!removed){
            JSONObject point=new JSONObject();
            try{
                point.put("ch",cursor.chapter);
                point.put("ofs",cursor.offset);
                result.put(point);
            }catch(Exception ignored){}
        }
        prefs.edit().putString(marksKey(),result.toString()).apply();
        topMark.setText(removed?"◇":"◆");
        Toast.makeText(host,removed?"しおりを解除":"上下2ページの位置を保存",Toast.LENGTH_SHORT).show();
    }
    private void showMarks(){
        JSONArray data=marks();
        if(data.length()==0){
            Toast.makeText(host,"しおりは未登録です。上画面の ◇ で追加できます",
                Toast.LENGTH_LONG).show();
            return;
        }
        String[] entries=new String[data.length()];
        for(int i=0;i<data.length();i++){
            JSONObject point=data.optJSONObject(i);
            entries[i]=point==null?"不明":("第"+(point.optInt("ch",0)+1)
                +"章  ·  位置"+point.optInt("ofs",0));
        }
        new AlertDialog.Builder(host).setTitle("2画面用のしおり")
            .setItems(entries,(dialog,which)->{
                JSONObject point=data.optJSONObject(which);
                if(point!=null){
                    cursor=new Cursor(point.optInt("ch",0),point.optInt("ofs",0));
                    history.clear();drawPages();
                }
            }).setNegativeButton("閉じる",null).show();
    }
    private void showSettings(){
        LinearLayout layout=new LinearLayout(host);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(18),dp(10),dp(18),dp(12));
        TextView topLabel=label(host,"上画面の文字： "+topFont+"sp",15,true);
        layout.addView(topLabel);
        SeekBar topSlider=new SeekBar(host);
        topSlider.setMax(12);topSlider.setProgress(topFont-14);
        layout.addView(topSlider);
        TextView bottomLabel=label(host,"下画面の文字： "+bottomFont+"sp",15,true);
        bottomLabel.setPadding(0,dp(15),0,0);
        layout.addView(bottomLabel);
        SeekBar bottomSlider=new SeekBar(host);
        bottomSlider.setMax(12);bottomSlider.setProgress(bottomFont-14);
        layout.addView(bottomSlider);
        SeekBar.OnSeekBarChangeListener listener=new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar bar,int value,boolean byUser){
                topLabel.setText("上画面の文字： "+(14+topSlider.getProgress())+"sp");
                bottomLabel.setText("下画面の文字： "+(14+bottomSlider.getProgress())+"sp");
            }
            public void onStartTrackingTouch(SeekBar bar){}
            public void onStopTrackingTouch(SeekBar bar){}
        };
        topSlider.setOnSeekBarChangeListener(listener);
        bottomSlider.setOnSeekBarChangeListener(listener);
        TextView note=label(host,
            "下画面は文字を小さくせず、文章量を減らして表示できます。\n"
            +"ページは上画面 → 下画面の順に読み、次で2ページ進みます。",12,false);
        note.setTextColor(muted());
        note.setPadding(0,dp(12),0,0);
        layout.addView(note);
        final String[] themes={"ライト","セピア","ダーク"};
        new AlertDialog.Builder(host).setTitle("AYN Thor 2画面設定")
            .setView(layout)
            .setSingleChoiceItems(themes,theme,(dialog,choice)->{
                theme=choice;
                prefs.edit().putInt("theme",theme).apply();
                dialog.dismiss();showSettings();
            })
            .setNegativeButton("閉じる",null)
            .setPositiveButton("適用",(dialog,which)->{
                topFont=14+topSlider.getProgress();
                bottomFont=14+bottomSlider.getProgress();
                prefs.edit().putInt("top_font",topFont)
                    .putInt("bottom_font",bottomFont).apply();
                if(topText!=null)topText.setTextSize(TypedValue.COMPLEX_UNIT_SP,topFont);
                if(bottomText!=null)bottomText.setTextSize(TypedValue.COMPLEX_UNIT_SP,bottomFont);
                drawPages();
            }).show();
    }
    public void close(){
        if(closing)return;
        closing=true;
        shown=false;
        save();
        if(displayManager!=null&&listener!=null)
            displayManager.unregisterDisplayListener(listener);
        listener=null;
        if(presentation!=null){
            presentation.dismiss();
            presentation=null;
        }
    }
    private void closeAndExit(){
        close();
        exit.run();
    }
    public boolean active(){return shown&&!closing;}
    public void onHostResume(){
        // AYN firmware may invalidate a presentation after a display-mode swap.
        // Reopen from the shelf rather than attaching to a stale display.
        if(shown && presentationDisplay!=null && !presentationDisplay.isValid())
            closeAndExit();
    }
}
