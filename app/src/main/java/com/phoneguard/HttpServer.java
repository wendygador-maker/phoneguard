package com.phoneguard;

import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_POWER_DIALOG;

import android.content.Intent;
import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

public class HttpServer extends NanoHTTPD {

    private static final String TAG = "PhoneGuard";
    private final String token;

    public HttpServer(int port, String token) {
        super("127.0.0.1", port);
        this.token = token;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();

        // CORS headers for local access
        Response resp;

        // Status endpoint doesn't require auth (but only returns limited info)
        if (uri.equals("/status") && method == Method.GET) {
            return handleStatus(session);
        }

        // Verify token for all other endpoints
        String auth = session.getHeaders().get("authorization");
        if (auth == null || !auth.equals("Bearer " + token)) {
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED,
                    "application/json", "{\"error\":\"Unauthorized\"}");
        }

        try {
            switch (uri) {
                case "/screenshot":
                    resp = handleScreenshot(session);
                    break;
                case "/click":
                    resp = handleClick(session);
                    break;
                case "/long-press":
                    resp = handleLongPress(session);
                    break;
                case "/tap-text":
                    resp = handleTapText(session);
                    break;
                case "/input":
                    resp = handleInput(session);
                    break;
                case "/swipe":
                    resp = handleSwipe(session);
                    break;
                case "/key":
                    resp = handleKey(session);
                    break;
                case "/launch":
                    resp = handleLaunch(session);
                    break;
                case "/node-info":
                    resp = handleNodeInfo(session);
                    break;
                case "/current-app":
                    resp = handleCurrentApp(session);
                    break;
                case "/apps":
                    resp = handleApps(session);
                    break;
                case "/screen-size":
                    resp = handleScreenSize(session);
                    break;
                case "/find-text":
                    resp = handleFindText(session);
                    break;
                case "/scroll":
                    resp = handleScroll(session);
                    break;
                default:
                    resp = newFixedLengthResponse(Response.Status.NOT_FOUND,
                            "application/json", "{\"error\":\"Not found\"}");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling " + uri, e);
            resp = newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }

        return resp;
    }

    private Response handleStatus(IHTTPSession session) {
        try {
            JSONObject json = new JSONObject();
            json.put("ok", true);
            json.put("accessibility", GuardAccessibilityService.isRunning());
            json.put("screenshot", GuardAccessibilityService.isRunning() && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R);
            json.put("token", token);
            return newFixedLengthResponse(Response.Status.OK,
                    "application/json", json.toString());
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleScreenshot(IHTTPSession session) {
        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        Map<String, String> params = session.getParms();
        int quality = parseInt(params.get("quality"), 80);
        float scale = parseFloat(params.get("scale"), 1.0f);

        byte[] png = svc.takeScreenshot(quality, scale);
        if (png == null) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"error\":\"Screenshot failed. Requires Android 11+.\"}");
        }

        return newFixedLengthResponse(Response.Status.OK,
                "image/png", new ByteArrayInputStream(png), png.length);
    }

    private Response handleClick(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        float x = (float) body.getDouble("x");
        float y = (float) body.getDouble("y");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        boolean ok = svc.performClick(x, y);
        return jsonResponse(ok);
    }

    private Response handleLongPress(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        float x = (float) body.getDouble("x");
        float y = (float) body.getDouble("y");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        boolean ok = svc.performLongPress(x, y);
        return jsonResponse(ok);
    }

    private Response handleTapText(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        String text = body.getString("text");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        boolean ok = svc.performTapText(text);
        return jsonResponse(ok);
    }

    private Response handleInput(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        String text = body.getString("text");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        boolean ok = svc.performInput(text);
        return jsonResponse(ok);
    }

    private Response handleSwipe(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        float x1 = (float) body.getDouble("x1");
        float y1 = (float) body.getDouble("y1");
        float x2 = (float) body.getDouble("x2");
        float y2 = (float) body.getDouble("y2");
        long duration = body.optLong("duration", 300);

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        boolean ok = svc.performSwipe(x1, y1, x2, y2, duration);
        return jsonResponse(ok);
    }

    private Response handleKey(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        String key = body.getString("key");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        boolean ok;
        switch (key.toLowerCase()) {
            case "back":
                ok = svc.performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            case "home":
                ok = svc.performGlobalAction(GLOBAL_ACTION_HOME);
                break;
            case "recents":
                ok = svc.performGlobalAction(GLOBAL_ACTION_RECENTS);
                break;
            case "notifications":
                ok = svc.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                break;
            case "quick_settings":
                ok = svc.performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
                break;
            case "power":
                ok = svc.performGlobalAction(GLOBAL_ACTION_POWER_DIALOG);
                break;
            default:
                return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        "application/json", "{\"error\":\"Unknown key: " + key + "\"}");
        }
        return jsonResponse(ok);
    }

    private Response handleLaunch(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        String pkg = body.getString("package");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        try {
            Intent intent = svc.getPackageManager().getLaunchIntentForPackage(pkg);
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                svc.startActivity(intent);
                return jsonResponse(true);
            } else {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        "application/json", "{\"error\":\"Package not found: " + pkg + "\"}");
            }
        } catch (Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                    "application/json", "{\"error\":\"" + e.getMessage() + "\"}");
        }
    }

    private Response handleNodeInfo(IHTTPSession session) {
        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", svc.getNodeTree().toString());
    }

    private Response handleCurrentApp(IHTTPSession session) {
        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", svc.getCurrentAppInfo().toString());
    }

    private Response handleApps(IHTTPSession session) {
        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", svc.getInstalledApps().toString());
    }

    private Response handleScreenSize(IHTTPSession session) {
        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", svc.getScreenSize().toString());
    }

    private Response handleFindText(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        String text = body.getString("text");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        return newFixedLengthResponse(Response.Status.OK,
                "application/json", svc.findTextNodes(text).toString());
    }

    private Response handleScroll(IHTTPSession session) throws Exception {
        JSONObject body = parseBody(session);
        String direction = body.getString("direction");

        GuardAccessibilityService svc = GuardAccessibilityService.getInstance();
        if (svc == null) return serviceUnavailable();

        // Get screen dimensions for calculating scroll coordinates
        JSONObject screenSize = svc.getScreenSize();
        int w = screenSize.getInt("width");
        int h = screenSize.getInt("height");
        int distance = body.optInt("distance", h / 3);

        int cx = w / 2;
        int cy = h / 2;
        float x1, y1, x2, y2;

        switch (direction.toLowerCase()) {
            case "down":
                x1 = cx; y1 = cy + distance / 2f;
                x2 = cx; y2 = cy - distance / 2f;
                break;
            case "up":
                x1 = cx; y1 = cy - distance / 2f;
                x2 = cx; y2 = cy + distance / 2f;
                break;
            case "left":
                x1 = cx - distance / 2f; y1 = cy;
                x2 = cx + distance / 2f; y2 = cy;
                break;
            case "right":
                x1 = cx + distance / 2f; y1 = cy;
                x2 = cx - distance / 2f; y2 = cy;
                break;
            default:
                return newFixedLengthResponse(Response.Status.BAD_REQUEST,
                        "application/json", "{\"error\":\"Unknown direction: " + direction + "\"}");
        }

        boolean ok = svc.performSwipe(x1, y1, x2, y2, 300);
        return jsonResponse(ok);
    }

    // --- Helpers ---

    private JSONObject parseBody(IHTTPSession session) throws Exception {
        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        String body = files.get("postData");
        if (body == null || body.isEmpty()) {
            body = "{}";
        }
        return new JSONObject(body);
    }

    private Response jsonResponse(boolean success) {
        String json = "{\"ok\":" + success + "}";
        return newFixedLengthResponse(Response.Status.OK, "application/json", json);
    }

    private Response serviceUnavailable() {
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                "application/json", "{\"error\":\"Accessibility service not running\"}");
    }

    private int parseInt(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private float parseFloat(String s, float def) {
        if (s == null) return def;
        try { return Float.parseFloat(s); } catch (Exception e) { return def; }
    }
}
