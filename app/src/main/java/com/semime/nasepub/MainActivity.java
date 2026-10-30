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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
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
            if(dir.getName().equals(o.optString("id"))) books.add(o);
        }catch(Exception ignored){}
        Collections.sort(books, Comparator.comparing(x->x.optString("title","")));
        return books;
    }
    private void shelf(){
        openedBook=null;openedFolder=null;screen();
        LinearLayout bar=layout(true);bar.setPadding(d(14),d(10),d(12),d(6));
        TextView title=text("My EPUB Library",22,true);
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        bar.addView(button("＋ EPUB追加",v->pick()));
        root.addView(bar);
        TextView note=text("ローカル読書版 v0.1  /  SMB・Tailscaleは次期対応",12,false);
        note.setPadding(d(16),0,d(8),d(12));root.addView(note);
        ScrollView scroll=new ScrollView(this);
        LinearLayout list=layout(false);list.setPadding(d(12),0,d(12),d(20));
        scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        List<JSONObject> books=readBooks();
        if(books.isEmpty()){
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
    @Override public void onBackPressed(){if(openedBook!=null)shelf();else super.onBackPressed();}
}
