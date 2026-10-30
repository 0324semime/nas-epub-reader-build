package com.semime.nasepub;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
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
    private final int background = Color.rgb(248,247,244);
    private final int ink = Color.rgb(32,45,56);
    private File library, openedFolder;
    private JSONObject openedBook;
    private LinearLayout root;
    private WebView reader;
    private int chapter = 0;
    private int fontSize = 100;
    private SecurePrefs smbPreferences;
    private SmbRepository.Settings smbSettings;
    private final List<SmbRepository.RemoteBook> nasBooks = new ArrayList<>();
    private String nasStatus = "NASは未接続です";
    private volatile boolean nasBusy = false;
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
        library = new File(getFilesDir(), "library");
        if (!library.exists()) library.mkdirs();
        fontSize = getPreferences(0).getInt("fontSize",100);
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
        v.setText(title);v.setTextSize(13);v.setAllCaps(false);v.setOnClickListener(fn);
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
            if(!source.isEmpty()){
                if(!smbPreferences.hasFolderSelection())continue;
                if(!dir.getName().equals(remoteId(source)))continue;
                String selected=smbSettings.folder;
                if(!selected.isEmpty()&&!source.toLowerCase(Locale.ROOT)
                    .startsWith((selected+"\\").toLowerCase(Locale.ROOT)))continue;
            }
            books.add(o);
        }catch(Exception ignored){}
        Collections.sort(books, Comparator.comparing(x->x.optString("title","")));
        return books;
    }
    private void shelf(){
        folderBrowserVisible=false;
        ++folderBrowserRevision;
        openedBook=null;openedFolder=null;screen();
        LinearLayout bar=layout(true);bar.setPadding(d(14),d(10),d(12),d(6));
        TextView title=text("My EPUB Library",22,true);
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(button("＋ EPUB追加",v->pick()));
        root.addView(bar);
        TextView note=text("v0.3  /  NASフォルダ選択  /  Tailscaleは次期対応",12,false);
        note.setPadding(d(16),0,d(8),d(6));root.addView(note);
        LinearLayout smbBar=layout(true);
        smbBar.setPadding(d(8),0,d(8),d(4));
        smbBar.addView(button("接続設定",v->showSmbSettings()));
        smbBar.addView(button("フォルダ選択",v->startFolderBrowser()));
        smbBar.addView(button("本棚更新",v->refreshNas()));
        root.addView(smbBar);
        TextView smbState=text(nasStatus,12,false);
        smbState.setPadding(d(16),0,d(12),d(6));
        root.addView(smbState);
        TextView selected=text(smbPreferences.hasFolderSelection()
          ? "📁 読み取り先: " + smbSettings.share
            + (smbSettings.folder.isEmpty() ? " （共有の直下）" : "\\" + smbSettings.folder)
          : "📁 読み取りフォルダが未設定です。まず「フォルダ選択」を押してください。",12,false);
        selected.setPadding(d(16),0,d(12),d(10));
        root.addView(selected);
        ScrollView scroll=new ScrollView(this);
        LinearLayout list=layout(false);list.setPadding(d(12),0,d(12),d(20));
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        renderRemoteBooks(list);
        List<JSONObject> books=readBooks();
        if(books.isEmpty() && nasBooks.isEmpty()){
            TextView empty=text("まだ本がありません。\n「＋ EPUB追加」からEPUBを選択してください。",16,false);
            empty.setPadding(d(20),d(80),d(20),0);
            empty.setGravity(Gravity.CENTER);list.addView(empty);
        }
        for(int start=0;start<books.size();start+=2){
            LinearLayout row=layout(true);row.setGravity(Gravity.TOP);list.addView(row);
            for(int j=start;j<Math.min(start+2,books.size());j++){
                final JSONObject book=books.get(j);
                LinearLayout card=layout(false);card.setPadding(d(8),d(8),d(8),d(16));
                row.addView(card,new LinearLayout.LayoutParams(0,-2,1));
                ImageView image=new ImageView(this);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setBackgroundColor(Color.rgb(225,229,231));
                File cover=new File(new File(library,book.optString("id")),book.optString("cover"));
                if(!book.optString("cover").isEmpty() && cover.isFile()){
                    BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=2;
                    Bitmap bmp=BitmapFactory.decodeFile(cover.getAbsolutePath(),options);
                    if(bmp!=null)image.setImageBitmap(bmp);
                }
                card.addView(image,new LinearLayout.LayoutParams(-1,d(190)));
                TextView name=text(book.optString("title","名称未設定"),15,true);
                name.setMaxLines(2);card.addView(name);
                TextView author=text(book.optString("author",""),12,false);author.setMaxLines(1);card.addView(author);
                card.setOnClickListener(v->open(book));
                card.setOnLongClickListener(v->{
                    new AlertDialog.Builder(this).setTitle("本を削除しますか？")
                       .setMessage(book.optString("title",""))
                       .setNegativeButton("キャンセル",null)
                       .setPositiveButton("削除",(dia,w)->{
                           File f=new File(library,book.optString("id"));
                           try{if(f.getCanonicalFile().getParentFile().equals(library.getCanonicalFile()))delete(f);}catch(Exception ignored){}
                           shelf();
                       }).show();
                    return true;
                });
            }
        }
    }

    private String remoteId(String filePath){
        try{
            MessageDigest md=MessageDigest.getInstance("SHA-256");
            String key=smbSettings.host+"|"+smbSettings.share+"|"+filePath;
            byte[] bytes=md.digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder b=new StringBuilder("nas-");
            for(int i=0;i<12;i++)b.append(String.format(Locale.ROOT,"%02x",bytes[i]&255));
            return b.toString();
        }catch(Exception e){return "nas-fallback";}
    }
    private JSONObject cachedRemote(SmbRepository.RemoteBook remote){
        try {
            String id=remoteId(remote.path);
            File metadata=new File(new File(library,id),"book.json");
            if(!metadata.isFile())return null;
            JSONObject obj=new JSONObject(new String(readFile(metadata,500000),StandardCharsets.UTF_8));
            return id.equals(obj.optString("id"))?obj:null;
        }catch(Exception e){return null;}
    }
    private void renderRemoteBooks(LinearLayout list){
        if(nasBooks.isEmpty())return;
        int missing=0;
        for(SmbRepository.RemoteBook item:nasBooks)if(cachedRemote(item)==null)missing++;
        TextView section=text("NAS の本（未取得 "+missing+"冊）",17,true);
        section.setPadding(d(6),d(8),d(6),d(3));list.addView(section);
        TextView help=text("表紙を一括取得するか、題名をタップして取り込んでください。",12,false);
        list.addView(help);
        list.addView(button("表紙を取得（最大12冊ずつ）",v->syncNasCovers()));
        int shown=0;
        for(SmbRepository.RemoteBook item:nasBooks){
            if(cachedRemote(item)!=null)continue;
            if(shown++>=120){
                list.addView(text("ほかのファイルもあります。検索範囲を絞ってください。",12,false));
                break;
            }
            TextView entry=text("  ↓  "+item.name,14,false);
            entry.setPadding(d(6),d(11),d(6),d(11));
            entry.setOnClickListener(v->fetchAndOpen(item));
            list.addView(entry);
        }
        if(shown>0){
            TextView heading=text("読み込んだ本",17,true);
            heading.setPadding(d(6),d(14),d(4),d(6));
            list.addView(heading);
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
                nasBooks.clear();
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
            nasBooks.clear();
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
        if(!smbPreferences.hasFolderSelection()){
            startFolderBrowser();
            return;
        }
        SmbRepository.Settings cfg=smbSettings.copy();
        try{cfg.validate();}catch(Exception e){showSmbSettings();return;}
        nasBusy=true;nasStatus="NASへ接続しています…";shelf();
        new Thread(()->{
            try{
                List<SmbRepository.RemoteBook> results=SmbRepository.list(cfg);
                results.sort(Comparator.comparing(x->x.name.toLowerCase(Locale.ROOT)));
                runOnUiThread(()->{
                    nasBooks.clear();nasBooks.addAll(results);
                    nasStatus="NAS接続成功: EPUB "+results.size()+"冊";
                    nasBusy=false;shelf();
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    nasStatus="接続失敗: "+friendly(e);nasBusy=false;shelf();
                });
            }
        },"smb-list").start();
    }
    private String friendly(Exception e){
        String message=e.getMessage();
        if(message==null||message.trim().isEmpty())message=e.getClass().getSimpleName();
        if(message.length()>170)message=message.substring(0,170);
        return message;
    }
    private JSONObject fetchRemote(SmbRepository.Settings cfg,SmbRepository.RemoteBook item) throws Exception {
        String id=remoteId(item.path);
        JSONObject cached=cachedRemote(item);
        if(cached!=null)return cached;
        File dir=new File(library,id);
        File temp=new File(getCacheDir(),"nas-"+id+".epub");
        try{
            SmbRepository.download(cfg,item.path,temp,150L*1024*1024);
            EpubParser.BookInfo info=EpubParser.extractAndParse(temp,dir);
            JSONObject obj=new JSONObject();
            obj.put("id",id);obj.put("title",info.title);
            obj.put("author",info.author);obj.put("cover",info.cover);
            obj.put("source",item.path);
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
    private void fetchAndOpen(SmbRepository.RemoteBook item){
        if(nasBusy)return;
        SmbRepository.Settings cfg=smbSettings.copy();
        nasBusy=true;nasStatus="EPUBを取得中: "+item.name;shelf();
        new Thread(()->{
            try{
                JSONObject book=fetchRemote(cfg,item);
                runOnUiThread(()->{
                    nasBusy=false;nasStatus="読書できます: "+item.name;
                    open(book);
                });
            }catch(Exception e){
                runOnUiThread(()->{
                    nasBusy=false;nasStatus="取得失敗: "+friendly(e);
                    shelf();
                    new AlertDialog.Builder(this).setTitle("EPUB取得失敗")
                        .setMessage(friendly(e)).setPositiveButton("閉じる",null).show();
                });
            }
        },"smb-download").start();
    }
    private void syncNasCovers(){
        if(nasBusy)return;
        SmbRepository.Settings cfg=smbSettings.copy();
        List<SmbRepository.RemoteBook> queue=new ArrayList<>();
        for(SmbRepository.RemoteBook item:nasBooks){
            if(cachedRemote(item)==null && queue.size()<12)queue.add(item);
        }
        if(queue.isEmpty()){Toast.makeText(this,"すべて取り込み済みです",Toast.LENGTH_SHORT).show();return;}
        nasBusy=true;nasStatus="EPUBの表紙を取得中…";shelf();
        new Thread(()->{
            int ok=0,fail=0;
            for(SmbRepository.RemoteBook item:queue){
                try{fetchRemote(cfg,item);ok++;}
                catch(Exception e){fail++;}
            }
            final int success=ok,errors=fail;
            runOnUiThread(()->{
                nasBusy=false;
                nasStatus="表紙取得: "+success+"冊成功・"+errors+"冊失敗";
                shelf();
            });
        },"smb-cover-sync").start();
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
    private void open(JSONObject book){
        openedBook=book;openedFolder=new File(library,book.optString("id"));
        chapter=getPreferences(0).getInt("chapter_"+openedFolder.getName(),0);
        screen();
        LinearLayout top=layout(true);top.setPadding(d(8),d(4),d(8),d(4));
        top.addView(button("← 本棚",v->shelf()));
        TextView title=text(book.optString("title",""),14,true);title.setSingleLine(true);
        top.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        root.addView(top);
        reader=new WebView(this);
        reader.setBackgroundColor(Color.WHITE);
        reader.getSettings().setJavaScriptEnabled(false);
        reader.getSettings().setAllowFileAccess(true);
        reader.getSettings().setAllowContentAccess(false);
        reader.getSettings().setBlockNetworkLoads(true);
        reader.getSettings().setTextZoom(fontSize);
        final String prefix;
        try{prefix=openedFolder.getCanonicalPath()+File.separator;}catch(Exception e){shelf();return;}
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
                    return new WebResourceResponse("text/plain","UTF-8",new ByteArrayInputStream(new byte[0]));
                return null;
            }
        });
        root.addView(reader,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout buttons=layout(true);buttons.setGravity(Gravity.CENTER);
        buttons.addView(button("前の章",v->page(chapter-1)));
        buttons.addView(button("A−",v->zoom(-10)));
        buttons.addView(button("A＋",v->zoom(10)));
        buttons.addView(button("次の章",v->page(chapter+1)));
        root.addView(buttons,new LinearLayout.LayoutParams(-1,d(62)));
        page(chapter);
    }
    private void zoom(int change){
        fontSize=Math.max(70,Math.min(180,fontSize+change));
        getPreferences(0).edit().putInt("fontSize",fontSize).apply();
        if(reader!=null)reader.getSettings().setTextZoom(fontSize);
    }
    private void page(int n){
        if(reader==null||openedBook==null)return;
        JSONArray pages=openedBook.optJSONArray("chapters");
        if(pages==null||n<0||n>=pages.length())return;
        File f=new File(openedFolder,pages.optString(n));
        try{
            if(!f.getCanonicalPath().startsWith(openedFolder.getCanonicalPath()+File.separator))return;
            chapter=n;
            getPreferences(0).edit().putInt("chapter_"+openedFolder.getName(),chapter).apply();
            reader.loadUrl(Uri.fromFile(f).toString());
        }catch(Exception e){Toast.makeText(this,"章を開けません",Toast.LENGTH_SHORT).show();}
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
        if(folderBrowserVisible){
            if(folderBrowserPath.isEmpty())leaveBrowser();
            else loadFolder(parentFolder(folderBrowserPath));
        }else if(openedBook!=null)shelf();
        else super.onBackPressed();
    }
}
