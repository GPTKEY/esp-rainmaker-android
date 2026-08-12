package com.espressif.utils;

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
