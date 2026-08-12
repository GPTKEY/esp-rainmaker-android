#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(rel):
    return (ROOT / rel).read_text(encoding='utf-8')


def write(rel, text):
    (ROOT / rel).write_text(text, encoding='utf-8')


def add_import(text, line):
    if line in text:
        return text
    marker = '\n\nimport '
    pos = text.find(marker)
    if pos < 0:
        raise RuntimeError(f'cannot insert import: {line}')
    return text[:pos+2] + line + '\n' + text[pos+2:]


# Exact localized Toast text -> ProvisioningLog
(ROOT / 'app/src/main/java/com/espressif/utils/ProvisioningToast.java').write_text(r'''package com.espressif.utils;

import android.content.Context;
import android.widget.Toast;

/**
 * 配网流程 Toast 代理。
 * 保留原 Toast 行为，同时把用户实际看到的本地化文字写入本次进程内日志。
 */
public final class ProvisioningToast {
    private ProvisioningToast() {}

    public static Toast makeText(Context context, int resId, int duration) {
        CharSequence text;
        try {
            text = context.getText(resId);
        } catch (Exception e) {
            text = "<resource:" + resId + ">";
        }
        ProvisioningLog.uiNotice(context, "Toast", text);
        return Toast.makeText(context, resId, duration);
    }

    public static Toast makeText(Context context, CharSequence text, int duration) {
        ProvisioningLog.uiNotice(context, "Toast", text);
        return Toast.makeText(context, text, duration);
    }
}
''', encoding='utf-8')

# Exact AlertDialog title/message/button labels -> ProvisioningLog
(ROOT / 'app/src/main/java/com/espressif/utils/ProvisioningAlertDialogBuilder.java').write_text(r'''package com.espressif.utils;

import android.content.Context;
import android.content.DialogInterface;

import androidx.appcompat.app.AlertDialog;

/**
 * 配网流程 AlertDialog.Builder 代理。
 * 只记录标题、正文和按钮文案，不读取 EditText 等对话框输入内容。
 */
public class ProvisioningAlertDialogBuilder extends AlertDialog.Builder {
    private final Context context;
    private CharSequence title;
    private CharSequence message;
    private CharSequence positive;
    private CharSequence negative;
    private CharSequence neutral;
    private boolean logged;

    public ProvisioningAlertDialogBuilder(Context context) {
        super(context);
        this.context = context;
    }

    public ProvisioningAlertDialogBuilder(Context context, int themeResId) {
        super(context, themeResId);
        this.context = context;
    }

    private CharSequence resolve(int resId) {
        try {
            return context.getText(resId);
        } catch (Exception e) {
            return "<resource:" + resId + ">";
        }
    }

    @Override
    public AlertDialog.Builder setTitle(int titleId) {
        title = resolve(titleId);
        return super.setTitle(titleId);
    }

    @Override
    public AlertDialog.Builder setTitle(CharSequence title) {
        this.title = title;
        return super.setTitle(title);
    }

    @Override
    public AlertDialog.Builder setMessage(int messageId) {
        message = resolve(messageId);
        return super.setMessage(messageId);
    }

    @Override
    public AlertDialog.Builder setMessage(CharSequence message) {
        this.message = message;
        return super.setMessage(message);
    }

    @Override
    public AlertDialog.Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) {
        positive = resolve(textId);
        return super.setPositiveButton(textId, listener);
    }

    @Override
    public AlertDialog.Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
        positive = text;
        return super.setPositiveButton(text, listener);
    }

    @Override
    public AlertDialog.Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) {
        negative = resolve(textId);
        return super.setNegativeButton(textId, listener);
    }

    @Override
    public AlertDialog.Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
        negative = text;
        return super.setNegativeButton(text, listener);
    }

    @Override
    public AlertDialog.Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) {
        neutral = resolve(textId);
        return super.setNeutralButton(textId, listener);
    }

    @Override
    public AlertDialog.Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
        neutral = text;
        return super.setNeutralButton(text, listener);
    }

    private void logOnce() {
        if (logged) return;
        logged = true;
        StringBuilder line = new StringBuilder("[UI通知][对话框]");
        if (title != null && title.length() > 0) line.append(" 标题=").append(title);
        if (message != null && message.length() > 0) line.append(" 内容=").append(message);
        if (positive != null && positive.length() > 0) line.append(" 确认=").append(positive);
        if (negative != null && negative.length() > 0) line.append(" 取消=").append(negative);
        if (neutral != null && neutral.length() > 0) line.append(" 其他=").append(neutral);
        ProvisioningLog.uiNotice("Dialog", line);
    }

    @Override
    public AlertDialog create() {
        logOnce();
        return super.create();
    }
}
''', encoding='utf-8')

# Only pairing/provisioning screens are instrumented, not the whole application.
files = [
    'app/src/main/java/com/espressif/ui/activities/BLEProvisionLanding.java',
    'app/src/main/java/com/espressif/ui/activities/ProofOfPossessionActivity.java',
    'app/src/main/java/com/espressif/ui/activities/ClaimingActivity.java',
    'app/src/main/java/com/espressif/ui/activities/WiFiScanActivity.java',
    'app/src/main/java/com/espressif/ui/activities/WiFiConfigActivity.java',
    'app/src/main/java/com/espressif/ui/activities/ProvisionActivity.java',
    'app/src/main/java/com/espressif/ui/activities/BleWifiProvisionActivity.java',
]

for rel in files:
    text = read(rel)
    if 'Toast.makeText(' in text:
        text = add_import(text, 'import com.espressif.utils.ProvisioningToast;')
        text = text.replace('Toast.makeText(', 'ProvisioningToast.makeText(')
    if 'new AlertDialog.Builder(' in text:
        text = add_import(text, 'import com.espressif.utils.ProvisioningAlertDialogBuilder;')
        text = text.replace('new AlertDialog.Builder(', 'new ProvisioningAlertDialogBuilder(')
    write(rel, text)

# ExistingWifiReuseHelper lives in com.espressif.utils, so no import is needed.
rel = 'app/src/main/java/com/espressif/utils/ExistingWifiReuseHelper.java'
text = read(rel)
text = text.replace('new AlertDialog.Builder(', 'new ProvisioningAlertDialogBuilder(')
write(rel, text)

print('Provisioning Toast/dialog notification capture applied.')
