package lol.yukichat.wallet;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.hardware.fingerprint.FingerprintManager;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.webkit.WebView;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Fingerprint / face unlock. The wallet passcode is encrypted with an Android Keystore key that can only be
 * used after a strong biometric check, so the stored copy is useless without the owner's finger.
 * Results go back to the page as window.__ywBio(ok, data).
 * Some phones (seen on Samsung) rate their sensor "weak": a Keystore key can't be tied to a weak biometric,
 * so there the key needs no auth and the prompt alone gates the unlock ("weak" mode).
 * Every API above minSdk is guarded by an SDK_INT check; status() is "none" below Android 9.
 */
@android.annotation.TargetApi(Build.VERSION_CODES.R)
class Bio {
    private static final String KEY = "yw_bio";
    private final Activity act;
    private final WebView web;
    private final SharedPreferences prefs;

    Bio(Activity act, WebView web) {
        this.act = act;
        this.web = web;
        this.prefs = act.getSharedPreferences("bio", Activity.MODE_PRIVATE);
    }

    /** "none" (no hardware or nothing enrolled), "available" or "enabled". */
    String status() {
        if (mode() == null) return "none";
        return prefs.contains("ct") ? "enabled" : "available";
    }

    /** What the phone reports, shown in Settings when unlock is not available. */
    String info() {
        if (Build.VERSION.SDK_INT < 30) return "Android " + Build.VERSION.SDK_INT;
        BiometricManager bm = act.getSystemService(BiometricManager.class);
        return "sdk " + Build.VERSION.SDK_INT + ", strong " + bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            + ", weak " + bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK);
    }

    /** "strong", "weak" or null when no biometric can be used. */
    @SuppressWarnings("deprecation")
    private String mode() {
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager bm = act.getSystemService(BiometricManager.class);
            if (bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS) return "strong";
            return bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS ? "weak" : null;
        }
        if (Build.VERSION.SDK_INT == 29)
            return act.getSystemService(BiometricManager.class).canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS ? "strong" : null;
        if (Build.VERSION.SDK_INT == 28) {
            FingerprintManager fm = act.getSystemService(FingerprintManager.class);
            return fm != null && fm.isHardwareDetected() && fm.hasEnrolledFingerprints() ? "strong" : null;
        }
        return null;
    }

    void enable(String pass) {
        String m = mode();
        if (m == null) { reply(false, "Fingerprint unlock is not available"); return; }
        act.runOnUiThread(() -> {
            try {
                boolean strong = "strong".equals(m);
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, newKey(strong));
                prompt("Turn on fingerprint unlock", c, strong, ok -> {
                    byte[] ct = ok.doFinal(pass.getBytes(StandardCharsets.UTF_8));
                    prefs.edit().putString("mode", m).putString("iv", b64(ok.getIV())).putString("ct", b64(ct)).apply();
                    return "";
                });
            } catch (Exception e) {
                reply(false, "Fingerprint unlock is not available");
            }
        });
    }

    void unlock() {
        if (mode() == null) { reply(false, "Fingerprint unlock is not available"); return; }
        act.runOnUiThread(() -> {
            String iv = prefs.getString("iv", null), ct = prefs.getString("ct", null);
            if (iv == null || ct == null) { reply(false, "off"); return; }
            try {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)));
                prompt("Unlock Yuki Wallet", c, !"weak".equals(prefs.getString("mode", "strong")), ok -> new String(ok.doFinal(Base64.decode(ct, Base64.NO_WRAP)), StandardCharsets.UTF_8));
            } catch (KeyPermanentlyInvalidatedException e) {
                // a fingerprint was added or removed: the old key is gone for good
                disable();
                reply(false, "Fingerprints changed. Enter your passcode and turn fingerprint unlock on again");
            } catch (Exception e) {
                disable();
                reply(false, "Fingerprint unlock is not available");
            }
        });
    }

    void disable() {
        prefs.edit().clear().apply();
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            ks.deleteEntry(KEY);
        } catch (Exception ignored) { }
    }

    private interface Step { String run(Cipher c) throws Exception; }

    private void prompt(String title, Cipher c, boolean strong, Step step) {
        BiometricPrompt.Builder b = new BiometricPrompt.Builder(act).setTitle(title)
            .setNegativeButton("Use passcode", act.getMainExecutor(), (d, w) -> reply(false, "cancel"));
        if (Build.VERSION.SDK_INT >= 30)
            b.setAllowedAuthenticators(strong ? BiometricManager.Authenticators.BIOMETRIC_STRONG : BiometricManager.Authenticators.BIOMETRIC_WEAK);
        BiometricPrompt.AuthenticationCallback cb = new BiometricPrompt.AuthenticationCallback() {
                @Override
                public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) {
                    try { reply(true, step.run(strong ? r.getCryptoObject().getCipher() : c)); }
                    catch (Exception e) { reply(false, "Fingerprint unlock failed"); }
                }

                @Override
                public void onAuthenticationError(int code, CharSequence msg) {
                    boolean cancel = code == BiometricPrompt.BIOMETRIC_ERROR_CANCELED || code == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED;
                    reply(false, cancel ? "cancel" : String.valueOf(msg));
                }
            };
        // a weak biometric can't unlock a Keystore key, so it is a plain prompt
        if (strong) b.build().authenticate(new BiometricPrompt.CryptoObject(c), new CancellationSignal(), act.getMainExecutor(), cb);
        else b.build().authenticate(new CancellationSignal(), act.getMainExecutor(), cb);
    }

    private SecretKey newKey(boolean strong) throws Exception {
        KeyGenParameterSpec.Builder spec = new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE);
        if (strong) {
            spec.setUserAuthenticationRequired(true).setInvalidatedByBiometricEnrollment(true);
            if (Build.VERSION.SDK_INT >= 30) spec.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG);
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(spec.build());
        return g.generateKey();
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        SecretKey k = (SecretKey) ks.getKey(KEY, null);
        if (k == null) throw new IllegalStateException("no key");
        return k;
    }

    private static String b64(byte[] b) { return Base64.encodeToString(b, Base64.NO_WRAP); }

    private void reply(boolean ok, String data) {
        act.runOnUiThread(() -> web.evaluateJavascript("window.__ywBio&&__ywBio(" + ok + "," + JSONObject.quote(data) + ")", null));
    }
}
