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
    private static final String KEY_AGENT_PROMPT = "agent_system_prompt";

    private static final String DEFAULT_AGENT_PROMPT =
            "你是一个手机自动化任务的规划者和监督者（Agent）。\n\n"
            + "你的职责：\n"
            + "1. 分析用户任务，制定详细的执行策略\n"
            + "2. 给手机模型写详细的操作指令（像教一个会用手机但知识有限的人）\n"
            + "3. 分析手机模型返回的结果和截图，提取关键信息\n"
            + "4. 判断任务是否完成，决定是否需要下一轮操作\n"
            + "5. 在手机模型遇到困难时提供更具体的指引\n\n"
            + "关于手机模型的能力：\n"
            + "- 它能看屏幕截图，能点击、滑动、输入文字、启动应用\n"
            + "- 它有 Note 动作，可以标记关键页面（会触发截图保存给你分析）\n"
            + "- 它有上下文记忆，能记住之前的操作\n"
            + "- 但它知识有限，不了解特定手机系统的限制\n"
            + "- 它不擅长深度分析（比如计算实际到手价）\n\n"
            + "输出格式要求：\n"
            + "- 规划阶段：先分析策略，然后输出JSON（用```json包裹）\n"
            + "- 分析阶段：分析结果，如果需要继续则用【下一轮指令】标记下一轮指令\n"
            + "- 完成阶段：用【最终结论】标记最终结果\n"
            + "- 用中文回答\n";

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

    // --- Agent system prompt (editable) ---

    public String getAgentSystemPrompt() {
        return prefs.getString(KEY_AGENT_PROMPT, DEFAULT_AGENT_PROMPT);
    }

    public void saveAgentSystemPrompt(String prompt) {
        prefs.edit().putString(KEY_AGENT_PROMPT, prompt).apply();
    }

    public String getDefaultAgentPrompt() {
        return DEFAULT_AGENT_PROMPT;
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
            result.put("agent_prompt_length", getAgentSystemPrompt().length());
        } catch (Exception e) {
            // ignore
        }
        return result;
    }
}
