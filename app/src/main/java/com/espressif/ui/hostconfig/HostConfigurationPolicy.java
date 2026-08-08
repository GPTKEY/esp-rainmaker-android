// Copyright 2026 Espressif Systems (Shanghai) PTE LTD
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.espressif.ui.hostconfig;

import com.espressif.AppConstants;
import com.espressif.ui.models.Device;
import com.espressif.ui.models.Param;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.Map;

/**
 * 主机配置同步的 Android 侧协议策略。
 *
 * <p>职责边界：</p>
 * <ul>
 *     <li>只解析 RainMaker 已经下发到 {@link Device}/{@link Param} 的稳定产品参数；</li>
 *     <li>根据 CloudOnline + RemoteControlEnabled 计算“当前 UI 是否允许发起写请求”；</li>
 *     <li>按当前另一阈值收紧 Low/High Slider 的有效范围；</li>
 *     <li>提供写请求预检函数，供后续所有写入口统一接入；</li>
 *     <li>不保存业务状态、不执行泵控制、不替代主机 AppCore 的最终安全校验。</li>
 * </ul>
 *
 * <p>主机仍然是唯一权威。本类只负责 Android 侧的体验门禁和明显非法请求拦截，
 * 任意允许通过的请求仍可能被主机拒绝。</p>
 */
public final class HostConfigurationPolicy {

    /** Host Controller：设备端是否允许远程控制，只读，手机端绝不能写。 */
    public static final String PARAM_REMOTE_CONTROL_ENABLED = "RemoteControlEnabled";
    /** Host Controller：主机当前云连接状态，只读。 */
    public static final String PARAM_CLOUD_ONLINE = "CloudOnline";
    /** Host Controller：系统工作模式，稳定整数协议。 */
    public static final String PARAM_WORK_MODE = "WorkMode";
    /** Host Controller：主机本机输出状态。 */
    public static final String PARAM_OUTPUT_STATE = "OutputState";
    /** Liquid Monitor：液位低阈值。 */
    public static final String PARAM_LOW_THRESHOLD = "LowThreshold";
    /** Liquid Monitor：液位高阈值。 */
    public static final String PARAM_HIGH_THRESHOLD = "HighThreshold";

    /*
     * 工作模式协议值来自主机 app_state.h，属于已经发布的稳定枚举，禁止在 Android 端重排。
     * 0 为 UNKNOWN，不允许作为手机主动配置目标。
     */
    public static final int WORK_MODE_FILL = 1;
    public static final int WORK_MODE_DRAIN = 2;
    public static final int WORK_MODE_TIMER = 3;
    public static final int WORK_MODE_MANUAL = 4;

    private HostConfigurationPolicy() {
        // 工具类禁止实例化。
    }

    /**
     * 主机配置模型的只读快照。
     *
     * <p>该结构不拥有 Param，只复制本次判断需要的标量值，因此不会与 RainMaker 状态形成第二份
     * 可写业务真值。每次 UI 绑定/写请求都应从当前 Device/Param 重新生成快照。</p>
     */
    public static final class Snapshot {
        public final boolean hostModel;
        public final boolean cloudOnlineKnown;
        public final boolean cloudOnline;
        public final boolean remoteControlKnown;
        public final boolean remoteControlEnabled;
        public final boolean workModeKnown;
        public final int workMode;

        private Snapshot(boolean hostModel,
                         boolean cloudOnlineKnown,
                         boolean cloudOnline,
                         boolean remoteControlKnown,
                         boolean remoteControlEnabled,
                         boolean workModeKnown,
                         int workMode) {
            this.hostModel = hostModel;
            this.cloudOnlineKnown = cloudOnlineKnown;
            this.cloudOnline = cloudOnline;
            this.remoteControlKnown = remoteControlKnown;
            this.remoteControlEnabled = remoteControlEnabled;
            this.workModeKnown = workModeKnown;
            this.workMode = workMode;
        }

        /**
         * Android 侧“可以尝试远程写”的派生状态。
         *
         * <p>CloudOnline 或 RemoteControlEnabled 任一缺失时采用 fail-closed，避免旧缓存或模型不完整
         * 时误开放写入口。主机端仍会再次进行权限判断。</p>
         */
        public boolean isControlAvailable() {
            return hostModel
                    && cloudOnlineKnown
                    && cloudOnline
                    && remoteControlKnown
                    && remoteControlEnabled;
        }
    }

    /** Android 本地请求预检结果。 */
    public enum BlockReason {
        NONE,
        PARAM_NOT_FOUND,
        PARAM_READ_ONLY,
        REMOTE_CONTROL_DISABLED,
        CLOUD_OFFLINE,
        HOST_STATE_INCOMPLETE,
        OUTPUT_REQUIRES_MANUAL_MODE,
        INVALID_WORK_MODE,
        INVALID_THRESHOLDS,
        MALFORMED_REQUEST
    }

    /**
     * 写请求预检结果。
     *
     * <p>paramName 用于日志定位，不作为用户可编辑字段。reason==NONE 时 allowed=true。</p>
     */
    public static final class WriteDecision {
        public final boolean allowed;
        public final BlockReason reason;
        public final String paramName;

        private WriteDecision(boolean allowed, BlockReason reason, String paramName) {
            this.allowed = allowed;
            this.reason = reason;
            this.paramName = paramName;
        }

        public static WriteDecision allow() {
            return new WriteDecision(true, BlockReason.NONE, null);
        }

        public static WriteDecision block(BlockReason reason, String paramName) {
            return new WriteDecision(false, reason, paramName);
        }
    }

    /**
     * 从整个 RainMaker Node 的 Device 列表提取主机权限/模式状态。
     *
     * <p>RemoteControlEnabled 可能位于 Host Controller，而阈值位于 Liquid Monitor，因此不能只扫描
     * 当前设备页的 params。</p>
     */
    public static Snapshot snapshot(ArrayList<Device> devices) {
        if (devices == null) {
            return new Snapshot(false, false, false, false, false, false, 0);
        }

        Param remoteControl = findParam(devices, PARAM_REMOTE_CONTROL_ENABLED);
        Param cloudOnline = findParam(devices, PARAM_CLOUD_ONLINE);
        Param workMode = findParam(devices, PARAM_WORK_MODE);

        boolean hostModel = remoteControl != null;
        boolean remoteKnown = remoteControl != null;
        boolean cloudKnown = cloudOnline != null;
        boolean workModeKnown = workMode != null;

        return new Snapshot(
                hostModel,
                cloudKnown,
                readBoolean(cloudOnline),
                remoteKnown,
                readBoolean(remoteControl),
                workModeKnown,
                workModeKnown ? safeIntegerValue(workMode) : 0
        );
    }

    /**
     * 把主机状态投影到 Param 的“有效写权限 / 有效阈值编辑范围”。
     *
     * <p>所有投影均为可逆的临时字段，不删除 RainMaker 原始 properties、bounds。
     * 非主机模型会清空投影并完全保持上游行为。</p>
     */
    public static void applyEffectiveWriteGate(ArrayList<Device> devices) {
        if (devices == null) {
            return;
        }

        Snapshot snapshot = snapshot(devices);
        Param currentLowParam = findParam(devices, PARAM_LOW_THRESHOLD);
        Param currentHighParam = findParam(devices, PARAM_HIGH_THRESHOLD);
        boolean thresholdModelComplete = currentLowParam != null && currentHighParam != null;
        int currentLow = thresholdModelComplete ? safeIntegerValue(currentLowParam) : 0;
        int currentHigh = thresholdModelComplete ? safeIntegerValue(currentHighParam) : 0;
        boolean thresholdPairValid = thresholdModelComplete && isValidThresholdPair(currentLow, currentHigh);

        for (Device device : devices) {
            if (device == null || device.getParams() == null) {
                continue;
            }
            for (Param param : device.getParams()) {
                if (param == null) {
                    continue;
                }

                // 每次从当前权威状态重新投影，先清理上一次阈值 bounds 覆盖。
                param.setHostBoundsOverride(false, 0, 0);

                if (!snapshot.hostModel) {
                    param.setHostWriteGate(false, true);
                    continue;
                }

                boolean allowed = snapshot.isControlAvailable();
                if (PARAM_REMOTE_CONTROL_ENABLED.equals(param.getName())) {
                    // 本参数是设备端授权状态，Android 永远只读。
                    allowed = false;
                } else if (PARAM_OUTPUT_STATE.equals(param.getName())) {
                    // OutputState 只有 MANUAL 模式允许手机主动操作。
                    allowed = allowed
                            && snapshot.workModeKnown
                            && snapshot.workMode == WORK_MODE_MANUAL;
                } else if (PARAM_LOW_THRESHOLD.equals(param.getName())) {
                    if (thresholdPairValid) {
                        int effectiveMin = Math.max(param.getBaseMinBounds(), 0);
                        int effectiveMax = Math.min(param.getBaseMaxBounds(), currentHigh - 1);
                        if (effectiveMin <= effectiveMax) {
                            param.setHostBoundsOverride(true, effectiveMin, effectiveMax);
                        } else {
                            allowed = false;
                        }
                    } else {
                        // 当前权威阈值本身非法或缺失时，Android 先只读，等待主机纠正/重新上报。
                        allowed = false;
                    }
                } else if (PARAM_HIGH_THRESHOLD.equals(param.getName())) {
                    if (thresholdPairValid) {
                        int effectiveMin = Math.max(param.getBaseMinBounds(), currentLow + 1);
                        int effectiveMax = Math.min(param.getBaseMaxBounds(), 100);
                        if (effectiveMin <= effectiveMax) {
                            param.setHostBoundsOverride(true, effectiveMin, effectiveMax);
                        } else {
                            allowed = false;
                        }
                    } else {
                        allowed = false;
                    }
                }

                // 仅对协议本身声明为可写的 Param 隐藏 WRITE；只读 Param 继续保持只读。
                if (param.hasBaseProperty(AppConstants.KEY_PROPERTY_WRITE)
                        || PARAM_REMOTE_CONTROL_ENABLED.equals(param.getName())) {
                    param.setHostWriteGate(true, allowed);
                } else {
                    param.setHostWriteGate(false, true);
                }
            }
        }
    }

    /**
     * Android 发送 RainMaker Param 写请求前的统一预检。
     *
     * <p>body 格式沿用现有 SDK：{ "DeviceName": { "Param": value } }。
     * 本函数不修改 body，也不会更新任何业务状态。</p>
     */
    public static WriteDecision evaluateWriteRequest(ArrayList<Device> devices, JsonObject body) {
        if (body == null || body.entrySet().isEmpty()) {
            return WriteDecision.block(BlockReason.MALFORMED_REQUEST, null);
        }

        Snapshot snapshot = snapshot(devices);
        if (!snapshot.hostModel) {
            // 非本项目主机模型完全沿用 RainMaker 原行为。
            return WriteDecision.allow();
        }

        if (!snapshot.cloudOnlineKnown || !snapshot.remoteControlKnown) {
            return WriteDecision.block(BlockReason.HOST_STATE_INCOMPLETE, null);
        }
        if (!snapshot.cloudOnline) {
            return WriteDecision.block(BlockReason.CLOUD_OFFLINE, null);
        }
        if (!snapshot.remoteControlEnabled) {
            return WriteDecision.block(BlockReason.REMOTE_CONTROL_DISABLED, null);
        }

        Integer requestedLow = null;
        Integer requestedHigh = null;

        for (Map.Entry<String, JsonElement> deviceEntry : body.entrySet()) {
            Device targetDevice = findDevice(devices, deviceEntry.getKey());
            if (targetDevice == null || deviceEntry.getValue() == null
                    || !deviceEntry.getValue().isJsonObject()) {
                return WriteDecision.block(BlockReason.MALFORMED_REQUEST, null);
            }

            JsonObject paramObject = deviceEntry.getValue().getAsJsonObject();
            for (Map.Entry<String, JsonElement> paramEntry : paramObject.entrySet()) {
                String paramName = paramEntry.getKey();
                Param targetParam = findParam(targetDevice, paramName);
                if (targetParam == null) {
                    return WriteDecision.block(BlockReason.PARAM_NOT_FOUND, paramName);
                }

                if (PARAM_REMOTE_CONTROL_ENABLED.equals(paramName)
                        || !targetParam.hasBaseProperty(AppConstants.KEY_PROPERTY_WRITE)) {
                    return WriteDecision.block(BlockReason.PARAM_READ_ONLY, paramName);
                }

                if (PARAM_OUTPUT_STATE.equals(paramName)
                        && (!snapshot.workModeKnown || snapshot.workMode != WORK_MODE_MANUAL)) {
                    return WriteDecision.block(BlockReason.OUTPUT_REQUIRES_MANUAL_MODE, paramName);
                }

                if (PARAM_WORK_MODE.equals(paramName)) {
                    Integer requestedMode = jsonInteger(paramEntry.getValue());
                    if (requestedMode == null || !isSupportedWorkMode(requestedMode)) {
                        return WriteDecision.block(BlockReason.INVALID_WORK_MODE, paramName);
                    }
                } else if (PARAM_LOW_THRESHOLD.equals(paramName)) {
                    requestedLow = jsonInteger(paramEntry.getValue());
                    if (requestedLow == null) {
                        return WriteDecision.block(BlockReason.INVALID_THRESHOLDS, paramName);
                    }
                } else if (PARAM_HIGH_THRESHOLD.equals(paramName)) {
                    requestedHigh = jsonInteger(paramEntry.getValue());
                    if (requestedHigh == null) {
                        return WriteDecision.block(BlockReason.INVALID_THRESHOLDS, paramName);
                    }
                }
            }
        }

        if (requestedLow != null || requestedHigh != null) {
            Param currentLowParam = findParam(devices, PARAM_LOW_THRESHOLD);
            Param currentHighParam = findParam(devices, PARAM_HIGH_THRESHOLD);
            if (currentLowParam == null || currentHighParam == null) {
                return WriteDecision.block(BlockReason.HOST_STATE_INCOMPLETE,
                        requestedLow != null ? PARAM_LOW_THRESHOLD : PARAM_HIGH_THRESHOLD);
            }

            int low = requestedLow != null ? requestedLow : safeIntegerValue(currentLowParam);
            int high = requestedHigh != null ? requestedHigh : safeIntegerValue(currentHighParam);
            if (!isValidThresholdPair(low, high)) {
                return WriteDecision.block(BlockReason.INVALID_THRESHOLDS,
                        requestedLow != null ? PARAM_LOW_THRESHOLD : PARAM_HIGH_THRESHOLD);
            }
        }

        return WriteDecision.allow();
    }

    /** 阈值属于成对配置；continuous update 的中间值由 DeviceParamUpdates 屏蔽。 */
    public static boolean isThresholdParam(String paramName) {
        return PARAM_LOW_THRESHOLD.equals(paramName) || PARAM_HIGH_THRESHOLD.equals(paramName);
    }

    /** 主机已发布的 WorkMode 有效主动配置值。 */
    public static boolean isSupportedWorkMode(int value) {
        return value >= WORK_MODE_FILL && value <= WORK_MODE_MANUAL;
    }

    /** 主机和 Android 共同遵守的液位阈值基本关系。 */
    public static boolean isValidThresholdPair(int low, int high) {
        return low >= 0 && high <= 100 && low < high;
    }

    public static Param findParam(ArrayList<Device> devices, String paramName) {
        if (devices == null || isEmpty(paramName)) {
            return null;
        }
        for (Device device : devices) {
            Param param = findParam(device, paramName);
            if (param != null) {
                return param;
            }
        }
        return null;
    }

    private static Device findDevice(ArrayList<Device> devices, String deviceName) {
        if (devices == null || isEmpty(deviceName)) {
            return null;
        }
        for (Device device : devices) {
            if (device != null && deviceName.equals(device.getDeviceName())) {
                return device;
            }
        }
        return null;
    }

    private static Param findParam(Device device, String paramName) {
        if (device == null || device.getParams() == null || isEmpty(paramName)) {
            return null;
        }
        for (Param param : device.getParams()) {
            if (param != null && paramName.equals(param.getName())) {
                return param;
            }
        }
        return null;
    }

    /**
     * Boolean Param 的 switchStatus 是现有解析链的规范值；优先使用它，避免 BLE 刷新只更新
     * switchStatus 时被历史 labelValue 覆盖。非 Boolean 参数才回退解析 labelValue。
     */
    private static boolean readBoolean(Param param) {
        if (param == null) {
            return false;
        }
        String dataType = param.getDataType();
        if ("bool".equalsIgnoreCase(dataType) || "boolean".equalsIgnoreCase(dataType)) {
            return param.getSwitchStatus();
        }
        String label = param.getLabelValue();
        if (!isEmpty(label)) {
            if ("true".equalsIgnoreCase(label) || "1".equals(label)) {
                return true;
            }
            if ("false".equalsIgnoreCase(label) || "0".equals(label)) {
                return false;
            }
        }
        return param.getSwitchStatus();
    }

    private static int safeIntegerValue(Param param) {
        if (param == null || Double.isNaN(param.getValue()) || Double.isInfinite(param.getValue())) {
            return 0;
        }
        double value = param.getValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            return 0;
        }
        return (int) value;
    }

    private static Integer jsonInteger(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) {
            return null;
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        if (!primitive.isNumber()) {
            return null;
        }
        try {
            double value = primitive.getAsDouble();
            if (Double.isNaN(value) || Double.isInfinite(value)
                    || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE
                    || value != Math.rint(value)) {
                return null;
            }
            return (int) value;
        } catch (NumberFormatException | UnsupportedOperationException ex) {
            return null;
        }
    }

    /** 纯 Java 空串判断，便于 HostConfigurationPolicy 在本地 JVM 单元测试中直接执行。 */
    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}
