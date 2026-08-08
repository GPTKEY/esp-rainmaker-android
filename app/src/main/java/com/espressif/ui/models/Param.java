// Copyright 2020 Espressif Systems (Shanghai) PTE LTD
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

package com.espressif.ui.models;

import android.os.Parcel;
import android.os.Parcelable;

import com.espressif.AppConstants;

import java.util.ArrayList;

public class Param implements Parcelable, Comparable {

    private String name;
    private String paramType;
    private String dataType;
    private String uiType;
    private ArrayList<String> properties;
    private int minBounds;
    private int maxBounds;
    private float stepCount;
    private double value;
    private boolean switchStatus;
    private String labelValue;
    private ArrayList<String> validStrings;
    private boolean isDynamicParam;
    private boolean isSelected;
    private String dependencies;

    /**
     * 主机配置页的临时“有效写权限”投影标记。
     *
     * <p>这两个字段不改变 RainMaker 下发的原始 properties，只控制 {@link #getProperties()}
     * 是否向现有通用 UI 暴露 WRITE。这样 RemoteControlEnabled/CloudOnline/WorkMode 更新后，
     * UI 可以重新计算权限而不丢失原始 RW 契约。</p>
     */
    private boolean hostWriteGateApplied;
    private boolean hostWriteAllowed = true;

    public Param(Param param) {

        name = param.getName();
        paramType = param.getParamType();
        dataType = param.getDataType();
        uiType = param.getUiType();
        // 必须复制原始 properties，不能调用 getProperties()，否则只读投影会被永久写进副本。
        properties = param.properties == null ? null : new ArrayList<>(param.properties);
        minBounds = param.getMinBounds();
        maxBounds = param.getMaxBounds();
        stepCount = param.getStepCount();
        value = param.getValue();
        switchStatus = param.getSwitchStatus();
        labelValue = param.getLabelValue();
        validStrings = param.getValidStrings() == null ? null : new ArrayList<>(param.getValidStrings());
        isDynamicParam = param.isDynamicParam();
        isSelected = param.isSelected();
        dependencies = param.getDependencies();
        hostWriteGateApplied = param.hostWriteGateApplied;
        hostWriteAllowed = param.hostWriteAllowed;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getParamType() {
        return paramType;
    }

    public void setParamType(String paramType) {
        this.paramType = paramType;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getUiType() {
        return uiType;
    }

    public void setUiType(String uiType) {
        this.uiType = uiType;
    }

    /**
     * 返回 UI 当前可见的有效属性。
     *
     * <p>当主机在线但设备端未授权远控、CloudOnline=false，或 OutputState 不处于 MANUAL 模式时，
     * 仅在返回值中隐藏 WRITE；原始 properties 保留，主机状态变化后可以立即恢复。</p>
     */
    public ArrayList<String> getProperties() {
        if (properties == null) {
            return new ArrayList<>();
        }
        if (hostWriteGateApplied && !hostWriteAllowed
                && properties.contains(AppConstants.KEY_PROPERTY_WRITE)) {
            ArrayList<String> effectiveProperties = new ArrayList<>(properties);
            effectiveProperties.remove(AppConstants.KEY_PROPERTY_WRITE);
            return effectiveProperties;
        }
        return properties;
    }

    public void setProperties(ArrayList<String> properties) {
        this.properties = properties;
    }

    /** 判断 RainMaker 原始模型是否声明某属性，不受 Android UI 门禁影响。 */
    public boolean hasBaseProperty(String property) {
        return properties != null && properties.contains(property);
    }

    /**
     * 设置主机配置页的有效写权限投影。
     *
     * @param applied 是否启用主机专用门禁；false 表示完全使用 RainMaker 原始 properties。
     * @param allowed 门禁启用时是否允许向通用 UI 暴露 WRITE。
     */
    public void setHostWriteGate(boolean applied, boolean allowed) {
        hostWriteGateApplied = applied;
        hostWriteAllowed = allowed;
    }

    public boolean isHostWriteGateApplied() {
        return hostWriteGateApplied;
    }

    public boolean isHostWriteAllowed() {
        return hostWriteAllowed;
    }

    public int getMinBounds() {
        return minBounds;
    }

    public void setMinBounds(int minBounds) {
        this.minBounds = minBounds;
    }

    public int getMaxBounds() {
        return maxBounds;
    }

    public void setMaxBounds(int maxBounds) {
        this.maxBounds = maxBounds;
    }

    public float getStepCount() {
        return stepCount;
    }

    public void setStepCount(float stepCount) {
        this.stepCount = stepCount;
    }

    public double getValue() {
        return value;
    }

    public void setValue(double value) {
        this.value = value;
    }

    public boolean getSwitchStatus() {
        return switchStatus;
    }

    public void setSwitchStatus(boolean switchStatus) {
        this.switchStatus = switchStatus;
    }

    public String getLabelValue() {
        return labelValue;
    }

    public void setLabelValue(String labelValue) {
        this.labelValue = labelValue;
    }

    public ArrayList<String> getValidStrings() {
        return validStrings;
    }

    public void setValidStrings(ArrayList<String> validStrings) {
        this.validStrings = validStrings;
    }

    public boolean isDynamicParam() {
        return isDynamicParam;
    }

    public void setDynamicParam(boolean dynamicParam) {
        isDynamicParam = dynamicParam;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public String getDependencies() {
        return dependencies;
    }

    public void setDependencies(String dependencies) {
        this.dependencies = dependencies;
    }

    public Param() {
    }

    protected Param(Parcel in) {
        name = in.readString();
        paramType = in.readString();
        dataType = in.readString();
        uiType = in.readString();
        properties = in.createStringArrayList();
        minBounds = in.readInt();
        maxBounds = in.readInt();
        stepCount = in.readFloat();
        value = in.readDouble();
        switchStatus = in.readByte() != 0;
        labelValue = in.readString();
        validStrings = in.createStringArrayList();
        isDynamicParam = in.readByte() != 0;
        isSelected = in.readByte() != 0;
        dependencies = in.readString();
        hostWriteGateApplied = in.readByte() != 0;
        hostWriteAllowed = in.readByte() != 0;
    }

    public static final Creator<Param> CREATOR = new Creator<Param>() {
        @Override
        public Param createFromParcel(Parcel in) {
            return new Param(in);
        }

        @Override
        public Param[] newArray(int size) {
            return new Param[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(name);
        dest.writeString(paramType);
        dest.writeString(dataType);
        dest.writeString(uiType);
        dest.writeStringList(properties);
        dest.writeInt(minBounds);
        dest.writeInt(maxBounds);
        dest.writeFloat(stepCount);
        dest.writeDouble(value);
        dest.writeByte((byte) (switchStatus ? 1 : 0));
        dest.writeString(labelValue);
        dest.writeStringList(validStrings);
        dest.writeByte((byte) (isDynamicParam ? 1 : 0));
        dest.writeByte((byte) (isSelected ? 1 : 0));
        dest.writeString(dependencies);
        dest.writeByte((byte) (hostWriteGateApplied ? 1 : 0));
        dest.writeByte((byte) (hostWriteAllowed ? 1 : 0));
    }

    @Override
    public String toString() {
        return "Param {" +
                "name = '" + name + '\'' +
                ", dataType ='" + dataType + '\'' +
                ", uiType ='" + uiType + '\'' +
                '}';
    }

    @Override
    public int compareTo(Object o) {
        Param compare = (Param) o;
        if (compare.name != null && this.name != null && !compare.name.equals(this.name)) {
            return 1;
        } else if (compare.paramType != null && this.paramType != null && !compare.paramType.equals(this.paramType)) {
            return 1;
        } else if (compare.dataType != null && this.dataType != null && !compare.dataType.equals(this.dataType)) {
            return 1;
        } else if (compare.uiType != null && this.uiType != null && !compare.uiType.equals(this.uiType)) {
            return 1;
        } else if (compare.labelValue != null && this.labelValue != null && !compare.labelValue.equals(this.labelValue)) {
            return 1;
        } else if (compare.properties != null && this.properties != null && !compare.properties.equals(this.properties)) {
            return 1;
        } else if (compare.validStrings != null && this.validStrings != null && !compare.validStrings.equals(this.validStrings)) {
            return 1;
        } else if (compare.dependencies != null && this.dependencies != null && !compare.dependencies.equals(this.dependencies)) {
            return 1;
        } else if (compare.minBounds == this.minBounds
                && compare.maxBounds == this.maxBounds
                && compare.stepCount == this.stepCount
                && compare.value == this.value
                && compare.switchStatus == this.switchStatus
                && compare.isDynamicParam == this.isDynamicParam
                && compare.isSelected == this.isSelected
                && compare.hostWriteGateApplied == this.hostWriteGateApplied
                && compare.hostWriteAllowed == this.hostWriteAllowed) {
            return 0;
        }
        return 1;
    }
}
