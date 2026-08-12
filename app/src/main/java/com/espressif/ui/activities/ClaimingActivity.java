// Copyright 2021 Espressif Systems (Shanghai) PTE LTD
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

package com.espressif.ui.activities;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import com.espressif.utils.ProvisioningLog;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.LinearInterpolator;
import android.view.animation.RotateAnimation;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.espressif.AppConstants;
import com.espressif.cloudapi.ApiManager;
import com.espressif.cloudapi.ApiResponseListener;
import com.espressif.provisioning.DeviceConnectionEvent;
import com.espressif.provisioning.ESPConstants;
import com.espressif.provisioning.ESPProvisionManager;
import com.espressif.provisioning.listeners.ResponseListener;
import com.espressif.rainmaker.R;
import com.espressif.rainmaker.databinding.ActivityClaimingBinding;
import com.espressif.utils.ExistingWifiReuseHelper;
import com.google.android.material.card.MaterialCardView;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import rmaker_claim.EspRmakerClaim;

public class ClaimingActivity extends AppCompatActivity {

    private static final String TAG = ClaimingActivity.class.getSimpleName();
    /**
     * Assisted Claim 低敏诊断标签。
     *
     * 只记录阶段、长度、状态和异常类型；严禁写入 Claim 云响应正文、CSR、证书、PoP、私钥或
     * Wi-Fi 凭据。这样 Logcat 可以直接和设备端 `esp_claim` 日志按阶段对齐，又不会扩大敏感
     * 数据暴露面。
     */
    private static final String CLAIM_DIAG_TAG = "CLAIM_DIAG";

    private MaterialCardView btnOk;
    private TextView txtOkBtn;

    private int dataCount = 0;
    private String certificateData = "";
    private StringBuilder csrData = new StringBuilder();
    private boolean isClaimingAborted = false, shouldSendClaimAbortReq = false;
    private boolean hasTriedAgain = false;
    private boolean isCameraClaim = false;

    private Handler handler;
    private ApiManager apiManager;
    private ESPProvisionManager provisionManager;

    private ActivityClaimingBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityClaimingBinding.inflate(getLayoutInflater());
        View view = binding.getRoot();
        setContentView(view);

        hasTriedAgain = false;
        isCameraClaim = getIntent().getBooleanExtra(AppConstants.KEY_IS_CAMERA_CLAIM, false);
        handler = new Handler();
        apiManager = ApiManager.getInstance(getApplicationContext());
        provisionManager = ESPProvisionManager.getInstance(getApplicationContext());
        initViews();
        EventBus.getDefault().register(this);
        displayClaimingProgress();
        handler.postDelayed(timeoutTask, 10000);
        sendClaimStartRequest();
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (provisionManager.getEspDevice() != null) {
            provisionManager.getEspDevice().disconnectDevice();
        }
        super.onBackPressed();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onEvent(DeviceConnectionEvent event) {

        ProvisioningLog.d(TAG, "On Device Connection Event RECEIVED : " + event.getEventType());

        switch (event.getEventType()) {

            case ESPConstants.EVENT_DEVICE_CONNECTED:
                ProvisioningLog.i(CLAIM_DIAG_TAG, "device_connected claim_start_retry=" + hasTriedAgain);
                sendClaimStartRequest();
                break;

            case ESPConstants.EVENT_DEVICE_DISCONNECTED:
                ProvisioningLog.e(CLAIM_DIAG_TAG, "device_disconnected during_claiming=true");
                if (!isFinishing()) {
                    showAlertForDeviceDisconnected();
                }
                break;
        }
    }

    private View.OnClickListener okBtnClickListener = new View.OnClickListener() {

        @Override
        public void onClick(View v) {

            if (provisionManager.getEspDevice() != null) {
                provisionManager.getEspDevice().disconnectDevice();
            }
            finish();
        }
    };

    private void initViews() {

        setSupportActionBar(binding.toolbarLayout.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setTitle(R.string.title_activity_claiming);
        binding.toolbarLayout.toolbar.setNavigationIcon(R.drawable.ic_arrow_left);
        binding.toolbarLayout.toolbar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

                if (shouldSendClaimAbortReq) {
                    sendClaimAbortRequest();

                    handler.postDelayed(new Runnable() {

                        @Override
                        public void run() {

                            if (provisionManager.getEspDevice() != null) {
                                provisionManager.getEspDevice().disconnectDevice();
                            }
                            finish();
                        }
                    }, 2000);
                } else {
                    if (provisionManager.getEspDevice() != null) {
                        provisionManager.getEspDevice().disconnectDevice();
                    }
                    finish();
                }
            }
        });

        btnOk = findViewById(R.id.btn_ok);
        txtOkBtn = findViewById(R.id.text_btn);
        btnOk.findViewById(R.id.iv_arrow).setVisibility(View.GONE);
        btnOk.setVisibility(View.GONE);

        txtOkBtn.setText(R.string.btn_ok);
        btnOk.setOnClickListener(okBtnClickListener);
    }

    private void sendClaimStartRequest() {

        ProvisioningLog.uiProgress(this, TAG, "Claim 1/5", "读取设备声明信息");
        ProvisioningLog.d(TAG, "Claim Start Request");
        ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_start_send endpoint=" + AppConstants.HANDLER_RM_CLAIM
                + " retried=" + hasTriedAgain);

        EspRmakerClaim.PayloadBuf payloadBuf = EspRmakerClaim.PayloadBuf.newBuilder()
                .build();

        EspRmakerClaim.RMakerClaimMsgType msgType = EspRmakerClaim.RMakerClaimMsgType.TypeCmdClaimStart;
        EspRmakerClaim.RMakerClaimPayload payload = EspRmakerClaim.RMakerClaimPayload.newBuilder()
                .setMsg(msgType)
                .setCmdPayload(payloadBuf)
                .build();

        provisionManager.getEspDevice().sendDataToCustomEndPoint(AppConstants.HANDLER_RM_CLAIM, payload.toByteArray(), new ResponseListener() {

            @Override
            public void onSuccess(byte[] returnData) {

                ProvisioningLog.d(TAG, "Successfully sent claiming start command");
                ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_start_transport_success response_len="
                        + (returnData != null ? returnData.length : -1));
                processClaimingStartResponse(returnData);
            }

            @Override
            public void onFailure(Exception e) {

                ProvisioningLog.e(TAG, "Failed to start claiming");
                ProvisioningLog.e(CLAIM_DIAG_TAG, "claim_start_transport_failed retried=" + hasTriedAgain
                        + " exception=" + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(e.getMessage()));
                e.printStackTrace();

                if (hasTriedAgain) {
                    runOnUiThread(new Runnable() {

                        @Override
                        public void run() {

                            binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                            binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_start);
                            displayError();
                        }
                    });
                } else {
                    hasTriedAgain = true;
                    ProvisioningLog.w(CLAIM_DIAG_TAG, "claim_start_refresh_ble_services");
                    provisionManager.getEspDevice().refreshServicesOfBleDevice();
                }
            }
        });
    }

    private void processClaimingStartResponse(byte[] responseData) {

        try {
            EspRmakerClaim.RMakerClaimPayload payload = EspRmakerClaim.RMakerClaimPayload.parseFrom(responseData);
            EspRmakerClaim.RespPayload response = payload.getRespPayload();
            ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_start_response status=" + response.getStatus()
                    + " payload_len=" + response.getBuf().getPayload().size()
                    + " offset=" + response.getBuf().getOffset()
                    + " total_len=" + response.getBuf().getTotalLen());

            if (response.getStatus() == EspRmakerClaim.RMakerClaimStatus.Success) {

                sendDeviceInfoToCloud(response.getBuf().getPayload().toStringUtf8());

            } else {

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_start);
                        displayError();
                    }
                });
            }

        } catch (InvalidProtocolBufferException e) {

            ProvisioningLog.e(CLAIM_DIAG_TAG, "claim_start_response_parse_failed response_len="
                    + (responseData != null ? responseData.length : -1)
                    + " exception=" + e.getClass().getSimpleName());
            e.printStackTrace();

            if (!hasTriedAgain) {
                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_start);
                        displayError();
                    }
                });
            }
        }
    }

    private void sendClaimInitRequest(String data) {

        ProvisioningLog.uiProgress(this, TAG, "Claim 2/5", "将云端 Claim 初始化数据发送到设备");
        ProvisioningLog.d(TAG, "Claim Init Request");
        ByteString byteString = ByteString.copyFromUtf8(data);
        EspRmakerClaim.PayloadBuf payloadBuf = EspRmakerClaim.PayloadBuf.newBuilder()
                .setOffset(0)
                .setTotalLen(byteString.size())
                .setPayload(byteString)
                .build();

        EspRmakerClaim.RMakerClaimMsgType msgType = EspRmakerClaim.RMakerClaimMsgType.TypeCmdClaimInit;
        EspRmakerClaim.RMakerClaimPayload payload = EspRmakerClaim.RMakerClaimPayload.newBuilder()
                .setMsg(msgType)
                .setCmdPayload(payloadBuf)
                .build();
        byte[] requestBytes = payload.toByteArray();
        ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_init_send cloud_response_len=" + byteString.size()
                + " protobuf_len=" + requestBytes.length);

        provisionManager.getEspDevice().sendDataToCustomEndPoint(AppConstants.HANDLER_RM_CLAIM, requestBytes, new ResponseListener() {

            @Override
            public void onSuccess(byte[] returnData) {

                ProvisioningLog.d(TAG, "Successfully sent claiming init command");
                ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_init_transport_success response_len="
                        + (returnData != null ? returnData.length : -1));
                getCSRFromDevice(returnData);
            }

            @Override
            public void onFailure(Exception e) {

                ProvisioningLog.e(TAG, "Send config data : Error : " + e.getMessage());
                ProvisioningLog.e(CLAIM_DIAG_TAG, "claim_init_transport_failed exception="
                        + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(e.getMessage()));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_init);
                        displayError();
                    }
                });
            }
        });
    }

    private void getCSRFromDevice(byte[] responseData) {

        try {
            EspRmakerClaim.RMakerClaimPayload payload = EspRmakerClaim.RMakerClaimPayload.parseFrom(responseData);
            EspRmakerClaim.RespPayload response = payload.getRespPayload();
            int payloadLen = response.getBuf().getPayload().size();
            int offset = response.getBuf().getOffset();
            int totalLen = response.getBuf().getTotalLen();
            ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_init_response status=" + response.getStatus()
                    + " offset=" + offset
                    + " total_len=" + totalLen
                    + " payload_len=" + payloadLen
                    + " response_len=" + (responseData != null ? responseData.length : -1));

            if (response.getStatus() == EspRmakerClaim.RMakerClaimStatus.Success) {

                String data = response.getBuf().getPayload().toStringUtf8();
                ProvisioningLog.d(TAG, "Offset : " + offset + " and total length : " + totalLen);

                if (offset == 0) {
                    dataCount = data.length();
                    csrData = new StringBuilder();
                    ProvisioningLog.i(CLAIM_DIAG_TAG, "csr_first_fragment data_count=" + dataCount
                            + " total_len=" + totalLen);
                }
                csrData.append(data);
                ProvisioningLog.d(TAG, "Received CSR Length till now : " + csrData.length());
                ProvisioningLog.d(TAG, "dataCount : " + dataCount);
                ProvisioningLog.i(CLAIM_DIAG_TAG, "csr_fragment_accumulated chars=" + csrData.length()
                        + " total_len=" + totalLen);

                if (csrData.length() >= totalLen) {
                    ProvisioningLog.i(CLAIM_DIAG_TAG, "csr_complete chars=" + csrData.length());
                    sendCSRToAPI(csrData.toString());
                } else {
                    requestCSRData();
                }
            } else {

                ProvisioningLog.d(TAG, "Claiming init status : " + response.getStatus());
                ProvisioningLog.e(CLAIM_DIAG_TAG, "claim_init_device_rejected status=" + response.getStatus()
                        + " offset=" + offset
                        + " total_len=" + totalLen
                        + " payload_len=" + payloadLen);
                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_init);
                        displayError();
                    }
                });
            }

        } catch (InvalidProtocolBufferException e) {

            ProvisioningLog.e(CLAIM_DIAG_TAG, "claim_init_response_parse_failed response_len="
                    + (responseData != null ? responseData.length : -1)
                    + " exception=" + e.getClass().getSimpleName());
            e.printStackTrace();
            runOnUiThread(new Runnable() {

                @Override
                public void run() {

                    binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                    binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_init);
                    displayError();
                }
            });
        }
    }

    private void requestCSRData() {

        if (isClaimingAborted) {
            return;
        }
        EspRmakerClaim.PayloadBuf payloadBuf = EspRmakerClaim.PayloadBuf.newBuilder()
                .build();

        EspRmakerClaim.RMakerClaimMsgType msgType = EspRmakerClaim.RMakerClaimMsgType.TypeCmdClaimInit;
        EspRmakerClaim.RMakerClaimPayload payload = EspRmakerClaim.RMakerClaimPayload.newBuilder()
                .setMsg(msgType)
                .setCmdPayload(payloadBuf)
                .build();
        ProvisioningLog.i(CLAIM_DIAG_TAG, "csr_next_fragment_request accumulated_chars=" + csrData.length());

        provisionManager.getEspDevice().sendDataToCustomEndPoint(AppConstants.HANDLER_RM_CLAIM, payload.toByteArray(), new ResponseListener() {

            @Override
            public void onSuccess(byte[] returnData) {

                ProvisioningLog.i(CLAIM_DIAG_TAG, "csr_next_fragment_transport_success response_len="
                        + (returnData != null ? returnData.length : -1));
                getCSRFromDevice(returnData);
            }

            @Override
            public void onFailure(Exception e) {

                ProvisioningLog.e(TAG, "Error : " + e.getMessage());
                ProvisioningLog.e(CLAIM_DIAG_TAG, "csr_next_fragment_transport_failed accumulated_chars="
                        + csrData.length()
                        + " exception=" + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(e.getMessage()));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_init);
                        displayError();
                    }
                });
            }
        });
    }

    private void sendCertificateToDevice(final int offset) {

        if (offset == 0) {
            ProvisioningLog.uiProgress(this, TAG, "Claim 4/5", "证书已签发，正在通过 BLE 写入设备");
        }
        if (isClaimingAborted) {
            return;
        }
        ProvisioningLog.d(TAG, "Send certificate to device, offset : " + offset);
        String data = "";

        try {
            int totalLen = certificateData.length();
            int len = offset + dataCount;

            ProvisioningLog.d(TAG, "Length : " + len + " and total len : " + totalLen);

            if (len > totalLen) {
                ProvisioningLog.d(TAG, "Actual end index : " + totalLen);
                data = certificateData.substring(offset, totalLen);
            } else {
                ProvisioningLog.d(TAG, "Actual end index : " + len);
                data = certificateData.substring(offset, len);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        ByteString byteString = ByteString.copyFromUtf8(data);
        EspRmakerClaim.PayloadBuf payloadBuf = EspRmakerClaim.PayloadBuf.newBuilder()
                .setOffset(offset)
                .setTotalLen(certificateData.length())
                .setPayload(byteString)
                .build();

        EspRmakerClaim.RMakerClaimMsgType msgType = EspRmakerClaim.RMakerClaimMsgType.TypeCmdClaimVerify;
        EspRmakerClaim.RMakerClaimPayload payload = EspRmakerClaim.RMakerClaimPayload.newBuilder()
                .setMsg(msgType)
                .setCmdPayload(payloadBuf)
                .build();

        provisionManager.getEspDevice().sendDataToCustomEndPoint(AppConstants.HANDLER_RM_CLAIM, payload.toByteArray(), new ResponseListener() {

            @Override
            public void onSuccess(byte[] returnData) {

                if ((offset + dataCount) >= certificateData.length()) {

                    ProvisioningLog.d(TAG, "Certificate Sent to device successfully.");
                    ProvisioningLog.i(CLAIM_DIAG_TAG, "claim_verify_complete certificate_len=" + certificateData.length());
                    ProvisioningLog.uiProgress(ClaimingActivity.this, TAG, "Claim 5/5", "设备证书写入完成，Claim 成功");
                    ArrayList<String> deviceCaps = provisionManager.getEspDevice().getDeviceCapabilities();
                    /* Claim 顺序保持不变。Claim 完成后先读取设备当前标准 Wi-Fi status，
                     * 再决定复用当前网络还是进入原有 Wi-Fi 配置页面。 */
                    routeAfterClaimWithExistingWifiCheck(deviceCaps);
                } else {
                    int newOffset = offset + dataCount;
                    sendCertificateToDevice(newOffset);
                }
            }

            @Override
            public void onFailure(Exception e) {

                ProvisioningLog.e(TAG, "Error : " + e.getMessage());
                ProvisioningLog.e(CLAIM_DIAG_TAG, "claim_verify_transport_failed offset=" + offset
                        + " exception=" + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(e.getMessage()));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(R.string.error_claiming_verify);
                        displayError();
                    }
                });
            }
        });
    }

    private void sendClaimAbortRequest() {

        ProvisioningLog.d(TAG, "Claim Abort Request");
        ProvisioningLog.w(CLAIM_DIAG_TAG, "claim_abort_send");
        isClaimingAborted = true;

        EspRmakerClaim.PayloadBuf payloadBuf = EspRmakerClaim.PayloadBuf.newBuilder()
                .build();

        EspRmakerClaim.RMakerClaimMsgType msgType = EspRmakerClaim.RMakerClaimMsgType.TypeCmdClaimAbort;
        EspRmakerClaim.RMakerClaimPayload payload = EspRmakerClaim.RMakerClaimPayload.newBuilder()
                .setMsg(msgType)
                .setCmdPayload(payloadBuf)
                .build();

        provisionManager.getEspDevice().sendDataToCustomEndPoint(AppConstants.HANDLER_RM_CLAIM, payload.toByteArray(), new ResponseListener() {

            @Override
            public void onSuccess(byte[] returnData) {

                ProvisioningLog.d(TAG, "Successfully sent claiming abort command");
                ProvisioningLog.w(CLAIM_DIAG_TAG, "claim_abort_transport_success response_len="
                        + (returnData != null ? returnData.length : -1));
            }

            @Override
            public void onFailure(Exception e) {

                ProvisioningLog.e(TAG, "Failed to abort claiming");
                ProvisioningLog.e(CLAIM_DIAG_TAG, "claim_abort_transport_failed exception="
                        + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(e.getMessage()));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        displayError();
                    }
                });
            }
        });
    }

    /**
     * Check if the device is a camera device based on already parsed device information
     * This leverages existing device capabilities and version info parsing
     */
    private boolean isCameraDevice() {
        try {
            // Check version info for camera-related device configuration (following existing pattern)
            String versionInfo = provisionManager.getEspDevice().getVersionInfo();
            if (versionInfo != null && (versionInfo.toLowerCase().contains("camera") ||
                    versionInfo.toLowerCase().contains("stream") ||
                    versionInfo.toLowerCase().contains("video"))) {
                ProvisioningLog.d(TAG, "Camera device detected in version info");
                return true;
            }

            // Check device capabilities for camera-related capabilities
            ArrayList<String> deviceCaps = provisionManager.getEspDevice().getDeviceCapabilities();
            if (deviceCaps != null) {
                for (String cap : deviceCaps) {
                    if (cap != null && (cap.toLowerCase().contains("camera") ||
                            cap.toLowerCase().contains("video") ||
                            cap.toLowerCase().contains("stream"))) {
                        ProvisioningLog.d(TAG, "Camera device detected by capability: " + cap);
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            ProvisioningLog.e(TAG, "Error checking camera device type: " + e.getMessage());
            e.printStackTrace();
        }

        ProvisioningLog.d(TAG, "Device is not detected as a camera device");
        return false;
    }

    private void sendDeviceInfoToCloud(String data) {

        ProvisioningLog.uiProgress(this, TAG, "Claim 云端", "正在向 RainMaker Claim 服务提交设备信息");
        if (isClaimingAborted) {
            return;
        }
        ProvisioningLog.i(CLAIM_DIAG_TAG, "cloud_claim_init_send device_info_len="
                + (data != null ? data.length() : -1));
        Gson gson = new Gson();
        JsonObject body = gson.fromJson(data, JsonObject.class);
        apiManager.initiateClaim(body, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle data) {

                if (data != null) {
                    String res = data.getString(AppConstants.KEY_CLAIM_INIT_RESPONSE);
                    ProvisioningLog.i(CLAIM_DIAG_TAG, "cloud_claim_init_success response_len="
                            + (res != null ? res.length() : -1));
                    sendClaimInitRequest(res);
                } else {
                    ProvisioningLog.e(CLAIM_DIAG_TAG, "cloud_claim_init_success bundle_null=true");
                }
            }

            @Override
            public void onResponseFailure(Exception e) {

                final String errMsg = e.getMessage();
                ProvisioningLog.e(TAG, "Failed to start claiming. Error : " + errMsg);
                ProvisioningLog.e(CLAIM_DIAG_TAG, "cloud_claim_init_response_failed exception="
                        + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(errMsg));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        sendClaimAbortRequest();
                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(errMsg);
                        displayError();
                    }
                });
            }

            @Override
            public void onNetworkFailure(Exception e) {

                final String errMsg = e.getMessage();
                ProvisioningLog.e(TAG, "Failed to start claiming. Error : " + errMsg);
                ProvisioningLog.e(CLAIM_DIAG_TAG, "cloud_claim_init_network_failed exception="
                        + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(errMsg));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        sendClaimAbortRequest();
                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(errMsg);
                        displayError();
                    }
                });
            }
        });
    }

    private void sendCSRToAPI(String data) {

        ProvisioningLog.uiProgress(this, TAG, "Claim 3/5", "设备 CSR 已生成，正在向 RainMaker 请求证书");
        if (isClaimingAborted) {
            return;
        }
        ProvisioningLog.i(CLAIM_DIAG_TAG, "cloud_claim_verify_send csr_len="
                + (data != null ? data.length() : -1));
        Gson gson = new Gson();
        JsonObject body = gson.fromJson(data, JsonObject.class);

        // Add node_policies for camera_claim devices
        if (isCameraClaim) {
            body.addProperty("node_policies", "videostream");
            ProvisioningLog.d(TAG, "Added node_policies: videostream for camera_claim device");
        }

        apiManager.verifyClaiming(body, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle data) {

                if (data != null) {
                    certificateData = data.getString(AppConstants.KEY_CLAIM_VERIFY_RESPONSE);
                    ProvisioningLog.d(TAG, "Data send to cloud for verify");
                    ProvisioningLog.i(CLAIM_DIAG_TAG, "cloud_claim_verify_success certificate_len="
                            + (certificateData != null ? certificateData.length() : -1));
                    sendCertificateToDevice(0);
                } else {
                    ProvisioningLog.e(CLAIM_DIAG_TAG, "cloud_claim_verify_success bundle_null=true");
                }
            }

            @Override
            public void onResponseFailure(Exception e) {

                final String errMsg = e.getMessage();
                ProvisioningLog.e(TAG, "Failed to verify claiming. Error : " + errMsg);
                ProvisioningLog.e(CLAIM_DIAG_TAG, "cloud_claim_verify_response_failed exception="
                        + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(errMsg));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        sendClaimAbortRequest();
                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(errMsg);
                        displayError();
                    }
                });
            }

            @Override
            public void onNetworkFailure(Exception e) {

                final String errMsg = e.getMessage();
                ProvisioningLog.e(TAG, "Failed to verify claiming. Error : " + errMsg);
                ProvisioningLog.e(CLAIM_DIAG_TAG, "cloud_claim_verify_network_failed exception="
                        + e.getClass().getSimpleName()
                        + " message=" + String.valueOf(errMsg));
                e.printStackTrace();

                runOnUiThread(new Runnable() {

                    @Override
                    public void run() {

                        sendClaimAbortRequest();
                        binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                        binding.layoutClaiming.tvClaimingError.setText(errMsg);
                        displayError();
                    }
                });
            }
        });
    }

    private void routeAfterClaimWithExistingWifiCheck(final ArrayList<String> deviceCaps) {
        if (!ExistingWifiReuseHelper.shouldCheckWifi(deviceCaps)) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        if (provisionManager.getEspDevice() == null) {
            routeToWifiOrThread(deviceCaps);
            return;
        }

        ExistingWifiReuseHelper.queryAndAsk(this, provisionManager.getEspDevice(),
                new ExistingWifiReuseHelper.DecisionListener() {
                    @Override
                    public void onReuseCurrentWifi(ExistingWifiReuseHelper.CurrentWifiStatus status) {
                        Intent provisionIntent = new Intent(getApplicationContext(), ProvisionActivity.class);
                        provisionIntent.putExtras(getIntent());
                        /* 继续使用当前网络：显式移除密码，且由 ProvisionActivity 的独立
                         * KEY_REUSE_CURRENT_WIFI 分支保证不调用 ESPDevice.provision()。 */
                        provisionIntent.removeExtra(AppConstants.KEY_PASSWORD);
                        provisionIntent.putExtra(AppConstants.KEY_SSID, status.getSsid());
                        provisionIntent.putExtra(AppConstants.KEY_REUSE_CURRENT_WIFI, true);
                        startActivity(provisionIntent);
                        finish();
                    }

                    @Override
                    public void onReconfigureWifi() {
                        routeToWifiOrThread(deviceCaps);
                    }
                });
    }

    private void goToWiFiScanActivity() {
        finish();
        Intent wifiListIntent = new Intent(getApplicationContext(), WiFiScanActivity.class);
        wifiListIntent.putExtras(getIntent());
        wifiListIntent.putExtra(AppConstants.KEY_SSID, getIntent().getStringExtra(AppConstants.KEY_SSID));
        startActivity(wifiListIntent);
    }

    private void goToThreadConfigActivity(boolean scanCapAvailable) {
        finish();
        Intent threadConfigIntent = new Intent(getApplicationContext(), ThreadConfigActivity.class);
        threadConfigIntent.putExtras(getIntent());
        threadConfigIntent.putExtra(AppConstants.KEY_THREAD_SCAN_AVAILABLE, scanCapAvailable);
        startActivity(threadConfigIntent);
    }

    private void goToWiFiConfigActivity() {
        finish();
        Intent wifiConfigIntent = new Intent(getApplicationContext(), WiFiConfigActivity.class);
        wifiConfigIntent.putExtras(getIntent());
        wifiConfigIntent.putExtra(AppConstants.KEY_SSID, getIntent().getStringExtra(AppConstants.KEY_SSID));
        startActivity(wifiConfigIntent);
    }

    private void routeToWifiOrThread(ArrayList<String> deviceCaps) {
        if (deviceCaps != null) {
            if (deviceCaps.contains(AppConstants.CAPABILITY_WIFI_SCAN)) {
                goToWiFiScanActivity();
            } else if (deviceCaps.contains(AppConstants.CAPABILITY_THREAD_SCAN)) {
                goToThreadConfigActivity(true);
            } else if (deviceCaps.contains(AppConstants.CAPABILITY_THREAD_PROV)) {
                goToThreadConfigActivity(false);
            } else {
                goToWiFiConfigActivity();
            }
        } else {
            goToWiFiConfigActivity();
        }
    }

    private boolean checkAndShowBleLocalCtrlFlow() {
        try {
            String versionInfo = provisionManager.getEspDevice().getVersionInfo();
            if (TextUtils.isEmpty(versionInfo)) return false;

            ArrayList<String> rmakerExtraCaps = new ArrayList<>();
            JSONObject jsonObject = new JSONObject(versionInfo);
            JSONObject rmakerExtraInfo = jsonObject.optJSONObject("rmaker_extra");
            if (rmakerExtraInfo != null) {
                JSONArray caps = rmakerExtraInfo.optJSONArray("cap");
                if (caps != null) {
                    for (int i = 0; i < caps.length(); i++) {
                        rmakerExtraCaps.add(caps.getString(i));
                    }
                }
            }

            boolean hasLocalCtrl = rmakerExtraCaps.contains(AppConstants.CAPABILITY_LOCAL_CTRL);
            ArrayList<String> deviceCaps = provisionManager.getEspDevice().getDeviceCapabilities();
            boolean hasChResp = rmakerExtraCaps.contains(AppConstants.CAPABILITY_CHALLENGE_RESP)
                    || (deviceCaps != null && deviceCaps.contains(AppConstants.CAPABILITY_CHALLENGE_RESP));

            if (hasLocalCtrl && hasChResp) {
                showSkipWifiProvisioningDialog();
                return true;
            }
        } catch (Exception e) {
            ProvisioningLog.e(TAG, "Error checking BLE local ctrl caps: " + e.getMessage());
        }
        return false;
    }

    private void showSkipWifiProvisioningDialog() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                AlertDialog.Builder builder = new AlertDialog.Builder(ClaimingActivity.this);
                builder.setCancelable(false);
                builder.setTitle(R.string.skip_wifi_provisioning_title);
                builder.setMessage(R.string.skip_wifi_provisioning_msg);

                builder.setPositiveButton(R.string.btn_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        String pop = provisionManager.getEspDevice().getProofOfPossession();
                        String devName = provisionManager.getEspDevice().getDeviceName();

                        Intent provisionIntent = new Intent(getApplicationContext(), ProvisionActivity.class);
                        provisionIntent.putExtras(getIntent());
                        if (!TextUtils.isEmpty(devName)) {
                            provisionIntent.putExtra(AppConstants.KEY_DEVICE_NAME, devName);
                        }
                        provisionIntent.putExtra(AppConstants.KEY_PROOF_OF_POSSESSION, pop);
                        provisionIntent.putExtra(AppConstants.KEY_BLE_LOCAL_CTRL, true);
                        startActivity(provisionIntent);
                        finish();
                    }
                });

                builder.setNegativeButton(R.string.btn_no, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ArrayList<String> deviceCaps = provisionManager.getEspDevice().getDeviceCapabilities();
                        routeToWifiOrThread(deviceCaps);
                    }
                });

                if (!isFinishing()) {
                    builder.show();
                }
            }
        });
    }

    private void displayClaimingProgress() {

        RotateAnimation rotate = new RotateAnimation(0, 180, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        rotate.setDuration(3000);
        rotate.setRepeatCount(Animation.INFINITE);
        rotate.setInterpolator(new LinearInterpolator());
        binding.layoutClaiming.ivClaiming.startAnimation(rotate);
        binding.layoutClaiming.tvClaimingProgress.setText(R.string.progress_claiming);
        binding.layoutClaiming.tvClaimingError.setText(R.string.process_take_time);
        ProvisioningLog.uiStatus(this, TAG, binding.layoutClaiming.tvClaimingProgress.getText());
        ProvisioningLog.uiNotice(this, TAG, binding.layoutClaiming.tvClaimingError.getText());
    }

    private void stopClaimingProgress() {
        binding.layoutClaiming.ivClaiming.clearAnimation();
    }

    private void displayError() {

        ProvisioningLog.e(TAG, "Claiming error occurred");
        ProvisioningLog.uiStatus(this, TAG, binding.layoutClaiming.tvClaimingProgress.getText());
        ProvisioningLog.uiNotice(this, TAG, binding.layoutClaiming.tvClaimingError.getText());
        stopClaimingProgress();
        binding.layoutClaiming.tvPleaseWait.setVisibility(View.GONE);
        btnOk.setVisibility(View.VISIBLE);
        binding.layoutClaiming.tvClaimingError.setVisibility(View.VISIBLE);
        binding.layoutClaiming.tvClaimingFailure.setVisibility(View.VISIBLE);
    }

    private void hideError() {

        ProvisioningLog.e(TAG, "Claiming error occurred");
        stopClaimingProgress();
        binding.layoutClaiming.tvPleaseWait.setVisibility(View.VISIBLE);
        btnOk.setVisibility(View.GONE);
        binding.layoutClaiming.tvClaimingError.setVisibility(View.INVISIBLE);
        binding.layoutClaiming.tvClaimingFailure.setVisibility(View.INVISIBLE);
    }

    private Runnable timeoutTask = new Runnable() {

        @Override
        public void run() {
            shouldSendClaimAbortReq = true;
            ProvisioningLog.w(CLAIM_DIAG_TAG, "claim_ui_timeout_reached abort_allowed=true");
        }
    };

    private void showAlertForDeviceDisconnected() {

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setCancelable(false);
        builder.setTitle(R.string.error_title);
        builder.setMessage(R.string.dialog_msg_ble_device_disconnection);

        // Set up the buttons
        builder.setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {

            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.dismiss();
                finish();
            }
        });
        builder.show();
    }
}
