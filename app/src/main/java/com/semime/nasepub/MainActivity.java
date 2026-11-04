package com.semime.nasepub;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.drawable.GradientDrawable;
import android.view.Window;
import android.view.WindowManager;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.ProgressBar;
import android.content.res.ColorStateList;
import android.widget.CheckBox;
import android.content.ActivityNotFoundException;
import android.widget.EditText;
import android.text.InputType;
import java.security.MessageDigest;
import java.util.Locale;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Offline EPUB bookshelf and reader. SMB and embedded Tailscale follow in later releases. */
public final class MainActivity extends Activity {
    private static final int PICK = 902;
    private final int background = Color.rgb(247,249,247);
    private final int surface = Color.WHITE;
    private final int accent = Color.rgb(36,110,91);
    private final int accentSoft = Color.rgb(224,242,233);
    private final int subText = Color.rgb(107,121,118);
    private final int ink = Color.rgb(32,45,56);
    private File library, previews, openedFolder;
    private JSONObject openedBook;
    private LinearLayout root;
    private WebView reader;
    private int chapter = 0;
    private int fontSize = 100;
    private String shelfFilter = "";
    private int shelfTab = 0; // 0 = 本（すべて）, 1 = 保存済み
    private SmbRepository.RemoteBook streamingRemote;
    private SmbRepository.Settings streamingSettings;
    private int streamingGeneration = 0;
    private boolean streamingBusy = false;
    private int readerTheme = 0;
    private int readerMargin = 1;
    private TextView readingProgress;
    private TextView bookmarkControl;
    private boolean restoreReaderPosition = false;
    private boolean restoringScroll = false;
    private int pendingScroll = 0;
    private long lastPositionSave = 0;
    private String activeSearch = "";
    private SecurePrefs smbPreferences;
    private SmbRepository.Settings smbSettings;
    private final List<SmbRepository.RemoteBook> nasBooks = new ArrayList<>();
    private final Map<String,Integer> transferPercents=new HashMap<>();
    private final Map<String,ProgressBar> displayedProgress=new HashMap<>();
    private String nasStatus = "NASは未接続です";
    private volatile boolean nasBusy = false;
    private TextView nasStatusView;
    private boolean tailnetPanelVisible = false;
    private boolean folderBrowserVisible = false;
    private boolean folderBrowserLoading = false;
    private String folderBrowserPath = "";
    private String folderBrowserError = "";
    private final List<SmbRepository.BrowseEntry> folderBrowserItems = new ArrayList<>();
    private long folderBrowserRevision = 0;


    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        smbPreferences=new SecurePrefs(this);
        smbSettings=smbPreferences.load();
        EmbeddedTailnet.initialize(this);
        if(EmbeddedTailnet.enabled()){
            new Thread(()->{try{EmbeddedTailnet.start();}catch(Throwable ignored){}},"tsnet-start").start();
        }
        library = new File(getFilesDir(), "library");
        if (!library.exists()) library.mkdirs();
        previews = new File(getFilesDir(),"previews");
        if(!previews.exists())previews.mkdirs();
        loadSavedIndex();
        fontSize = getPreferences(0).getInt("fontSize",100);
        readerTheme = getPreferences(0).getInt("reader_theme",0);
        readerMargin = getPreferences(0).getInt("reader_margin",1);
        shelf();
    }
    private int d(float px) { return (int)(getResources().getDisplayMetrics().density*px+.5f); }
    private LinearLayout layout(boolean horizontal) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(horizontal ? 0 : 1);
        if(horizontal) l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }
    private TextView text(String value,int size,boolean bold) {
        TextView v=new TextView(this);
        v.setText(value); v.setTextColor(ink); v.setTextSize(size);
        if(bold) v.setTypeface(null,1);
        return v;
    }
    private Button button(String title, View.OnClickListener fn) {
        Button v=new Button(this);
        v.setText(title);v.setTextSize(13);v.setAllCaps(false);
        v.setTextColor(accent);
        v.setBackground(rounded(Color.rgb(231,239,234),12));
        v.setMinHeight(d(43));
        v.setPadding(d(10),d(5),d(10),d(5));
        v.setOnClickListener(fn);
        return v;
    }
    private void screen() {
        if(reader!=null){reader.stopLoading();reader.destroy();reader=null;}
        root=layout(false);root.setBackgroundColor(background);setContentView(root);
    }
    private List<JSONObject> readBooks(){
        List<JSONObject> books=new ArrayList<>();
        File[] dirs=library.listFiles();
        if(dirs==null)return books;
        for(File dir:dirs)try{
            if(!dir.isDirectory())continue;
            File f=new File(dir,"book.json");
            if(!f.isFile()||f.length()>500000)continue;
            byte[] data=readFile(f,500000);
            JSONObject o=new JSONObject(new String(data,StandardCharsets.UTF_8));
            if(!dir.getName().equals(o.optString("id")))continue;
            String source=o.optString("source","");
            if(!source.isEmpty()&&isHidden(o.optString("id")))continue;
            // Downloaded EPUBs stay offline-available even when a remote profile
            // is deselected, unavailable, or removed from the connection list.
            books.add(o);
        }catch(Exception ignored){}
        Collections.sort(books, Comparator.comparing(x->x.optString("title","")));
        return books;
    }
    private GradientDrawable rounded(int color,int radius){
        GradientDrawable shape=new GradientDrawable();
        shape.setColor(color);shape.setCornerRadius(d(radius));
        return shape;
    }
    private GradientDrawable bordered(int color,int radius,int stroke){
        GradientDrawable shape=rounded(color,radius);
        shape.setStroke(d(1),stroke);
        return shape;
    }
    private TextView actionIcon(String label){
        TextView view=text(label,23,false);
        view.setGravity(Gravity.CENTER);
        view.setBackground(rounded(Color.rgb(240,244,242),13));
        return view;
    }
    private TextView subtle(String value,int size){
        TextView view=text(value,size,false);
        view.setTextColor(subText);
        return view;
    }
    private TextView statusChip(String label,boolean saved){
        TextView view=text(label,10,true);
        view.setTextColor(saved?accent:Color.rgb(79,104,125));
        view.setBackground(rounded(saved?accentSoft:Color.rgb(233,239,247),20));
        view.setGravity(Gravity.CENTER);
        view.setPadding(d(8),d(4),d(8),d(4));
        return view;
    }
    private int countUndownloaded(){
        int count=0;
        for(SmbRepository.RemoteBook item:nasBooks){
            if(!isHidden(remoteId(item)) && cachedRemote(item)==null)count++;
        }
        return count;
    }
    private void shelf(){
        folderBrowserVisible=false;
        tailnetPanelVisible=false;
        ++folderBrowserRevision;
        streamingGeneration++;
        streamingBusy=false;
        streamingRemote=null;
        streamingSettings=null;
        openedBook=null;openedFolder=null;screen();
        displayedProgress.clear();
        // Compact header: navigation, bookshelf name, and one refresh action.
        LinearLayout header=layout(true);
        header.setPadding(d(16),d(18),d(16),d(12));
        TextView menu=actionIcon("☰");
        header.addView(menu,new LinearLayout.LayoutParams(d(46),d(46)));
        menu.setOnClickListener(v->showLibraryDrawer());
        LinearLayout titles=layout(false);titles.setPadding(d(13),0,0,0);
        TextView heading=text("マイライブラリ",21,true);
        titles.addView(heading);
        titles.addView(subtle("READ YOUR WAY",10));
        header.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        TextView lookup=actionIcon("⌕");
        LinearLayout.LayoutParams lookupParams=new LinearLayout.LayoutParams(d(45),d(46));
        lookupParams.rightMargin=d(7);
        header.addView(lookup,lookupParams);
        lookup.setOnClickListener(v->searchLibrary());
        TextView refresh=actionIcon("↻");
        header.addView(refresh,new LinearLayout.LayoutParams(d(46),d(46)));
        refresh.setOnClickListener(v->refreshNas());
        root.addView(header);

        LinearLayout tabs=layout(true);
        tabs.setPadding(d(16),d(3),d(16),d(11));
        tabs.setBackgroundColor(background);
        TextView allTab=shelfTabButton("本",shelfTab==0);
        TextView savedTab=shelfTabButton("保存済み",shelfTab==1);
        allTab.setOnClickListener(v->{if(shelfTab!=0){shelfTab=0;shelf();}});
        savedTab.setOnClickListener(v->{if(shelfTab!=1){shelfTab=1;shelf();}});
        LinearLayout.LayoutParams firstTab=new LinearLayout.LayoutParams(0,d(43),1);
        tabs.addView(allTab,firstTab);
        LinearLayout.LayoutParams secondTab=new LinearLayout.LayoutParams(0,d(43),1);
        secondTab.leftMargin=d(10);
        tabs.addView(savedTab,secondTab);
        root.addView(tabs);

        ScrollView scroll=new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout page=layout(false);
        page.setPadding(d(16),0,d(16),d(28));
        scroll.addView(page);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        // Keep connectivity details inside the sidebar; only the selected
        // shelf and actual transfer/errors appear in this calm main view.
        LinearLayout locationBar=layout(true);
        locationBar.setPadding(d(13),d(11),d(13),d(11));
        locationBar.setBackground(rounded(Color.rgb(234,242,237),14));
        int sourceCount=0;
        for(SecurePrefs.Profile profile:smbPreferences.listProfiles())
            if(profile.folderSelected && !profile.settings.share.isEmpty())sourceCount++;
        String folderText=sourceCount>1
            ?sourceCount+"カ所のNASフォルダを登録済み"
            :smbPreferences.hasFolderSelection()
              ?smbSettings.share+(smbSettings.folder.isEmpty()?"":" / "+smbSettings.folder.replace('\\','/'))
              :"フォルダ未選択";
        TextView pathLabel=text("▣  "+folderText,12,true);
        pathLabel.setTextColor(Color.rgb(55,98,82));
        pathLabel.setMaxLines(2);
        locationBar.addView(pathLabel,new LinearLayout.LayoutParams(0,-2,1));
        TextView connectionIcon=subtle("NAS",10);
        connectionIcon.setTextColor(accent);
        locationBar.addView(connectionIcon);
        page.addView(locationBar);

        TextView state=subtle(nasStatus,11);
        state.setPadding(d(5),d(7),d(5),d(2));
        state.setVisibility(nasBusy||nasStatus.contains("失敗")?View.VISIBLE:View.GONE);
        page.addView(state);
        nasStatusView=state;

        List<Object> visible=new ArrayList<>();
        for(JSONObject book:readBooks()){
            if(shelfFilter.isEmpty()||bookMatches(book.optString("title",""),
                                                   book.optString("author","")))
                visible.add(book);
        }
        if(shelfTab==0){
            for(SmbRepository.RemoteBook remote:nasBooks){
                if(isHidden(remoteId(remote)) || cachedRemote(remote)!=null)continue;
                JSONObject preview=cachedPreview(remote);
                String title=preview==null?remote.name:preview.optString("title",remote.name);
                String author=preview==null?"":preview.optString("author","");
                if(shelfFilter.isEmpty()||bookMatches(title,author))visible.add(remote);
            }
        }
        visible.sort(Comparator.comparing(this::gridItemTitle,String.CASE_INSENSITIVE_ORDER));
        if(!shelfFilter.isEmpty()){
            TextView query=buttonAsLabel("検索: "+shelfFilter+"   ✕",v->{shelfFilter="";shelf();});
            LinearLayout.LayoutParams q=new LinearLayout.LayoutParams(-1,-2);
            q.topMargin=d(12);
            page.addView(query,q);
        }
        TextView overview=subtle(shelfTab==0
            ? "すべての本  ·  "+visible.size()+"冊  /  NASの本もタップで読めます"
            : "端末に保存済み  ·  "+visible.size()+"冊  /  オフライン読書",12);
        overview.setPadding(d(4),d(19),d(4),d(17));
        page.addView(overview);
        if(visible.isEmpty()){
            addEmptyShelf(page,shelfTab==0?"本が見つかりません":"保存済みの本はありません",
                shelfTab==0?"NASのフォルダを更新するか、EPUBを追加してください。"
                    :"本の表紙を長押しして、Wi-Fiで本文を保存できます。");
        }else{
            for(int pos=0;pos<visible.size();pos+=3){
                LinearLayout row=layout(true);
                row.setGravity(Gravity.TOP);
                page.addView(row);
                for(int i=0;i<3;i++){
                    LinearLayout.LayoutParams cell=new LinearLayout.LayoutParams(0,-2,1);
                    if(i>0)cell.leftMargin=d(9);
                    if(pos+i<visible.size()){
                        Object item=visible.get(pos+i);
                        if(item instanceof JSONObject)addSavedCard(row,(JSONObject)item,cell);
                        else addNasCard(row,(SmbRepository.RemoteBook)item,cell);
                    }else row.addView(new View(this),cell);
                }
            }
        }
        if(nasBooks.isEmpty()&&sourceCount==0){
            TextView setup=text("左上の ☰ → NAS接続設定 → 読み取りフォルダ選択から始められます。",12,false);
            setup.setTextColor(subText);
            setup.setPadding(d(5),d(12),d(5),d(12));
            page.addView(setup);
        }
    }

    private boolean bookMatches(String name,String author){
        String search=shelfFilter.toLowerCase(Locale.ROOT);
        return name.toLowerCase(Locale.ROOT).contains(search)
            ||author.toLowerCase(Locale.ROOT).contains(search);
    }
    private TextView buttonAsLabel(String value,View.OnClickListener click){
        TextView label=text(value,12,true);
        label.setTextColor(accent);
        label.setPadding(d(14),d(11),d(14),d(11));
        label.setBackground(rounded(accentSoft,12));
        label.setOnClickListener(click);
        return label;
    }
    private void searchLibrary(){
        EditText query=new EditText(this);
        query.setSingleLine(true);
        query.setHint("タイトル・著者名で探す");
        query.setText(shelfFilter);
        query.setPadding(d(20),d(14),d(20),d(12));
        new AlertDialog.Builder(this).setTitle("本棚を検索")
            .setView(query)
            .setNegativeButton("キャンセル",null)
            .setNeutralButton("検索解除",(dialog,which)->{shelfFilter="";shelf();})
            .setPositiveButton("検索",(dialog,which)->{
                shelfFilter=query.getText().toString().trim();
                shelf();
            }).show();
    }

    private TextView shelfTabButton(String label,boolean active){
        TextView v=text(label,14,true);
        v.setGravity(Gravity.CENTER);
        v.setTextColor(active?Color.WHITE:accent);
        v.setBackground(rounded(active?accent:Color.rgb(232,240,235),14));
        return v;
    }
    private String gridItemTitle(Object item){
        if(item instanceof JSONObject)return ((JSONObject)item).optString("title","");
        SmbRepository.RemoteBook remote=(SmbRepository.RemoteBook)item;
        JSONObject preview=cachedPreview(remote);
        return preview==null?remote.name:preview.optString("title",remote.name);
    }

    private void addShelfHeading(LinearLayout page,String title,int count,String description){
        LinearLayout line=layout(true);
        line.setPadding(d(2),d(24),d(2),d(11));
        TextView heading=text(title,18,true);
        line.addView(heading);
        TextView number=statusChip(count+"冊",true);
        LinearLayout.LayoutParams badge=new LinearLayout.LayoutParams(-2,-2);
        badge.leftMargin=d(9);
        line.addView(number,badge);
        page.addView(line);
        TextView sub=subtle(description,11);
        sub.setPadding(d(2),0,d(2),d(12));
        page.addView(sub);
    }
    private void addEmptyShelf(LinearLayout page,String headline,String detail){
        LinearLayout empty=layout(false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(d(12),d(24),d(12),d(24));
        empty.setBackground(bordered(Color.WHITE,15,Color.rgb(230,235,232)));
        TextView text=text(headline,13,true);
        text.setGravity(Gravity.CENTER);
        empty.addView(text);
        TextView explain=subtle(detail,11);
        explain.setGravity(Gravity.CENTER);
        explain.setPadding(0,d(7),0,0);
        empty.addView(explain);
        page.addView(empty);
    }
    private LinearLayout bookCardBase(LinearLayout row,LinearLayout.LayoutParams params){
        LinearLayout card=layout(false);
        card.setPadding(d(6),d(6),d(6),d(11));
        card.setBackground(bordered(surface,14,Color.rgb(234,238,235)));
        card.setElevation(d(2));
        LinearLayout.LayoutParams outer=new LinearLayout.LayoutParams(params.width,params.height,params.weight);
        outer.leftMargin=params.leftMargin;
        outer.bottomMargin=d(10);
        row.addView(card,outer);
        return card;
    }
    private ImageView bookImage(File cover){
        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackground(rounded(Color.rgb(240,243,241),11));
        if(cover!=null && cover.isFile()){
            BitmapFactory.Options options=new BitmapFactory.Options();
            options.inSampleSize=3;
            Bitmap bmp=BitmapFactory.decodeFile(cover.getAbsolutePath(),options);
            if(bmp!=null)image.setImageBitmap(bmp);
        }
        return image;
    }
    private void cardText(LinearLayout card,String name,String author,boolean saved,String key){
        TextView chip=statusChip(saved?"保存済み":"NAS",saved);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);
        cp.topMargin=d(7);
        card.addView(chip,cp);
        TextView bookTitle=text(name,12,true);
        bookTitle.setMaxLines(2);
        bookTitle.setMinLines(2);
        bookTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleParam=new LinearLayout.LayoutParams(-1,-2);
        titleParam.topMargin=d(6);
        card.addView(bookTitle,titleParam);

        // Download indicator sits BETWEEN book title and author.
        // Muted empty track fills left-to-right with emerald as bytes arrive.
        if(key!=null && transferPercents.containsKey(key)){
            ProgressBar progress=new ProgressBar(this,null,
                android.R.attr.progressBarStyleHorizontal);
            progress.setIndeterminate(false);
            progress.setMax(100);
            progress.setProgress(Math.max(0,Math.min(100,transferPercents.get(key))));
            progress.setProgressTintList(ColorStateList.valueOf(accent));
            progress.setProgressBackgroundTintList(ColorStateList.valueOf(
                Color.rgb(225,231,227)));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,d(5));
            lp.topMargin=d(9);lp.bottomMargin=d(7);
            card.addView(progress,lp);
            displayedProgress.put(key,progress);
        }else{
            View spacer=new View(this);
            card.addView(spacer,new LinearLayout.LayoutParams(-1,d(8)));
        }
        TextView by=subtle(author,10);
        by.setMaxLines(1);
        by.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(by);
    }
    private void showDownloadProgress(String bookKey,long transferred,long total){
        int pct=total>0? (int)Math.min(100L,(100L*transferred)/total):0;
        runOnUiThread(()->{
            if(!transferPercents.containsKey(bookKey))return;
            transferPercents.put(bookKey,pct);
            ProgressBar bar=displayedProgress.get(bookKey);
            if(bar!=null)bar.setProgress(pct,true);
        });
    }

    private void addSavedCard(LinearLayout row,JSONObject book,LinearLayout.LayoutParams params){
        LinearLayout card=bookCardBase(row,params);
        String id=book.optString("id","");
        String path=book.optString("cover","");
        File cover=path.isEmpty()?null:new File(new File(library,id),path);
        ImageView image=bookImage(cover);
        card.addView(image,new LinearLayout.LayoutParams(-1,d(148)));
        cardText(card,book.optString("title","名称未設定"),book.optString("author",""),true,id);
        String source=book.optString("source","");
        SmbRepository.RemoteBook remote=source.isEmpty()?null:remoteForBook(book);
        View.OnClickListener onClick=v->open(book);
        View.OnLongClickListener onLong=v->{
            showBookMenu(remote,book,null);return true;
        };
        card.setOnClickListener(onClick);card.setOnLongClickListener(onLong);
        image.setOnClickListener(onClick);image.setOnLongClickListener(onLong);
    }
    private void addNasCard(LinearLayout row,SmbRepository.RemoteBook remote,
                            LinearLayout.LayoutParams params){
        LinearLayout card=bookCardBase(row,params);
        JSONObject info=cachedPreview(remote);
        String title=info==null?remote.name:info.optString("title",remote.name);
        String author=info==null?"":info.optString("author","");
        String coverPath=info==null?"":info.optString("cover","");
        File cover=coverPath.isEmpty()?null:new File(new File(previews,remoteId(remote)),coverPath);
        ImageView image=bookImage(cover);
        card.addView(image,new LinearLayout.LayoutParams(-1,d(148)));
        cardText(card,title,author,false,remoteId(remote));
        SecurePrefs.Profile origin=smbPreferences.profile(remote.profileId);
        if(origin!=null && smbPreferences.listProfiles().size()>1){
            TextView source=subtle(origin.name,9);
            source.setSingleLine(true);
            source.setEllipsize(android.text.TextUtils.TruncateAt.END);
            source.setPadding(0,d(5),0,0);
            card.addView(source);
        }
        View.OnClickListener onClick=v->showPreviewTap(remote);
        View.OnLongClickListener onLong=v->{
            showBookMenu(remote,null,info);return true;
        };
        card.setOnClickListener(onClick);card.setOnLongClickListener(onLong);
        image.setOnClickListener(onClick);image.setOnLongClickListener(onLong);
    }
    private void addDrawerItem(LinearLayout list,String symbol,String title,String description,
                                Runnable action,Dialog dialog){
        LinearLayout item=layout(true);
        item.setPadding(d(17),d(12),d(12),d(12));
        TextView ico=text(symbol,20,false);
        ico.setGravity(Gravity.CENTER);
        ico.setTextColor(accent);
        item.addView(ico,new LinearLayout.LayoutParams(d(34),d(40)));
        LinearLayout labels=layout(false);
        labels.setPadding(d(10),0,0,0);
        labels.addView(text(title,15,true));
        TextView detail=subtle(description,11);
        detail.setPadding(0,d(3),0,0);
        labels.addView(detail);
        item.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        list.addView(item);
        View separator=new View(this);
        separator.setBackgroundColor(Color.rgb(239,242,239));
        list.addView(separator,new LinearLayout.LayoutParams(-1,d(1)));
        item.setOnClickListener(v->{dialog.dismiss();action.run();});
    }
    private void showLibraryDrawer(){
        Dialog dialog=new Dialog(this);
        LinearLayout panel=layout(false);
        panel.setBackgroundColor(Color.WHITE);
        panel.setPadding(0,d(24),0,d(12));
        TextView appTitle=text("  読書棚",24,true);
        appTitle.setTextColor(accent);
        panel.addView(appTitle);
        TextView slogan=subtle("    NAS EPUB READER  ·  v0.4.9",11);
        slogan.setPadding(0,d(5),0,d(20));
        panel.addView(slogan);
        ScrollView scroll=new ScrollView(this);
        LinearLayout list=layout(false);
        scroll.addView(list);
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        addDrawerItem(list,"▦","本棚","保存済みとNASの本",()->shelf(),dialog);
        addDrawerItem(list,"↻","NASの本を更新","フォルダ内のEPUBを再検索",()->refreshNas(),dialog);
        addDrawerItem(list,"⌂","NAS接続先を管理","複数NASの追加・切替・削除",()->showProfileManager(),dialog);
        addDrawerItem(list,"✎","接続先の編集","選択中のNASの認証設定",()->showSmbSettings(),dialog);
        addDrawerItem(list,"▤","読み取りフォルダ選択","選択中のNASでフォルダを参照",()->startFolderBrowser(),dialog);
        addDrawerItem(list,"◇","内蔵Tailscale","外出先接続と通信診断",()->showTailnetPanel(),dialog);
        addDrawerItem(list,"＋","端末のEPUBを追加","ローカルファイルから取り込む",()->pick(),dialog);
        addDrawerItem(list,"◷","読書設定","文字の大きさ",()->showReaderSettings(),dialog);
        addDrawerItem(list,"▧","キャッシュ管理","表紙データの確認と削除",()->showCacheManager(),dialog);
        addDrawerItem(list,"↺","非表示の本を戻す","この端末の非表示設定を解除",()->restoreHidden(),dialog);
        addDrawerItem(list,"ⓘ","アプリ情報","バージョンと利用方法",()->showAppInfo(),dialog);
        dialog.setContentView(panel);
        Window window=dialog.getWindow();
        if(window!=null){
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.START|Gravity.TOP);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes=window.getAttributes();
            attributes.width=Math.min(d(340),(int)(getResources().getDisplayMetrics().widthPixels*0.86f));
            attributes.height=WindowManager.LayoutParams.MATCH_PARENT;
            attributes.dimAmount=0.43f;
            window.setAttributes(attributes);
        }
        dialog.show();
        Window shown=dialog.getWindow();
        if(shown!=null){
            shown.setBackgroundDrawableResource(android.R.color.transparent);
            shown.setGravity(Gravity.START|Gravity.TOP);
            shown.setLayout(Math.min(d(340),(int)(getResources().getDisplayMetrics().widthPixels*0.86f)),
                WindowManager.LayoutParams.MATCH_PARENT);
        }
    }
    private void restoreHidden(){
        int count=getHiddenIds().size();
        if(count==0){
            Toast.makeText(this,"非表示の本はありません",Toast.LENGTH_SHORT).show();return;
        }
        new AlertDialog.Builder(this).setTitle("非表示の本を戻す")
            .setMessage(count+"冊を本棚へ戻します。NASのファイルは変更されません。")
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("戻す",(dialog,which)->{
                getSharedPreferences("hidden_books_v1",MODE_PRIVATE)
                    .edit().remove("hidden").apply();
                shelf();
            }).show();
    }
    private void showReaderSettings(){
        final int[] sizes={80,90,100,110,125,150,175};
        String[] options=new String[sizes.length];
        int selected=0;
        for(int i=0;i<sizes.length;i++){
            options[i]="文字サイズ "+sizes[i]+"%";
            if(Math.abs(fontSize-sizes[i])<Math.abs(fontSize-sizes[selected]))
                selected=i;
        }
        new AlertDialog.Builder(this).setTitle("読書設定")
            .setSingleChoiceItems(options,selected,(dialog,which)->{
                fontSize=sizes[which];
                getPreferences(0).edit().putInt("fontSize",fontSize).apply();
                dialog.dismiss();
                Toast.makeText(this,"文字サイズを保存しました",Toast.LENGTH_SHORT).show();
            }).setNegativeButton("閉じる",null).show();
    }
    private long directorySize(File dir){
        if(dir==null||!dir.exists())return 0;
        if(dir.isFile())return dir.length();
        long size=0;
        File[] children=dir.listFiles();
        if(children!=null)for(File child:children)size+=directorySize(child);
        return size;
    }
    private void showCacheManager(){
        long covers=directorySize(previews);
        long books=directorySize(library);
        String info="表紙の一時データ: "+(covers/(1024*1024))+" MB"
            +"\n保存済み本文: "+(books/(1024*1024))+" MB"
            +"\n\n表紙キャッシュを削除してもNASの原本や保存済み本文には影響しません。";
        new AlertDialog.Builder(this).setTitle("キャッシュ管理")
            .setMessage(info)
            .setNegativeButton("閉じる",null)
            .setPositiveButton("表紙キャッシュを削除",(dialog,which)->{
                File[] dirs=previews.listFiles();
                if(dirs!=null)for(File dir:dirs)delete(dir);
                shelf();
            }).show();
    }
    private void showAppInfo(){
        new AlertDialog.Builder(this)
            .setTitle("NAS EPUB Reader")
            .setMessage("Version 0.4.9\n\n"
                +"NAS内のEPUBを表紙付きで管理できる読書アプリです。"
                +"表紙を長押しすると書籍メニューが開きます。\n"
                +"本文のダウンロードはWi-Fi接続時のみ可能です。\n"
                +"内蔵Tailscaleで外出先からの表紙取得にも対応しています。\n"
                +"NASの原本をアプリから削除することはありません。")
            .setPositiveButton("閉じる",null).show();
    }

    private String remoteId(String path){return idFor(smbSettings,path);}
    private String idFor(SmbRepository.Settings cfg,String path){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            String key=cfg.host+"|"+cfg.share+"|"+path;
            byte[] bytes=md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder("nas-");
            for(int i=0;i<12;i++)b.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));
            return b.toString();
        }catch(Exception e){return "nas-fallback";}
    }
    private SmbRepository.Settings settingsFor(SmbRepository.RemoteBook item){
        SecurePrefs.Profile profile=smbPreferences.profile(item.profileId);
        if(profile==null)throw new IllegalArgumentException("NAS接続先が削除されています");
        return profile.settings.copy();
    }
    private String remoteId(SmbRepository.RemoteBook item){
        return idFor(settingsFor(item),item.path);
    }
    private SmbRepository.RemoteBook remoteForBook(JSONObject book){
        String source=book.optString("source","");
        String id=book.optString("id","");
        if(source.isEmpty())return null;
        for(SecurePrefs.Profile p:smbPreferences.listProfiles()){
            if(idFor(p.settings,source).equals(id))
                return new SmbRepository.RemoteBook(book.optString("title",""),
                    source,p.id);
        }
        return null;
    }
    private JSONObject cachedRemote(SmbRepository.RemoteBook remote){
        try{
            String id=remoteId(remote);
            File metadata=new File(new File(library,id),"book.json");
            if(!metadata.isFile())return null;
            JSONObject obj=new JSONObject(new String(readFile(metadata,500000),StandardCharsets.UTF_8));
            return id.equals(obj.optString("id"))?obj:null;
        }catch(Exception e){return null;}
    }
    private String indexKey(SmbRepository.Settings cfg){
        return cfg.host+"|"+cfg.share+"|"+cfg.folder;
    }
    private void saveRemoteIndex(){
        android.content.SharedPreferences.Editor editor=
            getSharedPreferences("book_index_v1",MODE_PRIVATE).edit();
        try{
            for(SecurePrefs.Profile p:smbPreferences.listProfiles()){
                if(!p.folderSelected)continue;
                JSONObject data=new JSONObject();
                data.put("key",indexKey(p.settings));
                JSONArray names=new JSONArray();
                for(SmbRepository.RemoteBook book:nasBooks){
                    if(!book.profileId.equals(p.id))continue;
                    JSONObject item=new JSONObject();
                    item.put("name",book.name);item.put("path",book.path);
                    names.put(item);
                }
                data.put("books",names);
                editor.putString("index_"+p.id,data.toString());
            }
            editor.apply();
        }catch(Exception ignored){}
    }
    private void loadSavedIndex(){
        nasBooks.clear();
        android.content.SharedPreferences index=
            getSharedPreferences("book_index_v1",MODE_PRIVATE);
        for(SecurePrefs.Profile p:smbPreferences.listProfiles()){
            if(!p.folderSelected)continue;
            try{
                String raw=index.getString("index_"+p.id,"");
                if(raw.isEmpty() && "primary".equals(p.id))
                    raw=index.getString("index","");
                if(raw.isEmpty())continue;
                JSONObject data=new JSONObject(raw);
                if(!indexKey(p.settings).equals(data.optString("key")))continue;
                JSONArray items=data.optJSONArray("books");
                if(items==null)continue;
                for(int i=0;i<Math.min(items.length(),350);i++){
                    JSONObject x=items.optJSONObject(i);
                    if(x==null)continue;
                    String name=x.optString("name",""),path=x.optString("path","");
                    if(name.toLowerCase(Locale.ROOT).endsWith(".epub")&&!path.isEmpty())
                        nasBooks.add(new SmbRepository.RemoteBook(name,path,p.id));
                }
            }catch(Exception ignored){}
        }
        if(!nasBooks.isEmpty())nasStatus="本棚キャッシュ: "+nasBooks.size()+"冊（更新でNASと同期）";
    }
    private JSONObject cachedPreview(SmbRepository.RemoteBook remote){
        try{
            String id=remoteId(remote);
            File f=new File(new File(previews,id),"preview.json");
            if(!f.isFile() || f.length()>500000)return null;
            JSONObject data=new JSONObject(new String(readFile(f,500000),StandardCharsets.UTF_8));
            if(!id.equals(data.optString("id")))return null;
            return data;
        }catch(Exception e){return null;}
    }
    private JSONObject fetchPreview(SmbRepository.Settings cfg,SmbRepository.RemoteBook item)throws Exception{
        JSONObject hit=cachedPreview(item);
        if(hit!=null)return hit;
        String id=remoteId(item);
        File dir=new File(previews,id);
        try{
            EpubParser.BookInfo parsed=SmbRepository.preview(cfg,item.path,dir);
            JSONObject data=new JSONObject();
            data.put("id",id);data.put("source",item.path);
            data.put("profile_id",item.profileId);
            data.put("title",parsed.title);data.put("author",parsed.author);
            data.put("cover",parsed.cover);
            try(FileOutputStream out=new FileOutputStream(new File(dir,"preview.json"))){
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            }
            return data;
        }catch(Exception e){
            delete(dir);
            throw e;
        }
    }
    private void addPreviewCard(LinearLayout list,SmbRepository.RemoteBook remote,JSONObject info){
        LinearLayout card=layout(true);
        card.setPadding(d(8),d(7),d(8),d(9));
        ImageView image=new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setBackgroundColor(Color.rgb(232,235,238));
        File cover=new File(new File(previews,remoteId(remote)),info.optString("cover",""));
        if(!info.optString("cover").isEmpty()&&cover.isFile()){
            BitmapFactory.Options opts=new BitmapFactory.Options();
            opts.inSampleSize=4;
            Bitmap bmp=BitmapFactory.decodeFile(cover.getAbsolutePath(),opts);
            if(bmp!=null)image.setImageBitmap(bmp);
        }
        card.addView(image,new LinearLayout.LayoutParams(d(90),d(130)));
        LinearLayout labels=layout(false);
        labels.setPadding(d(12),d(9),d(2),d(4));
        TextView name=text(info.optString("title",remote.name),15,true);
        labels.addView(name);
        labels.addView(text(info.optString("author",""),12,false));
        labels.addView(text("本文未保存・表紙を長押しでメニュー",12,false));
        card.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        card.setOnClickListener(v->showPreviewTap(remote));
        card.setOnLongClickListener(v->{
            showBookMenu(remote,null,info);
            return true;
        });
        list.addView(card);
        addLine(list);
    }

    private void renderRemoteBooks(LinearLayout list){
        if(nasBooks.isEmpty())return;
        int remaining=0,withoutPreview=0;
        for(SmbRepository.RemoteBook item:nasBooks){
            if(isHidden(remoteId(item)) || cachedRemote(item)!=null)continue;
            remaining++;
            if(cachedPreview(item)==null)withoutPreview++;
        }
        TextView section=text("NASの本（本文未取得 "+remaining+"冊）",17,true);
        section.setPadding(d(6),d(8),d(6),d(3));list.addView(section);
        TextView help=text("本文はWi-Fi時のみ手動で保存します。表紙を長押しすると操作メニューが開きます。",12,false);
        list.addView(help);
        if(withoutPreview>0)
            list.addView(button("表紙だけ軽量取得（最大12冊ずつ）",v->syncNasCovers()));
        int shown=0;
        for(SmbRepository.RemoteBook item:nasBooks){
            if(isHidden(remoteId(item)) || cachedRemote(item)!=null)continue;
            if(shown++>=120){
                list.addView(text("ほかのファイルもあります。検索範囲を絞ってください。",12,false));
                break;
            }
            JSONObject preview=cachedPreview(item);
            if(preview!=null){addPreviewCard(list,item,preview);continue;}
            TextView entry=text("  ↓  "+item.name,14,false);
            entry.setPadding(d(6),d(11),d(6),d(11));
            entry.setOnClickListener(v->showPreviewTap(item));
            entry.setOnLongClickListener(v->{
                showBookMenu(item,null,null);
                return true;
            });
            list.addView(entry);
        }
        if(remaining<nasBooks.size() || remaining>0){
            TextView heading=text("本文を保存した本",17,true);
            heading.setPadding(d(6),d(14),d(4),d(6));list.addView(heading);
        }
    }
    private EditText settingField(String hint,String value,int type){
        EditText field=new EditText(this);
        field.setSingleLine(true);
        field.setTextSize(15);
        field.setHint(hint);
        field.setInputType(type);
        field.setText(value);
        return field;
    }
    private void showProfileManager(){
        List<SecurePrefs.Profile> profiles=smbPreferences.listProfiles();
        List<String> actions=new ArrayList<>();
        String active=smbPreferences.activeId();
        for(SecurePrefs.Profile p:profiles){
            String target=p.settings.host.isEmpty()?"未設定":p.settings.host;
            actions.add((p.id.equals(active)?"● ":"○ ")+p.name
                +"  /  "+target+(p.folderSelected?"":"  [フォルダ未選択]"));
        }
        actions.add("＋ NAS接続先を追加");
        actions.add("✎ 選択中の接続先を編集");
        actions.add("▤ 選択中の読み取りフォルダを設定");
        actions.add("⋮ 選択中の接続先を変更・削除");
        actions.add("↻ すべてのNASを更新");
        new AlertDialog.Builder(this).setTitle("NAS接続先  ·  "+profiles.size()+"件")
            .setItems(actions.toArray(new String[0]),(dialog,which)->{
                if(which<profiles.size()){
                    SecurePrefs.Profile profile=profiles.get(which);
                    smbPreferences.select(profile.id);
                    smbSettings=smbPreferences.load();
                    nasStatus="接続先を選択: "+profile.name;
                    shelf();
                    Toast.makeText(this,profile.name+" を選択しました",
                        Toast.LENGTH_SHORT).show();
                    return;
                }
                switch(which-profiles.size()){
                    case 0: addNasProfile();break;
                    case 1: showSmbSettings();break;
                    case 2: startFolderBrowser();break;
                    case 3: editProfileActions();break;
                    default: refreshNas();
                }
            }).setNegativeButton("閉じる",null).show();
    }
    private void addNasProfile(){
        EditText name=settingField("例: 自宅NAS / 研究室NAS","",
            InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this).setTitle("新しいNAS接続先")
            .setMessage("接続先ごとにホスト・共有・フォルダを設定できます。")
            .setView(name)
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("追加",(dialog,which)->{
                try{
                    smbPreferences.add(name.getText().toString());
                    smbSettings=smbPreferences.load();
                    shelf();
                    showSmbSettings();
                }catch(Exception e){
                    new AlertDialog.Builder(this).setTitle("追加できません")
                        .setMessage(friendly(e)).setPositiveButton("閉じる",null).show();
                }
            }).show();
    }
    private void editProfileActions(){
        SecurePrefs.Profile p=smbPreferences.profile(smbPreferences.activeId());
        if(p==null)return;
        new AlertDialog.Builder(this).setTitle(p.name)
            .setItems(new String[]{"名前を変更","この接続先を削除"},(dialog,which)->{
                if(which==0){
                    EditText field=settingField("接続先名",p.name,InputType.TYPE_CLASS_TEXT);
                    new AlertDialog.Builder(this).setTitle("接続先の名前")
                        .setView(field).setNegativeButton("キャンセル",null)
                        .setPositiveButton("保存",(d,w)->{
                            try{smbPreferences.rename(p.id,field.getText().toString());shelf();}
                            catch(Exception e){Toast.makeText(this,friendly(e),
                                Toast.LENGTH_LONG).show();}
                        }).show();
                }else{
                    new AlertDialog.Builder(this).setTitle("接続先を削除しますか？")
                        .setMessage("接続設定のみ削除します。"
                            +"NASの原本や端末内に保存済みの本は削除しません。")
                        .setNegativeButton("キャンセル",null)
                        .setPositiveButton("削除",(d,w)->{
                            try{
                                smbPreferences.remove(p.id);
                                smbSettings=smbPreferences.load();
                                loadSavedIndex();
                                shelf();
                            }catch(Exception e){
                                Toast.makeText(this,friendly(e),Toast.LENGTH_LONG).show();
                            }
                        }).show();
                }
            }).setNegativeButton("閉じる",null).show();
    }

    private void showSmbSettings(){
        ScrollView view=new ScrollView(this);
        LinearLayout columns=layout(false);
        columns.setPadding(d(16),d(10),d(16),d(10));
        view.addView(columns);
        TextView hint=text("共有名を指定したあと、画面でフォルダを選びます。",13,false);
        columns.addView(hint);
        EditText host=settingField("NASのIPアドレス / ホスト名",smbSettings.host,InputType.TYPE_CLASS_TEXT);
        EditText share=settingField("SMB共有フォルダ名（例: personal_folder）",smbSettings.share,InputType.TYPE_CLASS_TEXT);
        EditText user=settingField("SMBユーザー名",smbSettings.user,InputType.TYPE_CLASS_TEXT);
        EditText pass=settingField("SMBパスワード",smbSettings.password,InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        EditText domain=settingField("ドメイン（通常は空欄）",smbSettings.domain,InputType.TYPE_CLASS_TEXT);
        for(EditText f:new EditText[]{host,share,user,pass,domain})columns.addView(f);
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("NAS接続設定（SMB2/3）")
            .setView(view)
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("保存してフォルダを探す",null)
            .create();
        dialog.setOnShowListener(a->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            SmbRepository.Settings next=new SmbRepository.Settings();
            next.host=host.getText().toString().trim();
            next.share=share.getText().toString().trim();
            next.user=user.getText().toString();
            next.password=pass.getText().toString();
            next.domain=domain.getText().toString();
            boolean locationChanged=!smbSettings.host.equals(next.host)
                || !smbSettings.share.equals(next.share)
                || !smbSettings.user.equals(next.user)
                || !smbSettings.domain.equals(next.domain);
            next.folder=locationChanged?"":smbSettings.folder;
            try{
                next.validate();
                smbPreferences.save(next);
                if(locationChanged)smbPreferences.setFolderSelected(false);
                smbSettings=next;
                if(locationChanged)
                    nasBooks.removeIf(book->book.profileId.equals(smbPreferences.activeId()));
                dialog.dismiss();
                loadFolder(locationChanged?"":next.folder);
            }catch(Exception e){
                new AlertDialog.Builder(this).setTitle("設定エラー")
                    .setMessage(e.getMessage()).setPositiveButton("閉じる",null).show();
            }
        }));
        dialog.show();
    }

    /** The folder list is a read-only file-browser view inside one SMB share. */
    private void startFolderBrowser(){
        if(nasBusy){
            Toast.makeText(this,"処理の終了後に操作してください",Toast.LENGTH_SHORT).show();
            return;
        }
        try{smbSettings.validate();}
        catch(Exception e){showSmbSettings();return;}
        loadFolder(smbPreferences.hasFolderSelection()?smbSettings.folder:"");
    }
    private static String parentFolder(String folder){
        int index=folder.lastIndexOf('\\');
        return index<0?"":folder.substring(0,index);
    }
    private void loadFolder(String path){
        if(nasBusy)return;
        folderBrowserVisible=true;
        folderBrowserLoading=true;
        folderBrowserPath=path;
        folderBrowserError="";
        folderBrowserItems.clear();
        long revision=++folderBrowserRevision;
        SmbRepository.Settings credentials=smbSettings.copy();
        showFolderBrowser();
        new Thread(()->{
            try{
                List<SmbRepository.BrowseEntry> items=SmbRepository.browse(credentials,path);
                runOnUiThread(()->{
                    if(!folderBrowserVisible || folderBrowserRevision!=revision)return;
                    folderBrowserItems.clear();
                    folderBrowserItems.addAll(items);
                    folderBrowserLoading=false;
                    showFolderBrowser();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(!folderBrowserVisible || folderBrowserRevision!=revision)return;
                    folderBrowserLoading=false;
                    folderBrowserError=friendly(e);
                    showFolderBrowser();
                });
            }
        },"smb-browse").start();
    }
    private void showFolderBrowser(){
        folderBrowserVisible=true;
        screen();
        LinearLayout header=layout(false);
        header.setPadding(d(12),d(13),d(12),d(10));
        header.setBackgroundColor(Color.rgb(35,35,35));
        LinearLayout headerButtons=layout(true);
        Button back=button("← 本棚",v->leaveBrowser());
        headerButtons.addView(back);
        TextView heading=text("NASフォルダ選択",19,true);
        heading.setTextColor(Color.WHITE);
        heading.setPadding(d(10),0,0,0);
        headerButtons.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        headerButtons.addView(button("更新",v->loadFolder(folderBrowserPath)));
        header.addView(headerButtons);
        TextView address=text(smbSettings.host+"  ›  "+smbSettings.share
                +(folderBrowserPath.isEmpty()?"":"  ›  "+folderBrowserPath.replace("\\","  ›  ")),13,false);
        address.setTextColor(Color.WHITE);
        address.setPadding(d(6),d(9),d(6),d(3));
        header.addView(address);
        root.addView(header);
        TextView routeInfo=text("実際のSMB通信経路: "+EmbeddedTailnet.routeDescription(smbSettings.host),12,true);
        routeInfo.setPadding(d(14),d(8),d(12),d(8));
        root.addView(routeInfo);

        ScrollView scroll=new ScrollView(this);
        LinearLayout list=layout(false);
        list.setPadding(d(10),d(10),d(10),d(10));
        scroll.addView(list);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));

        if(!folderBrowserPath.isEmpty()){
            TextView parent=text("⬆  1つ上のフォルダ",16,true);
            parent.setPadding(d(14),d(16),d(8),d(16));
            parent.setOnClickListener(v->loadFolder(parentFolder(folderBrowserPath)));
            list.addView(parent);
            addLine(list);
        }
        if(folderBrowserLoading){
            TextView loading=text("NASから一覧を取得しています…",15,false);
            loading.setPadding(d(12),d(30),d(12),d(30));
            list.addView(loading);
        }else if(!folderBrowserError.isEmpty()){
            TextView err=text("一覧を取得できませんでした\n"+folderBrowserError,14,false);
            err.setPadding(d(14),d(22),d(14),d(22));
            list.addView(err);
            list.addView(button("再接続",v->loadFolder(folderBrowserPath)));
        }else{
            int directories=0, files=0;
            for(SmbRepository.BrowseEntry entry:folderBrowserItems){
                if(entry.directory)directories++;else files++;
            }
            TextView summary=text(directories+" フォルダ ・ "+files+" ファイル",12,false);
            summary.setPadding(d(9),d(5),d(9),d(9));list.addView(summary);
            for(SmbRepository.BrowseEntry entry:folderBrowserItems){
                LinearLayout row=layout(true);
                row.setPadding(d(14),d(7),d(8),d(7));
                TextView icon=text(entry.directory?"📁":"📄",28,false);
                row.addView(icon);
                LinearLayout labels=layout(false);
                labels.setPadding(d(12),d(6),d(6),d(6));
                TextView name=text(entry.name,16,entry.directory);
                name.setMaxLines(2);
                labels.addView(name);
                if(!entry.directory && entry.name.toLowerCase(Locale.ROOT).endsWith(".epub")){
                    TextView tag=text("EPUB",11,false);labels.addView(tag);
                }
                row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
                if(entry.directory){
                    TextView arrow=text("›",24,false);row.addView(arrow);
                    row.setOnClickListener(v->loadFolder(entry.path));
                }
                list.addView(row);
                addLine(list);
            }
            if(folderBrowserItems.isEmpty()){
                TextView empty=text("このフォルダは空です。",14,false);
                empty.setPadding(d(14),d(30),d(14),d(30));list.addView(empty);
            }
            if(folderBrowserItems.size()>=1000){
                TextView cut=text("一覧は先頭1000件まで表示しています。",12,false);
                list.addView(cut);
            }
        }

        LinearLayout footer=layout(false);
        footer.setPadding(d(12),d(8),d(12),d(18));
        footer.setBackgroundColor(Color.WHITE);
        TextView subtitle=text("この場所からEPUBを検索して本棚を作ります。",12,false);
        footer.addView(subtitle);
        Button choose=button("✓ このフォルダを読み取り先に設定",v->selectCurrentFolder());
        choose.setEnabled(!folderBrowserLoading && folderBrowserError.isEmpty());
        footer.addView(choose);
        root.addView(footer);
    }
    private void addLine(LinearLayout parent){
        View line=new View(this);
        line.setBackgroundColor(Color.rgb(225,225,225));
        parent.addView(line,new LinearLayout.LayoutParams(-1,d(1)));
    }
    private void leaveBrowser(){
        ++folderBrowserRevision;
        folderBrowserVisible=false;
        shelf();
    }
    private void selectCurrentFolder(){
        if(folderBrowserLoading || !folderBrowserError.isEmpty())return;
        try{
            SmbRepository.Settings chosen=smbSettings.copy();
            chosen.folder=folderBrowserPath;
            chosen.validate();
            smbPreferences.save(chosen);
            smbPreferences.setFolderSelected(true);
            smbSettings=chosen;
            nasBooks.removeIf(book->book.profileId.equals(smbPreferences.activeId()));
            nasStatus="読み取り先を保存しました。EPUBを検索します…";
            leaveBrowser();
            refreshNas();
        }catch(Exception e){
            new AlertDialog.Builder(this).setTitle("保存できません")
                .setMessage(friendly(e)).setPositiveButton("閉じる",null).show();
        }
    }

    private void refreshNas(){
        if(nasBusy)return;
        List<SecurePrefs.Profile> sources=new ArrayList<>();
        for(SecurePrefs.Profile p:smbPreferences.listProfiles())
            if(p.folderSelected && !p.settings.share.isEmpty()
                && !p.settings.host.isEmpty())sources.add(p);
        if(sources.isEmpty()){
            if(!smbPreferences.hasFolderSelection())startFolderBrowser();
            else showProfileManager();
            return;
        }
        nasBusy=true;
        nasStatus="登録済み "+sources.size()+"カ所のNASを更新しています…";
        shelf();
        new Thread(()->{
            int success=0,failed=0;
            String firstError="";
            Map<String,List<SmbRepository.RemoteBook>> byProfile=new LinkedHashMap<>();
            for(SecurePrefs.Profile p:sources){
                try{
                    SmbRepository.Settings settings=p.settings.copy();
                    settings.validate();
                    List<SmbRepository.RemoteBook> fetched=SmbRepository.list(settings);
                    List<SmbRepository.RemoteBook> tagged=new ArrayList<>();
                    for(SmbRepository.RemoteBook book:fetched)
                        tagged.add(new SmbRepository.RemoteBook(book.name,book.path,p.id));
                    byProfile.put(p.id,tagged);
                    success++;
                }catch(Exception e){
                    failed++;
                    if(firstError.isEmpty())firstError=p.name+": "+friendly(e);
                }
                final int done=success+failed;
                runOnUiThread(()->reportTransfer("NASを検索中: "+done+"/"+sources.size()+"カ所"));
            }
            final int ok=success,bad=failed;
            final String error=firstError;
            runOnUiThread(()->{
                // Preserve cached entries for any source that failed.
                for(Map.Entry<String,List<SmbRepository.RemoteBook>> entry:byProfile.entrySet()){
                    nasBooks.removeIf(book->book.profileId.equals(entry.getKey()));
                    nasBooks.addAll(entry.getValue());
                }
                nasBooks.sort(Comparator.comparing(x->x.name.toLowerCase(Locale.ROOT)));
                saveRemoteIndex();
                nasBusy=false;
                nasStatus="NAS更新: "+ok+"/"+sources.size()+"カ所成功・"
                    +nasBooks.size()+"冊"+(bad>0?"／失敗: "+error:"");
                shelf();
                boolean needsCover=false;
                for(SmbRepository.RemoteBook item:nasBooks){
                    if(!isHidden(remoteId(item))
                        && cachedRemote(item)==null && cachedPreview(item)==null){
                        needsCover=true;break;
                    }
                }
                if(needsCover)syncNasCovers();
            });
        },"smb-list-all").start();
    }
    private String friendly(Exception e){
        String message=e.getMessage();
        if(message==null||message.trim().isEmpty())message=e.getClass().getSimpleName();
        if(message.length()>170)message=message.substring(0,170);
        return message;
    }
    private void reportTransfer(String message){
        runOnUiThread(()->{
            if(!nasBusy)return;
            nasStatus=message;
            if(nasStatusView!=null){nasStatusView.setText(message);nasStatusView.setVisibility(View.VISIBLE);}
        });
    }
    private String exceptionDetails(Throwable e){
        StringBuilder detail=new StringBuilder();
        int depth=0;
        while(e!=null && depth<7){
            if(depth>0)detail.append("\n原因").append(depth).append(": ");
            detail.append(e.getClass().getSimpleName());
            String m=e.getMessage();
            if(m!=null && !m.trim().isEmpty())detail.append(": ").append(m);
            e=e.getCause();depth++;
            if(e!=null)detail.append("\n");
        }
        String result=detail.toString();
        return result.length()>4500?result.substring(0,4500):result;
    }
    /** Do not copy the Tailscale authorization/login URL to diagnostic reports. */
    private String safeTailnetDiagnostic(){
        try{
            JSONObject data=new JSONObject(EmbeddedTailnet.status());
            return "phase="+data.optString("phase","unknown")
                +", target="+data.optString("target","")
                +", sessions="+data.optLong("tunnel_sessions",0)
                +", received="+data.optLong("bytes_from_nas",0)
                +", sent="+data.optLong("bytes_to_nas",0)
                +", lastError="+data.optString("last_tunnel_error","");
        }catch(Exception ex){return "状態の取得に失敗";}
    }
    private void showTransferError(String headline,Throwable e){
        String details="通信経路: "+EmbeddedTailnet.routeDescription(smbSettings.host)
            +"\nTailscale状態: "+safeTailnetDiagnostic()
            +"\n\n"+exceptionDetails(e);
        new AlertDialog.Builder(this).setTitle(headline)
            .setMessage(details)
            .setPositiveButton("閉じる",null)
            .setNeutralButton("診断内容をコピー",(dialog,which)->{
                ClipboardManager clipboard=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
                if(clipboard!=null)clipboard.setPrimaryClip(
                    ClipData.newPlainText("NAS EPUB Reader診断",details));
            }).show();
    }
    private JSONObject fetchRemote(SmbRepository.Settings cfg,SmbRepository.RemoteBook item) throws Exception {
        String id=remoteId(item);
        JSONObject cached=cachedRemote(item);
        if(cached!=null)return cached;
        File dir=new File(library,id);
        File temp=new File(getCacheDir(),"nas-"+id+".epub");
        try{
            reportTransfer("SMBからEPUBを取得中: "+item.name+"（0 KB）");
            SmbRepository.download(cfg,item.path,temp,150L*1024*1024,
                new SmbRepository.DownloadProgress(){
                    @Override public void onBytes(long bytes){
                        reportTransfer("SMBからEPUBを取得中: "+item.name
                            +"（"+(bytes/1024L)+" KB）");
                    }
                    @Override public void onProgress(long bytes,long total){
                        onBytes(bytes);
                        showDownloadProgress(id,bytes,total);
                    }
                },this::isWifiConnected);
            reportTransfer("転送完了。EPUBを解析中: "+item.name
                +"（"+(temp.length()/1024L)+" KB）");
            EpubParser.BookInfo info=EpubParser.extractAndParse(temp,dir);
            JSONObject obj=new JSONObject();
            obj.put("id",id);obj.put("title",info.title);
            obj.put("author",info.author);obj.put("cover",info.cover);
            obj.put("source",item.path);
            obj.put("profile_id",item.profileId);
            JSONArray pages=new JSONArray();
            for(String chapter:info.chapters)pages.put(chapter);
            obj.put("chapters",pages);
            try(FileOutputStream out=new FileOutputStream(new File(dir,"book.json"))){
                out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
            }
            return obj;
        }catch(Exception e){
            delete(dir);
            throw e;
        }finally{temp.delete();}
    }
    private boolean isWifiConnected(){
        ConnectivityManager cm=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if(cm==null)return false;
        Network active=cm.getActiveNetwork();
        if(active==null)return false;
        NetworkCapabilities caps=cm.getNetworkCapabilities(active);
        return caps!=null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
    private Set<String> getHiddenIds(){
        Set<String> hidden=getSharedPreferences("hidden_books_v1",MODE_PRIVATE)
            .getStringSet("hidden",Collections.emptySet());
        return new HashSet<>(hidden);
    }
    private boolean isHidden(String id){return getHiddenIds().contains(id);}
    private void hideRemoteBook(SmbRepository.RemoteBook remote){
        Set<String> hidden=getHiddenIds();
        hidden.add(remoteId(remote));
        getSharedPreferences("hidden_books_v1",MODE_PRIVATE)
            .edit().putStringSet("hidden",hidden).apply();
        shelf();
    }
    private void showPreviewTap(SmbRepository.RemoteBook remote){
        JSONObject book=cachedRemote(remote);
        if(book!=null){open(book);return;}
        openRemoteBook(remote);
    }
    /** Open an online EPUB using its spine and one lazily-loaded chapter. */
    private void openRemoteBook(SmbRepository.RemoteBook remote){
        if(nasBusy){
            Toast.makeText(this,"別の処理が実行中です",Toast.LENGTH_SHORT).show();
            return;
        }
        SmbRepository.Settings cfg=settingsFor(remote);
        nasBusy=true;
        nasStatus="オンライン読書を準備中: "+remote.name;
        shelf();
        new Thread(()->{
            try{
                final String id=remoteId(remote);
                File dir=new File(previews,id);
                EpubParser.BookInfo info;
                try{
                    info=EpubParser.parsePreview(dir);
                }catch(Exception invalid){
                    // The lightweight metadata index can exist without the OPF after
                    // manual cache removal. Re-fetch just metadata, not the full EPUB.
                    if(dir.exists())delete(dir);
                    info=SmbRepository.preview(cfg,remote.path,dir);
                    JSONObject preview=new JSONObject();
                    preview.put("id",id);preview.put("source",remote.path);
                    preview.put("profile_id",remote.profileId);
                    preview.put("title",info.title);preview.put("author",info.author);
                    preview.put("cover",info.cover);
                    try(OutputStream out=new FileOutputStream(new File(dir,"preview.json"))){
                        out.write(preview.toString().getBytes(StandardCharsets.UTF_8));
                    }
                }
                JSONObject book=new JSONObject();
                book.put("id",id);
                book.put("title",info.title);
                book.put("author",info.author);
                book.put("cover",info.cover);
                book.put("source",remote.path);
                book.put("profile_id",remote.profileId);
                JSONArray chapters=new JSONArray();
                for(String path:info.chapters)chapters.put(path);
                book.put("chapters",chapters);
                runOnUiThread(()->{
                    nasBusy=false;
                    streamingRemote=remote;
                    streamingSettings=cfg;
                    streamingBusy=false;
                    streamingGeneration++;
                    nasStatus="オンライン読書中: "+remote.name;
                    open(book);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    nasBusy=false;
                    nasStatus="オンライン読書を開始できません: "+friendly(e);
                    shelf();
                    showTransferError("NASから本を開けませんでした",e);
                });
            }
        },"smb-open-online-book").start();
    }


    private void showBookMenu(SmbRepository.RemoteBook remote,JSONObject book,JSONObject preview){
        String name=book!=null?book.optString("title",""):preview!=null
            ?preview.optString("title",remote==null?"":remote.name)
            :remote==null?"":remote.name;
        boolean downloaded=book!=null;
        List<String> actions=new ArrayList<>();
        actions.add(downloaded?"📖 本を読む":"📖 オンラインで読む");
        if(!downloaded && remote!=null)
            actions.add("⬇ 本文をダウンロード（Wi-Fiのみ）");
        if(downloaded)actions.add("🗑 本文を削除（端末のみ）");
        if(remote!=null)actions.add("🙈 本棚から非表示（この端末のみ）");
        if(remote!=null)actions.add("🖼 表紙キャッシュを削除");
        actions.add("ℹ 書籍情報");
        new AlertDialog.Builder(this).setTitle(name.isEmpty()?"書籍メニュー":name)
            .setItems(actions.toArray(new String[0]),(dialog,which)->{
                String selected=actions.get(which);
                if(selected.startsWith("📖")){
                    if(book!=null)open(book);
                    else if(remote!=null)openRemoteBook(remote);
                }
                else if(selected.startsWith("⬇") && remote!=null)downloadRemoteBook(remote);
                else if(selected.startsWith("🗑") && book!=null)confirmLocalDeletion(book);
                else if(selected.startsWith("🙈") && remote!=null){
                    new AlertDialog.Builder(this).setTitle("本棚から非表示にしますか？")
                        .setMessage("この端末の本棚から隠すだけです。NAS上のEPUBは削除されません。"
                          +"非表示の本は本棚画面から復元できます。")
                        .setNegativeButton("キャンセル",null)
                        .setPositiveButton("非表示にする",(d,w)->hideRemoteBook(remote))
                        .show();
                }else if(selected.startsWith("🖼") && remote!=null){
                    File dir=new File(previews,remoteId(remote));
                    if(isDirectChild(previews,dir))delete(dir);
                    shelf();
                    Toast.makeText(this,"表紙キャッシュだけ削除しました",Toast.LENGTH_SHORT).show();
                }else if(selected.startsWith("ℹ")){
                    String detail=(name.isEmpty()?"タイトル不明":name)
                        +"\n"+(book!=null?book.optString("author","")
                        :preview!=null?preview.optString("author",""):"")
                        +"\n本文: "+(downloaded?"端末に保存済み":"未保存")
                        +"\n原本: "+(remote!=null?"NAS（変更しません）":"端末")
                        +(remote!=null && smbPreferences.profile(remote.profileId)!=null
                            ?"\n接続先: "+smbPreferences.profile(remote.profileId).name:"");
                    new AlertDialog.Builder(this).setTitle("書籍情報").setMessage(detail)
                        .setPositiveButton("閉じる",null).show();
                }
            }).setNegativeButton("閉じる",null).show();
    }
    private boolean isDirectChild(File parent,File child){
        try{return child.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile());}
        catch(Exception e){return false;}
    }
    private void confirmLocalDeletion(JSONObject book){
        String id=book.optString("id","");
        File dir=new File(library,id);
        if(id.isEmpty()||!isDirectChild(library,dir))return;
        new AlertDialog.Builder(this).setTitle("端末内の本文を削除しますか？")
            .setMessage(book.optString("title","")
                +"\n本文データと、この端末の読書位置が削除されます。"
                +"\nNAS上のEPUBは削除しません。")
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("端末から削除",(dialog,which)->{
                delete(dir);
                getPreferences(0).edit().remove("chapter_"+id).apply();
                getSharedPreferences("reader_marks_v1",MODE_PRIVATE).edit()
                    .remove("bookmarks_"+id).apply();
                nasStatus="端末内の本文を削除しました（NASの原本は保持）";
                shelf();
            }).show();
    }
    private void downloadRemoteBook(SmbRepository.RemoteBook item){
        if(nasBusy){
            Toast.makeText(this,"別の処理が実行中です",Toast.LENGTH_SHORT).show();return;
        }
        JSONObject existing=cachedRemote(item);
        if(existing!=null){
            Toast.makeText(this,"本文は保存済みです",Toast.LENGTH_SHORT).show();
            open(existing);return;
        }
        if(!isWifiConnected()){
            new AlertDialog.Builder(this).setTitle("Wi-Fi接続が必要です")
                .setMessage("本文はWi-Fi接続時のみダウンロードできます。"
                    +"5Gなどのモバイル回線では、表紙と書誌情報だけを表示します。")
                .setPositiveButton("閉じる",null).show();
            return;
        }
        SmbRepository.Settings cfg=settingsFor(item);
        final String key=remoteId(item);
        transferPercents.put(key,0);
        nasBusy=true;nasStatus="Wi-FiでEPUB本文を取得中: "+item.name;shelf();
        new Thread(()->{
            try{
                JSONObject book=fetchRemote(cfg,item);
                runOnUiThread(()->{
                    transferPercents.remove(key);
                    nasBusy=false;nasStatus="Wi-Fiで本文を保存しました: "+item.name;
                    shelf();
                    Toast.makeText(this,"本文を保存しました。表紙をタップすると読めます",Toast.LENGTH_LONG).show();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    transferPercents.remove(key);
                    nasBusy=false;nasStatus="本文取得失敗: "+friendly(e);
                    shelf();
                    showTransferError("本文ダウンロードに失敗しました",e);
                });
            }
        },"wifi-smb-download").start();
    }
    private void syncNasCovers(){
        if(nasBusy)return;
        List<SmbRepository.RemoteBook> queue=new ArrayList<>();
        for(SmbRepository.RemoteBook item:nasBooks){
            if(!isHidden(remoteId(item))
                && cachedRemote(item)==null && cachedPreview(item)==null && queue.size()<12)
                queue.add(item);
        }
        if(queue.isEmpty()){
            Toast.makeText(this,"表紙は取得済みです",Toast.LENGTH_SHORT).show();return;
        }
        nasBusy=true;nasStatus="表紙の軽量取得を開始しています…";shelf();
        new Thread(()->{
            int ok=0,fail=0;
            Exception error=null;String filename="";
            for(SmbRepository.RemoteBook item:queue){
                reportTransfer("表紙・書誌情報だけ取得中: "+item.name+" ("+(ok+fail+1)+"/"+queue.size()+")");
                try{fetchPreview(settingsFor(item),item);ok++;}
                catch(Exception e){
                    fail++;
                    if(error==null){error=e;filename=item.name;}
                }
            }
            final int success=ok,errors=fail;
            final Exception first=error;final String firstName=filename;
            runOnUiThread(()->{
                nasBusy=false;
                nasStatus="軽量表紙取得: "+success+"冊成功・"+errors+"冊失敗";
                shelf();
                if(first!=null)showTransferError("表紙の軽量取得に失敗："+firstName,first);
            });
        },"smb-preview-sync").start();
    }


    private void showTailnetPanel(){
        tailnetPanelVisible=true;
        screen();
        LinearLayout header=layout(true);
        header.setPadding(d(12),d(12),d(12),d(6));
        header.addView(button("← 本棚",v->{tailnetPanelVisible=false;shelf();}));
        TextView title=text("内蔵Tailscale",22,true);
        title.setPadding(d(10),0,0,0);
        header.addView(title);
        root.addView(header);
        ScrollView scroll=new ScrollView(this);
        LinearLayout form=layout(false);
        form.setPadding(d(18),d(10),d(18),d(16));
        scroll.addView(form);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView message=text("外出先からNASのSMBへ接続します。AndroidのVPN権限は使用しません。初回はTailscaleアカウントへのログインが必要です。",14,false);
        form.addView(message);
        CheckBox enabled=new CheckBox(this);
        enabled.setText("SMB接続に内蔵Tailscaleを使用");
        enabled.setChecked(EmbeddedTailnet.enabled());
        form.addView(enabled);
        TextView hint=text("Tailscale側のNASアドレス（100.x.x.x または xxxx.ts.net）。LAN IPを使う場合はサブネットルーターが必要です。",12,false);
        hint.setPadding(0,d(10),0,0);
        form.addView(hint);
        EditText target=settingField("NASのTailscaleアドレス",EmbeddedTailnet.target(smbSettings.host),InputType.TYPE_CLASS_TEXT);
        form.addView(target);
        TextView state=text("保存済み経路: "+EmbeddedTailnet.routeDescription(smbSettings.host)+"\nTailscale状態: "+tailnetPhaseText(),13,false);
        state.setPadding(0,d(14),0,d(10));
        form.addView(state);
        enabled.setOnCheckedChangeListener((buttonView,isChecked)->
            state.setText("未保存の変更があります。保存後の通信経路を確認してください。\n"
                +"現在の保存済み経路: "+EmbeddedTailnet.routeDescription(smbSettings.host)));
        form.addView(button("設定を保存",v->saveTailnetUi(enabled,target,state)));
        form.addView(button("Tailscaleを起動 / 再確認",v->{
            if(!saveTailnetUi(enabled,target,state))return;
            state.setText("設定保存済み。Tailscaleを起動しています…");
            new Thread(()->{
                try{
                    String result=EmbeddedTailnet.start();
                    runOnUiThread(()->state.setText("起動結果: "+result+"\n"+tailnetPhaseText()));
                }catch(Throwable e){
                    runOnUiThread(()->state.setText("起動失敗: "+friendlyThrowable(e)));
                }
            },"tsnet-user-start").start();
        }));
        form.addView(button("接続状態を更新",v->state.setText(
            "保存済み経路: "+EmbeddedTailnet.routeDescription(smbSettings.host)
            +"\nTailscale状態: "+tailnetPhaseText())));
        form.addView(button("NASのSMB接続を診断（445番）",v->{
            if(!saveTailnetUi(enabled,target,state))return;
            if(!EmbeddedTailnet.enabled()){
                state.setText("現在は直接LAN接続です。チェックを有効にすると内蔵Tailscale経由の診断ができます。");
                return;
            }
            state.setText("Tailscale → "+EmbeddedTailnet.target(smbSettings.host)+" のTCP 445番を確認中…");
            final String host=EmbeddedTailnet.target(smbSettings.host);
            new Thread(()->{
                try{
                    String raw=EmbeddedTailnet.probeSmb(host);
                    JSONObject obj=new JSONObject(raw);
                    final String outcome=obj.optBoolean("ok",false)?"成功：":"失敗：";
                    final String detail=obj.optString("detail","応答がありません");
                    runOnUiThread(()->{
                        if(tailnetPanelVisible)state.setText(outcome+detail
                            +"\n保存済み経路: "+EmbeddedTailnet.routeDescription(smbSettings.host));
                    });
                }catch(Throwable e){
                    runOnUiThread(()->{
                        if(tailnetPanelVisible)state.setText("診断エラー: "+friendlyThrowable(e));
                    });
                }
            },"tsnet-smb-diagnostic").start();
        }));
        form.addView(button("ログインURLを開く",v->{
            String url=EmbeddedTailnet.loginUrl();
            if(!url.startsWith("https://login.tailscale.com/")){
                new AlertDialog.Builder(this).setTitle("ログインURLがありません")
                  .setMessage("起動ボタンを押してから「接続状態を更新」を押してください。\n"+
                     "状態: "+tailnetPhaseText())
                  .setPositiveButton("閉じる",null).show();
                return;
            }
            try{
                startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));
            }catch(ActivityNotFoundException e){
                new AlertDialog.Builder(this).setMessage(url).setPositiveButton("閉じる",null).show();
            }
        }));
        form.addView(button("Tailscaleを停止",v->{
            state.setText("停止しています…");
            new Thread(()->{
                try{
                    String result=EmbeddedTailnet.stop();
                    runOnUiThread(()->state.setText("停止結果: "+result));
                }catch(Throwable e){
                    runOnUiThread(()->state.setText("停止失敗: "+friendlyThrowable(e)));
                }
            },"tsnet-stop").start();
        }));
        TextView footnote=text("重要: Tailscale接続が確認できた後、本棚に戻り「本棚更新」を押してください。NASのSMBユーザー名・パスワードは今までどおり使用します。",13,false);
        footnote.setPadding(0,d(16),0,d(8));
        form.addView(footnote);
    }
    private boolean saveTailnetUi(CheckBox enabled,EditText target,TextView state){
        try{
            EmbeddedTailnet.setSettings(enabled.isChecked(),target.getText().toString().trim());
            nasBooks.clear();
            nasStatus="通信経路を保存しました。NASの本棚を更新してください。";
            state.setText("保存完了: "+EmbeddedTailnet.routeDescription(smbSettings.host)
                +"\nTailscale状態: "+tailnetPhaseText());
            return true;
        }catch(Throwable e){
            state.setText("保存に失敗しました: "+friendlyThrowable(e));
            return false;
        }
    }
    private String tailnetPhaseText(){
        try{
            JSONObject status=new JSONObject(EmbeddedTailnet.status());
            String phase=status.optString("phase","unknown");
            String address=status.optString("ip","");
            String error=status.optString("error","");
            String login=status.optString("login_url","");
            return phase+(address.isEmpty()?"":" / "+address)
                +(error.isEmpty()?"":" / "+error)
                +(login.isEmpty()?"":"\nログインURL: "+login);
        }catch(Exception e){return "状態の取得に失敗しました";}
    }
    private String friendlyThrowable(Throwable e){
        if(e.getMessage()!=null&&!e.getMessage().isEmpty())return e.getMessage();
        return e.getClass().getSimpleName();
    }

    private void pick(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/epub+zip","application/octet-stream","application/zip"});
        startActivityForResult(i,PICK);
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode!=PICK||resultCode!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();
        Toast.makeText(this,"EPUBを読み込み中…",Toast.LENGTH_SHORT).show();
        new Thread(()->{
            File dir=new File(library,UUID.randomUUID().toString());
            File tmp=new File(getCacheDir(),"import-"+dir.getName()+".epub");
            try {
                try(InputStream in=getContentResolver().openInputStream(uri);
                    OutputStream out=new FileOutputStream(tmp)){
                    if(in==null)throw new IOException("ファイルを開けません");
                    byte[] buffer=new byte[32768];int n;long count=0;
                    while((n=in.read(buffer))!=-1){
                        count+=n;if(count>150L*1024*1024)throw new IOException("150MBを超えるEPUBは未対応です");
                        out.write(buffer,0,n);
                    }
                }
                EpubParser.BookInfo book=EpubParser.extractAndParse(tmp,dir);
                JSONObject obj=new JSONObject();
                obj.put("id",dir.getName());obj.put("title",book.title);
                obj.put("author",book.author);obj.put("cover",book.cover);
                JSONArray pages=new JSONArray();for(String c:book.chapters)pages.put(c);
                obj.put("chapters",pages);
                try(OutputStream out=new FileOutputStream(new File(dir,"book.json"))){
                    out.write(obj.toString().getBytes(StandardCharsets.UTF_8));
                }
                runOnUiThread(()->{Toast.makeText(this,"追加しました: "+book.title,Toast.LENGTH_SHORT).show();shelf();});
            }catch(Exception ex){
                delete(dir);
                runOnUiThread(()->new AlertDialog.Builder(this).setTitle("取り込みに失敗しました")
                  .setMessage(ex.getMessage()==null?"EPUBの形式を確認してください":ex.getMessage())
                  .setPositiveButton("閉じる",null).show());
            }finally{tmp.delete();}
        },"epub-import").start();
    }

    private String currentBookId(){
        return openedFolder==null?"":openedFolder.getName();
    }
    private String scrollKey(int chapterNumber){
        return "scroll_"+currentBookId()+"_"+chapterNumber;
    }
    private int readerChapterCount(){
        JSONArray chapters=openedBook==null?null:openedBook.optJSONArray("chapters");
        return chapters==null?0:chapters.length();
    }
    private int currentMaxScroll(){
        if(reader==null)return 0;
        return Math.max(0,Math.round(reader.getContentHeight()*reader.getScale())-reader.getHeight());
    }
    private int currentScroll(){
        return reader==null?0:Math.max(0,reader.getScrollY());
    }
    private void rememberReaderPosition(){
        if(reader==null||openedBook==null||restoringScroll)return;
        getPreferences(0).edit()
            .putInt("chapter_"+currentBookId(),chapter)
            .putInt(scrollKey(chapter),currentScroll()).apply();
    }
    @Override protected void onPause(){
        rememberReaderPosition();
        super.onPause();
    }
    private void updateProgress(){
        if(readingProgress==null||openedBook==null)return;
        int count=readerChapterCount();
        if(count==0)return;
        int max=Math.max(1,currentMaxScroll());
        float share=Math.min(1f,(float)currentScroll()/max);
        int percent=Math.max(0,Math.min(100,Math.round(100f*(chapter+share)/count)));
        readingProgress.setText((chapter+1)+" / "+count+"  ·  "+percent+"%");
        if(bookmarkControl!=null)
            bookmarkControl.setText(hasNearbyBookmark()?"◆":"◇");
    }
    private String bookmarkKey(){
        return "bookmarks_"+currentBookId();
    }
    private JSONArray bookmarks(){
        if(openedBook==null)return new JSONArray();
        try{return new JSONArray(getSharedPreferences("reader_marks_v1",MODE_PRIVATE)
                    .getString(bookmarkKey(),"[]"));}
        catch(Exception e){return new JSONArray();}
    }
    private void storeBookmarks(JSONArray marks){
        getSharedPreferences("reader_marks_v1",MODE_PRIVATE).edit()
            .putString(bookmarkKey(),marks.toString()).apply();
    }
    private boolean hasNearbyBookmark(){
        JSONArray marks=bookmarks();
        for(int i=0;i<marks.length();i++){
            JSONObject entry=marks.optJSONObject(i);
            if(entry!=null&&entry.optInt("ch",-1)==chapter
                &&Math.abs(entry.optInt("y",-10000)-currentScroll())<d(130))
                return true;
        }
        return false;
    }
    private void toggleBookmark(){
        if(openedBook==null)return;
        JSONArray old=bookmarks(),updated=new JSONArray();
        boolean found=false;
        for(int i=0;i<old.length();i++){
            JSONObject mark=old.optJSONObject(i);
            if(mark==null)continue;
            if(mark.optInt("ch",-1)==chapter
                    &&Math.abs(mark.optInt("y",-10000)-currentScroll())<d(130)){
                found=true;
                continue;
            }
            updated.put(mark);
        }
        if(!found){
            if(updated.length()>=300){
                Toast.makeText(this,"しおりの登録上限は300件です",Toast.LENGTH_SHORT).show();return;
            }
            try{
                JSONObject mark=new JSONObject();
                mark.put("ch",chapter);
                mark.put("y",currentScroll());
                mark.put("date",new java.text.SimpleDateFormat("MM/dd HH:mm",Locale.JAPAN)
                    .format(new java.util.Date()));
                updated.put(mark);
            }catch(Exception e){return;}
        }
        storeBookmarks(updated);
        updateProgress();
        Toast.makeText(this,found?"しおりを解除しました":"この位置にしおりを付けました",
            Toast.LENGTH_SHORT).show();
    }
    private void listBookmarks(){
        JSONArray marks=bookmarks();
        if(marks.length()==0){
            new AlertDialog.Builder(this).setTitle("しおり")
                .setMessage("まだしおりはありません。\n読書画面右上の ◇ を押すと現在位置に追加できます。")
                .setPositiveButton("閉じる",null).show();
            return;
        }
        String[] labels=new String[marks.length()];
        for(int i=0;i<marks.length();i++){
            JSONObject mark=marks.optJSONObject(i);
            labels[i]=mark==null?"不明":("ページ / 章 "+(mark.optInt("ch",0)+1)
                +"  ·  "+mark.optString("date","")+"  ·  位置 "+mark.optInt("y",0));
        }
        AlertDialog dialog=new AlertDialog.Builder(this)
            .setTitle("しおり  ·  "+marks.length()+"件")
            .setItems(labels,(d,which)->{
                JSONObject mark=marks.optJSONObject(which);
                if(mark!=null)pageAt(mark.optInt("ch",0),mark.optInt("y",0));
            })
            .setPositiveButton("閉じる",null).create();
        dialog.setOnShowListener(v->dialog.getListView().setOnItemLongClickListener(
            (parent,view,which,id)->{
                JSONObject selected=marks.optJSONObject(which);
                if(selected==null)return true;
                new AlertDialog.Builder(this).setTitle("しおりを削除")
                    .setMessage(labels[which])
                    .setNegativeButton("キャンセル",null)
                    .setPositiveButton("削除",(d,w)->{
                        JSONArray keep=new JSONArray();
                        for(int i=0;i<marks.length();i++)if(i!=which)
                            keep.put(marks.optJSONObject(i));
                        storeBookmarks(keep);
                        dialog.dismiss();listBookmarks();
                        updateProgress();
                    }).show();
                return true;
            }));
        dialog.show();
    }
    private void tableOfContents(){
        JSONArray pages=openedBook==null?null:openedBook.optJSONArray("chapters");
        if(pages==null||pages.length()==0)return;
        String[] entries=new String[pages.length()];
        for(int i=0;i<pages.length();i++){
            String path=pages.optString(i,"");
            String name=new File(path).getName()
                .replaceFirst("(?i)\\.(xhtml|html|htm)$","");
            entries[i]=String.format(Locale.JAPAN,"%03d  %s",i+1,name);
        }
        new AlertDialog.Builder(this).setTitle("目次・ページ一覧")
            .setSingleChoiceItems(entries,Math.max(0,chapter),(dialog,which)->{
                dialog.dismiss();
                pageAt(which,0);
            })
            .setNegativeButton("閉じる",null).show();
    }
    private void searchInChapter(){
        if(reader==null)return;
        final EditText term=new EditText(this);
        term.setSingleLine(true);
        term.setText(activeSearch);
        term.setHint("検索する単語を入力");
        term.setPadding(d(20),d(16),d(20),d(16));
        new AlertDialog.Builder(this).setTitle("現在のページ・章を検索")
            .setView(term)
            .setNegativeButton("キャンセル",null)
            .setNeutralButton("検索を終了",(dialog,which)->{
                activeSearch="";
                if(reader!=null)reader.clearMatches();
            })
            .setPositiveButton("検索",(dialog,which)->{
                activeSearch=term.getText().toString().trim();
                if(!activeSearch.isEmpty()&&reader!=null){
                    reader.findAllAsync(activeSearch);
                    showSearchNavigation();
                }
            }).show();
    }
    private void showSearchNavigation(){
        if(reader==null||activeSearch.isEmpty())return;
        new AlertDialog.Builder(this).setTitle("検索中: "+activeSearch)
            .setMessage("現在のページ・章の一致箇所を移動します。")
            .setNeutralButton("前を検索",(dialog,which)->{
                if(reader!=null){reader.findNext(false);showSearchNavigation();}
            })
            .setPositiveButton("次を検索",(dialog,which)->{
                if(reader!=null){reader.findNext(true);showSearchNavigation();}
            })
            .setNegativeButton("終了",(dialog,which)->{
                activeSearch="";if(reader!=null)reader.clearMatches();
            }).show();
    }
    private String backgroundHtmlColor(){
        return readerTheme==2?"#151D22":readerTheme==1?"#F2E9D5":"#FAFBF8";
    }
    private String foregroundHtmlColor(){
        return readerTheme==2?"#E3E8E5":readerTheme==1?"#453A2D":"#24332D";
    }
    private int readingBackground(){
        return readerTheme==2?Color.rgb(21,29,34)
            :readerTheme==1?Color.rgb(242,233,213):Color.rgb(250,251,248);
    }
    private String readerStyle(){
        int margin=readerMargin==0?9:readerMargin==2?27:17;
        return "<style data-reader-style='1'>"
            +"html,body{background-color:"+backgroundHtmlColor()+" !important;"
            +"color:"+foregroundHtmlColor()+" !important;}"
            +"body{line-height:1.65 !important;margin:"+margin+"px !important;}"
            +"a{color:"+(readerTheme==2?"#9ECBBC":"#347F6C")+" !important;}"
            +"</style>";
    }
    private void loadThemedChapter(File f){
        if(reader==null)return;
        reader.setBackgroundColor(readingBackground());
        try{
            if(f.length()>0&&f.length()<6L*1024*1024){
                String html=new String(readFile(f,6*1024*1024),StandardCharsets.UTF_8);
                String style=readerStyle();
                if(html.matches("(?is).*<head(?:\\s[^>]*)?>.*"))
                    html=html.replaceFirst("(?i)<head(\\s[^>]*)?>","<head$1>"+style);
                else if(html.matches("(?is).*<html(?:\\s[^>]*)?>.*"))
                    html=html.replaceFirst("(?i)<html(\\s[^>]*)?>","<html$1><head>"+style+"</head>");
                else html="<html><head>"+style+"</head><body>"+html+"</body></html>";
                reader.loadDataWithBaseURL(Uri.fromFile(f).toString(),html,
                    "text/html","UTF-8",null);
                return;
            }
        }catch(Exception ignored){}
        reader.loadUrl(Uri.fromFile(f).toString());
    }
    private void open(JSONObject book){
        openedBook=book;
        openedFolder=new File(streamingRemote!=null?previews:library,
                              book.optString("id"));
        chapter=getPreferences(0).getInt("chapter_"+currentBookId(),0);
        readerTheme=getPreferences(0).getInt("reader_theme",0);
        readerMargin=getPreferences(0).getInt("reader_margin",1);
        screen();
        root.setBackgroundColor(readingBackground());
        LinearLayout top=layout(true);
        top.setPadding(d(12),d(8),d(12),d(9));
        top.setBackgroundColor(readingBackground());
        TextView back=actionIcon("‹");
        top.addView(back,new LinearLayout.LayoutParams(d(42),d(42)));
        back.setOnClickListener(v->{rememberReaderPosition();shelf();});
        LinearLayout titleGroup=layout(false);
        titleGroup.setPadding(d(10),0,0,0);
        TextView title=text(book.optString("title","本を読む"),15,true);
        if(readerTheme==2)title.setTextColor(Color.rgb(228,233,230));
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleGroup.addView(title);
        TextView modeLabel=subtle("READING MODE",10);
        if(readerTheme==2)modeLabel.setTextColor(Color.rgb(161,178,170));
        titleGroup.addView(modeLabel);
        top.addView(titleGroup,new LinearLayout.LayoutParams(0,-2,1));
        bookmarkControl=actionIcon("◇");
        bookmarkControl.setTextColor(readerTheme==2?Color.rgb(186,228,205):accent);
        bookmarkControl.setOnClickListener(v->toggleBookmark());
        bookmarkControl.setOnLongClickListener(v->{listBookmarks();return true;});
        top.addView(bookmarkControl,new LinearLayout.LayoutParams(d(42),d(42)));
        root.addView(top);
        reader=new WebView(this);
        reader.setBackgroundColor(readingBackground());
        reader.getSettings().setJavaScriptEnabled(false);
        reader.getSettings().setAllowFileAccess(true);
        reader.getSettings().setAllowContentAccess(false);
        reader.getSettings().setBlockNetworkLoads(true);
        reader.getSettings().setTextZoom(fontSize);
        final String prefix;
        try{prefix=openedFolder.getCanonicalPath()+File.separator;}
        catch(Exception e){shelf();return;}
        reader.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){
                Uri u=r.getUrl();
                if(!"file".equals(u.getScheme()))return true;
                try{return !new File(u.getPath()).getCanonicalPath().startsWith(prefix);}
                catch(Exception e){return true;}
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView v,WebResourceRequest r){
                String scheme=r.getUrl().getScheme();
                if("http".equals(scheme)||"https".equals(scheme))
                    return new WebResourceResponse("text/plain","UTF-8",
                        new ByteArrayInputStream(new byte[0]));
                return null;
            }
            @Override public void onPageFinished(WebView v,String url){
                super.onPageFinished(v,url);
                final int restore=pendingScroll;
                restoringScroll=true;
                v.postDelayed(()->{
                    if(reader!=v)return;
                    v.scrollTo(0,restore);
                    updateProgress();
                    v.postDelayed(()->{
                        if(reader!=v)return;
                        v.scrollTo(0,restore);
                        restoringScroll=false;
                        updateProgress();
                    },210);
                },170);
            }
        });
        reader.setOnScrollChangeListener((v,x,y,oldX,oldY)->{
            if(!restoringScroll){
                long now=SystemClock.elapsedRealtime();
                if(now-lastPositionSave>=900){
                    rememberReaderPosition();
                    lastPositionSave=now;
                }
            }
            updateProgress();
        });
        root.addView(reader,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout toolbar=layout(true);
        toolbar.setPadding(d(12),d(8),d(12),d(8));
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(readingBackground());
        TextView previous=actionIcon("‹");
        previous.setOnClickListener(v->pageAt(chapter-1,0));
        toolbar.addView(previous,new LinearLayout.LayoutParams(d(42),d(43)));
        readingProgress=subtle("1 / 1",12);
        readingProgress.setGravity(Gravity.CENTER);
        if(readerTheme==2)readingProgress.setTextColor(Color.rgb(188,203,195));
        toolbar.addView(readingProgress,new LinearLayout.LayoutParams(0,-2,1));
        TextView next=actionIcon("›");
        next.setOnClickListener(v->pageAt(chapter+1,0));
        toolbar.addView(next,new LinearLayout.LayoutParams(d(42),d(43)));
        root.addView(toolbar);
        LinearLayout actions=layout(true);
        actions.setPadding(d(12),0,d(12),d(14));
        actions.setBackgroundColor(readingBackground());
        Button toc=button("目次",v->tableOfContents());
        Button marks=button("しおり",v->listBookmarks());
        Button find=button("検索",v->searchInChapter());
        Button settings=button("Aa",v->showReadingAppearance());
        Button[] controls={toc,marks,find,settings};
        for(int i=0;i<controls.length;i++){
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,d(43),1);
            if(i>0)params.leftMargin=d(7);
            actions.addView(controls[i],params);
        }
        root.addView(actions);
        int count=readerChapterCount();
        if(count<=0){
            new AlertDialog.Builder(this).setTitle("本文を表示できません")
                .setMessage("このEPUBには読み取れる本文がありません。")
                .setPositiveButton("本棚へ戻る",(dialog,which)->shelf()).show();
            return;
        }
        chapter=Math.max(0,Math.min(chapter,count-1));
        pageAt(chapter,getPreferences(0).getInt(scrollKey(chapter),0));
    }
    private void showReadingAppearance(){
        final String[] themes={"ライト — やわらかい白","セピア — 紙のような色","ダーク — 夜間向け"};
        LinearLayout panel=layout(false);
        panel.setPadding(d(18),d(8),d(18),d(6));
        panel.addView(text("背景テーマ",14,true));
        final android.widget.RadioGroup themeChoices=new android.widget.RadioGroup(this);
        for(int i=0;i<themes.length;i++){
            android.widget.RadioButton radio=new android.widget.RadioButton(this);
            radio.setText(themes[i]);
            radio.setId(5000+i);
            radio.setTextSize(14);
            themeChoices.addView(radio);
        }
        themeChoices.check(5000+readerTheme);
        panel.addView(themeChoices);
        TextView fontDisplay=text("文字サイズ  "+fontSize+"%",14,true);
        fontDisplay.setPadding(0,d(16),0,d(6));
        panel.addView(fontDisplay);
        android.widget.SeekBar slider=new android.widget.SeekBar(this);
        slider.setMax(110);slider.setProgress(fontSize-70);
        slider.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(android.widget.SeekBar bar,int n,boolean user){
                fontDisplay.setText("文字サイズ  "+(70+n)+"%");
            }
            public void onStartTrackingTouch(android.widget.SeekBar bar){}
            public void onStopTrackingTouch(android.widget.SeekBar bar){}
        });
        panel.addView(slider);
        final String[] margins={"狭い","標準","広い"};
        android.widget.Spinner spacing=new android.widget.Spinner(this);
        spacing.setAdapter(new android.widget.ArrayAdapter<String>(this,
            android.R.layout.simple_spinner_dropdown_item,margins));
        spacing.setSelection(readerMargin);
        panel.addView(text("余白",14,true));
        panel.addView(spacing);
        new AlertDialog.Builder(this).setTitle("読書の表示設定").setView(panel)
            .setNegativeButton("キャンセル",null)
            .setPositiveButton("適用",(dialog,which)->{
                rememberReaderPosition();
                readerTheme=themeChoices.getCheckedRadioButtonId()-5000;
                readerTheme=Math.max(0,Math.min(2,readerTheme));
                fontSize=70+slider.getProgress();
                readerMargin=spacing.getSelectedItemPosition();
                getPreferences(0).edit()
                    .putInt("reader_theme",readerTheme)
                    .putInt("reader_margin",readerMargin)
                    .putInt("fontSize",fontSize).apply();
                open(openedBook);
            }).show();
    }
    private void zoom(int change){
        fontSize=Math.max(70,Math.min(180,fontSize+change));
        getPreferences(0).edit().putInt("fontSize",fontSize).apply();
        if(reader!=null)reader.getSettings().setTextZoom(fontSize);
    }
    private void page(int n){pageAt(n,0);}
    private void pageAt(int n,int y){
        if(reader==null||openedBook==null)return;
        JSONArray pages=openedBook.optJSONArray("chapters");
        if(pages==null||n<0||n>=pages.length())return;
        File f=new File(openedFolder,pages.optString(n));
        if(streamingRemote!=null && !f.isFile()){
            fetchStreamingChapter(n,y);
            return;
        }
        try{
            if(!f.getCanonicalPath().startsWith(openedFolder.getCanonicalPath()+File.separator))return;
            if(chapter!=n)rememberReaderPosition();
            chapter=n;
            pendingScroll=Math.max(0,y);
            restoringScroll=true;
            getPreferences(0).edit().putInt("chapter_"+currentBookId(),chapter).apply();
            if(!activeSearch.isEmpty()){
                activeSearch="";
                reader.clearMatches();
            }
            loadThemedChapter(f);
            updateProgress();
        }catch(Exception e){
            restoringScroll=false;
            Toast.makeText(this,"本文を開けません",Toast.LENGTH_SHORT).show();
        }
    }

    /** Page-level SMB range loading: receiving a chapter is not an offline download. */
    private void fetchStreamingChapter(int chapterIndex,int scrollOffset){
        if(streamingRemote==null || streamingSettings==null || openedBook==null)return;
        if(streamingBusy){
            Toast.makeText(this,"章を読み込み中です",Toast.LENGTH_SHORT).show();
            return;
        }
        JSONArray chapters=openedBook.optJSONArray("chapters");
        if(chapters==null||chapterIndex<0||chapterIndex>=chapters.length())return;
        final String relative=chapters.optString(chapterIndex,"");
        final SmbRepository.RemoteBook remote=streamingRemote;
        final SmbRepository.Settings cfg=streamingSettings.copy();
        final File target=openedFolder;
        final int ticket=++streamingGeneration;
        streamingBusy=true;
        if(readingProgress!=null)readingProgress.setText("NASから章を読み込み中…");
        new Thread(()->{
            try{
                SmbRepository.loadChapter(cfg,remote.path,target,relative);
                runOnUiThread(()->{
                    if(ticket!=streamingGeneration||streamingRemote!=remote
                            ||openedBook==null)return;
                    streamingBusy=false;
                    pageAt(chapterIndex,scrollOffset);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    if(ticket!=streamingGeneration||streamingRemote!=remote)return;
                    streamingBusy=false;
                    if(readingProgress!=null)readingProgress.setText("章の取得に失敗");
                    showTransferError("章のオンライン読み込みに失敗しました",e);
                });
            }
        },"smb-epub-chapter").start();
    }

    private static byte[] readFile(File file,int max)throws Exception{
        try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;
            while((n=in.read(buf))!=-1){if(out.size()+n>max)throw new IOException("Too large");out.write(buf,0,n);}
            return out.toByteArray();
        }
    }
    private static void delete(File file){
        if(file.isDirectory()){File[] list=file.listFiles();if(list!=null)for(File child:list)delete(child);}
        file.delete();
    }
    @Override public void onBackPressed(){
        if(tailnetPanelVisible){tailnetPanelVisible=false;shelf();}
        else if(folderBrowserVisible){
            if(folderBrowserPath.isEmpty())leaveBrowser();
            else loadFolder(parentFolder(folderBrowserPath));
        }else if(openedBook!=null){rememberReaderPosition();shelf();}
        else super.onBackPressed();
    }
}
