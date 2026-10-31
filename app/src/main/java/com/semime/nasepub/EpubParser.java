package com.semime.nasepub;

import android.util.Xml;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.xmlpull.v1.XmlPullParser;

/**
 * EPUB 2/3 metadata extraction. Uses Android's supported XmlPullParser rather
 * than JVM-specific DocumentBuilderFactory XML feature flags.
 */
public final class EpubParser {
    private EpubParser() {}

    public static final class BookInfo {
        public String title = "名称未設定";
        public String author = "";
        public String cover = "";
        public final List<String> chapters = new ArrayList<>();
    }

    private static final long MAX_EXPANDED_BYTES = 350L * 1024 * 1024;
    private static final int MAX_ENTRIES = 8000;
    private static final long MAX_CONTAINER_BYTES = 1024 * 1024;
    private static final long MAX_OPF_BYTES = 10 * 1024 * 1024;
    private static final int MAX_XML_EVENTS = 250000;

    public static BookInfo extractAndParse(File sourceEpub, File destination) throws Exception {
        if (!destination.exists() && !destination.mkdirs())
            throw new IllegalStateException("展開先を作成できません");
        String destPrefix = destination.getCanonicalPath() + File.separator;
        byte[] buf = new byte[32768];
        long expanded = 0;
        int entryCount = 0;
        try (ZipInputStream zin = new ZipInputStream(new FileInputStream(sourceEpub))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (++entryCount > MAX_ENTRIES)
                    throw new IllegalArgumentException("EPUBのファイル数が多すぎます");
                File out = new File(destination, entry.getName());
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(destPrefix))
                    throw new IllegalArgumentException("不正なEPUB内パス");
                if (entry.isDirectory()) {
                    if (!out.exists() && !out.mkdirs())
                        throw new IllegalStateException("フォルダを作成できません");
                } else {
                    File parent = out.getParentFile();
                    if (!parent.exists() && !parent.mkdirs())
                        throw new IllegalStateException("フォルダを作成できません");
                    try (OutputStream fout = new FileOutputStream(out)) {
                        int count;
                        while ((count = zin.read(buf)) != -1) {
                            expanded += count;
                            if (expanded > MAX_EXPANDED_BYTES)
                                throw new IllegalArgumentException("EPUBが大きすぎます");
                            fout.write(buf, 0, count);
                        }
                    }
                }
                zin.closeEntry();
            }
        }
        if (entryCount == 0)
            throw new IllegalArgumentException("EPUBのZIPデータを読み取れません");
        return parseExtracted(destination);
    }

    public static BookInfo parseExtracted(File destination) throws Exception {
        return parseExtracted(destination,false);
    }
    /** The metadata-only scan does not require chapter HTML files to be present. */
    public static BookInfo parsePreview(File destination) throws Exception {
        return parseExtracted(destination,true);
    }
    private static BookInfo parseExtracted(File destination,boolean preview) throws Exception {
        String opfPath = locatePackage(new File(destination, "META-INF/container.xml"));
        File opf = resolveInside(destination, destination, opfPath);
        BookInfo info = new BookInfo();
        final class Item {
            String href, type, props;
            Item(String h, String t, String p) { href=h; type=t; props=p; }
        }
        Map<String, Item> items = new HashMap<>();
        List<String> spineIds = new ArrayList<>();
        String coverId = "";
        int metadataDepth = -1, manifestDepth = -1, spineDepth = -1;
        int textDepth = -1;
        String textKind = null;
        StringBuilder value = new StringBuilder();

        requireXml(opf, MAX_OPF_BYTES);
        try (InputStream stream = new FileInputStream(opf)) {
            XmlPullParser p = Xml.newPullParser();
            p.setInput(stream, null);
            int count=0;
            while (true) {
                if (++count > MAX_XML_EVENTS)
                    throw new IllegalArgumentException("EPUBの書誌情報が複雑すぎます");
                int type = p.nextToken();
                if (type == XmlPullParser.END_DOCUMENT) break;
                rejectUnsafeXml(type);
                if (type == XmlPullParser.START_TAG) {
                    String name = p.getName();
                    int depth = p.getDepth();
                    if ("metadata".equals(name)) metadataDepth=depth;
                    if ("manifest".equals(name)) manifestDepth=depth;
                    if ("spine".equals(name)) spineDepth=depth;

                    if (metadataDepth > 0 && depth > metadataDepth) {
                        if ("meta".equals(name) && "cover".equalsIgnoreCase(attr(p,"name")))
                            coverId=attr(p,"content");
                        if (textKind==null &&
                            (("title".equals(name) && "名称未設定".equals(info.title))
                            || ("creator".equals(name) && info.author.isEmpty()))) {
                            textKind=name;
                            textDepth=depth;
                            value.setLength(0);
                        }
                    }
                    if ("item".equals(name) && manifestDepth>0 && depth>manifestDepth) {
                        String id=attr(p,"id");
                        String href=attr(p,"href");
                        String media=attr(p,"media-type");
                        String props=attr(p,"properties");
                        if (!id.isEmpty() && !href.isEmpty()) {
                            Item it=new Item(href,media,props);
                            items.put(id,it);
                            for(String property : props.split("\\s+")) {
                                if ("cover-image".equals(property)) coverId=id;
                            }
                        }
                    }
                    if ("itemref".equals(name) && spineDepth>0 && depth>spineDepth) {
                        String id=attr(p,"idref");
                        if(!id.isEmpty())spineIds.add(id);
                    }
                } else if (type == XmlPullParser.TEXT ||
                           type == XmlPullParser.CDSECT ||
                           type == XmlPullParser.ENTITY_REF) {
                    if (textKind != null) {
                        if (type == XmlPullParser.ENTITY_REF) {
                            value.append(predefinedEntity(p.getName()));
                        } else if (p.getText()!=null) {
                            value.append(p.getText());
                        }
                    } else if(type==XmlPullParser.ENTITY_REF) {
                        predefinedEntity(p.getName()); // Only XML built-in entities allowed.
                    }
                } else if(type==XmlPullParser.END_TAG) {
                    if(textKind!=null && p.getDepth()==textDepth) {
                        String cleaned=value.toString().trim();
                        if(!cleaned.isEmpty()) {
                            if("title".equals(textKind)) info.title=cleaned;
                            else if("creator".equals(textKind)) info.author=cleaned;
                        }
                        textKind=null; textDepth=-1;
                    }
                    if(metadataDepth==p.getDepth() && "metadata".equals(p.getName()))
                        metadataDepth=-1;
                    if(manifestDepth==p.getDepth() && "manifest".equals(p.getName()))
                        manifestDepth=-1;
                    if(spineDepth==p.getDepth() && "spine".equals(p.getName()))
                        spineDepth=-1;
                }
            }
        }

        if(items.isEmpty())throw new IllegalArgumentException("EPUBのmanifestがありません");
        File contentRoot = opf.getParentFile();

        Item cover = items.get(coverId);
        if (cover == null) {
            for (Map.Entry<String, Item> pair : items.entrySet()) {
                Item it=pair.getValue();
                if (it.type.startsWith("image/") &&
                    it.href.toLowerCase(Locale.ROOT).contains("cover")) {
                    cover=it;break;
                }
            }
        }
        if(cover!=null && !"image/svg+xml".equals(cover.type)){
            try{
                File image=resolveInside(destination,contentRoot,cover.href);
                if(preview || image.isFile())info.cover=relativize(destination,image);
            }catch(IllegalArgumentException ignored){}
        }
        for(String id:spineIds){
            Item chapter=items.get(id);
            if(chapter==null || !("application/xhtml+xml".equals(chapter.type) ||
                                  "text/html".equals(chapter.type)))continue;
            File f=resolveInside(destination,contentRoot,chapter.href);
            if(preview || f.isFile())info.chapters.add(relativize(destination,f));
        }
        if(info.chapters.isEmpty())
            throw new IllegalArgumentException("EPUBに表示できる本文がありません");
        return info;
    }

    public static String locatePackage(File container) throws Exception {
        requireXml(container,MAX_CONTAINER_BYTES);
        try(InputStream stream=new FileInputStream(container)){
            XmlPullParser p=Xml.newPullParser();
            p.setInput(stream,null);
            int count=0;
            while(true){
                if(++count>MAX_XML_EVENTS)
                    throw new IllegalArgumentException("EPUBのcontainer.xmlが複雑すぎます");
                int type=p.nextToken();
                if(type==XmlPullParser.END_DOCUMENT)break;
                rejectUnsafeXml(type);
                if(type==XmlPullParser.START_TAG && "rootfile".equals(p.getName())){
                    String path=attr(p,"full-path");
                    if(!path.isEmpty())return path;
                }
                if(type==XmlPullParser.ENTITY_REF)predefinedEntity(p.getName());
            }
        }
        throw new IllegalArgumentException("EPUBのcontainer.xmlにrootfileがありません");
    }

    private static void requireXml(File file,long maxBytes){
        if(!file.isFile())
            throw new IllegalArgumentException("必要なEPUBファイルがありません: "+file.getName());
        if(file.length()>maxBytes)
            throw new IllegalArgumentException("EPUBのXMLデータが大きすぎます");
    }
    private static String attr(XmlPullParser p,String name){
        String v=p.getAttributeValue(null,name);
        return v==null?"":v;
    }
    private static void rejectUnsafeXml(int type){
        if(type==XmlPullParser.DOCDECL)
            throw new IllegalArgumentException("外部DTDを含むEPUBは読み込めません");
    }
    private static String predefinedEntity(String name){
        if("amp".equals(name))return "&";
        if("lt".equals(name))return "<";
        if("gt".equals(name))return ">";
        if("quot".equals(name))return "\"";
        if("apos".equals(name))return "'";
        throw new IllegalArgumentException("不明なXMLエンティティを含むEPUBは読み込めません");
    }

    private static File resolveInside(File root,File baseDir,String href) throws Exception {
        String clean=href.split("[#?]",2)[0];
        String decoded=URLDecoder.decode(clean.replace("+","%2B"),"UTF-8");
        File file=new File(baseDir,decoded).getCanonicalFile();
        String allowed=root.getCanonicalPath()+File.separator;
        if(!file.getPath().startsWith(allowed))
            throw new IllegalArgumentException("EPUB内パスが不正です");
        return file;
    }
    private static String relativize(File root,File child)throws Exception{
        String prefix=root.getCanonicalPath()+File.separator;
        String path=child.getCanonicalPath();
        if(!path.startsWith(prefix))throw new IllegalArgumentException("EPUB内パスが不正です");
        return path.substring(prefix.length()).replace(File.separatorChar,'/');
    }
}
