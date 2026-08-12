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

package com.espressif.ui.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.SpinnerAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatSpinner;

import com.espressif.rainmaker.R;
import com.espressif.ui.hostconfig.HostConfigurationPolicy;

import java.util.ArrayList;

public class EspDropDown extends AppCompatSpinner {
    private OnSpinnerEventsListener mListener;
    private boolean mOpenInitiated = false;
    private boolean settingInternalAdapter = false;

    public EspDropDown(Context context, AttributeSet attrs, int defStyleAttr, int mode) {
        super(context, attrs, defStyleAttr, mode);
    }

    public EspDropDown(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public EspDropDown(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EspDropDown(Context context, int mode) {
        super(context, mode);
    }

    public EspDropDown(Context context) {
        super(context);
    }

    public interface OnSpinnerEventsListener {

        void onSpinnerOpened(Spinner spin);

        void onSpinnerClosed(Spinner spin);
    }

    /**
     * 对主机 WorkMode 做“只改显示、不改协议值”的本地化包装。
     *
     * <p>ParamAdapter 仍然从 {@link #getItemAtPosition(int)} 得到原始字符串 "1"～"4"，
     * 因此写请求继续发送主机稳定整数枚举；仅 Spinner 的 TextView 显示“蓄水/排水/定时/手动”。
     * 其它 RainMaker 下拉框完全沿用原 Adapter，避免影响通用设备。</p>
     */
    @Override
    public void setAdapter(SpinnerAdapter adapter) {
        if (!settingInternalAdapter && adapter != null && isWorkModeSpinner()) {
            ArrayList<String> protocolValues = new ArrayList<>();
            for (int i = 0; i < adapter.getCount(); i++) {
                Object item = adapter.getItem(i);
                protocolValues.add(item == null ? "" : item.toString());
            }

            WorkModeDisplayAdapter displayAdapter = new WorkModeDisplayAdapter(getContext(), protocolValues);
            settingInternalAdapter = true;
            try {
                super.setAdapter(displayAdapter);
            } finally {
                settingInternalAdapter = false;
            }
            return;
        }
        super.setAdapter(adapter);
    }

    private boolean isWorkModeSpinner() {
        if (!(getParent() instanceof View)) {
            return false;
        }
        TextView nameView = ((View) getParent()).findViewById(R.id.tv_spinner_name);
        return nameView != null
                && HostConfigurationPolicy.PARAM_WORK_MODE.contentEquals(nameView.getText());
    }

    /**
     * WorkMode 专用显示 Adapter。
     *
     * <p>items 永远保存协议数字文本，getItem() 也返回协议值；getView()/getDropDownView() 才替换
     * TextView 显示内容。因此不会发生“本地化字符串被当成协议值发送”的问题。</p>
     */
    private static final class WorkModeDisplayAdapter extends ArrayAdapter<String> {

        WorkModeDisplayAdapter(Context context, ArrayList<String> protocolValues) {
            super(context, android.R.layout.simple_spinner_item, protocolValues);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public @NonNull View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            applyLocalizedText(view, getItem(position));
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = super.getDropDownView(position, convertView, parent);
            applyLocalizedText(view, getItem(position));
            return view;
        }

        private void applyLocalizedText(View view, String protocolValue) {
            if (!(view instanceof TextView)) {
                return;
            }
            TextView textView = (TextView) view;
            int mode;
            try {
                mode = Integer.parseInt(protocolValue);
            } catch (NumberFormatException ex) {
                textView.setText(protocolValue);
                return;
            }

            switch (mode) {
                case HostConfigurationPolicy.WORK_MODE_FILL:
                    textView.setText(R.string.host_work_mode_fill);
                    break;
                case HostConfigurationPolicy.WORK_MODE_DRAIN:
                    textView.setText(R.string.host_work_mode_drain);
                    break;
                case HostConfigurationPolicy.WORK_MODE_TIMER:
                    textView.setText(R.string.host_work_mode_timer);
                    break;
                case HostConfigurationPolicy.WORK_MODE_MANUAL:
                    textView.setText(R.string.host_work_mode_manual);
                    break;
                default:
                    textView.setText(R.string.host_work_mode_unknown);
                    break;
            }
        }
    }

    @Override
    public boolean performClick() {
        // register that the Spinner was opened so we have a status
        // indicator for the activity(which may lose focus for some other
        // reasons)
        mOpenInitiated = true;
        if (mListener != null) {
            mListener.onSpinnerOpened(this);
        }
        return super.performClick();
    }

    public void setSpinnerEventsListener(OnSpinnerEventsListener onSpinnerEventsListener) {
        mListener = onSpinnerEventsListener;
    }

    /**
     * Propagate the closed Spinner event to the listener from outside.
     */
    public void performClosedEvent() {
        mOpenInitiated = false;
        if (mListener != null) {
            mListener.onSpinnerClosed(this);
        }
    }

    /**
     * A boolean flag indicating that the Spinner triggered an open event.
     *
     * @return true for opened Spinner
     */
    public boolean hasBeenOpened() {
        return mOpenInitiated;
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (hasBeenOpened() && hasWindowFocus) {
            performClosedEvent();
        }
    }
}
