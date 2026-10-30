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
        try(SMBClient client=new SMBClient();
            Connection connection=client.connect(cfg.host.trim())) {
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
    public static void download(Settings cfg,String path,File output,long maxBytes) throws Exception{
        if(path.startsWith("\\")||path.startsWith("/")||path.contains("..")||path.contains("\0"))
            throw new IllegalArgumentException("不正なファイルパス");
        connected(cfg,share->{
            try(com.hierynomus.smbj.share.File remote=share.openFile(
                path,EnumSet.of(AccessMask.GENERIC_READ),null,SMB2ShareAccess.ALL,SMB2CreateDisposition.FILE_OPEN,null);
                InputStream in=remote.getInputStream();
                FileOutputStream out=new FileOutputStream(output)){
                byte[] buffer=new byte[32768];int n;long size=0;
                while((n=in.read(buffer))!=-1){
                    size+=n;
                    if(size>maxBytes)throw new IOException("EPUBは150MB以下にしてください");
                    out.write(buffer,0,n);
                }
            }
            return null;
        });
    }
}
