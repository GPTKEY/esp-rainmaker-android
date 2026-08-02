#!/usr/bin/env python3
"""Apply the temporary Assisted Claim diagnostics used by the cloud APK build.

The patch is intentionally kept as a checked script during diagnosis so that:
- upstream application source remains easy to compare;
- every cloud build applies exactly the same changes;
- the build fails loudly if upstream code changes and a patch anchor no longer matches.

No access token, proof-of-possession value, certificate, CSR, or full claim response
is written to logcat by this patch.
"""

from pathlib import Path

TARGET = Path("app/src/main/java/com/espressif/ui/activities/ClaimingActivity.java")


def replace_once(source: str, old: str, new: str, label: str) -> str:
    count = source.count(old)
    if count != 1:
        raise RuntimeError(
            f"Patch anchor '{label}' expected exactly once, found {count}. "
            "The upstream file may have changed."
        )
    return source.replace(old, new, 1)


def main() -> None:
    source = TARGET.read_text(encoding="utf-8")

    source = replace_once(
        source,
        "import com.espressif.rainmaker.R;\n",
        "import com.espressif.rainmaker.BuildConfig;\n"
        "import com.espressif.rainmaker.R;\n",
        "BuildConfig import",
    )

    source = replace_once(
        source,
        "    private static final String TAG = ClaimingActivity.class.getSimpleName();\n",
        "    private static final String TAG = ClaimingActivity.class.getSimpleName();\n"
        "    private static final long CLAIM_CLOUD_TIMEOUT_MS = 30000L;\n",
        "diagnostic timeout constant",
    )

    source = replace_once(
        source,
        "    private boolean hasTriedAgain = false;\n"
        "    private boolean isCameraClaim = false;\n",
        "    private boolean hasTriedAgain = false;\n"
        "    private boolean isCameraClaim = false;\n"
        "    private boolean claimStartInProgress = false;\n"
        "    private boolean claimStartCompleted = false;\n"
        "    private boolean claimCloudRequestInProgress = false;\n"
        "    private String claimStage = \"activity-created\";\n",
        "diagnostic state fields",
    )

    source = replace_once(
        source,
        "    protected void onDestroy() {\n"
        "        EventBus.getDefault().unregister(this);\n"
        "        super.onDestroy();\n"
        "    }\n",
        "    protected void onDestroy() {\n"
        "        handler.removeCallbacks(claimCloudTimeoutTask);\n"
        "        EventBus.getDefault().unregister(this);\n"
        "        super.onDestroy();\n"
        "    }\n",
        "onDestroy cleanup",
    )

    source = replace_once(
        source,
        "    private void sendClaimStartRequest() {\n\n"
        "        Log.d(TAG, \"Claim Start Request\");\n",
        "    private void sendClaimStartRequest() {\n\n"
        "        if (isClaimingAborted || claimStartInProgress || claimStartCompleted) {\n"
        "            Log.w(TAG, \"[CLAIM-DIAG] Ignoring duplicate Claim Start; stage=\" + claimStage\n"
        "                    + \", inProgress=\" + claimStartInProgress\n"
        "                    + \", completed=\" + claimStartCompleted);\n"
        "            return;\n"
        "        }\n"
        "        claimStartInProgress = true;\n"
        "        claimStage = \"ble-claim-start\";\n"
        "        Log.i(TAG, \"[CLAIM-DIAG] Claim Start Request\");\n",
        "claim start guard",
    )

    source = replace_once(
        source,
        "                Log.d(TAG, \"Successfully sent claiming start command\");\n"
        "                processClaimingStartResponse(returnData);\n",
        "                claimStartInProgress = false;\n"
        "                claimStartCompleted = true;\n"
        "                claimStage = \"ble-claim-start-response\";\n"
        "                Log.i(TAG, \"[CLAIM-DIAG] Claim Start response received; bytes=\"\n"
        "                        + (returnData == null ? 0 : returnData.length));\n"
        "                processClaimingStartResponse(returnData);\n",
        "claim start success diagnostics",
    )

    source = replace_once(
        source,
        "                Log.e(TAG, \"Failed to start claiming\");\n"
        "                e.printStackTrace();\n",
        "                claimStartInProgress = false;\n"
        "                claimStartCompleted = false;\n"
        "                claimStage = \"ble-claim-start-failed\";\n"
        "                Log.e(TAG, \"[CLAIM-DIAG] Failed to start claiming: \"\n"
        "                        + describeException(e), e);\n",
        "claim start failure diagnostics",
    )

    old_cloud_method = '''    private void sendDeviceInfoToCloud(String data) {

        if (isClaimingAborted) {
            return;
        }
        Gson gson = new Gson();
        JsonObject body = gson.fromJson(data, JsonObject.class);
        apiManager.initiateClaim(body, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle data) {

                if (data != null) {
                    String res = data.getString(AppConstants.KEY_CLAIM_INIT_RESPONSE);
                    Log.d(TAG, "API Response : " + res);
                    sendClaimInitRequest(res);
                }
            }

            @Override
            public void onResponseFailure(Exception e) {

                final String errMsg = e.getMessage();
                Log.e(TAG, "Failed to start claiming. Error : " + errMsg);
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
                Log.e(TAG, "Failed to start claiming. Error : " + errMsg);
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
'''

    new_cloud_method = '''    private void sendDeviceInfoToCloud(String data) {

        if (isClaimingAborted) {
            Log.w(TAG, "[CLAIM-DIAG] Cloud initiate skipped because claiming is aborted");
            return;
        }
        if (claimCloudRequestInProgress) {
            Log.w(TAG, "[CLAIM-DIAG] Duplicate cloud initiate request ignored; stage=" + claimStage);
            return;
        }

        final JsonObject body;
        try {
            Gson gson = new Gson();
            body = gson.fromJson(data, JsonObject.class);
        } catch (Exception e) {
            showClaimDiagnosticError("cloud-payload-parse-failed", e);
            return;
        }

        if (body == null) {
            showClaimDiagnosticError("cloud-payload-empty",
                    new IllegalStateException("Device returned an empty claim payload"));
            return;
        }

        claimCloudRequestInProgress = true;
        claimStage = "cloud-initiate-request";
        handler.removeCallbacks(claimCloudTimeoutTask);
        handler.postDelayed(claimCloudTimeoutTask, CLAIM_CLOUD_TIMEOUT_MS);

        Log.i(TAG, "[CLAIM-DIAG] Calling initiateClaim; baseUrl=" + BuildConfig.CLAIM_BASE_URL
                + ", payloadKeys=" + body.keySet()
                + ", payloadChars=" + data.length());

        apiManager.initiateClaim(body, new ApiResponseListener() {

            @Override
            public void onSuccess(Bundle result) {

                handler.removeCallbacks(claimCloudTimeoutTask);
                claimCloudRequestInProgress = false;
                claimStage = "cloud-initiate-response";

                if (result == null) {
                    showClaimDiagnosticError("cloud-response-null",
                            new IllegalStateException("Claim service returned no response bundle"));
                    return;
                }

                String response = result.getString(AppConstants.KEY_CLAIM_INIT_RESPONSE);
                if (TextUtils.isEmpty(response)) {
                    showClaimDiagnosticError("cloud-response-empty",
                            new IllegalStateException("Claim service returned an empty init response"));
                    return;
                }

                Log.i(TAG, "[CLAIM-DIAG] initiateClaim succeeded; responseChars=" + response.length());
                sendClaimInitRequest(response);
            }

            @Override
            public void onResponseFailure(Exception e) {
                showClaimDiagnosticError("cloud-response-failed", e);
            }

            @Override
            public void onNetworkFailure(Exception e) {
                showClaimDiagnosticError("cloud-network-failed", e);
            }
        });
    }
'''

    source = replace_once(
        source,
        old_cloud_method,
        new_cloud_method,
        "cloud initiate diagnostics",
    )

    source = replace_once(
        source,
        "    private Runnable timeoutTask = new Runnable() {\n",
        '''    private String describeException(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        String message = e.getMessage();
        if (TextUtils.isEmpty(message)) {
            return e.getClass().getSimpleName();
        }
        return e.getClass().getSimpleName() + ": " + message;
    }

    private void showClaimDiagnosticError(final String stage, final Exception error) {
        claimStage = stage;
        claimCloudRequestInProgress = false;
        handler.removeCallbacks(claimCloudTimeoutTask);

        final String detail = describeException(error);
        Log.e(TAG, "[CLAIM-DIAG] stage=" + stage + ", error=" + detail, error);

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                binding.layoutClaiming.tvClaimingError.setText(stage + "\n" + detail);
                displayError();
            }
        });
    }

    private final Runnable claimCloudTimeoutTask = new Runnable() {
        @Override
        public void run() {
            if (!claimCloudRequestInProgress || isFinishing()) {
                return;
            }
            claimCloudRequestInProgress = false;
            claimStage = "cloud-initiate-timeout";
            Log.e(TAG, "[CLAIM-DIAG] initiateClaim timed out after "
                    + CLAIM_CLOUD_TIMEOUT_MS + " ms; baseUrl=" + BuildConfig.CLAIM_BASE_URL);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    binding.layoutClaiming.tvClaimingProgress.setText(R.string.error_claiming_progress);
                    binding.layoutClaiming.tvClaimingError.setText(
                            "cloud-initiate-timeout\nNo response from claim service within 30 seconds");
                    displayError();
                }
            });
        }
    };

    private Runnable timeoutTask = new Runnable() {
''',
        "diagnostic helpers",
    )

    TARGET.write_text(source, encoding="utf-8")
    print(f"Patched {TARGET}")


if __name__ == "__main__":
    main()
