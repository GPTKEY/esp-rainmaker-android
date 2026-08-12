package com.espressif.ui.activities;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;

import com.espressif.rainmaker.R;
import com.espressif.utils.ProvisioningLog;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;

/** 显示当前 App 进程内的 BLE / Claim / Wi-Fi / 节点添加配网日志。 */
public class ProvisioningLogActivity extends AppCompatActivity implements ProvisioningLog.Listener {

    private TextView logText;
    private ScrollView logScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_provisioning_log);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setDisplayShowHomeEnabled(true);
        }
        toolbar.setTitle(R.string.title_activity_logs);
        toolbar.setNavigationIcon(AppCompatResources.getDrawable(this, R.drawable.ic_arrow_left));
        toolbar.setNavigationOnClickListener(v -> finish());

        logText = findViewById(R.id.tv_provisioning_log);
        logScroll = findViewById(R.id.scroll_provisioning_log);
        MaterialButton copyButton = findViewById(R.id.btn_copy_log);
        MaterialButton clearButton = findViewById(R.id.btn_clear_log);

        copyButton.setOnClickListener(v -> copyLogs());
        clearButton.setOnClickListener(v -> ProvisioningLog.clear());
        refreshAllLogs();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ProvisioningLog.addListener(this);
        refreshAllLogs();
    }

    @Override
    protected void onStop() {
        ProvisioningLog.removeListener(this);
        super.onStop();
    }

    private void refreshAllLogs() {
        String text = ProvisioningLog.snapshotText();
        if (text.isEmpty()) {
            text = getString(R.string.logs_empty);
        }
        logText.setText(text);
        scrollToBottom();
    }

    private void copyLogs() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("ESP RainMaker logs", ProvisioningLog.snapshotText()));
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
    }

    private void scrollToBottom() {
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    public void onLogAdded(final String line) {
        runOnUiThread(() -> {
            CharSequence current = logText.getText();
            if (current.length() == 0 || getString(R.string.logs_empty).contentEquals(current)) {
                logText.setText(line + "\n");
            } else {
                logText.append(line + "\n");
            }
            scrollToBottom();
        });
    }

    @Override
    public void onLogReset() {
        runOnUiThread(() -> logText.setText(""));
    }
}
