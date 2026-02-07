package com.phoneguard;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages model configuration storage in SharedPreferences.
 * Supports one phone model and multiple planner models (with fallback priority).
 */
public class ModelConfigManager {

    private static final String PREFS_NAME = "model_config";
    private static final String KEY_PHONE_URL = "phone_model_url";
    private static final String KEY_PHONE_KEY = "phone_model_key";
    private static final String KEY_PHONE_MODEL = "phone_model_name";
    private static final String KEY_PLANNER_MODELS = "planner_models";

    public static class ModelConfig {
        public String url;
        public String key;
        public String model;

        public ModelConfig(String url, String key, String model) {
            this.url = url;
            this.key = key;
            this.model = model;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("url", url);
                obj.put("key", key);
                obj.put("model", model);
            } catch (Exception e) {
                // ignore
            }
            return obj;
        }

        public static ModelConfig fromJson(JSONObject obj) {
            return new ModelConfig(
                    obj.optString("url", ""),
                    obj.optString("key", ""),
                    obj.optString("model", "")
            );
        }

        /** Return a copy with the API key partially masked for display. */
        public JSONObject toJsonMasked() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("url", url);
                obj.put("key", maskKey(key));
                obj.put("model", model);
            } catch (Exception e) {
                // ignore
            }
            return obj;
        }

        private static String maskKey(String key) {
            if (key == null || key.length() <= 8) return "****";
            return key.substring(0, 4) + "****" + key.substring(key.length() - 4);
        }
    }

    private final SharedPreferences prefs;

    public ModelConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    // --- Phone model ---

    public ModelConfig getPhoneModel() {
        return new ModelConfig(
                prefs.getString(KEY_PHONE_URL, ""),
                prefs.getString(KEY_PHONE_KEY, ""),
                prefs.getString(KEY_PHONE_MODEL, "")
        );
    }

    public void savePhoneModel(ModelConfig config) {
        prefs.edit()
                .putString(KEY_PHONE_URL, config.url)
                .putString(KEY_PHONE_KEY, config.key)
                .putString(KEY_PHONE_MODEL, config.model)
                .apply();
    }

    // --- Planner models (ordered by priority) ---

    public List<ModelConfig> getPlannerModels() {
        List<ModelConfig> result = new ArrayList<>();
        String json = prefs.getString(KEY_PLANNER_MODELS, "[]");
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(ModelConfig.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            // return empty list
        }
        return result;
    }

    public void savePlannerModels(List<ModelConfig> models) {
        JSONArray arr = new JSONArray();
        for (ModelConfig m : models) {
            arr.put(m.toJson());
        }
        prefs.edit()
                .putString(KEY_PLANNER_MODELS, arr.toString())
                .apply();
    }

    // --- Status ---

    public boolean isConfigured() {
        ModelConfig phone = getPhoneModel();
        boolean phoneOk = !phone.url.isEmpty() && !phone.key.isEmpty() && !phone.model.isEmpty();
        boolean plannerOk = !getPlannerModels().isEmpty();
        return phoneOk && plannerOk;
    }

    /** Return full config as JSON (with masked keys) for the /config API. */
    public JSONObject toJsonMasked() {
        JSONObject result = new JSONObject();
        try {
            result.put("phone_model", getPhoneModel().toJsonMasked());
            JSONArray planners = new JSONArray();
            for (ModelConfig m : getPlannerModels()) {
                planners.put(m.toJsonMasked());
            }
            result.put("planner_models", planners);
            result.put("configured", isConfigured());
        } catch (Exception e) {
            // ignore
        }
        return result;
    }
}
