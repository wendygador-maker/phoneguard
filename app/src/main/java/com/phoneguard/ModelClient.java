package com.phoneguard;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI-compatible API client for calling LLM models.
 * Supports text chat, vision (image) chat, model list fetching, and multi-model fallback.
 */
public class ModelClient {

    private static final String TAG = "PhoneGuard";
    private static final int TIMEOUT_MS = 60000;

    /**
     * Send a chat request (text only).
     */
    public static String chat(String baseUrl, String apiKey, String model,
                              JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        return doPost(baseUrl + "/chat/completions", apiKey, body);
    }

    /**
     * Send a chat request with an image (vision).
     * Appends the image as a base64 data URL to the last user message.
     */
    public static String chatWithImage(String baseUrl, String apiKey, String model,
                                       JSONArray messages, String base64Image) throws Exception {
        // Build vision content for the last user message
        JSONArray content = new JSONArray();
        // Find last user message text
        String lastText = "";
        int lastUserIdx = -1;
        for (int i = messages.length() - 1; i >= 0; i--) {
            if ("user".equals(messages.getJSONObject(i).optString("role"))) {
                lastText = messages.getJSONObject(i).optString("content", "");
                lastUserIdx = i;
                break;
            }
        }

        // Text part
        JSONObject textPart = new JSONObject();
        textPart.put("type", "text");
        textPart.put("text", lastText);
        content.put(textPart);

        // Image part
        JSONObject imagePart = new JSONObject();
        imagePart.put("type", "image_url");
        JSONObject imageUrl = new JSONObject();
        imageUrl.put("url", "data:image/png;base64," + base64Image);
        imagePart.put("image_url", imageUrl);
        content.put(imagePart);

        // Replace last user message content with multimodal content
        if (lastUserIdx >= 0) {
            messages.getJSONObject(lastUserIdx).put("content", content);
        }

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        return doPost(baseUrl + "/chat/completions", apiKey, body);
    }

    /**
     * Chat with fallback across multiple model configs.
     * Tries each model in order; returns first successful response.
     * Returns null if all models fail.
     */
    public static String chatWithFallback(List<ModelConfigManager.ModelConfig> models,
                                          JSONArray messages) {
        for (ModelConfigManager.ModelConfig config : models) {
            try {
                String result = chat(config.url, config.key, config.model, messages);
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "Model " + config.model + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Chat with image and fallback across multiple model configs.
     */
    public static String chatWithImageFallback(List<ModelConfigManager.ModelConfig> models,
                                               JSONArray messages, String base64Image) {
        for (ModelConfigManager.ModelConfig config : models) {
            try {
                // Deep copy messages for each attempt (chatWithImage modifies the array)
                JSONArray msgCopy = new JSONArray(messages.toString());
                String result = chatWithImage(config.url, config.key, config.model,
                        msgCopy, base64Image);
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "Model " + config.model + " (vision) failed: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Fetch available models from an OpenAI-compatible API.
     * Works with OpenAI, NewAPI, OneAPI, etc.
     */
    public static List<String> fetchModels(String baseUrl, String apiKey) throws Exception {
        List<String> models = new ArrayList<>();
        String urlStr = baseUrl + "/models";

        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("HTTP " + code);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject resp = new JSONObject(sb.toString());
            JSONArray data = resp.getJSONArray("data");
            for (int i = 0; i < data.length(); i++) {
                String id = data.getJSONObject(i).getString("id");
                models.add(id);
            }
        } finally {
            conn.disconnect();
        }

        return models;
    }

    /**
     * Internal: POST JSON to an API endpoint and extract the response content.
     */
    private static String doPost(String urlStr, String apiKey, JSONObject body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setDoOutput(true);

        try {
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                // Read error body
                StringBuilder errSb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        errSb.append(line);
                    }
                } catch (Exception ignored) {}
                throw new Exception("HTTP " + code + ": " + errSb);
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }

            JSONObject resp = new JSONObject(sb.toString());
            JSONArray choices = resp.getJSONArray("choices");
            if (choices.length() > 0) {
                return choices.getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content");
            }
            return "";
        } finally {
            conn.disconnect();
        }
    }
}
