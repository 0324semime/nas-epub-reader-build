package com.semime.nasepub;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.Html;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;

/** Detect fixed-layout/image-based EPUB spine items without network access. */
public final class FixedEpubPage {
    private FixedEpubPage(){}
    private static final Pattern IMAGE_TAG=Pattern.compile(
        "(?is)<(?:[a-z0-9_-]+:)?(?:img|image)\\b([^>]{0,16000})>");
    private static final Pattern HREF=Pattern.compile(
        "(?is)\\b(?:src|(?:xlink:)?href)\\s*=\\s*([\"'])(.*?)\\1");
    private static final Pattern CSS_URL=Pattern.compile(
        "(?is)\\b(?:background|background-image)\\s*:\\s*url\\s*\\(\\s*['\"]?([^)'\"\\s]+)");
    private static final Pattern HEAD=Pattern.compile("(?is)<head\\b[^>]*>.*?</head\\s*>");
    private static final Pattern IMAGE_SUFFIX=Pattern.compile(
        "(?i).*\\.(?:jpg|jpeg|png|webp|bmp|gif)(?:[?#].*)?$");
    public static final class Page {
        public final File image;
        public final File xhtml;
        public final String title;
        public final int textChars;
        Page(File image,File xhtml,String title,int textChars){
            this.image=image;this.xhtml=xhtml;this.title=title;this.textChars=textChars;
        }
        public boolean imagePage(){return image!=null&&image.isFile();}
    }
    private static String read(File file,int max)throws Exception{
        if(!file.isFile()||file.length()>max)throw new IllegalArgumentException("EPUBのページがありません");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(FileInputStream in=new FileInputStream(file)){
            byte[] buf=new byte[8192];int n;
            while((n=in.read(buf))!=-1){
                if(out.size()+n>max)throw new IllegalArgumentException("ページHTMLが大きすぎます");
                out.write(buf,0,n);
            }
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private static File inside(File root,File relative,String href){
        try{
            String clean=href.trim().replace("&amp;","&")
                .replace("&quot;","\"").replace("&#39;","'");
            if(clean.startsWith("/")||clean.startsWith("#")||
                clean.matches("(?i)^[a-z][a-z0-9+.-]*:.*"))return null;
            String path=clean.split("[?#]",2)[0];
            if(!IMAGE_SUFFIX.matcher(path).matches())return null;
            path=URLDecoder.decode(path.replace("+","%2B"),"UTF-8");
            File f=new File(relative.getParentFile(),path).getCanonicalFile();
            String rootPath=root.getCanonicalPath()+File.separator;
            return f.getPath().startsWith(rootPath)&&f.isFile()?f:null;
        }catch(Exception ignored){return null;}
    }
    public static Page inspect(File root,String chapter)throws Exception{
        File xhtml=new File(root,chapter).getCanonicalFile();
        if(!xhtml.getPath().startsWith(root.getCanonicalPath()+File.separator))
            throw new IllegalArgumentException("EPUBの本文パスが不正です");
        String html=read(xhtml,4*1024*1024);
        String body=HEAD.matcher(html).replaceAll("");
        String cleaned=Html.fromHtml(body,Html.FROM_HTML_MODE_COMPACT)
            .toString().replace("\u00a0"," ").replaceAll("\\s+"," ").trim();
        File image=null;
        Matcher tag=IMAGE_TAG.matcher(body);
        while(tag.find() && image==null){
            Matcher href=HREF.matcher(tag.group(1));
            while(href.find() && image==null)image=inside(root,xhtml,href.group(2));
        }
        if(image==null){
            Matcher css=CSS_URL.matcher(body);
            while(css.find()&&image==null)image=inside(root,xhtml,css.group(1));
        }
        return new Page(image,xhtml,cleaned,cleaned.length());
    }
    public static boolean isFixedBook(File root,JSONArray chapters){
        if(root==null||chapters==null)return false;
        int checked=0,imageOnly=0;
        for(int i=0;i<chapters.length()&&checked<28;i++){
            try{
                Page p=inspect(root,chapters.optString(i,""));
                checked++;
                if(p.imagePage()&&p.textChars<150)imageOnly++;
            }catch(Exception ignored){}
            if(imageOnly>=6 && imageOnly*2>=checked)return true;
        }
        return imageOnly>=4&&imageOnly*2>=checked;
    }
    public static Bitmap decode(File image,int width,int height){
        if(image==null || !image.isFile())return null;
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(image.getAbsolutePath(),options);
        if(options.outWidth<=0||options.outHeight<=0)return null;
        int limitW=Math.max(600,Math.max(width,300)*2);
        int limitH=Math.max(600,Math.max(height,300)*2);
        int sample=1;
        while(options.outWidth/sample>limitW*2&&options.outHeight/sample>limitH*2)
            sample*=2;
        options.inJustDecodeBounds=false;
        options.inSampleSize=sample;
        options.inPreferredConfig=Bitmap.Config.RGB_565;
        try{return BitmapFactory.decodeFile(image.getAbsolutePath(),options);}
        catch(OutOfMemoryError error){return null;}
    }
}
