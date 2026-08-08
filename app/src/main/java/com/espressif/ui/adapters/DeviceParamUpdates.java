// Copyright 2023 Espressif Systems (Shanghai) PTE LTD
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

package com.espressif.ui.adapters;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;

import com.espressif.EspApplication;
import com.espressif.NetworkApiManager;
import com.espressif.cloudapi.ApiResponseListener;
import com.espressif.ui.Utils;
import com.espressif.ui.activities.EspDeviceActivity;
import com.espressif.ui.hostconfig.HostConfigurationPolicy;
import com.espressif.ui.models.EspNode;
import com.espressif.ui.models.ParamUpdateRequest;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceParamUpdates {

    private static final String TAG = DeviceParamUpdates.class.getSimpleName();
    private final int QUEUE_SIZE = 5;

    private String nodeId;
    private String deviceName;
    private ExecutorService exeService;
    private NetworkApiManager networkApiManager;
    private EspApplication espApp;

    private HashMap<String, Queue<Number>> sliderParamMap;  // Param 名称 -> Slider 待发送值队列。
    private HashMap<String, Number> lastSliderValues;       // Param 名称 -> 最近一次 Slider 值。
    private HashMap<String, Long> lastRequestTimes;         // Param 名称 -> 最近一次请求时间。
    private volatile boolean isWait;
    private ArrayList<ParamUpdateRequest> paramUpdateRequests;
    private long THROTTLE_DELAY;
    private Context context;

    public DeviceParamUpdates(Context activityContext, String nodeId, String deviceName) {
        this.context = activityContext;
        this.nodeId = nodeId;
        this.deviceName = deviceName;
        sliderParamMap = new HashMap<>();
        lastSliderValues = new HashMap<>();
        lastRequestTimes = new HashMap<>();
        paramUpdateRequests = new ArrayList<>();
        isWait = false;
        THROTTLE_DELAY = Utils.getThrottleDelay();
        exeService = Executors.newSingleThreadExecutor();
        networkApiManager = new NetworkApiManager(activityContext.getApplicationContext());
        espApp = (EspApplication) activityContext.getApplicationContext();
    }

    /**
     * 把普通 Param 写请求加入现有单线程发送队列。
     *
     * <p>synchronized 仅保护内存队列和 in-flight 标志，不在锁内等待网络响应。</p>
     */
    public synchronized void addParamUpdateRequest(JsonObject body, ApiResponseListener listener) {

        Log.d(TAG, "Added param update : " + body);
        ParamUpdateRequest paramReq = new ParamUpdateRequest();
        paramReq.body = body;
        paramReq.listener = listener;
        paramUpdateRequests.add(paramReq);
        processParamRequests();
    }

    public synchronized void processSliderChange(String paramName, Number sliderValue) {

        /*
         * LowThreshold / HighThreshold 是关联配置。
         *
         * 现有 ParamAdapter 在 continuous update 模式下会在 onSeeking() 中持续调用本函数，
         * 同时在 onStopTrackingTouch() 中始终调用 clearQueueAndSendLastValue() 提交最终值。
         * 因此这里仅针对两项液位阈值忽略拖动过程中的中间值，既保留原 Slider 体验，也保证
         * 每次手指释放只发送最终值，避免连续产生 low >= high 等无意义的瞬时请求。
         *
         * 其它 Slider 完全保持 RainMaker 原有节流队列行为。
         */
        if (HostConfigurationPolicy.isThresholdParam(paramName)) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long lastRequestTime = 0;
        if (lastRequestTimes.containsKey(paramName)) {
            lastRequestTime = lastRequestTimes.get(paramName);
        }
        Number lastSliderValue = lastSliderValues.get(paramName);

        if (sliderValue instanceof Float) {
            if (lastSliderValue != null && lastSliderValue.floatValue() == sliderValue.floatValue()) {
                return;
            }
        } else {
            if (lastSliderValue != null && lastSliderValue.intValue() == sliderValue.intValue()) {
                return;
            }
        }

        if (lastRequestTime != 0 && currentTime - lastRequestTime < THROTTLE_DELAY) {
            addToQueue(paramName, sliderValue, currentTime, true);
            return;
        } else {
            addToQueue(paramName, sliderValue, currentTime, false);
        }
        processParamRequests();
    }

    /**
     * 将 Slider 值加入对应队列。
     *
     * <p>本函数只由 synchronized 的公开入口调用，因此不单独加锁。</p>
     */
    private void addToQueue(String paramName, Number sliderValue, long currentTime, boolean clearSome) {

        Queue<Number> sliderQueue;
        int queueSize = 0;
        if (sliderParamMap.containsKey(paramName)) {
            sliderQueue = sliderParamMap.get(paramName);
            queueSize = sliderQueue.size();
        } else {
            sliderQueue = new LinkedList<>();
        }

        if (clearSome) {
            if (queueSize == QUEUE_SIZE) {
                makeSpace(paramName);
                processSliderQueue(paramName);
            }

            sliderQueue.offer(sliderValue);
            sliderParamMap.put(paramName, sliderQueue);

        } else {
            if (queueSize < QUEUE_SIZE) {
                sliderQueue.offer(sliderValue);
            } else if (queueSize == QUEUE_SIZE) {
                makeSpace(paramName);
                sliderQueue.offer(sliderValue);
            }
            sliderParamMap.put(paramName, sliderQueue);
        }
        lastRequestTimes.put(paramName, currentTime);
        lastSliderValues.put(paramName, sliderValue);
    }

    private void makeSpace(String paramName) {

        Queue<Number> requestQueue;
        if (sliderParamMap.containsKey(paramName)) {
            requestQueue = sliderParamMap.get(paramName);
        } else {
            requestQueue = new LinkedList<>();
        }

        if (!requestQueue.isEmpty()) {
            for (int i = 0; i < QUEUE_SIZE; i = i + 2) {
                int counter = 0;
                Queue<Number> tempQueue = new LinkedList<>();
                while (!requestQueue.isEmpty()) {
                    Number item = requestQueue.remove();
                    if (counter != i) {
                        tempQueue.add(item);
                    }
                    counter++;
                }
                while (!tempQueue.isEmpty()) {
                    Number item = tempQueue.remove();
                    requestQueue.offer(item);
                }
            }
        }
        sliderParamMap.put(paramName, requestQueue);
    }

    /**
     * 队列满时尝试提前发送一个 Slider 值。
     *
     * <p>如果已经存在 in-flight 请求，则不出队，避免值被提前 poll 后丢失。</p>
     */
    private void processSliderQueue(String paramName) {

        if (isWait) {
            return;
        }

        if (sliderParamMap.containsKey(paramName)) {
            Queue<Number> queue = sliderParamMap.get(paramName);
            Number sliderValue = queue.peek();
            if (sliderValue != null && processSliderRequest(paramName, sliderValue)) {
                queue.poll();
            }
        }
    }

    /**
     * 串行选取下一条 Param/Slider 请求。
     *
     * <p>关键点：在提交 Executor 之前先把 isWait 置为 true，表示已经“预约”了唯一 in-flight
     * 请求。这样 UI 线程在 worker 真正开始执行前再次入队，也不会形成第二个并行网络请求。</p>
     */
    private synchronized void processParamRequests() {

        if (isWait) {
            return;
        }

        if (!paramUpdateRequests.isEmpty()) {
            ParamUpdateRequest paramReq = paramUpdateRequests.remove(0);
            isWait = true;
            exeService.submit(new Runnable() {
                @Override
                public void run() {
                    sendParamUpdates(paramReq.body, paramReq.listener);
                }
            });
            return;
        }

        if (!sliderParamMap.isEmpty()) {
            Iterator<Map.Entry<String, Queue<Number>>> itr = sliderParamMap.entrySet().iterator();

            while (itr.hasNext()) {
                Map.Entry<String, Queue<Number>> entry = itr.next();
                String paramName = entry.getKey();
                Queue<Number> queue = entry.getValue();
                if (queue.isEmpty()) {
                    itr.remove();
                    continue;
                }

                Number sliderValue = queue.peek();
                if (sliderValue != null && processSliderRequest(paramName, sliderValue)) {
                    queue.poll();
                }
                return;
            }
        }
    }

    public synchronized void clearQueueAndSendLastValue(String paramName,
                                                         Number sliderValue,
                                                         ApiResponseListener listener) {

        if (sliderParamMap.containsKey(paramName)) {
            sliderParamMap.get(paramName).clear();
            sliderParamMap.remove(paramName);
        }
        lastRequestTimes.put(paramName, System.currentTimeMillis());
        lastSliderValues.put(paramName, sliderValue);

        JsonObject jsonParam = new JsonObject();
        JsonObject body = new JsonObject();
        if (sliderValue instanceof Float) {
            jsonParam.addProperty(paramName, sliderValue.floatValue());
        } else {
            jsonParam.addProperty(paramName, sliderValue.intValue());
        }
        body.add(deviceName, jsonParam);
        addParamUpdateRequest(body, listener);
    }

    /**
     * 尝试预约并提交一条 Slider 请求。
     *
     * @return true 表示已成功预约并提交，调用者此时才可以从队列移除该值。
     */
    private synchronized boolean processSliderRequest(String paramName, Number sliderValue) {

        if (sliderValue == null) {
            Log.e(TAG, "Slider value cannot be null");
            return false;
        }

        if (isWait) {
            return false;
        }

        JsonObject jsonParam = new JsonObject();
        JsonObject body = new JsonObject();
        if (sliderValue instanceof Float) {
            jsonParam.addProperty(paramName, sliderValue.floatValue());
        } else {
            jsonParam.addProperty(paramName, sliderValue.intValue());
        }
        body.add(deviceName, jsonParam);

        isWait = true;
        exeService.submit(new Runnable() {
            @Override
            public void run() {
                sendParamUpdates(body, null);
            }
        });
        return true;
    }

    /**
     * 所有非 Matter Param 的统一最终发送点。
     *
     * <p>真正调用 NetworkApiManager 前，对本项目主机模型重新读取一次当前 Node 状态并执行
     * HostConfigurationPolicy 预检。这样 UI 已经排队的请求即使遇到 RemoteControlEnabled、
     * CloudOnline、WorkMode 或另一阈值刚刚变化，也会在网络发送前再次被拦截。</p>
     */
    private void sendParamUpdates(JsonObject body, ApiResponseListener listener) {
        Log.d(TAG, "sendParamUpdates called with body: " + body.toString());

        EspNode currentNode = espApp.nodeMap.get(nodeId);
        boolean hostConfigurationModel = false;
        if (currentNode != null) {
            ArrayList<com.espressif.ui.models.Device> currentDevices = currentNode.getDevices();
            HostConfigurationPolicy.Snapshot snapshot = HostConfigurationPolicy.snapshot(currentDevices);
            hostConfigurationModel = snapshot.hostModel;

            HostConfigurationPolicy.WriteDecision decision = HostConfigurationPolicy.evaluateWriteRequest(
                    currentDevices, body);
            if (!decision.allowed) {
                Log.w(TAG, "Host configuration write blocked before network request, reason="
                        + decision.reason + ", param=" + decision.paramName);
                finishRequest();
                if (listener != null) {
                    listener.onResponseFailure(new IllegalStateException(
                            "Host configuration write blocked: " + decision.reason));
                }
                processParamRequests();
                return;
            }
        }

        if (context instanceof EspDeviceActivity) {
            ((EspDeviceActivity) context).setLastUpdateRequestTime(System.currentTimeMillis());
        }

        final boolean shouldRefreshAuthoritativeParams = hostConfigurationModel;
        networkApiManager.updateParamValue(nodeId, body, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle data) {
                /*
                 * 先把写 ACK 交给原监听器，让现有控件完成 loading/交互状态收尾；随后再立即读取
                 * 权威 Param。这样即使原监听器做了 optimistic update，后续回读仍会覆盖成主机真值。
                 */
                if (listener != null) {
                    listener.onSuccess(data);
                }

                if (shouldRefreshAuthoritativeParams) {
                    refreshAuthoritativeParamsAfterWrite();
                } else {
                    finishRequestAndContinue();
                }
            }

            @Override
            public void onResponseFailure(Exception exception) {
                finishRequest();
                if (listener != null) {
                    listener.onResponseFailure(exception);
                }
                processParamRequests();
            }

            @Override
            public void onNetworkFailure(Exception exception) {
                finishRequest();
                if (listener != null) {
                    listener.onNetworkFailure(exception);
                }
                processParamRequests();
            }
        });
    }

    /**
     * 主机配置写 ACK 后立即复用现有 NetworkApiManager 读取链回读权威 Param。
     *
     * <p>Cloud 路径的 ApiManager.getParamsValues() 会把返回值写回 espApp.nodeMap；Local/BLE
     * 路径也沿用项目现有解析逻辑。这里不创建第二份配置缓存。</p>
     *
     * <p>回读失败不把已经成功的写请求伪装成“写失败”：记录告警并释放队列，现有周期刷新仍会
     * 继续收敛。底层 NetworkApiManager/Retrofit 负责已有网络超时与 fallback。</p>
     */
    private void refreshAuthoritativeParamsAfterWrite() {
        networkApiManager.getParamsValues(nodeId, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle data) {
                Log.d(TAG, "Authoritative host params refreshed after write ACK");
                refreshDeviceActivityFromCurrentNode();
                finishRequestAndContinue();
            }

            @Override
            public void onResponseFailure(Exception exception) {
                Log.w(TAG, "Host param write succeeded but authoritative readback failed: "
                        + exception.getMessage());
                finishRequestAndContinue();
            }

            @Override
            public void onNetworkFailure(Exception exception) {
                Log.w(TAG, "Host param write succeeded but authoritative readback network failed: "
                        + exception.getMessage());
                finishRequestAndContinue();
            }
        });
    }

    /**
     * 回读完成后直接复用 EspDeviceActivity 已有 updateViewTask 刷新当前页面。
     *
     * <p>不修改 Activity 架构，也不引入新的 EventBus 事件。</p>
     */
    private void refreshDeviceActivityFromCurrentNode() {
        if (!(context instanceof EspDeviceActivity)) {
            return;
        }

        EspDeviceActivity activity = (EspDeviceActivity) context;
        activity.runOnUiThread(activity.getUpdateViewTask());
    }

    /** 释放唯一 in-flight 标记。 */
    private void finishRequest() {
        isWait = false;
    }

    /** 释放当前请求并继续调度下一项。 */
    private void finishRequestAndContinue() {
        finishRequest();
        processParamRequests();
    }
}
