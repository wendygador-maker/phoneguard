package com.phoneguard;

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

/**
 * Agent Engine v2: Multi-round architecture.
 *
 * Agent (planner model) = brain: plans strategy, writes detailed prompts, analyzes screenshots.
 * Phone model (AutoGLM) = hands+eyes: navigates, taps, swipes, uses Note to mark key pages.
 * Engine = coordinator: hooks Note for screenshots, passes data between models.
 *
 * Flow:
 *   1. Agent analyzes task → decides strategy (single_pass vs multi_round)
 *   2. Agent writes detailed instruction for phone model
 *   3. Engine calls AutoGLM via CLI, hooks Note actions to save screenshots
 *   4. AutoGLM finishes → engine collects finish message + Note screenshots
 *   5. Agent analyzes results + screenshots → decides next round or final summary
 */
public class AgentEngine {

    private static final String TAG = "PhoneGuard";

    private final GuardAccessibilityService svc;
    private final ModelConfigManager configManager;
    private volatile boolean cancelled = false;

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
        public String currentPhase; // what's happening now
        public int roundNumber;
        public String plannerModel;
        public String plannerFallbackFrom;
        public List<RoundState> rounds = new ArrayList<>();

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("task_id", taskId);
                obj.put("status", status);
                if (result != null) obj.put("result", result);
                if (currentPhase != null) obj.put("current_phase", currentPhase);
                obj.put("round", roundNumber);
                if (plannerModel != null) obj.put("planner_model", plannerModel);
                if (plannerFallbackFrom != null) obj.put("planner_fallback_from", plannerFallbackFrom);
                JSONArray arr = new JSONArray();
                for (RoundState r : rounds) {
                    arr.put(r.toJson());
                }
                obj.put("rounds", arr);
            } catch (Exception e) {
                Log.e(TAG, "TaskState toJson error", e);
            }
            return obj;
        }
    }

    public static class RoundState {
        public int number;
        public String instruction;
        public String status; // "pending", "running", "done", "failed"
        public String phoneModelResult; // finish message from AutoGLM
        public int screenshotCount;
        public String agentAnalysis; // what agent concluded from this round

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            try {
                obj.put("number", number);
                obj.put("instruction", instruction != null ?
                        (instruction.length() > 100 ? instruction.substring(0, 100) + "..." : instruction) : "");
                obj.put("status", status);
                if (phoneModelResult != null) obj.put("phone_model_result", phoneModelResult);
                obj.put("screenshot_count", screenshotCount);
                if (agentAnalysis != null) obj.put("agent_analysis", agentAnalysis);
            } catch (Exception e) {
                Log.e(TAG, "RoundState toJson error", e);
            }
            return obj;
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
        state.roundNumber = 0;
        tasks.put(taskId, state);

        try {
            // Get installed apps for context
            JSONArray installedApps = svc.getInstalledApps();
            String appListStr = buildAppListString(installedApps);

            // Get editable system prompt
            String agentSystemPrompt = configManager.getAgentSystemPrompt();

            // Conversation history with agent (planner model)
            JSONArray agentConversation = new JSONArray();

            // System message
            JSONObject sysMsg = new JSONObject();
            sysMsg.put("role", "system");
            sysMsg.put("content", agentSystemPrompt);
            agentConversation.put(sysMsg);

            // Initial user message with task + context
            JSONObject userMsg = new JSONObject();
            userMsg.put("role", "user");
            userMsg.put("content", buildInitialPrompt(task, appListStr));
            agentConversation.put(userMsg);

            // Agent planning loop
            state.currentPhase = "agent_planning";
            String agentResponse = callPlannerWithFallback(
                    configManager.getPlannerModels(), agentConversation, state);

            if (agentResponse == null) {
                return safeExit(state, "规划模型全部不可用");
            }

            // Add agent response to conversation
            JSONObject assistantMsg = new JSONObject();
            assistantMsg.put("role", "assistant");
            assistantMsg.put("content", agentResponse);
            agentConversation.put(assistantMsg);

            // Parse agent's plan
            JSONObject plan = parsePlan(agentResponse);
            if (plan == null) {
                return safeExit(state, "无法解析规划模型的响应");
            }

            // Execute rounds
            int maxRounds = plan.optInt("max_rounds", 10);
            boolean allowAppSwitch = plan.optBoolean("allow_phone_model_switch_app", true);

            while (state.roundNumber < maxRounds && !cancelled) {
                state.roundNumber++;

                // Get instruction for this round
                String instruction;
                if (state.roundNumber == 1) {
                    instruction = plan.optString("first_instruction", "");
                    if (instruction.isEmpty()) {
                        instruction = plan.optString("instruction", "");
                    }
                } else {
                    // Agent already provided next instruction in previous analysis
                    instruction = getNextInstruction(agentConversation);
                    if (instruction == null || instruction.isEmpty()) {
                        break; // Agent decided we're done
                    }
                }

                if (instruction.isEmpty()) {
                    break;
                }

                // Execute one round with AutoGLM
                RoundState round = new RoundState();
                round.number = state.roundNumber;
                round.instruction = instruction;
                round.status = "running";
                state.rounds.add(round);
                state.currentPhase = "phone_model_executing (round " + state.roundNumber + ")";

                Log.i(TAG, "=== Round " + state.roundNumber + " ===");
                Log.i(TAG, "Instruction: " + instruction);

                // Call AutoGLM via CLI
                PhoneModelResult pmResult = callPhoneModel(instruction, allowAppSwitch);
                round.phoneModelResult = pmResult.finishMessage;
                round.screenshotCount = pmResult.screenshots.size();

                if (pmResult.finishMessage == null) {
                    round.status = "failed";
                    Log.w(TAG, "Phone model failed or timed out");
                } else {
                    round.status = "done";
                    Log.i(TAG, "Phone model finished: " + pmResult.finishMessage);
                }

                // Send results back to agent for analysis
                state.currentPhase = "agent_analyzing (round " + state.roundNumber + ")";

                JSONObject roundReport = new JSONObject();
                roundReport.put("role", "user");

                StringBuilder reportContent = new StringBuilder();
                reportContent.append("【第").append(state.roundNumber).append("轮执行结果】\n");
                reportContent.append("手机模型返回: ").append(
                        pmResult.finishMessage != null ? pmResult.finishMessage : "执行失败/超时").append("\n");
                reportContent.append("截图数量: ").append(pmResult.screenshots.size()).append("\n");

                if (!pmResult.screenshots.isEmpty()) {
                    reportContent.append("\n以下是手机模型在关键页面的截图，请仔细分析：\n");
                }

                // For vision: attach screenshots to the message
                if (!pmResult.screenshots.isEmpty()) {
                    JSONArray content = new JSONArray();

                    // Text part
                    JSONObject textPart = new JSONObject();
                    textPart.put("type", "text");
                    textPart.put("text", reportContent.toString());
                    content.put(textPart);

                    // Image parts (max 5 to avoid token limits)
                    int maxImages = Math.min(pmResult.screenshots.size(), 5);
                    for (int i = 0; i < maxImages; i++) {
                        JSONObject imagePart = new JSONObject();
                        imagePart.put("type", "image_url");
                        JSONObject imageUrl = new JSONObject();
                        imageUrl.put("url", "data:image/png;base64," + pmResult.screenshots.get(i));
                        imagePart.put("image_url", imageUrl);
                        content.put(imagePart);
                    }

                    roundReport.put("content", content);
                } else {
                    reportContent.append("\n请根据手机模型的返回信息分析结果，并决定下一步。");
                    reportContent.append("\n如果任务已完成，回复最终结论。");
                    reportContent.append("\n如果还需要继续，给出下一轮的详细指令。");
                    roundReport.put("content", reportContent.toString());
                }

                agentConversation.put(roundReport);

                // Agent analyzes and decides next step
                String analysis = callPlannerWithFallback(
                        configManager.getPlannerModels(), agentConversation, state);

                if (analysis == null) {
                    return safeExit(state, "规划模型分析失败");
                }

                round.agentAnalysis = analysis;

                // Add to conversation
                JSONObject analysisMsg = new JSONObject();
                analysisMsg.put("role", "assistant");
                analysisMsg.put("content", analysis);
                agentConversation.put(analysisMsg);

                Log.i(TAG, "Agent analysis: " + analysis.substring(0, Math.min(200, analysis.length())));

                // Check if agent says we're done
                if (isTaskComplete(analysis)) {
                    state.status = "done";
                    state.result = extractFinalResult(analysis);
                    state.currentPhase = "completed";
                    return state;
                }
            }

            // If we got here, max rounds reached
            // Ask agent for final summary with whatever we have
            state.currentPhase = "final_summary";
            JSONObject finalMsg = new JSONObject();
            finalMsg.put("role", "user");
            finalMsg.put("content", "已达到最大轮次。请根据目前收集到的所有信息，给出最终结论。");
            agentConversation.put(finalMsg);

            String finalSummary = callPlannerWithFallback(
                    configManager.getPlannerModels(), agentConversation, state);
            state.status = "done";
            state.result = finalSummary != null ? finalSummary : "达到最大轮次，未能完成任务";
            state.currentPhase = "completed";

        } catch (Exception e) {
            Log.e(TAG, "Task execution error", e);
            safeExit(state, "执行异常: " + e.getMessage());
        }

        return state;
    }

    // --- Phone model result ---

    public static class PhoneModelResult {
        public String finishMessage;
        public List<String> screenshots = new ArrayList<>(); // base64 PNGs from Note actions
    }

    // --- Call AutoGLM via CLI ---

    private PhoneModelResult callPhoneModel(String instruction, boolean allowAppSwitch) {
        PhoneModelResult result = new PhoneModelResult();

        try {
            ModelConfigManager.ModelConfig phoneConfig = configManager.getPhoneModel();

            // Write instruction to temp file to avoid shell escaping issues
            String instrFile = svc.getCacheDir() + "/agent_instruction.txt";
            java.io.FileWriter fw = new java.io.FileWriter(instrFile);
            fw.write(instruction);
            fw.close();

            // Build command
            // Note: we use a wrapper script that hooks Note actions for screenshots
            String scriptPath = svc.getCacheDir() + "/run_phone_model.sh";
            String screenshotDir = svc.getCacheDir() + "/agent_screenshots";

            // Clean screenshot dir
            java.io.File ssDir = new java.io.File(screenshotDir);
            if (ssDir.exists()) {
                for (java.io.File f : ssDir.listFiles()) f.delete();
            } else {
                ssDir.mkdirs();
            }

            // Write runner script
            // This script runs AutoGLM and the Note hook is handled by a modified handler
            fw = new java.io.FileWriter(scriptPath);
            fw.write("#!/data/data/com.termux/files/usr/bin/bash\n");
            fw.write("export PHONEGUARD_SCREENSHOT_DIR=\"" + screenshotDir + "\"\n");
            fw.write("export PHONEGUARD_TOKEN=\"" + TokenManager.getToken(svc) + "\"\n");
            fw.write("cd /data/data/com.termux/files/home/Code/1/Open-AutoGLM\n");
            fw.write("python main.py");
            fw.write(" --base-url \"" + phoneConfig.url + "\"");
            fw.write(" --model \"" + phoneConfig.model + "\"");
            fw.write(" --apikey \"" + phoneConfig.key + "\"");
            fw.write(" --max-steps 50");
            fw.write(" \"$(cat " + instrFile + ")\"");
            fw.write(" 2>&1\n");
            fw.close();

            new java.io.File(scriptPath).setExecutable(true);

            // Execute with timeout
            ProcessBuilder pb = new ProcessBuilder(
                    "/data/data/com.termux/files/usr/bin/bash", scriptPath);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Read output
            StringBuilder output = new StringBuilder();
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
                if (cancelled) {
                    process.destroyForcibly();
                    return result;
                }
            }

            boolean finished = process.waitFor(300, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                Log.w(TAG, "Phone model timed out");
            }

            String fullOutput = output.toString();
            Log.i(TAG, "Phone model output length: " + fullOutput.length());

            // Extract finish message
            result.finishMessage = extractFinishMessage(fullOutput);

            // Collect Note screenshots
            if (ssDir.exists() && ssDir.listFiles() != null) {
                java.io.File[] files = ssDir.listFiles();
                java.util.Arrays.sort(files);
                for (java.io.File f : files) {
                    if (f.getName().endsWith(".png")) {
                        byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                        result.screenshots.add(Base64.encodeToString(bytes, Base64.NO_WRAP));
                    }
                }
            }

            Log.i(TAG, "Collected " + result.screenshots.size() + " Note screenshots");

        } catch (Exception e) {
            Log.e(TAG, "Phone model call error", e);
        }

        return result;
    }

    private String extractFinishMessage(String output) {
        // Look for the finish message in AutoGLM output
        // Format: "✅ 任务完成: ..." or "Result: ..."
        String[] lines = output.split("\n");
        StringBuilder finishMsg = new StringBuilder();
        boolean capturing = false;

        for (String line : lines) {
            if (line.contains("✅ 任务完成:") || line.contains("Result:")) {
                capturing = true;
                String msg = line;
                if (msg.contains("✅ 任务完成:")) {
                    msg = msg.substring(msg.indexOf("✅ 任务完成:") + "✅ 任务完成:".length()).trim();
                } else if (msg.contains("Result:")) {
                    msg = msg.substring(msg.indexOf("Result:") + "Result:".length()).trim();
                }
                finishMsg.append(msg);
            } else if (capturing) {
                if (line.contains("=====") || line.trim().isEmpty()) {
                    if (finishMsg.length() > 0) break;
                } else {
                    finishMsg.append("\n").append(line);
                }
            }
        }

        String msg = finishMsg.toString().trim();
        return msg.isEmpty() ? null : msg;
    }

    // --- Planner model calls ---

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

    private TaskState safeExit(TaskState state, String reason) {
        svc.performGlobalAction(GLOBAL_ACTION_HOME);
        state.status = "error";
        state.result = reason;
        state.currentPhase = "error";
        return state;
    }

    private String buildAppListString(JSONArray apps) {
        StringBuilder sb = new StringBuilder();
        try {
            for (int i = 0; i < apps.length(); i++) {
                JSONObject app = apps.getJSONObject(i);
                sb.append(app.getString("name")).append(" (").append(app.getString("package")).append(")\n");
            }
        } catch (Exception e) {
            // ignore
        }
        return sb.toString();
    }

    private String buildInitialPrompt(String task, String appList) {
        return "【用户任务】\n" + task + "\n\n"
                + "【手机已安装的应用】\n" + appList + "\n"
                + "【屏幕分辨率】" + svc.getScreenSize().optInt("width", 1080)
                + " x " + svc.getScreenSize().optInt("height", 2400) + "\n\n"
                + "请分析这个任务，输出你的执行计划。格式要求：\n"
                + "1. 先用自然语言分析任务策略\n"
                + "2. 然后输出一个JSON块（用```json包裹），包含：\n"
                + "   - max_rounds: 预计最多需要几轮（整数）\n"
                + "   - allow_phone_model_switch_app: 是否允许手机模型自由切换应用（true/false）\n"
                + "   - first_instruction: 第一轮给手机模型的详细指令（字符串）\n\n"
                + "注意：手机模型是一个能看屏幕、能操作手机的AI，但知识有限。\n"
                + "你给它的指令要像教一个会用手机但不太聪明的人一样，步骤要详细具体。\n"
                + "手机模型支持 Note 动作来标记关键页面（会触发截图），你可以在指令中要求它在特定页面使用 Note。";
    }

    private JSONObject parsePlan(String response) {
        try {
            // Find JSON block in response
            int jsonStart = response.indexOf("```json");
            int jsonEnd = response.indexOf("```", jsonStart + 7);
            if (jsonStart >= 0 && jsonEnd > jsonStart) {
                String jsonStr = response.substring(jsonStart + 7, jsonEnd).trim();
                return new JSONObject(jsonStr);
            }

            // Try finding raw JSON object
            int braceStart = response.indexOf('{');
            int braceEnd = response.lastIndexOf('}');
            if (braceStart >= 0 && braceEnd > braceStart) {
                String jsonStr = response.substring(braceStart, braceEnd + 1);
                return new JSONObject(jsonStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse plan JSON", e);
        }

        // Fallback: create a simple single-pass plan
        try {
            JSONObject fallback = new JSONObject();
            fallback.put("max_rounds", 3);
            fallback.put("allow_phone_model_switch_app", true);
            fallback.put("first_instruction", response); // Use entire response as instruction
            return fallback;
        } catch (Exception e) {
            return null;
        }
    }

    private String getNextInstruction(JSONArray conversation) {
        // The last assistant message should contain the next instruction
        // Look for patterns like "【下一轮指令】" or "next_instruction"
        try {
            for (int i = conversation.length() - 1; i >= 0; i--) {
                JSONObject msg = conversation.getJSONObject(i);
                if ("assistant".equals(msg.optString("role"))) {
                    String content = msg.optString("content", "");

                    // Check for explicit next instruction marker
                    if (content.contains("【下一轮指令】")) {
                        int start = content.indexOf("【下一轮指令】") + "【下一轮指令】".length();
                        return content.substring(start).trim();
                    }

                    // Check for JSON with next_instruction
                    try {
                        int jsonStart = content.indexOf("```json");
                        int jsonEnd = content.indexOf("```", jsonStart + 7);
                        if (jsonStart >= 0 && jsonEnd > jsonStart) {
                            JSONObject json = new JSONObject(content.substring(jsonStart + 7, jsonEnd).trim());
                            String nextInstr = json.optString("next_instruction", "");
                            if (!nextInstr.isEmpty()) return nextInstr;
                        }
                    } catch (Exception ignored) {}

                    // If agent didn't provide explicit instruction, it means task is done
                    return null;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting next instruction", e);
        }
        return null;
    }

    private boolean isTaskComplete(String analysis) {
        // Check if agent indicates task is complete
        String lower = analysis.toLowerCase();
        return (lower.contains("【最终结论】") || lower.contains("【任务完成】")
                || lower.contains("最终结论") || lower.contains("任务已完成"))
                && !lower.contains("【下一轮指令】") && !lower.contains("next_instruction");
    }

    private String extractFinalResult(String analysis) {
        // Extract the conclusion part
        if (analysis.contains("【最终结论】")) {
            return analysis.substring(analysis.indexOf("【最终结论】") + "【最终结论】".length()).trim();
        }
        if (analysis.contains("【任务完成】")) {
            return analysis.substring(analysis.indexOf("【任务完成】") + "【任务完成】".length()).trim();
        }
        return analysis;
    }
}