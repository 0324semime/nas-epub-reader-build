package com.semime.nasepub;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Keeps SMB credentials in Android KeyStore; nothing secret is embedded into the APK. */
public final class SecurePrefs {
    private static final String ALIAS="nas_epub_reader_smb_key_v2";
    private final SharedPreferences preferences;
    public SecurePrefs(Context context){
        preferences=context.getSharedPreferences("smb_connection_v2",Context.MODE_PRIVATE);
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
    public void save(SmbRepository.Settings s) throws Exception {
        String encrypted=encrypt(s.password);
        preferences.edit()
            .putString("host",s.host).putString("share",s.share)
            .putString("folder",s.folder).putString("user",s.user)
            .putString("domain",s.domain).putString("password",encrypted)
            .apply();
    }
    /** The root of a share can be explicitly selected (empty folder path). */
    public boolean hasFolderSelection() {
        return preferences.getBoolean("folder_selected",
            !preferences.getString("folder", "").isEmpty());
    }
    public void setFolderSelected(boolean selected) {
        preferences.edit().putBoolean("folder_selected", selected).apply();
    }
    public SmbRepository.Settings load(){
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
