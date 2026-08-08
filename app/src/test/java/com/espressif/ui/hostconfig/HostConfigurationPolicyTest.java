package com.espressif.ui.hostconfig;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.espressif.AppConstants;
import com.espressif.ui.models.Device;
import com.espressif.ui.models.Param;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * HostConfigurationPolicy 的本地 JVM 单元测试。
 *
 * <p>测试只构造 RainMaker Device/Param 内存模型，不访问网络、数据库、蓝牙或 Android UI，
 * 因此可以快速验证权限门禁、工作模式稳定枚举、OutputState MANUAL 门禁和阈值关联边界。</p>
 */
public class HostConfigurationPolicyTest {

    private static final String HOST_DEVICE = "Host Controller";
    private static final String LIQUID_DEVICE = "Liquid Monitor";

    @Test
    public void nonHostDevice_keepsOriginalWritePermission() {
        Param power = boolParam("Power", true, true);
        ArrayList<Device> devices = new ArrayList<>();
        devices.add(device("Switch", power));

        HostConfigurationPolicy.applyEffectiveWriteGate(devices);

        assertTrue(power.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(power.isHostWriteGateApplied());
    }

    @Test
    public void remoteControlDisabled_makesHostWritesReadOnlyButKeepsStatusVisible() {
        Fixture fixture = hostFixture(false, true, HostConfigurationPolicy.WORK_MODE_MANUAL, 20, 80);

        HostConfigurationPolicy.applyEffectiveWriteGate(fixture.devices);

        assertFalse(fixture.remoteControl.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(fixture.workMode.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(fixture.outputState.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(fixture.lowThreshold.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(fixture.highThreshold.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(HostConfigurationPolicy.snapshot(fixture.devices).isControlAvailable());
    }

    @Test
    public void cloudOffline_makesHostWritesReadOnly() {
        Fixture fixture = hostFixture(true, false, HostConfigurationPolicy.WORK_MODE_MANUAL, 20, 80);

        HostConfigurationPolicy.applyEffectiveWriteGate(fixture.devices);

        assertFalse(fixture.workMode.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(fixture.outputState.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(HostConfigurationPolicy.snapshot(fixture.devices).isControlAvailable());
    }

    @Test
    public void remoteEnabledAndCloudOnline_allowsWorkModeAndThresholds() {
        Fixture fixture = hostFixture(true, true, HostConfigurationPolicy.WORK_MODE_FILL, 20, 80);

        HostConfigurationPolicy.applyEffectiveWriteGate(fixture.devices);

        assertTrue(fixture.workMode.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertTrue(fixture.lowThreshold.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertTrue(fixture.highThreshold.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertTrue(HostConfigurationPolicy.snapshot(fixture.devices).isControlAvailable());
    }

    @Test
    public void outputState_isWritableOnlyInManualMode() {
        Fixture fillFixture = hostFixture(true, true, HostConfigurationPolicy.WORK_MODE_FILL, 20, 80);
        HostConfigurationPolicy.applyEffectiveWriteGate(fillFixture.devices);
        assertFalse(fillFixture.outputState.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));

        Fixture manualFixture = hostFixture(true, true, HostConfigurationPolicy.WORK_MODE_MANUAL, 20, 80);
        HostConfigurationPolicy.applyEffectiveWriteGate(manualFixture.devices);
        assertTrue(manualFixture.outputState.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
    }

    @Test
    public void thresholds_useSingleSubmitTextEditorAndPeerAwareBounds() {
        Fixture fixture = hostFixture(true, true, HostConfigurationPolicy.WORK_MODE_FILL, 20, 80);

        HostConfigurationPolicy.applyEffectiveWriteGate(fixture.devices);

        assertEquals(AppConstants.UI_TYPE_TEXT, fixture.lowThreshold.getUiType());
        assertEquals(AppConstants.UI_TYPE_TEXT, fixture.highThreshold.getUiType());
        assertEquals(0, fixture.lowThreshold.getMinBounds());
        assertEquals(79, fixture.lowThreshold.getMaxBounds());
        assertEquals(21, fixture.highThreshold.getMinBounds());
        assertEquals(100, fixture.highThreshold.getMaxBounds());
    }

    @Test
    public void invalidAuthoritativeThresholdPair_failsClosed() {
        Fixture fixture = hostFixture(true, true, HostConfigurationPolicy.WORK_MODE_FILL, 80, 20);

        HostConfigurationPolicy.applyEffectiveWriteGate(fixture.devices);

        assertFalse(fixture.lowThreshold.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
        assertFalse(fixture.highThreshold.getProperties().contains(AppConstants.KEY_PROPERTY_WRITE));
    }

    @Test
    public void workModeProtocolValues_areStable() {
        assertEquals(1, HostConfigurationPolicy.WORK_MODE_FILL);
        assertEquals(2, HostConfigurationPolicy.WORK_MODE_DRAIN);
        assertEquals(3, HostConfigurationPolicy.WORK_MODE_TIMER);
        assertEquals(4, HostConfigurationPolicy.WORK_MODE_MANUAL);
        assertFalse(HostConfigurationPolicy.isSupportedWorkMode(0));
        assertTrue(HostConfigurationPolicy.isSupportedWorkMode(4));
        assertFalse(HostConfigurationPolicy.isSupportedWorkMode(5));
    }

    @Test
    public void writePreflight_rejectsOutputOutsideManualAndInvalidThreshold() {
        Fixture fixture = hostFixture(true, true, HostConfigurationPolicy.WORK_MODE_FILL, 20, 80);

        JsonObject outputParams = new JsonObject();
        outputParams.addProperty(HostConfigurationPolicy.PARAM_OUTPUT_STATE, true);
        JsonObject outputBody = new JsonObject();
        outputBody.add(HOST_DEVICE, outputParams);

        HostConfigurationPolicy.WriteDecision outputDecision =
                HostConfigurationPolicy.evaluateWriteRequest(fixture.devices, outputBody);
        assertFalse(outputDecision.allowed);
        assertEquals(HostConfigurationPolicy.BlockReason.OUTPUT_REQUIRES_MANUAL_MODE,
                outputDecision.reason);

        JsonObject thresholdParams = new JsonObject();
        thresholdParams.addProperty(HostConfigurationPolicy.PARAM_LOW_THRESHOLD, 90);
        JsonObject thresholdBody = new JsonObject();
        thresholdBody.add(LIQUID_DEVICE, thresholdParams);

        HostConfigurationPolicy.WriteDecision thresholdDecision =
                HostConfigurationPolicy.evaluateWriteRequest(fixture.devices, thresholdBody);
        assertFalse(thresholdDecision.allowed);
        assertEquals(HostConfigurationPolicy.BlockReason.INVALID_THRESHOLDS,
                thresholdDecision.reason);
    }

    @Test
    public void booleanSnapshot_usesSwitchStatusAsCanonicalValue() {
        Fixture fixture = hostFixture(true, true, HostConfigurationPolicy.WORK_MODE_MANUAL, 20, 80);
        // 模拟 BLE 新值只更新 switchStatus，而 labelValue 仍残留旧值。
        fixture.remoteControl.setLabelValue("false");
        fixture.remoteControl.setSwitchStatus(true);

        HostConfigurationPolicy.Snapshot snapshot = HostConfigurationPolicy.snapshot(fixture.devices);

        assertTrue(snapshot.remoteControlEnabled);
        assertTrue(snapshot.isControlAvailable());
    }

    /**
     * 构造一套与主机端第一阶段模型一致的最小测试夹具。
     *
     * <p>Host Controller 保存授权、云状态、工作模式和输出；Liquid Monitor 保存高低阈值。
     * 所有数值范围均来自已确认的主机协议，不在测试中引入额外产品假设。</p>
     */
    private Fixture hostFixture(boolean remoteEnabled,
                                boolean cloudOnline,
                                int workModeValue,
                                int lowValue,
                                int highValue) {
        Fixture fixture = new Fixture();
        fixture.remoteControl = boolParam(
                HostConfigurationPolicy.PARAM_REMOTE_CONTROL_ENABLED, remoteEnabled, false);
        fixture.cloudOnline = boolParam(
                HostConfigurationPolicy.PARAM_CLOUD_ONLINE, cloudOnline, false);
        fixture.workMode = intParam(
                HostConfigurationPolicy.PARAM_WORK_MODE, workModeValue, 1, 4, true,
                AppConstants.UI_TYPE_DROP_DOWN);
        fixture.outputState = boolParam(
                HostConfigurationPolicy.PARAM_OUTPUT_STATE, false, true);
        fixture.lowThreshold = intParam(
                HostConfigurationPolicy.PARAM_LOW_THRESHOLD, lowValue, 0, 99, true,
                AppConstants.UI_TYPE_SLIDER);
        fixture.highThreshold = intParam(
                HostConfigurationPolicy.PARAM_HIGH_THRESHOLD, highValue, 1, 100, true,
                AppConstants.UI_TYPE_SLIDER);

        fixture.devices = new ArrayList<>();
        fixture.devices.add(device(HOST_DEVICE,
                fixture.remoteControl,
                fixture.cloudOnline,
                fixture.workMode,
                fixture.outputState));
        fixture.devices.add(device(LIQUID_DEVICE,
                fixture.lowThreshold,
                fixture.highThreshold));
        return fixture;
    }

    private Device device(String name, Param... params) {
        Device device = new Device();
        device.setDeviceName(name);
        device.setParams(new ArrayList<>(Arrays.asList(params)));
        return device;
    }

    private Param boolParam(String name, boolean value, boolean writable) {
        Param param = new Param();
        param.setName(name);
        param.setDataType("bool");
        param.setUiType(AppConstants.UI_TYPE_TOGGLE);
        param.setSwitchStatus(value);
        param.setLabelValue(String.valueOf(value));
        param.setProperties(properties(writable));
        return param;
    }

    private Param intParam(String name,
                           int value,
                           int min,
                           int max,
                           boolean writable,
                           String uiType) {
        Param param = new Param();
        param.setName(name);
        param.setDataType("int");
        param.setUiType(uiType);
        param.setValue(value);
        param.setLabelValue(String.valueOf(value));
        param.setMinBounds(min);
        param.setMaxBounds(max);
        param.setStepCount(1);
        param.setProperties(properties(writable));
        return param;
    }

    private ArrayList<String> properties(boolean writable) {
        ArrayList<String> properties = new ArrayList<>();
        properties.add("read");
        if (writable) {
            properties.add(AppConstants.KEY_PROPERTY_WRITE);
        }
        return properties;
    }

    /**
     * 一组测试 Param 引用，便于在调用策略后直接检查同一对象的有效 UI 投影。
     */
    private static final class Fixture {
        ArrayList<Device> devices;
        Param remoteControl;
        Param cloudOnline;
        Param workMode;
        Param outputState;
        Param lowThreshold;
        Param highThreshold;
    }
}
