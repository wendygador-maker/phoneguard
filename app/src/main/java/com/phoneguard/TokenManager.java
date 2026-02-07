package com.phoneguard;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.util.UUID;

public class TokenManager {

    private static final String TAG = "PhoneGuard";
    private static final String PREFS_NAME = "phoneguard_prefs";
    private static final String KEY_TOKEN = "auth_token";

    public static String getToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String token = prefs.getString(KEY_TOKEN, null);
        if (token == null) {
            token = UUID.randomUUID().toString().replace("-", "");
            prefs.edit().putString(KEY_TOKEN, token).apply();
            writeTokenFile(token);
            Log.i(TAG, "Generated new token");
        }
        return token;
    }

    public static void writeTokenFile(String token) {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), "Documents");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "phoneguard_token.txt");
            FileWriter writer = new FileWriter(file);
            writer.write(token);
            writer.close();
            Log.i(TAG, "Token written to " + file.getAbsolutePath());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write token file", e);
        }
    }
}
