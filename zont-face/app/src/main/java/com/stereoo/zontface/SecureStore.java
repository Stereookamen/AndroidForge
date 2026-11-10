package com.stereoo.zontface;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Credentials never stored as plaintext; per-install AndroidKeyStore key. */
final class SecureStore {
    private static final String ALIAS = "stereoo_zont_credential_key";
    private final SharedPreferences prefs;
    SecureStore(Context context) { prefs = context.getSharedPreferences("zont_settings", Context.MODE_PRIVATE); }
    String username() { return prefs.getString("username", ""); }
    int deviceId() { return prefs.getInt("device_id", 0); }
    String deviceName() { return prefs.getString("device_name", ""); }
    void selectDevice(int id, String name) { prefs.edit().putInt("device_id", id).putString("device_name", name).apply(); }
    boolean configured() { return !username().isEmpty() && !prefs.getString("ciphertext", "").isEmpty(); }
    void save(String username, String password) throws Exception {
        if (username.trim().isEmpty() || password.isEmpty()) throw new IllegalArgumentException("Заполни логин и пароль");
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        SecretKey key = ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS, null)).getSecretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] sealed = cipher.doFinal(password.getBytes(StandardCharsets.UTF_8));
        prefs.edit().putString("username", username.trim())
                .putString("ciphertext", Base64.encodeToString(sealed, Base64.NO_WRAP))
                .putString("iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .apply();
    }
    String password() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey key = ((KeyStore.SecretKeyEntry)ks.getEntry(ALIAS, null)).getSecretKey();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        byte[] iv = Base64.decode(prefs.getString("iv", ""), Base64.DEFAULT);
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(Base64.decode(prefs.getString("ciphertext", ""), Base64.DEFAULT)), StandardCharsets.UTF_8);
    }
}
