package com.semime.nasepub;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.net.URLDecoder;
import java.util.*;
import java.util.zip.*;

public final class RemoteZipPreview {
  private RemoteZipPreview(){}
  public interface Reader {
    long size();
    void read(long offset, byte[] target, int off, int count) throws IOException;
  }
  private static final class Item {
    String name;long offset,compressed,plain,crc;int method,flags;
  }
  private static int u16(byte[] a,int x){return (a[x]&255)|((a[x+1]&255)<<8);}
  private static long u32(byte[] a,int x){return (u16(a,x)&65535L)|((long)u16(a,x+2)<<16);}
  private static byte[] range(Reader r,long at,int length)throws IOException{
    if(at<0||length<0||at+length>r.size())throw new IOException("Invalid ZIP range");
    byte[] buf=new byte[length];r.read(at,buf,0,length);return buf;
  }
  private static Map<String,Item> index(Reader r)throws IOException{
    int size=(int)Math.min(r.size(),65557);
    byte[] tail=range(r,r.size()-size,size);
    int end=-1;
    for(int i=size-22;i>=0;i--)
      if(u32(tail,i)==0x06054b50L&&i+22+u16(tail,i+20)==size){end=i;break;}
    if(end<0)throw new IOException("ZIP end record missing");
    int count=u16(tail,end+10);
    long length=u32(tail,end+12),offset=u32(tail,end+16);
    if(count<1||count>8000||length<46||length>4000000||offset+length>r.size())
      throw new IOException("Unsupported ZIP directory");
    byte[] dir=range(r,offset,(int)length);
    Map<String,Item> items=new HashMap<>();
    int p=0;
    for(int i=0;i<count;i++){
      if(p+46>dir.length||u32(dir,p)!=0x02014b50L)throw new IOException("Bad ZIP index");
      int n=u16(dir,p+28),ex=u16(dir,p+30),cm=u16(dir,p+32);
      if(p+46+n+ex+cm>dir.length)throw new IOException("Bad ZIP filename");
      Item item=new Item();
      item.flags=u16(dir,p+8);item.method=u16(dir,p+10);
      item.crc=u32(dir,p+16);item.compressed=u32(dir,p+20);
      item.plain=u32(dir,p+24);item.offset=u32(dir,p+42);
      item.name=new String(dir,p+46,n,(item.flags&0x800)!=0?StandardCharsets.UTF_8:Charset.forName("IBM437")).replace('\\','/');
      if(items.put(item.name,item)!=null)throw new IOException("Duplicate ZIP entry");
      p+=46+n+ex+cm;
    }
    return items;
  }
  private static void extract(Reader r,Map<String,Item> index,File root,String name,int limit)throws Exception{
    name=URLDecoder.decode(name.replace("+","%2B"),"UTF-8");
    Item item=index.get(name);
    if(item==null)throw new IOException("Missing EPUB entry: "+name);
    if((item.flags&1)!=0||(item.method!=0&&item.method!=8)
       ||item.plain>limit||item.compressed>limit+65536)throw new IOException("Unsupported EPUB entry: "+name);
    byte[] header=range(r,item.offset,30);
    if(u32(header,0)!=0x04034b50L)throw new IOException("Invalid ZIP header");
    long start=item.offset+30+u16(header,26)+u16(header,28);
    byte[] packed=range(r,start,(int)item.compressed);
    ByteArrayOutputStream result=new ByteArrayOutputStream();
    InputStream input=item.method==0?new ByteArrayInputStream(packed):
      new InflaterInputStream(new ByteArrayInputStream(packed),new Inflater(true));
    try(InputStream in=input){
      byte[] chunk=new byte[16384];int n;
      while((n=in.read(chunk))!=-1){
        if(result.size()+n>limit)throw new IOException("EPUB preview exceeds limit");
        result.write(chunk,0,n);
      }
    }
    byte[] bytes=result.toByteArray();
    CRC32 crc=new CRC32();crc.update(bytes);
    if(bytes.length!=item.plain||crc.getValue()!=item.crc)throw new IOException("Bad EPUB CRC/size");
    File dest=new File(root,name).getCanonicalFile();
    if(!dest.getPath().startsWith(root.getCanonicalPath()+File.separator))
      throw new IOException("Unsafe EPUB path");
    if(!dest.getParentFile().exists()&&!dest.getParentFile().mkdirs())
      throw new IOException("Unable to create preview directory");
    try(FileOutputStream out=new FileOutputStream(dest)){out.write(bytes);}
  }
  /**
   * Read only the chosen spine HTML entry and referenced small assets.
   * The complete EPUB is never copied to local storage. Chapters may remain
   * as a disposable preview cache; their presence does not mean "saved".
   */
  public static void fetchChapter(Reader reader,File root,String chapter)throws Exception{
    if(chapter==null||chapter.isEmpty())throw new IOException("本文のパスがありません");
    Map<String,Item> entries=index(reader);
    extract(reader,entries,root,chapter,6*1024*1024);
    File html=new File(root,chapter).getCanonicalFile();
    if(!html.getPath().startsWith(root.getCanonicalPath()+File.separator))
      throw new IOException("Unsafe chapter path");
    if(!html.isFile())throw new IOException("本文ファイルが存在しません");
    String source=new String(java.nio.file.Files.readAllBytes(html.toPath()),StandardCharsets.UTF_8);
    // Fetch only optional linked images/stylesheets; failure never hides text.
    java.util.regex.Pattern links=java.util.regex.Pattern.compile(
      "(?i)(?:src|href)\\s*=\\s*['\\\"]([^'\\\"]+)['\\\"]");
    java.util.regex.Matcher matcher=links.matcher(source);
    Set<String> wanted=new LinkedHashSet<>();
    while(matcher.find()&&wanted.size()<18){
      String href=matcher.group(1);
      if(href==null||href.startsWith("#")||href.startsWith("//")||
         href.matches("(?i)^[a-z][a-z0-9+.-]*:.*"))continue;
      String noQuery=href.split("[?#]",2)[0];
      String ext=noQuery.toLowerCase(Locale.ROOT);
      if(!(ext.endsWith(".css")||ext.endsWith(".jpg")||ext.endsWith(".jpeg")||
        ext.endsWith(".png")||ext.endsWith(".gif")||ext.endsWith(".svg")||
        ext.endsWith(".webp")))continue;
      try{
        File target=new File(html.getParentFile(),
          URLDecoder.decode(noQuery.replace("+","%2B"),"UTF-8")).getCanonicalFile();
        String prefix=root.getCanonicalPath()+File.separator;
        if(!target.getPath().startsWith(prefix))continue;
        String entryName=target.getPath().substring(prefix.length()).replace(File.separatorChar,'/');
        wanted.add(entryName);
      }catch(Exception ignored){}
    }
    int attempts=0;
    long maxTransfer=12L*1024*1024;
    for(String filename:wanted){
      if(++attempts>14||maxTransfer<=0)break;
      Item item=entries.get(filename);
      if(item==null||item.plain<=0||item.plain>3L*1024*1024
        ||item.compressed>3L*1024*1024)continue;
      File existing=new File(root,filename);
      if(existing.isFile()&&existing.length()==item.plain)continue;
      if(item.plain>maxTransfer)continue;
      try{extract(reader,entries,root,filename,3*1024*1024);maxTransfer-=item.plain;}
      catch(IOException ignored){}
    }
  }

  public static EpubParser.BookInfo fetch(Reader r,File root)throws Exception{
    if(!root.exists()&&!root.mkdirs())throw new IOException("Unable to create cache");
    Map<String,Item> zip=index(r);
    extract(r,zip,root,"META-INF/container.xml",1024*1024);
    String opf=EpubParser.locatePackage(new File(root,"META-INF/container.xml"));
    extract(r,zip,root,opf,10*1024*1024);
    EpubParser.BookInfo info=EpubParser.parsePreview(root);
    if(!info.cover.isEmpty()){
      try{extract(r,zip,root,info.cover,3*1024*1024);}
      catch(IOException e){info.cover="";}
    }
    return info;
  }
}
