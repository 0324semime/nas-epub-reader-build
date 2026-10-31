package com.semime.nasepub;

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.protocol.commons.EnumWithValue;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Read-only SMB2/3 library access. No passwords or NAS paths are compiled into this app. */
public final class SmbRepository {
    public static final class Settings {
        public String host = "";
        public String share = "";
        public String folder = "";
        public String user = "";
        public String password = "";
        public String domain = "";
        public Settings copy(){
            Settings c=new Settings();c.host=host;c.share=share;c.folder=folder;
            c.user=user;c.password=password;c.domain=domain;return c;
        }
        public void validate(){
            if(host.trim().isEmpty()||share.trim().isEmpty())throw new IllegalArgumentException("NASのIPアドレスと共有フォルダ名を入力してください");
            if(host.contains("/")||host.contains("\\")||host.contains(":")||host.contains(" "))
                throw new IllegalArgumentException("ホストにはIPアドレスかホスト名のみ入力してください");
            if(share.contains("/")||share.contains("\\")||share.contains("..")||share.trim().isEmpty())
                throw new IllegalArgumentException("共有フォルダ名が正しくありません");
            if(folder.startsWith("/")||folder.startsWith("\\")||folder.contains(".."))
                throw new IllegalArgumentException("サブフォルダは共有フォルダからの相対パスにしてください");
        }
    }
    public static final class RemoteBook {
        public final String name;
        public final String path;
        public RemoteBook(String name,String path){this.name=name;this.path=path;}
    }
    /** One visible row in the share browser. Lists directories and files, without downloading. */
    public static final class BrowseEntry {
        public final String name;
        public final String path;
        public final boolean directory;
        public BrowseEntry(String name, String path, boolean directory) {
            this.name=name; this.path=path; this.directory=directory;
        }
    }
    /**
     * Returns only immediate children. An empty path means the root of the
     * configured share. Does not enumerate all shares on the server.
     */
    public static List<BrowseEntry> browse(Settings cfg, String relativePath) throws Exception {
        final String path=cleanRelativePath(relativePath);
        return connected(cfg,share->{
            List<BrowseEntry> items=new ArrayList<>();
            for(FileIdBothDirectoryInformation file:share.list(path)){
                String name=file.getFileName();
                if(name.equals(".")||name.equals("..")||name.contains("/")
                    ||name.contains("\\")||name.indexOf('\0')>=0)continue;
                boolean folder=EnumWithValue.EnumUtils.isSet(
                    file.getFileAttributes(),FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
                String child=path.isEmpty()?name:path+"\\"+name;
                items.add(new BrowseEntry(name,child,folder));
                if(items.size()>=1000)break;
            }
            items.sort((a,b)->{
                if(a.directory!=b.directory)return a.directory?-1:1;
                return a.name.compareToIgnoreCase(b.name);
            });
            return items;
        });
    }
    private static String cleanRelativePath(String input) {
        if(input==null)return "";
        String path=input.replace('/','\\').trim();
        if(path.isEmpty())return "";
        if(path.startsWith("\\")||path.endsWith("\\")||path.contains(":")
            ||path.indexOf('\0')>=0)
            throw new IllegalArgumentException("フォルダのパスが正しくありません");
        for(String part:path.split("\\\\",-1)){
            if(part.isEmpty()||part.equals(".")||part.equals(".."))
                throw new IllegalArgumentException("フォルダのパスが正しくありません");
        }
        return path;
    }
    private interface Task<T> { T run(DiskShare share) throws Exception; }
    private static <T> T connected(Settings cfg,Task<T> task) throws Exception {
        cfg.validate();
        boolean tailnet=EmbeddedTailnet.enabled();
        int port=tailnet ? EmbeddedTailnet.tunnelPort(cfg.host.trim()) : 445;
        String endpoint=tailnet ? "127.0.0.1" : cfg.host.trim();
        try(SMBClient client=new SMBClient();
            Connection connection=client.connect(endpoint,port)) {
            AuthenticationContext context=new AuthenticationContext(cfg.user,cfg.password.toCharArray(),cfg.domain);
            Session session=connection.authenticate(context);
            try(DiskShare share=(DiskShare)session.connectShare(cfg.share.trim())){
                return task.run(share);
            } finally { session.close(); }
        }
    }
    public static List<RemoteBook> list(Settings cfg) throws Exception {
        return connected(cfg,share->{
            List<RemoteBook> books=new ArrayList<>();
            String start=normalize(cfg.folder);
            scan(share,start,books,0);
            return books;
        });
    }
    private static String normalize(String folder){
        String f=folder.trim().replace('/','\\');
        while(f.endsWith("\\"))f=f.substring(0,f.length()-1);
        return f;
    }
    private static void scan(DiskShare share,String folder,List<RemoteBook> out,int depth){
        if(depth>5||out.size()>=350)return;
        for(FileIdBothDirectoryInformation file:share.list(folder)){
            String name=file.getFileName();
            if(name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\")||name.indexOf('\0')>=0)continue;
            String path=folder.isEmpty()?name:(folder+"\\"+name);
            boolean isDir=EnumWithValue.EnumUtils.isSet(file.getFileAttributes(),FileAttributes.FILE_ATTRIBUTE_DIRECTORY);
            if(isDir){
                if(depth<5)scan(share,path,out,depth+1);
            } else if(name.toLowerCase(Locale.ROOT).endsWith(".epub")){
                out.add(new RemoteBook(name,path));
                if(out.size()>=350)return;
            }
        }
    }
    /** Reads selected ZIP entries over SMB random access, not the entire EPUB. */
    public static EpubParser.BookInfo preview(Settings cfg,String path,java.io.File directory)throws Exception{
        if(path.startsWith("\\")||path.startsWith("/")||path.contains("..")||path.indexOf('\0')>=0)
            throw new IOException("不正なEPUBのパス");
        return connected(cfg,share->{
            try(com.hierynomus.smbj.share.File remote=share.openFile(
                path,EnumSet.of(AccessMask.GENERIC_READ),null,
                SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,null)){
                final long size=remote.getLength();
                RemoteZipPreview.Reader ranges=new RemoteZipPreview.Reader(){
                    @Override public long size(){return size;}
                    @Override public void read(long at,byte[] target,int offset,int count)throws IOException{
                        int n=0;
                        while(n<count){
                            try{
                                int got=remote.read(target,at+n,offset+n,Math.min(count-n,65536));
                                if(got<=0)throw new IOException("SMBの範囲読み取りが途中で終了しました");
                                n+=got;
                            }catch(Exception e){throw new IOException("SMB部分取得に失敗: "+e.getMessage(),e);}
                        }
                    }
                };
                return RemoteZipPreview.fetch(ranges,directory);
            }
        });
    }

    public interface DownloadProgress {
        void onBytes(long bytesRead);
    }
    public interface TransferAllowed {
        boolean allowed();
    }
    /** Downloads an EPUB from SMB with progress notifications, without printing secrets. */
    public static void download(Settings cfg,String path,File output,long maxBytes,
                                DownloadProgress progress,TransferAllowed permitted) throws Exception{
        if(path.startsWith("\\")||path.startsWith("/")||path.contains("..")||path.contains("\0"))
            throw new IllegalArgumentException("不正なファイルパス");
        if(permitted!=null && !permitted.allowed())
            throw new IOException("Wi-Fiに接続してから本文をダウンロードしてください");
        final long[] downloaded={0L};
        try {
            connected(cfg,share->{
                try(com.hierynomus.smbj.share.File remote=share.openFile(
                    path,EnumSet.of(AccessMask.GENERIC_READ),null,SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,null);
                    InputStream in=remote.getInputStream();
                    FileOutputStream out=new FileOutputStream(output)){
                    byte[] buffer=new byte[65536];
                    int n;
                    long lastBytes=0;
                    long lastSignal=System.nanoTime();
                    while(true){
                        if(permitted!=null && !permitted.allowed())
                            throw new IOException("Wi-Fi接続が切れたため、本文の取得を中止しました");
                        n=in.read(buffer);
                        if(n<0)break;
                        if(permitted!=null && !permitted.allowed())
                            throw new IOException("Wi-Fi接続が切れたため、本文の取得を中止しました");
                        if(n==0)continue;
                        downloaded[0]+=n;
                        if(downloaded[0]>maxBytes)throw new IOException("EPUBは150MB以下にしてください");
                        out.write(buffer,0,n);
                        long now=System.nanoTime();
                        if(progress!=null &&
                            (downloaded[0]-lastBytes>=524288L || now-lastSignal>500000000L)){
                            progress.onBytes(downloaded[0]);
                            lastBytes=downloaded[0];
                            lastSignal=now;
                        }
                    }
                    out.getFD().sync();
                    if(progress!=null)progress.onBytes(downloaded[0]);
                }
                return null;
            });
            if(downloaded[0]==0)throw new IOException("0バイトのEPUBを受信しました");
        }catch(Exception e){
            throw new IOException("SMBダウンロードに失敗（受信 "
                +downloaded[0]+" bytes）："+e.getClass().getSimpleName()
                +(e.getMessage()==null?"":": "+e.getMessage()),e);
        }
    }
    public static void download(Settings cfg,String path,File output,long maxBytes,
                                DownloadProgress progress)throws Exception{
        download(cfg,path,output,maxBytes,progress,null);
    }
    public static void download(Settings cfg,String path,File output,long maxBytes)throws Exception{
        download(cfg,path,output,maxBytes,null,null);
    }
}
