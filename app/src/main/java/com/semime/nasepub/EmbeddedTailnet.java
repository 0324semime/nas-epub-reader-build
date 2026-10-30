package com.semime.nasepub;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import org.json.JSONObject;
import org.semime.tsbridge.Tsbridge;

/** One app-private Tailscale node; no Android VPNService or global proxy. */
public final class EmbeddedTailnet {
    private static volatile Context application;
    private EmbeddedTailnet(){}
    public static void initialize(Context context) {
        application=context.getApplicationContext();
    }
    private static SharedPreferences preferences(){
        if(application==null)throw new IllegalStateException("Androidの初期化が未完了です");
        return application.getSharedPreferences("embedded_tailnet_v1",Context.MODE_PRIVATE);
    }
    public static boolean enabled(){return preferences().getBoolean("enabled",false);}
    public static String target(String localHost){
        String host=preferences().getString("target","");
        return host==null || host.trim().isEmpty()?localHost:host.trim();
    }
    public static void setSettings(boolean enabled,String target){
        if(enabled && !Tsbridge.validateTarget(target))
            throw new IllegalArgumentException("接続先は100.x.x.x、*.ts.netまたはサブネット経由のプライベートIPにしてください");
        preferences().edit().putBoolean("enabled",enabled).putString("target",target.trim()).apply();
        if(!enabled) {
            try{Tsbridge.stopSmbTunnel();}catch(Throwable ignored){}
        }
    }
    public static String start(){
        if(application==null)return "ERROR: アプリが初期化されていません";
        return Tsbridge.start(application.getNoBackupFilesDir().getAbsolutePath());
    }
    public static String status(){
        try{return Tsbridge.status();}
        catch(Throwable e){return "{\"phase\":\"failed\",\"error\":\"Tailscaleライブラリを利用できません\"}";}
    }
    public static boolean connected(){
        try{return "connected".equals(new JSONObject(status()).optString("phase"));}
        catch(Exception e){return false;}
    }
    public static String loginUrl(){
        try{return new JSONObject(status()).optString("login_url");}
        catch(Exception e){return "";}
    }
    public static String stop(){return Tsbridge.stop();}
    /**
     * Must run on a background thread. Returns a local port bound to 127.0.0.1.
     * Tailscale performs only network transit; SMBJ still authenticates to NAS.
     */
    public static int tunnelPort(String defaultHost)throws Exception {
        if(application==null)throw new IllegalStateException("Tailscaleが初期化されていません");
        if(!enabled())throw new IllegalStateException("内蔵Tailscaleが無効です");
        String host=target(defaultHost);
        if(!Tsbridge.validateTarget(host))
            throw new IllegalArgumentException("接続先のTailscaleアドレスが正しくありません");
        String result=start();
        if(result.startsWith("ERROR"))throw new IllegalStateException(result);
        long deadline=SystemClock.elapsedRealtime()+20000L;
        String phase="";
        while(SystemClock.elapsedRealtime()<deadline){
            JSONObject status=new JSONObject(Tsbridge.status());
            phase=status.optString("phase","");
            if("connected".equals(phase)){
                long port=Tsbridge.startSmbTunnel(host);
                if(port>0 && port<65536)return (int)port;
                throw new IllegalStateException("Tailscale SMB転送の起動に失敗しました("+port+")");
            }
            if("failed".equals(phase)){
                throw new IllegalStateException(status.optString("error","Tailscale接続に失敗しました"));
            }
            Thread.sleep(400);
        }
        throw new IllegalStateException("Tailscaleが未接続です（"+phase+"）。接続設定でログインURLを確認してください");
    }
}
