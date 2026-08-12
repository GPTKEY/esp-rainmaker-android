#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, text: str) -> None:
    (ROOT / path).write_text(text, encoding="utf-8")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one anchor, found {count}")
    return text.replace(old, new, 1)


def patch_dashboard() -> None:
    path = "app/src/main/java/com/espressif/ui/activities/MainControllerDashboardActivity.kt"
    text = read(path)
    if "import androidx.compose.foundation.layout.ColumnScope" not in text:
        text = replace_once(
            text,
            "import androidx.compose.foundation.layout.Column\n",
            "import androidx.compose.foundation.layout.Column\nimport androidx.compose.foundation.layout.ColumnScope\n",
            "ColumnScope import",
        )
    text = text.replace(
        "private fun DashboardCard(content: @Composable Column.() -> Unit)",
        "private fun DashboardCard(content: @Composable ColumnScope.() -> Unit)",
    )
    write(path, text)


def patch_adapter() -> None:
    path = "app/src/main/java/com/espressif/ui/adapters/EspDeviceAdapter.java"
    text = read(path)
    if "MainControllerDashboardActivity" not in text:
        text = replace_once(
            text,
            "import com.espressif.ui.activities.EspDeviceActivity;\n",
            "import com.espressif.ui.activities.EspDeviceActivity;\nimport com.espressif.ui.activities.MainControllerDashboardActivity;\n",
            "dashboard activity import",
        )

    old = '''                } else {
                    Intent intent = new Intent(context, EspDeviceActivity.class);
                    intent.putExtra(AppConstants.KEY_ESP_DEVICE, device);
                    context.startActivity(intent);
                }
'''
    new = '''                } else {
                    EspNode currentNode = espApp.nodeMap.get(device.getNodeId());
                    Class<?> targetActivity = MainControllerDashboardActivity.isMainControllerDevice(device, currentNode)
                            ? MainControllerDashboardActivity.class
                            : EspDeviceActivity.class;
                    Intent intent = new Intent(context, targetActivity);
                    intent.putExtra(AppConstants.KEY_ESP_DEVICE, device);
                    context.startActivity(intent);
                }
'''
    if "Class<?> targetActivity = MainControllerDashboardActivity.isMainControllerDevice" not in text:
        text = replace_once(text, old, new, "device page route")
    write(path, text)


def patch_manifest() -> None:
    path = "app/src/main/AndroidManifest.xml"
    text = read(path)
    if "MainControllerDashboardActivity" in text:
        return
    anchor = '''        <activity
            android:name="com.espressif.ui.activities.EspDeviceActivity"
            android:label="@string/title_activity_esp_device"
            android:screenOrientation="portrait"
            android:theme="@style/AppTheme.NoActionBar" />
'''
    addition = anchor + '''        <activity
            android:name="com.espressif.ui.activities.MainControllerDashboardActivity"
            android:label="主控制器"
            android:screenOrientation="portrait"
            android:theme="@style/AppTheme.NoActionBar" />
'''
    text = replace_once(text, anchor, addition, "manifest dashboard activity")
    write(path, text)


def main() -> None:
    patch_dashboard()
    patch_adapter()
    patch_manifest()
    print("Main controller dashboard routing applied")


if __name__ == "__main__":
    main()
