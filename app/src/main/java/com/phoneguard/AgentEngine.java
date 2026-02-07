package com.phoneguard;

import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK;
import static android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME;

import android.content.Intent;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Core agent engine: receives a task, uses planner model to decompose it,
 * then uses phone model to execute each subtask with fence validation.
 */
public class AgentEngine {

    private static final String TAG = "PhoneGuard";
    private static final int MAX_STEPS_PER_SUBTASK = 20;
    private static final int MAX_REPLAN_ATTEMPTS = 3;

    // Action patterns from phone model output
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "do\\(action=\"(\\w+)\"(?:,\\s*element=\\[(\\d+),\\s*(\\d+)\\])?(?:,\\s*text=\"([^\"]*)\")?(?:,\\s*direction=\"(\\w+)\")?\\)");
    private static final Pattern FINISH_PATTERN = Pattern.compile(
            "finish\\(message=\"([^\"]*)\"\\)");

    private final GuardAccessibilityService svc;
    private final ModelConfigManager configManager;
    private volatile boolean cancelled = false;

    // Task state storage
    private static final Map<String, TaskState> tasks = new HashMap<>();

    public AgentEngine(GuardAccessibilityService svc, ModelConfigManager configManager) {
        this.svc = svc;
        this.configManager = configManager;
    }

    public void cancel() {
        cancelled = true;
    }

    // --- Task state ---

    public static class TaskState {
        public String taskId;
        public String task;
        public String status; // "running", "done", "error", "cancelled"
        public String result;
        public String currentSubtask;
        public int currentStep;
        public String plannerModel;
        public String plannerFallbackFrom;
        public List<SubtaskState> subtasks = new ArrayList<>();

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("task_id", taskId);
                obj.put("status", status);
                if (result != null) obj.put("result", result);
                if (currentSubtask != null) obj.put("current_subtask", currentSubtask);
                obj.put("step", currentStep);
                if (plannerModel != null) obj.put("planner_model", plannerModel);
                if (plannerFallbackFrom != null) obj.put("planner_fallback_from", plannerFallbackFrom);
                JSONArray arr = new JSONArray();
                for (SubtaskState st : subtasks) {
                    JSONObject s = new JSONObject();
                    s.put("name", st.name);
                    s.put("status", st.status);
                    arr.put(s);
                }
                obj.put("subtasks", arr);
            } catch (Exception e) {
                Log.e(TAG, "TaskState toJson error", e);
            }
            return obj;
        }
    }

    public static class SubtaskState {
        public String name;
        public String status; // "pending", "running", "done", "failed"

        public SubtaskState(String name) {
            this.name = name;
            this.status = "pending";
        }
    }

    public static TaskState getTask(String taskId) {
        return tasks.get(taskId);
    }

    // --- Main entry point ---

    public TaskState executeTask(String task) {
        String taskId = "t_" + UUID.randomUUID().toString().substring(0, 8);
        TaskState state = new TaskState();
        state.taskId = taskId;
        state.task = task;
        state.status = "running";
        tasks.put(taskId, state);

        try {
            // 1. Plan: decompose task into subtasks
            List<String> subtaskNames = planTask(task, state);
            if (subtaskNames == null) {
                return safeExit(state, "规划模型全部不可用");
            }

            initSubtasks(state, subtaskNames);
            List<String> subtaskResults = new ArrayList<>();

            // 2. Execute each subtask
            int replanCount = 0;
            for (int i = 0; i < state.subtasks.size(); i++) {
                if (cancelled) {
                    state.status = "cancelled";
                    state.result = "任务已取消";
                    svc.performGlobalAction(GLOBAL_ACTION_HOME);
                    return state;
                }

                SubtaskState sub = state.subtasks.get(i);
                sub.status = "running";
                state.currentSubtask = sub.name;
                state.currentStep = 0;

                String subResult = executeSubtask(sub.name, state);

                if (subResult == null) {
                    // Subtask failed
                    sub.status = "failed";
                    if (replanCount < MAX_REPLAN_ATTEMPTS) {
                        replanCount++;
                        List<String> newPlan = replan(task, sub.name,
                                "子任务执行失败或超时", subtaskResults, state);
                        if (newPlan != null) {
                            initSubtasks(state, newPlan);
                            subtaskResults.clear();
                            i = -1; // restart from beginning of new plan
                            continue;
                        }
                    }
                    return safeExit(state, "子任务失败且重新规划失败: " + sub.name);
                }

                sub.status = "done";
                subtaskResults.add(sub.name + ": " + subResult);
            }

            // 3. Summarize results
            String summary = summarize(task, subtaskResults, state);
            state.status = "done";
            state.result = summary != null ? summary : String.join("\n", subtaskResults);
            state.currentSubtask = null;

        } catch (Exception e) {
            Log.e(TAG, "Task execution error", e);
            safeExit(state, "执行异常: " + e.getMessage());
        }

        return state;
    }

    // --- Subtask execution with fence ---

    private String executeSubtask(String subtask, TaskState state) {
        ModelConfigManager.ModelConfig phoneModel = configManager.getPhoneModel();
        if (phoneModel.url.isEmpty() || phoneModel.key.isEmpty()) {
            Log.e(TAG, "Phone model not configured");
            return null;
        }

        for (int step = 0; step < MAX_STEPS_PER_SUBTASK; step++) {
            if (cancelled) return null;
            state.currentStep = step + 1;

            try {
                // 1. Screenshot
                byte[] screenshot = svc.takeScreenshot(80, 0.3f);
                if (screenshot == null) {
                    Log.e(TAG, "Screenshot failed at step " + step);
                    continue;
                }
                String base64 = Base64.encodeToString(screenshot, Base64.NO_WRAP);

                // 2. Get current app info
                JSONObject appInfo = svc.getCurrentAppInfo();
                String currentApp = appInfo.optString("appName", "unknown");

                // 3. Build messages for phone model
                JSONArray messages = new JSONArray();

                JSONObject systemMsg = new JSONObject();
                systemMsg.put("role", "system");
                systemMsg.put("content", buildPhoneModelPrompt(currentApp));
                messages.put(systemMsg);

                JSONObject userMsg = new JSONObject();
                userMsg.put("role", "user");
                userMsg.put("content", "当前应用: " + currentApp + "\n任务: " + subtask
                        + "\n请根据截图决定下一步操作。");
                messages.put(userMsg);

                // 4. Call phone model with image
                String response = ModelClient.chatWithImage(
                        phoneModel.url, phoneModel.key, phoneModel.model,
                        messages, base64);

                if (response == null || response.isEmpty()) {
                    Log.w(TAG, "Phone model returned empty response");
                    continue;
                }

                Log.i(TAG, "Phone model response: " + response);

                // 5. Check for finish
                Matcher finishMatcher = FINISH_PATTERN.matcher(response);
                if (finishMatcher.find()) {
                    return finishMatcher.group(1);
                }

                // 6. Parse action
                Matcher actionMatcher = ACTION_PATTERN.matcher(response);
                if (!actionMatcher.find()) {
                    Log.w(TAG, "Could not parse action from response");
                    continue;
                }

                String action = actionMatcher.group(1);
                String elemX = actionMatcher.group(2);
                String elemY = actionMatcher.group(3);
                String text = actionMatcher.group(4);
                String direction = actionMatcher.group(5);

                // 7. Fence: block prohibited actions
                if (isProhibitedAction(action)) {
                    Log.w(TAG, "Fence blocked prohibited action: " + action);
                    return null; // Return to planner
                }

                // 8. Execute allowed action
                executeAction(action, elemX, elemY, text, direction);

                // Small delay between steps
                Thread.sleep(1000);

            } catch (Exception e) {
                Log.e(TAG, "Step " + step + " error", e);
            }
        }

        // Timeout
        return null;
    }

    // --- Fence: prohibited actions for phone model ---

    private boolean isProhibitedAction(String action) {
        if (action == null) return false;
        String lower = action.toLowerCase();
        return lower.equals("launch") || lower.equals("home") || lower.equals("recents");
    }

    // --- Execute a single action ---

    private void executeAction(String action, String elemX, String elemY,
                               String text, String direction) {
        JSONObject screenSize = svc.getScreenSize();
        int screenW = screenSize.optInt("width", 1080);
        int screenH = screenSize.optInt("height", 2400);

        switch (action.toLowerCase()) {
            case "tap": {
                if (elemX != null && elemY != null) {
                    // Convert 0-999 relative coords to absolute pixels
                    float x = Integer.parseInt(elemX) / 999f * screenW;
                    float y = Integer.parseInt(elemY) / 999f * screenH;
                    svc.performClick(x, y);
                }
                break;
            }
            case "swipe": {
                if (direction != null) {
                    int cx = screenW / 2;
                    int cy = screenH / 2;
                    int dist = screenH / 3;
                    switch (direction.toLowerCase()) {
                        case "up":
                            svc.performSwipe(cx, cy + dist / 2f, cx, cy - dist / 2f, 300);
                            break;
                        case "down":
                            svc.performSwipe(cx, cy - dist / 2f, cx, cy + dist / 2f, 300);
                            break;
                        case "left":
                            svc.performSwipe(cx + dist / 2f, cy, cx - dist / 2f, cy, 300);
                            break;
                        case "right":
                            svc.performSwipe(cx - dist / 2f, cy, cx + dist / 2f, cy, 300);
                            break;
                    }
                } else if (elemX != null && elemY != null) {
                    // Swipe with coordinates (start point, default scroll down)
                    float x = Integer.parseInt(elemX) / 999f * screenW;
                    float y = Integer.parseInt(elemY) / 999f * screenH;
                    svc.performSwipe(x, y, x, y - screenH / 3f, 300);
                }
                break;
            }
            case "type": {
                if (text != null) {
                    svc.performInput(text);
                }
                break;
            }
            case "scroll": {
                String dir = direction != null ? direction : "down";
                int cx = screenW / 2;
                int cy = screenH / 2;
                int dist = screenH / 3;
                switch (dir.toLowerCase()) {
                    case "down":
                        svc.performSwipe(cx, cy + dist / 2f, cx, cy - dist / 2f, 300);
                        break;
                    case "up":
                        svc.performSwipe(cx, cy - dist / 2f, cx, cy + dist / 2f, 300);
                        break;
                }
                break;
            }
            case "back": {
                svc.performGlobalAction(GLOBAL_ACTION_BACK);
                break;
            }
            case "long_press": {
                if (elemX != null && elemY != null) {
                    float x = Integer.parseInt(elemX) / 999f * screenW;
                    float y = Integer.parseInt(elemY) / 999f * screenH;
                    svc.performLongPress(x, y);
                }
                break;
            }
            case "double_tap": {
                if (elemX != null && elemY != null) {
                    float x = Integer.parseInt(elemX) / 999f * screenW;
                    float y = Integer.parseInt(elemY) / 999f * screenH;
                    svc.performClick(x, y);
                    try { Thread.sleep(100); } catch (Exception ignored) {}
                    svc.performClick(x, y);
                }
                break;
            }
            default:
                Log.w(TAG, "Unknown action: " + action);
        }
    }

    // --- Planner model calls ---

    private List<String> planTask(String task, TaskState state) {
        List<ModelConfigManager.ModelConfig> planners = configManager.getPlannerModels();
        if (planners.isEmpty()) return null;

        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", buildPlannerSystemPrompt());
            messages.put(sys);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", "请将以下任务拆解为子任务列表（JSON数组格式）:\n" + task);
            messages.put(user);
        } catch (Exception e) {
            return null;
        }

        String response = callPlannerWithFallback(planners, messages, state);
        if (response == null) return null;

        return parseSubtaskList(response);
    }

    private List<String> replan(String task, String failedSubtask, String error,
                                List<String> completedResults, TaskState state) {
        List<ModelConfigManager.ModelConfig> planners = configManager.getPlannerModels();
        if (planners.isEmpty()) return null;

        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", buildPlannerSystemPrompt());
            messages.put(sys);

            StringBuilder prompt = new StringBuilder();
            prompt.append("原始任务: ").append(task).append("\n");
            prompt.append("已完成的子任务:\n");
            for (String r : completedResults) {
                prompt.append("- ").append(r).append("\n");
            }
            prompt.append("失败的子任务: ").append(failedSubtask).append("\n");
            prompt.append("失败原因: ").append(error).append("\n");
            prompt.append("请重新规划剩余子任务（JSON数组格式），跳过已完成的部分:");

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", prompt.toString());
            messages.put(user);
        } catch (Exception e) {
            return null;
        }

        String response = callPlannerWithFallback(planners, messages, state);
        if (response == null) return null;

        return parseSubtaskList(response);
    }

    private String summarize(String task, List<String> results, TaskState state) {
        List<ModelConfigManager.ModelConfig> planners = configManager.getPlannerModels();
        if (planners.isEmpty()) return null;

        JSONArray messages = new JSONArray();
        try {
            JSONObject sys = new JSONObject();
            sys.put("role", "system");
            sys.put("content", "你是一个任务汇总助手。根据子任务的执行结果，给出简洁的最终结论。用中文回答。");
            messages.put(sys);

            StringBuilder prompt = new StringBuilder();
            prompt.append("原始任务: ").append(task).append("\n\n");
            prompt.append("各子任务结果:\n");
            for (String r : results) {
                prompt.append("- ").append(r).append("\n");
            }
            prompt.append("\n请给出最终结论:");

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", prompt.toString());
            messages.put(user);
        } catch (Exception e) {
            return null;
        }

        return callPlannerWithFallback(planners, messages, state);
    }

    private String callPlannerWithFallback(List<ModelConfigManager.ModelConfig> planners,
                                           JSONArray messages, TaskState state) {
        String previousModel = null;
        for (ModelConfigManager.ModelConfig config : planners) {
            try {
                state.plannerModel = config.model;
                if (previousModel != null) {
                    state.plannerFallbackFrom = previousModel;
                }
                String result = ModelClient.chat(config.url, config.key, config.model, messages);
                if (result != null && !result.isEmpty()) {
                    return result;
                }
            } catch (Exception e) {
                Log.w(TAG, "Planner " + config.model + " failed: " + e.getMessage());
                previousModel = config.model;
            }
        }
        return null;
    }

    // --- Helpers ---

    private void initSubtasks(TaskState state, List<String> names) {
        state.subtasks.clear();
        for (String name : names) {
            state.subtasks.add(new SubtaskState(name));
        }
    }

    private TaskState safeExit(TaskState state, String reason) {
        svc.performGlobalAction(GLOBAL_ACTION_HOME);
        state.status = "error";
        state.result = reason;
        return state;
    }

    private List<String> parseSubtaskList(String response) {
        List<String> result = new ArrayList<>();
        try {
            // Find JSON array in response
            int start = response.indexOf('[');
            int end = response.lastIndexOf(']');
            if (start >= 0 && end > start) {
                JSONArray arr = new JSONArray(response.substring(start, end + 1));
                for (int i = 0; i < arr.length(); i++) {
                    Object item = arr.get(i);
                    if (item instanceof String) {
                        result.add((String) item);
                    } else if (item instanceof JSONObject) {
                        // Support {"name":"..."} format
                        result.add(((JSONObject) item).optString("name",
                                ((JSONObject) item).optString("task", item.toString())));
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse subtask list: " + response, e);
        }
        return result.isEmpty() ? null : result;
    }

    private String buildPhoneModelPrompt(String currentApp) {
        return "你是一个手机屏幕操作助手。你只能在当前应用（" + currentApp + "）内进行操作。\n"
                + "严禁切换应用、按Home键、打开其他应用。\n\n"
                + "可用操作:\n"
                + "- do(action=\"Tap\", element=[x,y]) — 点击坐标 (0-999相对坐标)\n"
                + "- do(action=\"Swipe\", direction=\"up|down|left|right\") — 滑动\n"
                + "- do(action=\"Type\", text=\"内容\") — 输入文字\n"
                + "- do(action=\"Scroll\", direction=\"up|down\") — 滚动\n"
                + "- do(action=\"Back\") — 返回\n"
                + "- do(action=\"Long_press\", element=[x,y]) — 长按\n"
                + "- finish(message=\"完成描述\") — 子任务完成\n\n"
                + "坐标系: (0,0)左上角, (999,999)右下角\n"
                + "每次只返回一个操作。先简要分析截图，然后给出操作。";
    }

    private String buildPlannerSystemPrompt() {
        return "你是一个手机任务规划助手。你的职责是将用户的复杂任务拆解为简单的子任务列表。\n\n"
                + "规则:\n"
                + "1. 每个子任务应该是在一个应用内可以完成的操作\n"
                + "2. 需要切换应用时，拆分为独立的子任务（如\"打开淘宝\"、\"在淘宝搜索xxx\"）\n"
                + "3. 子任务描述要具体明确，包含操作目标\n"
                + "4. 返回JSON数组格式，如: [\"打开淘宝\", \"搜索车厘子并记录最低价\", ...]\n"
                + "5. 只返回JSON数组，不要其他内容";
    }
}
