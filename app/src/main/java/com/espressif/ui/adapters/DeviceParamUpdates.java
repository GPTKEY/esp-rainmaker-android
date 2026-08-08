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

    private HashMap<String, Queue<Number>> sliderParamMap;  // Map for Param name and Queue (To store queue per param)
    private HashMap<String, Number> lastSliderValues;   // Map for Param name and last slider value (To store last slider value per param)
    private HashMap<String, Long> lastRequestTimes; // Map for Param name and last request time (To store timestamp value per param)
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
     * <p>synchronized 仅保护内存队列，不在锁内执行网络阻塞。</p>
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
            if (queueSize < QUEUE_SIZE) { // if queue size reaches 50
                sliderQueue.offer(sliderValue); // clear some middle values
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

    private void processSliderQueue(String paramName) {

        if (sliderParamMap.containsKey(paramName)) {

            Queue<Number> queue = sliderParamMap.get(paramName);
            Number sliderValue = queue.poll();
            processSliderRequest(paramName, sliderValue);
        }
    }

    /**
     * 串行选取下一条 Param/Slider 请求。
     *
     * <p>只做内存队列调度；网络调用由单线程 Executor 执行。Iterator 遍历期间使用
     * iterator.remove() 删除空队列，避免 ConcurrentModificationException。</p>
     */
    private synchronized void processParamRequests() {

        if (!isWait) {

            if (paramUpdateRequests.size() > 0) {
                ParamUpdateRequest paramReq = paramUpdateRequests.remove(0);
                exeService.submit(new Runnable() {
                    @Override
                    public void run() {
                        sendParamUpdates(paramReq.body, paramReq.listener);
                    }
                });
            }

            if (sliderParamMap.size() > 0) {

                Iterator<Map.Entry<String, Queue<Number>>> itr = sliderParamMap.entrySet().iterator();

                // iterate and remove items simultaneously
                while (itr.hasNext()) {

                    Map.Entry<String, Queue<Number>> entry = itr.next();
                    String paramName = entry.getKey();
                    Queue<Number> queue = entry.getValue();
                    if (queue.isEmpty()) {
                        itr.remove();
                    } else {
                        Number sliderValue = queue.poll();
                        processSliderRequest(paramName, sliderValue);
                        break;
                    }
                }
            }
        }
    }

    public synchronized void clearQueueAndSendLastValue(String paramName, Number sliderValue, ApiResponseListener listener) {

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

    private void processSliderRequest(String paramName, Number sliderValue) {

        if (sliderValue == null) {
            Log.e(TAG, "Slider value cannot be null");
            return;
        }

        if (isWait) {
            return;
        }

        JsonObject jsonParam = new JsonObject();
        JsonObject body = new JsonObject();
        if (sliderValue instanceof Float) {
            jsonParam.addProperty(paramName, sliderValue.floatValue());
        } else {
            jsonParam.addProperty(paramName, sliderValue.intValue());
        }
        body.add(deviceName, jsonParam);

        exeService.submit(new Runnable() {
            @Override
            public void run() {
                sendParamUpdates(body, null);
            }
        });
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
        if (currentNode != null) {
            HostConfigurationPolicy.WriteDecision decision = HostConfigurationPolicy.evaluateWriteRequest(
                    currentNode.getDevices(), body);
            if (!decision.allowed) {
                Log.w(TAG, "Host configuration write blocked before network request, reason="
                        + decision.reason + ", param=" + decision.paramName);
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
        isWait = true;

        networkApiManager.updateParamValue(nodeId, body, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle data) {
                isWait = false;
                if (listener != null) {
                    listener.onSuccess(data);
                }
                processParamRequests();
            }

            @Override
            public void onResponseFailure(Exception exception) {
                isWait = false;
                if (listener != null) {
                    listener.onResponseFailure(exception);
                }
                processParamRequests();
            }

            @Override
            public void onNetworkFailure(Exception exception) {
                isWait = false;
                if (listener != null) {
                    listener.onNetworkFailure(exception);
                }
                processParamRequests();
            }
        });
    }
}
