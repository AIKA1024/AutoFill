package com.autofill.sms;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import io.github.libxposed.service.XposedService;

/**
 * 模块设置页。所有开关/规则即时写入 LSPosed 远程配置。
 */
public class MainActivity extends Activity implements App.ServiceStateListener {

    private Switch swEnabled;
    private Switch swAutoCopy;
    private Switch swCopyFull;
    private Switch swAutoFill;
    private Switch swToast;
    private Switch swNotification;
    private Switch swBlock;
    private Switch swRootFill;

    private EditText etRegex;
    private EditText etKeywords;
    private EditText etBlacklist;
    private EditText etTest;

    private TextView tvStatus;
    private TextView tvResult;
    private TextView tvRootHint;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable saveTask = this::saveValues;
    private boolean loading = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        swEnabled = findViewById(R.id.sw_enabled);
        swAutoCopy = findViewById(R.id.sw_auto_copy);
        swCopyFull = findViewById(R.id.sw_copy_full);
        swAutoFill = findViewById(R.id.sw_auto_fill);
        swToast = findViewById(R.id.sw_toast);
        swNotification = findViewById(R.id.sw_notification);
        swBlock = findViewById(R.id.sw_block);
        swRootFill = findViewById(R.id.sw_root_fill);

        etRegex = findViewById(R.id.et_regex);
        etKeywords = findViewById(R.id.et_keywords);
        etBlacklist = findViewById(R.id.et_blacklist);
        etTest = findViewById(R.id.et_test);

        tvStatus = findViewById(R.id.tv_status);
        tvResult = findViewById(R.id.tv_result);
        tvRootHint = findViewById(R.id.tv_root_hint);

        Button btnRegexReset = findViewById(R.id.btn_regex_reset);
        Button btnKeywordsReset = findViewById(R.id.btn_keywords_reset);
        Button btnTest = findViewById(R.id.btn_test);

        loadValues();

        swEnabled.setOnCheckedChangeListener((v, checked) -> onChanged());
        swAutoCopy.setOnCheckedChangeListener((v, checked) -> onChanged());
        swCopyFull.setOnCheckedChangeListener((v, checked) -> onChanged());
        swAutoFill.setOnCheckedChangeListener((v, checked) -> onChanged());
        swToast.setOnCheckedChangeListener((v, checked) -> onChanged());
        swNotification.setOnCheckedChangeListener((v, checked) -> onChanged());
        swBlock.setOnCheckedChangeListener((v, checked) -> onChanged());
        swRootFill.setOnCheckedChangeListener((v, checked) -> onChanged());

        etRegex.addTextChangedListener(watcher);
        etKeywords.addTextChangedListener(watcher);
        etBlacklist.addTextChangedListener(watcher);

        btnRegexReset.setOnClickListener(v -> {
            etRegex.setText(Config.DEF_REGEX);
            onChanged();
        });
        btnKeywordsReset.setOnClickListener(v -> {
            etKeywords.setText(Config.DEF_KEYWORDS);
            onChanged();
        });
        btnTest.setOnClickListener(v -> runTest());

        App.addListener(this, true);

        checkRootAvailability();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚在 root 管理器里授权完返回，重新探测一次，别让界面停留在旧结论
        checkRootAvailability();
    }

    /** 探测本应用能否拿到 root——决定「root 注入」这个开关走不走得通 */
    private void checkRootAvailability() {
        tvRootHint.setText(R.string.root_checking);
        tvRootHint.setTextColor(0xFF666666);
        new Thread(() -> {
            String error = null;
            boolean ok = false;
            try {
                ok = probeRoot();
            } catch (Throwable t) {
                error = String.valueOf(t.getMessage());
            }
            final boolean okFinal = ok;
            final String errFinal = error;
            runOnUiThread(() -> {
                if (errFinal != null) {
                    tvRootHint.setText(getString(R.string.root_probe_error, errFinal));
                    tvRootHint.setTextColor(0xFFD32F2F);
                } else if (okFinal) {
                    tvRootHint.setText(R.string.root_ok);
                    tvRootHint.setTextColor(0xFF2E7D32);
                } else {
                    tvRootHint.setText(R.string.root_missing);
                    tvRootHint.setTextColor(0xFFD32F2F);
                }
            });
        }, "root-probe").start();
    }

    /** @return 本应用能否以 root 身份执行 shell 命令 */
    private static boolean probeRoot() throws Exception {
        Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id -u"});
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line.trim());
            }
        }
        boolean finished = p.waitFor(8, TimeUnit.SECONDS);
        return finished && p.exitValue() == 0 && "0".equals(sb.toString().trim());
    }

    @Override
    protected void onDestroy() {
        App.removeListener(this);
        handler.removeCallbacks(saveTask);
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        saveValues();
        super.onPause();
    }

    private final TextWatcher watcher = new TextWatcher() {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            if (!loading) {
                scheduleSave();
            }
        }
    };

    private void onChanged() {
        if (!loading) {
            scheduleSave();
        }
    }

    private void scheduleSave() {
        handler.removeCallbacks(saveTask);
        handler.postDelayed(saveTask, 400L);
    }

    private void loadValues() {
        loading = true;
        swEnabled.setChecked(ConfigStore.getBoolean(this, Config.KEY_ENABLED, Config.DEF_ENABLED));
        swAutoCopy.setChecked(ConfigStore.getBoolean(this, Config.KEY_AUTO_COPY, Config.DEF_AUTO_COPY));
        swCopyFull.setChecked(ConfigStore.getBoolean(this, Config.KEY_COPY_FULL, Config.DEF_COPY_FULL));
        swAutoFill.setChecked(ConfigStore.getBoolean(this, Config.KEY_AUTO_FILL, Config.DEF_AUTO_FILL));
        swToast.setChecked(ConfigStore.getBoolean(this, Config.KEY_SHOW_TOAST, Config.DEF_SHOW_TOAST));
        swNotification.setChecked(ConfigStore.getBoolean(this, Config.KEY_SHOW_NOTIFICATION, Config.DEF_SHOW_NOTIFICATION));
        swBlock.setChecked(ConfigStore.getBoolean(this, Config.KEY_BLOCK_SMS, Config.DEF_BLOCK_SMS));
        swRootFill.setChecked(ConfigStore.getBoolean(this, Config.KEY_ROOT_FILL, Config.DEF_ROOT_FILL));

        etRegex.setText(ConfigStore.getString(this, Config.KEY_REGEX, Config.DEF_REGEX));
        etKeywords.setText(ConfigStore.getString(this, Config.KEY_KEYWORDS, Config.DEF_KEYWORDS));
        etBlacklist.setText(ConfigStore.getString(this, Config.KEY_BLACKLIST, Config.DEF_BLACKLIST));
        loading = false;
    }

    private void saveValues() {
        ConfigStore.putBoolean(this, Config.KEY_ENABLED, swEnabled.isChecked());
        ConfigStore.putBoolean(this, Config.KEY_AUTO_COPY, swAutoCopy.isChecked());
        ConfigStore.putBoolean(this, Config.KEY_COPY_FULL, swCopyFull.isChecked());
        ConfigStore.putBoolean(this, Config.KEY_AUTO_FILL, swAutoFill.isChecked());
        ConfigStore.putBoolean(this, Config.KEY_SHOW_TOAST, swToast.isChecked());
        ConfigStore.putBoolean(this, Config.KEY_SHOW_NOTIFICATION, swNotification.isChecked());
        ConfigStore.putBoolean(this, Config.KEY_BLOCK_SMS, swBlock.isChecked());
        ConfigStore.putBoolean(this, Config.KEY_ROOT_FILL, swRootFill.isChecked());

        ConfigStore.putString(this, Config.KEY_REGEX, etRegex.getText().toString());
        ConfigStore.putString(this, Config.KEY_KEYWORDS, etKeywords.getText().toString());
        ConfigStore.putString(this, Config.KEY_BLACKLIST, etBlacklist.getText().toString());
    }

    private void runTest() {
        String body = etTest.getText().toString();
        String regex = etRegex.getText().toString();
        String keywords = etKeywords.getText().toString();

        if (body.trim().isEmpty()) {
            tvResult.setText("请先输入一段短信正文");
            return;
        }
        if (!CodeParser.matchKeyword(body, keywords)) {
            tvResult.setText("未命中：短信正文不包含任何关键词");
            return;
        }
        String code = CodeParser.extract(body, regex, keywords);
        tvResult.setText(code == null ? "未命中：正则未匹配到内容" : "提取结果：" + code);
    }

    @Override
    public void onServiceStateChanged(XposedService service) {
        runOnUiThread(this::updateStatus);
        if (service != null) {
            // 框架连上后，以远程配置为准重新加载一次
            runOnUiThread(this::loadValues);
        }
    }

    private void updateStatus() {
        XposedService service = App.getService();
        if (service != null) {
            tvStatus.setText("框架已连接：" + service.getFrameworkName() + " "
                    + service.getFrameworkVersion() + "（API " + service.getApiVersion() + "）\n"
                    + "配置将实时下发给所有被 Hook 的进程。");
        } else {
            tvStatus.setText("未连接 LSPosed 框架。请先在 LSPosed 中启用本模块并重启（或软重启），"
                    + "然后再打开本应用一次；未连接时配置只保存在本地，Hook 进程读不到。");
        }
    }
}
