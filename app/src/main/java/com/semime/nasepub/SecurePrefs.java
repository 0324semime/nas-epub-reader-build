package com.semime.nasepub;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Multiple independent SMB connections; AES-GCM passwords remain in AndroidKeyStore. */
public final class SecurePrefs {
    private static final String ALIAS="nas_epub_reader_smb_key_v2";
    private final SharedPreferences preferences;
    private static final String PRIMARY="primary";
    private static final String PROFILES="profiles_v1";
    public static final class Profile {
        public final String id,name;
        public final SmbRepository.Settings settings;
        public final boolean folderSelected;
        Profile(String id,String name,SmbRepository.Settings settings,boolean selected){
            this.id=id;this.name=name;this.settings=settings;this.folderSelected=selected;
        }
    }
    public SecurePrefs(Context context){
        preferences=context.getSharedPreferences("smb_connection_v2",Context.MODE_PRIVATE);
        migrate();
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing=(SecretKey)store.getKey(ALIAS,null);
        if(existing!=null)return existing;
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(ALIAS,
            KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build();
        generator.init(spec);return generator.generateKey();
    }

    private SmbRepository.Settings legacy(){
        SmbRepository.Settings s=new SmbRepository.Settings();
        s.host=preferences.getString("host","192.168.3.4");
        s.share=preferences.getString("share","");
        s.folder=preferences.getString("folder","");
        s.user=preferences.getString("user","");
        s.domain=preferences.getString("domain","");
        String data=preferences.getString("password","");
        try{s.password=data.isEmpty()?"":decrypt(data);}
        catch(Exception e){s.password="";}
        return s;
    }
    private JSONObject serialize(Profile p)throws Exception{
        JSONObject obj=new JSONObject();
        obj.put("id",p.id);obj.put("name",p.name);
        obj.put("host",p.settings.host);obj.put("share",p.settings.share);
        obj.put("folder",p.settings.folder);obj.put("user",p.settings.user);
        obj.put("domain",p.settings.domain);obj.put("password",encrypt(p.settings.password));
        obj.put("folder_selected",p.folderSelected);
        return obj;
    }
    private Profile parse(JSONObject obj){
        String id=obj.optString("id",PRIMARY);
        String name=obj.optString("name","NAS");
        SmbRepository.Settings s=new SmbRepository.Settings();
        s.host=obj.optString("host","");s.share=obj.optString("share","");
        s.folder=obj.optString("folder","");s.user=obj.optString("user","");
        s.domain=obj.optString("domain","");
        String enc=obj.optString("password","");
        try{s.password=enc.isEmpty()?"":decrypt(enc);}
        catch(Exception e){s.password="";}
        return new Profile(id,name,s,obj.optBoolean("folder_selected",false));
    }
    private synchronized void migrate(){
        if(preferences.contains(PROFILES))return;
        SmbRepository.Settings old=legacy();
        boolean selected=preferences.getBoolean("folder_selected",!old.folder.isEmpty());
        try{
            JSONArray arr=new JSONArray();
            arr.put(serialize(new Profile(PRIMARY,"NAS 1",old,selected)));
            preferences.edit().putString(PROFILES,arr.toString())
                .putString("active_profile",PRIMARY).commit();
        }catch(Exception e){throw new IllegalStateException("NAS接続情報を移行できません",e);}
    }
    public synchronized List<Profile> listProfiles(){
        migrate();
        List<Profile> result=new ArrayList<>();
        try{
            JSONArray arr=new JSONArray(preferences.getString(PROFILES,"[]"));
            for(int i=0;i<arr.length();i++){
                JSONObject entry=arr.optJSONObject(i);
                if(entry!=null)result.add(parse(entry));
            }
        }catch(Exception ignored){}
        return result;
    }
    private synchronized void replace(List<Profile> items)throws Exception{
        JSONArray arr=new JSONArray();
        for(Profile item:items)arr.put(serialize(item));
        if(!preferences.edit().putString(PROFILES,arr.toString()).commit())
            throw new IllegalStateException("NAS接続情報を保存できません");
    }
    public synchronized String activeId(){
        return preferences.getString("active_profile",PRIMARY);
    }
    public synchronized Profile profile(String id){
        for(Profile p:listProfiles())if(p.id.equals(id))return p;
        return null;
    }
    public synchronized void select(String id){
        if(profile(id)==null)throw new IllegalArgumentException("不明な接続先です");
        preferences.edit().putString("active_profile",id).apply();
    }
    public synchronized String add(String name)throws Exception{
        List<Profile> profiles=listProfiles();
        if(profiles.size()>=12)throw new IllegalArgumentException("NAS接続先は最大12件です");
        String id=UUID.randomUUID().toString();
        SmbRepository.Settings empty=new SmbRepository.Settings();
        empty.host="";empty.share="";empty.folder="";empty.user="";empty.password="";
        profiles.add(new Profile(id,name.trim().isEmpty()?"NAS "+(profiles.size()+1):name.trim(),empty,false));
        replace(profiles);select(id);
        return id;
    }
    public synchronized void remove(String id)throws Exception{
        List<Profile> profiles=listProfiles();
        if(profiles.size()<=1)throw new IllegalArgumentException("最後の接続先は削除できません");
        if(!profiles.removeIf(x->x.id.equals(id)))return;
        replace(profiles);
        if(id.equals(activeId()))select(profiles.get(0).id);
    }
    public synchronized void rename(String id,String name)throws Exception{
        if(name==null||name.trim().isEmpty())throw new IllegalArgumentException("名前を入力してください");
        List<Profile> profiles=listProfiles();
        for(int i=0;i<profiles.size();i++){
            Profile p=profiles.get(i);
            if(p.id.equals(id))profiles.set(i,new Profile(p.id,name.trim(),p.settings,p.folderSelected));
        }
        replace(profiles);
    }
    public synchronized void save(SmbRepository.Settings s)throws Exception{
        List<Profile> profiles=listProfiles();
        String target=activeId();
        boolean updated=false;
        for(int i=0;i<profiles.size();i++){
            Profile p=profiles.get(i);
            if(p.id.equals(target)){
                profiles.set(i,new Profile(p.id,p.name,s.copy(),p.folderSelected));
                updated=true;break;
            }
        }
        if(!updated)throw new IllegalStateException("NAS接続先が見つかりません");
        replace(profiles);
    }
    public synchronized boolean hasFolderSelection(){
        Profile p=profile(activeId());
        return p!=null&&p.folderSelected;
    }
    public synchronized void setFolderSelected(boolean selected){
        try{
            List<Profile> profiles=listProfiles();
            for(int i=0;i<profiles.size();i++){
                Profile p=profiles.get(i);
                if(p.id.equals(activeId()))
                    profiles.set(i,new Profile(p.id,p.name,p.settings,selected));
            }
            replace(profiles);
        }catch(Exception e){throw new IllegalStateException("フォルダ設定を保存できません",e);}
    }
    public synchronized SmbRepository.Settings load(){
        Profile p=profile(activeId());
        return p==null?legacy():p.settings.copy();
    }
    private String encrypt(String plain) throws Exception{
        if(plain.isEmpty())return "";
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] iv=cipher.getIV();
        byte[] encrypted=cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv,Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP);
    }
    private String decrypt(String encoded) throws Exception{
        String[] parts=encoded.split(":",2);
        if(parts.length!=2)throw new IllegalArgumentException("invalid encrypted data");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),
            new GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)));
        return new String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
    }
}
