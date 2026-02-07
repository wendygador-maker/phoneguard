package com.phoneguard;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private ModelConfigManager configManager;
    private EditText etPhoneUrl, etPhoneKey, etPhoneModel;
    private LinearLayout plannerContainer;
    private final List<PlannerViewHolder> plannerHolders = new ArrayList<>();

    private static class PlannerViewHolder {
        EditText etUrl, etKey, etModel;
        Button btnFetch, btnDelete;
        View root;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        configManager = new ModelConfigManager(this);

        etPhoneUrl = findViewById(R.id.et_phone_url);
        etPhoneKey = findViewById(R.id.et_phone_key);
        etPhoneModel = findViewById(R.id.et_phone_model);
        plannerContainer = findViewById(R.id.planner_container);

        // Load phone model config
        ModelConfigManager.ModelConfig phone = configManager.getPhoneModel();
        etPhoneUrl.setText(phone.url);
        etPhoneKey.setText(phone.key);
        etPhoneModel.setText(phone.model);

        // Phone model fetch button
        findViewById(R.id.btn_phone_fetch).setOnClickListener(v -> {
            String url = etPhoneUrl.getText().toString().trim();
            String key = etPhoneKey.getText().toString().trim();
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "请先填写 URL 和 Key", Toast.LENGTH_SHORT).show();
                return;
            }
            fetchAndSelectModel(url, key, etPhoneModel);
        });

        // Load planner models
        List<ModelConfigManager.ModelConfig> planners = configManager.getPlannerModels();
        if (planners.isEmpty()) {
            addPlannerRow(null);
        } else {
            for (ModelConfigManager.ModelConfig m : planners) {
                addPlannerRow(m);
            }
        }

        // Add planner button
        findViewById(R.id.btn_add_planner).setOnClickListener(v -> addPlannerRow(null));

        // Save button
        findViewById(R.id.btn_save).setOnClickListener(v -> save());

        // Agent prompt
        EditText etAgentPrompt = findViewById(R.id.et_agent_prompt);
        etAgentPrompt.setText(configManager.getAgentSystemPrompt());

        findViewById(R.id.btn_reset_prompt).setOnClickListener(v -> {
            etAgentPrompt.setText(configManager.getDefaultAgentPrompt());
            Toast.makeText(this, "已恢复默认提示词", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btn_save_prompt).setOnClickListener(v -> {
            String prompt = etAgentPrompt.getText().toString().trim();
            if (!prompt.isEmpty()) {
                configManager.saveAgentSystemPrompt(prompt);
                Toast.makeText(this, "提示词已保存", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void addPlannerRow(ModelConfigManager.ModelConfig config) {
        PlannerViewHolder holder = new PlannerViewHolder();
        int index = plannerHolders.size() + 1;

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 0, 0, dpToPx(12));
        holder.root = row;

        // Header with number and delete button
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView label = new TextView(this);
        label.setText("模型 " + index);
        label.setTextSize(14);
        label.setTextColor(0xFF555555);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        label.setLayoutParams(labelParams);
        header.addView(label);

        holder.btnDelete = new Button(this);
        holder.btnDelete.setText("删除");
        holder.btnDelete.setTextSize(12);
        holder.btnDelete.setOnClickListener(v -> removePlannerRow(holder));
        header.addView(holder.btnDelete);
        row.addView(header);

        // URL
        holder.etUrl = createEditText("API URL", "https://api.openai.com/v1");
        row.addView(holder.etUrl);

        // Key
        holder.etKey = createEditText("API Key", "sk-...");
        holder.etKey.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        row.addView(holder.etKey);

        // Model + fetch
        LinearLayout modelRow = new LinearLayout(this);
        modelRow.setOrientation(LinearLayout.HORIZONTAL);

        holder.etModel = createEditText("模型名称", "gpt-4o");
        LinearLayout.LayoutParams modelParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        holder.etModel.setLayoutParams(modelParams);
        modelRow.addView(holder.etModel);

        holder.btnFetch = new Button(this);
        holder.btnFetch.setText("获取列表");
        holder.btnFetch.setTextSize(12);
        holder.btnFetch.setOnClickListener(v -> {
            String url = holder.etUrl.getText().toString().trim();
            String key = holder.etKey.getText().toString().trim();
            if (url.isEmpty() || key.isEmpty()) {
                Toast.makeText(this, "请先填写 URL 和 Key", Toast.LENGTH_SHORT).show();
                return;
            }
            fetchAndSelectModel(url, key, holder.etModel);
        });
        modelRow.addView(holder.btnFetch);
        row.addView(modelRow);

        // Separator
        View sep = new View(this);
        sep.setBackgroundColor(0xFFDDDDDD);
        sep.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)));
        row.addView(sep);

        // Fill data if provided
        if (config != null) {
            holder.etUrl.setText(config.url);
            holder.etKey.setText(config.key);
            holder.etModel.setText(config.model);
        }

        plannerHolders.add(holder);
        plannerContainer.addView(row);
    }

    private void removePlannerRow(PlannerViewHolder holder) {
        plannerContainer.removeView(holder.root);
        plannerHolders.remove(holder);
    }

    private EditText createEditText(String label, String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(44)));
        return et;
    }

    private void save() {
        // Save phone model
        configManager.savePhoneModel(new ModelConfigManager.ModelConfig(
                etPhoneUrl.getText().toString().trim(),
                etPhoneKey.getText().toString().trim(),
                etPhoneModel.getText().toString().trim()
        ));

        // Save planner models
        List<ModelConfigManager.ModelConfig> planners = new ArrayList<>();
        for (PlannerViewHolder h : plannerHolders) {
            String url = h.etUrl.getText().toString().trim();
            String key = h.etKey.getText().toString().trim();
            String model = h.etModel.getText().toString().trim();
            if (!url.isEmpty() && !key.isEmpty() && !model.isEmpty()) {
                planners.add(new ModelConfigManager.ModelConfig(url, key, model));
            }
        }
        configManager.savePlannerModels(planners);

        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void fetchAndSelectModel(String baseUrl, String apiKey, EditText target) {
        Toast.makeText(this, "正在获取模型列表...", Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                List<String> models = ModelClient.fetchModels(baseUrl, apiKey);
                runOnUiThread(() -> {
                    if (models.isEmpty()) {
                        Toast.makeText(this, "未获取到模型，请手动输入", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String[] items = models.toArray(new String[0]);
                    new AlertDialog.Builder(this)
                            .setTitle("选择模型")
                            .setItems(items, (dialog, which) -> target.setText(items[which]))
                            .show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this,
                        "获取失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }
}
