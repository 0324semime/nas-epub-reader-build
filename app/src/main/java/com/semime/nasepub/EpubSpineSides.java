package com.semime.nasepub;

import android.util.Xml;
import org.xmlpull.v1.XmlPullParser;
import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/** Read optional EPUB 2/3 explicit spread placements from OPF spine. */
public final class EpubSpineSides {
    private EpubSpineSides(){}
    public static final int AUTO=0, LEFT=1, RIGHT=2, CENTER=3;

    /**
     * Key is canonical path of the extracted spine XHTML.
     * Missing or malformed placement metadata leaves the reader usable.
     */
    public static Map<String,Integer> read(File root){
        Map<String,Integer> sides=new HashMap<>();
        try{
            File packageFile=new File(root,
                EpubParser.locatePackage(new File(root,"META-INF/container.xml"))).getCanonicalFile();
            String rootPrefix=root.getCanonicalPath()+File.separator;
            if(!packageFile.getPath().startsWith(rootPrefix)||
                !packageFile.isFile()||packageFile.length()>10*1024*1024)return sides;
            Map<String,String> entries=new HashMap<>();
            try(FileInputStream in=new FileInputStream(packageFile)){
                XmlPullParser xml=Xml.newPullParser();
                xml.setInput(in,null);
                int count=0,manifest=-1,spine=-1;
                while(true){
                    if(++count>150000)break;
                    int event=xml.nextToken();
                    if(event==XmlPullParser.END_DOCUMENT)break;
                    if(event==XmlPullParser.DOCDECL)break;
                    if(event!=XmlPullParser.START_TAG&&event!=XmlPullParser.END_TAG)continue;
                    String name=xml.getName();
                    int depth=xml.getDepth();
                    if(event==XmlPullParser.END_TAG){
                        if(depth==manifest&&"manifest".equals(name))manifest=-1;
                        if(depth==spine&&"spine".equals(name))spine=-1;
                        continue;
                    }
                    if("manifest".equals(name)){manifest=depth;continue;}
                    if("spine".equals(name)){spine=depth;continue;}
                    if(manifest>0&&depth>manifest&&"item".equals(name)){
                        String id=attr(xml,"id"),href=attr(xml,"href");
                        if(!id.isEmpty()&&!href.isEmpty())entries.put(id,href);
                    }
                    if(spine>0&&depth>spine&&"itemref".equals(name)){
                        String href=entries.get(attr(xml,"idref"));
                        if(href==null)continue;
                        int side=side(attr(xml,"properties"));
                        if(side==AUTO)continue;
                        String raw=href.split("[#?]",2)[0];
                        String decoded=java.net.URLDecoder.decode(
                            raw.replace("+","%2B"),"UTF-8");
                        File page=new File(packageFile.getParentFile(),decoded).getCanonicalFile();
                        if(page.getPath().startsWith(rootPrefix))
                            sides.put(page.getPath(),side);
                    }
                }
            }
        }catch(Exception ignored){
            // Fall back to synthetic spreads without losing the book.
        }
        return sides;
    }
    public static int side(String properties){
        for(String value:properties.split("\\s+")){
            if("page-spread-left".equals(value)||"rendition:page-spread-left".equals(value))return LEFT;
            if("page-spread-right".equals(value)||"rendition:page-spread-right".equals(value))return RIGHT;
            if("page-spread-center".equals(value)||"rendition:page-spread-center".equals(value))return CENTER;
        }
        return AUTO;
    }
    private static String attr(XmlPullParser xml,String key){
        String value=xml.getAttributeValue(null,key);
        return value==null?"":value;
    }
}
