package com.phoneguard;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class GuardAccessibilityService extends AccessibilityService {

    private static final String TAG = "PhoneGuard";
    private static GuardAccessibilityService instance;
    private HttpServer httpServer;
    private Handler mainHandler;
    private String currentPackage = "";
    private String currentActivity = "";

    public static GuardAccessibilityService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null;
    }

    /**
     * Check if the service is enabled in system accessibility settings,
     * regardless of whether the instance is alive in this process.
     */
    public static boolean isEnabledInSettings(Context context) {
        AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        for (AccessibilityServiceInfo info : am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            String id = info.getId();
            if (id != null && id.contains("com.phoneguard")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        mainHandler = new Handler(Looper.getMainLooper());
        Log.i(TAG, "Accessibility service connected");

        // Start HTTP server
        try {
            String token = TokenManager.getToken(this);
            httpServer = new HttpServer(8552, token);
            httpServer.start();
            Log.i(TAG, "HTTP server started on 127.0.0.1:8552");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start HTTP server", e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            CharSequence cls = event.getClassName();
            if (pkg != null && cls != null) {
                String className = cls.toString();
                // Filter out popups/dialogs — only track real Activity transitions
                if (!className.contains("Dialog")
                        && !className.contains("Popup")
                        && !className.contains("Menu")
                        && !className.startsWith("android.widget.")) {
                    currentPackage = pkg.toString();
                    currentActivity = className;
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        instance = null;
        if (httpServer != null) {
            httpServer.stop();
        }
        Log.i(TAG, "Accessibility service destroyed");
    }

    // --- Click at coordinates ---
    public boolean performClick(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 100);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        return dispatchGestureSync(gesture);
    }

    // --- Long press at coordinates ---
    public boolean performLongPress(float x, float y) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x, y);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, 1000);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        return dispatchGestureSync(gesture);
    }

    // --- Swipe ---
    public boolean performSwipe(float x1, float y1, float x2, float y2, long duration) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, duration);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();

        return dispatchGestureSync(gesture);
    }

    // --- Input text into focused field ---
    public boolean performInput(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        AccessibilityNodeInfo focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focused != null) {
            android.os.Bundle args = new android.os.Bundle();
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            boolean result = focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            focused.recycle();
            root.recycle();
            return result;
        }
        root.recycle();
        return false;
    }

    // --- Tap on text ---
    public boolean performTapText(String text) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;

        java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            AccessibilityNodeInfo node = nodes.get(0);
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            float cx = bounds.centerX();
            float cy = bounds.centerY();
            node.recycle();
            root.recycle();
            return performClick(cx, cy);
        }
        root.recycle();
        return false;
    }

    // --- Take screenshot using Accessibility API (Android 11+) ---
    public byte[] takeScreenshot(int quality, float scale) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Bitmap> bitmapRef = new AtomicReference<>(null);

        takeScreenshot(Display.DEFAULT_DISPLAY,
                getMainExecutor(),
                new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult result) {
                        try {
                            Bitmap bmp = Bitmap.wrapHardwareBuffer(
                                    result.getHardwareBuffer(), result.getColorSpace());
                            if (bmp != null) {
                                // Convert hardware bitmap to software bitmap for compression
                                bitmapRef.set(bmp.copy(Bitmap.Config.ARGB_8888, false));
                                bmp.recycle();
                            }
                            result.getHardwareBuffer().close();
                        } catch (Exception e) {
                            Log.e(TAG, "Screenshot onSuccess error", e);
                        }
                        latch.countDown();
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        Log.e(TAG, "Screenshot failed with error code: " + errorCode);
                        latch.countDown();
                    }
                });

        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return null;
        }

        Bitmap bitmap = bitmapRef.get();
        if (bitmap == null) return null;

        try {
            // Scale if needed
            if (scale > 0 && scale < 1.0f) {
                int sw = (int) (bitmap.getWidth() * scale);
                int sh = (int) (bitmap.getHeight() * scale);
                Bitmap scaled = Bitmap.createScaledBitmap(bitmap, sw, sh, true);
                bitmap.recycle();
                bitmap = scaled;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, quality > 0 ? quality : 80, baos);
            bitmap.recycle();
            return baos.toByteArray();
        } catch (Exception e) {
            Log.e(TAG, "Screenshot compress failed", e);
            bitmap.recycle();
            return null;
        }
    }

    // --- Get current foreground app info ---
    public JSONObject getCurrentAppInfo() {
        JSONObject result = new JSONObject();
        try {
            String pkg = currentPackage;
            String act = currentActivity;

            // Fallback: if no event received yet, try root window
            if (pkg.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    CharSequence rootPkg = root.getPackageName();
                    if (rootPkg != null) {
                        pkg = rootPkg.toString();
                    }
                    root.recycle();
                }
            }

            result.put("package", pkg);
            result.put("activity", act);

            // Resolve human-readable app name
            if (!pkg.isEmpty()) {
                try {
                    PackageManager pm = getPackageManager();
                    result.put("appName", pm.getApplicationLabel(
                            pm.getApplicationInfo(pkg, 0)).toString());
                } catch (PackageManager.NameNotFoundException e) {
                    result.put("appName", pkg);
                }
            } else {
                result.put("appName", "");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting current app info", e);
        }
        return result;
    }

    // --- Get all installed launchable apps ---
    public JSONArray getInstalledApps() {
        JSONArray result = new JSONArray();
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            PackageManager pm = getPackageManager();
            List<ResolveInfo> activities = pm.queryIntentActivities(intent, 0);
            for (ResolveInfo ri : activities) {
                JSONObject app = new JSONObject();
                app.put("package", ri.activityInfo.packageName);
                app.put("name", ri.loadLabel(pm).toString());
                result.put(app);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting installed apps", e);
        }
        return result;
    }

    // --- Get screen size ---
    public JSONObject getScreenSize() {
        JSONObject result = new JSONObject();
        try {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            result.put("width", dm.widthPixels);
            result.put("height", dm.heightPixels);
            result.put("density", dm.density);
        } catch (Exception e) {
            Log.e(TAG, "Error getting screen size", e);
        }
        return result;
    }

    // --- Find UI elements containing text ---
    public JSONArray findTextNodes(String text) {
        JSONArray result = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return result;

        try {
            List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
            if (nodes != null) {
                for (AccessibilityNodeInfo node : nodes) {
                    JSONObject obj = new JSONObject();
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);

                    CharSequence nodeText = node.getText();
                    CharSequence nodeDesc = node.getContentDescription();

                    obj.put("text", nodeText != null ? nodeText.toString() : "");
                    obj.put("desc", nodeDesc != null ? nodeDesc.toString() : "");
                    obj.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
                    obj.put("cx", bounds.centerX());
                    obj.put("cy", bounds.centerY());
                    obj.put("clickable", node.isClickable());
                    result.put(obj);
                    node.recycle();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding text nodes", e);
        }
        root.recycle();
        return result;
    }

    // --- Get UI node tree ---
    public JSONArray getNodeTree() {
        JSONArray result = new JSONArray();
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return result;

        try {
            collectNodes(root, result, 0);
        } catch (Exception e) {
            Log.e(TAG, "Error collecting nodes", e);
        }
        root.recycle();
        return result;
    }

    private void collectNodes(AccessibilityNodeInfo node, JSONArray result, int depth) {
        if (node == null || depth > 15) return;

        try {
            JSONObject obj = new JSONObject();
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            CharSequence cls = node.getClassName();

            if (text != null || desc != null || node.isClickable()) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);

                CharSequence pkg = node.getPackageName();
                obj.put("package", pkg != null ? pkg.toString() : "");
                obj.put("text", text != null ? text.toString() : "");
                obj.put("desc", desc != null ? desc.toString() : "");
                obj.put("class", cls != null ? cls.toString() : "");
                obj.put("clickable", node.isClickable());
                obj.put("scrollable", node.isScrollable());
                obj.put("editable", node.isEditable());
                obj.put("bounds", bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom);
                obj.put("cx", bounds.centerX());
                obj.put("cy", bounds.centerY());
                result.put(obj);
            }

            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child != null) {
                    collectNodes(child, result, depth + 1);
                    child.recycle();
                }
            }
        } catch (Exception e) {
            // skip
        }
    }

    // --- Dispatch gesture synchronously ---
    private boolean dispatchGestureSync(GestureDescription gesture) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false;

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicBoolean success = new AtomicBoolean(false);

        boolean dispatched = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                success.set(true);
                latch.countDown();
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                success.set(false);
                latch.countDown();
            }
        }, mainHandler);

        if (!dispatched) return false;

        try {
            latch.await(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            return false;
        }

        return success.get();
    }
}
