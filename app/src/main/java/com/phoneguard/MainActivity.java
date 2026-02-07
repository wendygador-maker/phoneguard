package com.phoneguard;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView tvAccessibility;
    private TextView tvScreenCapture;
    private TextView tvHttpServer;
    private TextView tvToken;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvAccessibility = findViewById(R.id.tv_accessibility);
        tvScreenCapture = findViewById(R.id.tv_screen_capture);
        tvHttpServer = findViewById(R.id.tv_http_server);
        tvToken = findViewById(R.id.tv_token);

        Button btnAccessibility = findViewById(R.id.btn_accessibility);
        Button btnBattery = findViewById(R.id.btn_battery);
        Button btnAutoStart = findViewById(R.id.btn_auto_start);
        Button btnCopyToken = findViewById(R.id.btn_copy_token);

        btnAccessibility.setOnClickListener(v -> openAccessibilitySettings());
        btnBattery.setOnClickListener(v -> requestBatteryOptimization());
        btnAutoStart.setOnClickListener(v -> openAutoStartSettings());
        btnCopyToken.setOnClickListener(v -> copyToken());

        // Write token file on first launch
        String token = TokenManager.getToken(this);
        TokenManager.writeTokenFile(token);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateStatus();
    }

    private void updateStatus() {
        boolean accOk = GuardAccessibilityService.isRunning();
        boolean capOk = accOk && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        boolean httpOk = accOk; // HTTP server starts with accessibility service

        tvAccessibility.setText(accOk ? "✅ 无障碍服务已开启" : "❌ 无障碍服务未开启");
        tvScreenCapture.setText(capOk ? "✅ 截图功能可用 (Android 11+)" : "❌ 截图功能不可用");
        tvHttpServer.setText(httpOk ? "✅ HTTP 服务运行中 (127.0.0.1:8552)" : "❌ HTTP 服务未运行");

        String token = TokenManager.getToken(this);
        tvToken.setText("Token: " + token.substring(0, 8) + "...");
    }

    private void openAccessibilitySettings() {
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
        Toast.makeText(this, "请找到 PhoneGuard 并开启", Toast.LENGTH_LONG).show();
    }

    private void requestBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (!pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } else {
                Toast.makeText(this, "已豁免电池优化", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void openAutoStartSettings() {
        // Try common manufacturer auto-start settings
        String[][] intents = {
                {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
                {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
                {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
                {"com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"},
                {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
                {"com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"},
                {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
                {"com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"},
        };

        for (String[] pair : intents) {
            try {
                Intent intent = new Intent();
                intent.setComponent(new android.content.ComponentName(pair[0], pair[1]));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                return;
            } catch (Exception ignored) {}
        }

        // Fallback to app info
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "请手动在设置中允许自启动", Toast.LENGTH_LONG).show();
        }
    }

    private void copyToken() {
        String token = TokenManager.getToken(this);
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("PhoneGuard Token", token));
        Toast.makeText(this, "Token 已复制", Toast.LENGTH_SHORT).show();
    }
}
