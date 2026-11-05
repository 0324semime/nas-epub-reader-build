package com.semime.nasepub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.widget.ImageView;
import android.widget.FrameLayout;
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
import java.util.ArrayList;
import java.util.List;
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
    private ThorPageView topText,bottomText;
    private ImageView topPicture,bottomPicture;
    private boolean fixedImages;
    private final List<FixedPagePosition> imagePages=new ArrayList<>();
    private int imageIndex=0;
    private static final class FixedPagePosition {
        final int chapter;
        final FixedEpubPage.Page file;
        FixedPagePosition(int chapter,FixedEpubPage.Page file){
            this.chapter=chapter;this.file=file;
        }
    }
    private TextView topStatus,bottomStatus,topMark;
    private LinearLayout topLayout,bottomLayout;
    private boolean verticalSpread;
    private int lowerRotation;
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
        this.verticalSpread=prefs.getBoolean("vertical_spread",true);
        // Thor screenshot showed that the lower screen presents the content
        // sideways. The default correction can be changed in the Aa dialog.
        this.lowerRotation=normalizeRotation(prefs.getInt("bottom_rotation",-90));
        this.cursor=new Cursor(prefs.getInt("ch_"+id,0),prefs.getInt("offset_"+id,0));
        // The user's EPUB is image-based (about one XHTML per scanned page).
        // In this mode never treat <title> metadata as readable book text.
        if(bookDir!=null && FixedEpubPage.isFixedBook(bookDir,chapters)){
            for(int i=0;i<chapters.length();i++){
                try{
                    FixedEpubPage.Page page=FixedEpubPage.inspect(bookDir,
                        chapters.optString(i,""));
                    if(page.imagePage())imagePages.add(new FixedPagePosition(i,page));
                }catch(Exception ignored){}
            }
            fixedImages=imagePages.size()>=4;
            imageIndex=Math.max(0,Math.min(imagePages.size()-1,
                prefs.getInt("fixed_img_"+id,0)));
            if(fixedImages && !imagePages.isEmpty()){
                cursor=new Cursor(imagePages.get(imageIndex).chapter,0);
                history.clear();
            }
        }
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
        if(bookDir==null){
            // Always start the visual test at the first right page.
            cursor=new Cursor(0,0);
            history.clear();
        }
    }
    private static int normalizeRotation(int value){
        return value==90?90:value==-90?-90:0;
    }
    private int dp(Context context,float units){
        return Math.round(units*context.getResources().getDisplayMetrics().density);
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
        if(available.length==0)available=dm.getDisplays();
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

        TextView hint=label(host,verticalSpread?"RIGHT PAGE  ·  右ページ（先に読む）":"上画面  ·  先に読む",11,false);
        hint.setTextColor(muted());
        hint.setPadding(dp(4),dp(12),0,dp(5));
        topLayout.addView(hint);

        topText=makePage(host,topFont,true);
        topText.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob)->{
            if(r-l!=or_-ol||b-t!=ob-ot)scheduleDraw();
        });
        FrameLayout pageSurface=new FrameLayout(host);
        pageSurface.addView(topText,new FrameLayout.LayoutParams(-1,-1));
        topText.setOnClickListener(v->forward());
        topPicture=new ImageView(host);
        topPicture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        topPicture.setBackgroundColor(paper());
        topPicture.setPadding(dp(7),dp(7),dp(7),dp(7));
        topPicture.setOnClickListener(v->forward());
        topPicture.setVisibility(fixedImages?View.VISIBLE:View.GONE);
        topText.setVisibility(fixedImages?View.GONE:View.VISIBLE);
        pageSurface.addView(topPicture,new FrameLayout.LayoutParams(-1,-1));
        pageSurface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob)->{
            if(r-l!=or_-ol||b-t!=ob-ot)scheduleDraw();
        });
        LinearLayout.LayoutParams body=new LinearLayout.LayoutParams(-1,0,1);
        body.topMargin=dp(2);
        body.bottomMargin=dp(5);
        topLayout.addView(pageSurface,body);

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
    private ThorPageView makePage(Context ctx,int fontSize,boolean right){
        ThorPageView view=new ThorPageView(ctx,fontSize,verticalSpread,right);
        view.configure(fontSize,verticalSpread,right,ink(),paper(),
            theme==2?Color.rgb(88,108,98):Color.rgb(216,202,166));
        return view;
    }
    private void makeBottom(){
        Context ctx=presentation.getContext();
        LinearLayout panel=new LinearLayout(ctx);
        bottomLayout=panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(ctx,9),dp(ctx,7),dp(ctx,9),dp(ctx,7));
        panel.setBackgroundColor(paper());

        TextView label=label(ctx,verticalSpread
            ?"LEFT PAGE  ②  ·  左ページ"
            :"下画面 ②  ·  続き",11,true);
        label.setTextColor(muted());
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setPadding(dp(ctx,7),0,0,0);
        panel.addView(label,new LinearLayout.LayoutParams(-1,dp(ctx,25)));

        bottomText=makePage(ctx,bottomFont,false);
        bottomText.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob)->{
            if(r-l!=or_-ol||b-t!=ob-ot)scheduleDraw();
        });
        FrameLayout pageSurface=new FrameLayout(ctx);
        pageSurface.addView(bottomText,new FrameLayout.LayoutParams(-1,-1));
        bottomText.setOnClickListener(v->forward());
        bottomPicture=new ImageView(ctx);
        bottomPicture.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bottomPicture.setBackgroundColor(paper());
        bottomPicture.setPadding(dp(ctx,5),dp(ctx,5),dp(ctx,5),dp(ctx,5));
        bottomPicture.setOnClickListener(v->forward());
        bottomPicture.setVisibility(fixedImages?View.VISIBLE:View.GONE);
        bottomText.setVisibility(fixedImages?View.GONE:View.VISIBLE);
        pageSurface.addView(bottomPicture,new FrameLayout.LayoutParams(-1,-1));
        pageSurface.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or_,ob)->{
            if(r-l!=or_-ol||b-t!=ob-ot)scheduleDraw();
        });
        LinearLayout.LayoutParams body=new LinearLayout.LayoutParams(-1,0,1);
        panel.addView(pageSurface,body);

        // Keep controls minimal until two physical pages have been verified.
        // Next/previous and display settings remain on the top screen.
        bottomStatus=label(ctx,"LEFT ②",11,false);
        bottomStatus.setTextColor(muted());
        bottomStatus.setGravity(Gravity.CENTER);
        panel.addView(bottomStatus,new LinearLayout.LayoutParams(-1,dp(ctx,24)));

        // Measure the page with the rotated *usable* size. A simple
        // setRotation() on an unmeasured full-screen layout would clip
        // nearly the entire book and rotate the bottom UI sideways.
        presentation.setContentView(
            new ThorDisplayFrame(ctx,panel,lowerRotation));
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
            if(presentation.getWindow()!=null)
                presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
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
        if(bookDir==null){
            String base="私だけの小さな図書室。\n\n"
                +"開いたページには、静かな午後の物語が広がっていました。"
                +"一行ずつ文字を追ううちに、遠くの風景が目の前に浮かびます。\n\n"
                +"上画面の右ページから読み始め、下画面の左ページへ続きを進めます。"
                +"画面ごとに文章の量が異なっても、順序は変わりません。\n\n"
                +"縦書きの場合は右端の列から下へ読み、次の列は左隣に進みます。"
                +"これは上下2画面を使った表示確認用の文章です。\n\n";
            StringBuilder story=new StringBuilder();
            for(int i=0;i<24;i++)story.append(base);
            String text=story.toString();
            chapterCache.put(index,text);
            return text;
        }
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
            // XHTML <head><title> is book metadata, not body content.
            // In fixed-image EPUBs repeating that title on each spine item
            // made the reader appear to print the book title endlessly.
            String body=html.replaceFirst("(?is)<head\\b[^>]*>.*?</head\\s*>","");
            String plain=Html.fromHtml(body,Html.FROM_HTML_MODE_COMPACT)
                .toString().replace("\r","").replace("\u00a0"," ");
            // Keep breaks and Japanese punctuation, drop unhelpful runs of blanks.
            plain=plain.replaceAll("[ \t]{3,}","  ").replaceAll("\\n{4,}","\n\n\n").trim();
            if(chapterCache.size()>7)chapterCache.clear();
            chapterCache.put(index,plain);
            return plain;
        }
    }
    private Cursor normal(Cursor input)throws Exception{
        int ch=Math.max(0,input.chapter);
        int ofs=Math.max(0,input.offset);
        while(ch<chapters.length()){
            String text=chapterText(ch);
            // Ignore whitespace-only resources and empty image-only chapters.
            // Otherwise one XHTML file can consume an entire blank page.
            while(ofs<text.length()){
                int cp=text.codePointAt(ofs);
                if(!Character.isWhitespace(cp) && cp!=0x200B &&
                    cp!=0xFEFF && cp!=0x00A0)break;
                ofs+=Character.charCount(cp);
            }
            if(ofs<text.length())return new Cursor(ch,ofs);
            ch++;ofs=0;
        }
        return new Cursor(chapters.length(),0);
    }
    private Cursor advance(Cursor from,int count)throws Exception{
        int ch=from.chapter,ofs=from.offset;
        int remain=count;
        while(remain>0 && ch<chapters.length()){
            String text=chapterText(ch);
            int n=Math.min(remain,Math.max(0,text.length()-ofs));
            ofs+=n;remain-=n;
            if(ofs>=text.length()){
                ch++;ofs=0;
            }
        }
        return normal(new Cursor(ch,ofs));
    }
    private Segment page(Cursor from,ThorPageView destination)throws Exception{
        Cursor start=normal(from);
        if(start.chapter>=chapters.length())return new Segment(start,start,"");
        StringBuilder reading=new StringBuilder(6000);
        int ch=start.chapter,offset=start.offset,scanned=0;
        // EPUBs may contain a cover, half-title or tiny standalone XHTML
        // pieces. Fill a single physical page across those spine boundaries
        // instead of yielding one empty/near-empty page per XHTML file.
        while(ch<chapters.length()&&reading.length()<6000&&scanned++<120){
            String chapter=chapterText(ch);
            int available=Math.max(0,chapter.length()-offset);
            if(available>0){
                int n=Math.min(available,6000-reading.length());
                reading.append(chapter,offset,offset+n);
            }
            ch++;offset=0;
        }
        if(reading.length()==0)return new Segment(start,start,"");
        int fit=destination.fit(reading.toString());
        if(fit<=0)throw new IllegalStateException("ページの文字配置を測定できません");
        fit=Math.min(fit,reading.length());
        Cursor end=advance(start,fit);
        return new Segment(start,end,reading.substring(0,fit));
    }
    private void showImagePage(ImageView view,int page){
        if(view==null)return;
        if(page<0||page>=imagePages.size()){
            view.setImageDrawable(null);
            return;
        }
        File image=imagePages.get(page).file.image;
        Bitmap bitmap=FixedEpubPage.decode(image,view.getWidth(),view.getHeight());
        if(bitmap!=null)view.setImageBitmap(bitmap);
        else {
            view.setImageDrawable(null);
            Toast.makeText(host,"画像ページを読み込めません: "+image.getName(),
                Toast.LENGTH_SHORT).show();
        }
    }
    private void drawImagePages(){
        if(imagePages.isEmpty()||topPicture==null||bottomPicture==null)return;
        if(topPicture.getWidth()<70||topPicture.getHeight()<70
            ||bottomPicture.getWidth()<70||bottomPicture.getHeight()<70){
            handler.postDelayed(()->drawPages(),160);
            return;
        }
        imageIndex=Math.max(0,Math.min(imageIndex,imagePages.size()-1));
        cursor=new Cursor(imagePages.get(imageIndex).chapter,0);
        showImagePage(topPicture,imageIndex);
        showImagePage(bottomPicture,imageIndex+1);
        nextCursor=new Cursor(Math.min(chapters.length(),cursor.chapter+2),0);
        int right=imageIndex+1;
        int left=Math.min(imagePages.size(),imageIndex+2);
        topStatus.setText("画像の本  ·  右 "+right+" / "+imagePages.size()
            +"  → 左 "+left);
        bottomStatus.setText("左ページ  ·  "+
            (imageIndex+1<imagePages.size()?left+" / "+imagePages.size():"終わり"));
        if(topMark!=null)topMark.setText(isMarked(cursor)?"◆":"◇");
        save();
    }
    private void drawPages(){
        if(!shown||closing||drawing||topText==null||bottomText==null)return;
        if(fixedImages){
            drawImagePages();
            return;
        }
        if(topText.getWidth()<70||topText.getHeight()<90
            ||bottomText.getWidth()<70||bottomText.getHeight()<90){
            handler.postDelayed(()->drawPages(),230);
            return;
        }
        drawing=true;
        try{
            cursor=normal(cursor);
            Segment top=page(cursor,topText);
            Segment bottom=page(top.end,bottomText);
            topText.setPage(top.text.isEmpty()?"（本文の終わり）":top.text);
            bottomText.setPage(bottom.text.isEmpty()?"この本の終わりです":bottom.text);
            nextCursor=bottom.end;
            int count=chapters.length();
            topStatus.setText(String.format(Locale.JAPAN,
                verticalSpread?"右 ① → 左 ②  ·  %d / %d 章":"上 → 下  ·  %d / %d 章",
                Math.min(count,cursor.chapter+1),count));
            bottomStatus.setText(String.format(Locale.JAPAN,
                verticalSpread?"左ページ ②  ·  %d字":"下画面 ②  ·  %d字",
                bottom.text.codePointCount(0,bottom.text.length())));
            topStatus.setText(topStatus.getText()+"  ·  "+
                top.text.codePointCount(0,top.text.length())+"字");
            topMark.setText(isMarked(cursor)?"◆":"◇");
            save();
        }catch(Exception e){
            topText.setPage("この章を表示できませんでした。\n"+e.getMessage()
                +"\n通常の1画面モードでお試しください。");
            bottomText.setPage("表示できません");
        }finally{drawing=false;}
    }
    private void save(){
        if(bookDir==null)return;
        JSONArray old=new JSONArray();
        for(Cursor h:history){
            JSONObject point=new JSONObject();
            try{point.put("ch",h.chapter);point.put("offset",h.offset);old.put(point);}
            catch(Exception ignored){}
        }
        prefs.edit().putInt("ch_"+id,cursor.chapter)
            .putInt("offset_"+id,cursor.offset)
            .putInt("fixed_img_"+id,imageIndex)
            .putString("history_"+id,old.toString()).apply();
    }
    private void forward(){
        if(fixedImages){
            if(!shown || imageIndex+2>=imagePages.size())return;
            imageIndex+=2;
            drawPages();
            return;
        }
        if(!shown||nextCursor==null||nextCursor.same(cursor))return;
        if(nextCursor.chapter>=chapters.length())return;
        history.addLast(cursor);
        while(history.size()>70)history.removeFirst();
        cursor=nextCursor;
        save();
        drawPages();
    }
    private void back(){
        if(fixedImages){
            if(!shown||imageIndex<2)return;
            imageIndex-=2;
            drawPages();
            return;
        }
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
                    int markChapter=point.optInt("ch",0);
                    if(fixedImages){
                        for(int i=0;i<imagePages.size();i++){
                            if(imagePages.get(i).chapter>=markChapter){
                                imageIndex=(i/2)*2;
                                break;
                            }
                        }
                    }else{
                        cursor=new Cursor(markChapter,point.optInt("ofs",0));
                        history.clear();
                    }
                    drawPages();
                }
            }).setNegativeButton("閉じる",null).show();
    }
    private void showSettings(){
        LinearLayout area=new LinearLayout(host);
        area.setOrientation(LinearLayout.VERTICAL);
        area.setPadding(dp(18),dp(6),dp(18),dp(12));
        TextView title=label(host,"本文の向き",14,true);
        area.addView(title);
        android.widget.RadioGroup modes=new android.widget.RadioGroup(host);
        android.widget.RadioButton vertical=new android.widget.RadioButton(host);
        vertical.setText("日本語縦書き：右ページ → 左ページ");
        vertical.setTextSize(13);vertical.setId(8101);modes.addView(vertical);
        android.widget.RadioButton horizontal=new android.widget.RadioButton(host);
        horizontal.setText("横書き：上画面 → 下画面");
        horizontal.setTextSize(13);horizontal.setId(8102);modes.addView(horizontal);
        modes.check(verticalSpread?8101:8102);
        area.addView(modes);

        TextView direction=label(host,"下画面の向き",14,true);
        direction.setPadding(0,dp(12),0,0);
        area.addView(direction);
        android.widget.RadioGroup rotations=new android.widget.RadioGroup(host);
        String[] labels={"-90° （初期値）","0° （回転なし）","+90°"};
        int[] ids={8201,8202,8203};
        for(int i=0;i<3;i++){
            android.widget.RadioButton button=new android.widget.RadioButton(host);
            button.setText(labels[i]);button.setTextSize(13);button.setId(ids[i]);
            rotations.addView(button);
        }
        rotations.check(lowerRotation==-90?8201:lowerRotation==90?8203:8202);
        area.addView(rotations);

        TextView topLabel=label(host,"上画面  "+topFont+"sp",14,true);
        topLabel.setPadding(0,dp(12),0,0);area.addView(topLabel);
        SeekBar topSlider=new SeekBar(host);
        topSlider.setMax(12);topSlider.setProgress(topFont-14);
        area.addView(topSlider);
        TextView bottomLabel=label(host,"下画面  "+bottomFont+"sp",14,true);
        area.addView(bottomLabel);
        SeekBar bottomSlider=new SeekBar(host);
        bottomSlider.setMax(12);bottomSlider.setProgress(bottomFont-14);
        area.addView(bottomSlider);
        SeekBar.OnSeekBarChangeListener l=new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar slider,int value,boolean user){
                topLabel.setText("上画面  "+(14+topSlider.getProgress())+"sp");
                bottomLabel.setText("下画面  "+(14+bottomSlider.getProgress())+"sp");
            }
            public void onStartTrackingTouch(SeekBar slider){}
            public void onStopTrackingTouch(SeekBar slider){}
        };
        topSlider.setOnSeekBarChangeListener(l);
        bottomSlider.setOnSeekBarChangeListener(l);
        TextView note=label(host,
            "上下それぞれの有効表示領域に文字を配置します。"
            +"画面が横向きになる場合は、下画面の向きだけ変更してください。",12,false);
        note.setTextColor(muted());note.setPadding(0,dp(8),0,0);
        area.addView(note);
        new AlertDialog.Builder(host).setTitle("Thor 見開き表示の調整")
            .setView(area)
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("表示に反映",(dialog,which)->{
                verticalSpread=modes.getCheckedRadioButtonId()!=8102;
                lowerRotation=rotations.getCheckedRadioButtonId()==8201?-90:
                    rotations.getCheckedRadioButtonId()==8203?90:0;
                topFont=14+topSlider.getProgress();
                bottomFont=14+bottomSlider.getProgress();
                prefs.edit().putBoolean("vertical_spread",verticalSpread)
                    .putInt("bottom_rotation",lowerRotation)
                    .putInt("top_font",topFont)
                    .putInt("bottom_font",bottomFont).apply();
                if(shown&&!closing)rebuildPages();
            }).show();
    }
    private void rebuildPages(){
        if(!shown||closing)return;
        // Preserve the current pair start. Re-pagination uses each panel's
        // new measured dimensions, not the other display's font metrics.
        if(presentation!=null)presentation.dismiss();
        presentation=new Presentation(host,presentationDisplay);
        makeBottom();
        if(presentation.getWindow()!=null)
            presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try{presentation.show();}
        catch(RuntimeException error){closeAndExit();return;}
        topLayout.removeAllViews();
        // createTopView returns a freshly constructed layout; the Activity
        // owns its root, so update the parent through a host callback.
        host.replaceThorTopView(createTopView());
        scheduleDraw();
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
