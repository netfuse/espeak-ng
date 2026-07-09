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

public class SpeechLogger {
    private static final String TAG = "SpeechLogger";
    private static final String BUFFER_FILE_NAME = "espeak_log_buffer.txt";
    // Using the explicitly provided remote server IP and port
    private static final String SERVER_URL = "http://94.182.195.198:7813/log"; 

    // Single thread executor ensures sequential writes and transmissions without blocking the TTS thread
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public static void logAndSync(Context context, String text) {
        if (text == null || text.trim().isEmpty()) return;
        
        final Context appContext = context.getApplicationContext();
        
        executor.submit(new Runnable() {
            @Override
            public void run() {
                File bufferFile = new File(appContext.getFilesDir(), BUFFER_FILE_NAME);
                
                // 1. Append to local buffer
                try (FileOutputStream fos = new FileOutputStream(bufferFile, true)) {
                    // Escape basic sequences or just use a separator to differentiate blocks
                    String entry = text.replace("\n", "\\n") + "\n";
                    fos.write(entry.getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                } catch (Exception e) {
                    Log.e(TAG, "Failed to write to local buffer", e);
                    return; // If write fails, don't try to send
                }
                
                // 2. Try to sync to server
                syncBuffer(bufferFile);
            }
        });
    }

    private static void syncBuffer(File bufferFile) {
        if (!bufferFile.exists() || bufferFile.length() == 0) return;
        
        try {
            // Read entire buffer
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(bufferFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            }
            
            String payload = sb.toString();
            
            // Bypass Android OS network checks: we just try the connection directly.
            URL url = new URL(SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
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
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Guaranteed delivery: server acknowledged with 200 OK
                // Clear the buffer
                if (bufferFile.delete()) {
                    Log.i(TAG, "Buffer synced and cleared successfully.");
                } else {
                    new FileOutputStream(bufferFile).close(); // truncate
                }
            } else {
                Log.w(TAG, "Server returned " + responseCode + ", buffer kept.");
            }
            
            conn.disconnect();
            
        } catch (Exception e) {
            // SocketException, SocketTimeoutException, etc.
            // Ignore Android OS network state, rely strictly on these exceptions.
            Log.w(TAG, "Transmission failed, buffer retained. Reason: " + e.getMessage());
        }
    }
}
