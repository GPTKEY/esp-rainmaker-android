#!/usr/bin/env python3
"""Preserve the real Retrofit/OkHttp failure for Assisted Claim diagnostics.

The upstream ApiManager replaces the original Throwable with a generic
RuntimeException("Claim init failed"), which hides whether the failure is DNS,
TLS, routing, connection refusal, or timeout. This build-only patch keeps the
original exception and logs only non-secret endpoint/error metadata.
"""

from pathlib import Path

TARGET = Path("app/src/main/java/com/espressif/cloudapi/ApiManager.java")


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

    old_method = '''    public void initiateClaim(JsonObject body, final ApiResponseListener listener) {

        Log.d(TAG, "Initiate Claiming...");
        String url = getClaimBaseUrl() + AppConstants.URL_CLAIM_INITIATE;

        apiInterface.initiateClaiming(url, accessToken, body).enqueue(new Callback<ResponseBody>() {

            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {

                Log.d(TAG, "onResponse code  : " + response.code());

                try {
                    if (response.isSuccessful()) {

                        String jsonResponse = response.body().string();
                        Bundle data = new Bundle();
                        data.putString(AppConstants.KEY_CLAIM_INIT_RESPONSE, jsonResponse);
                        listener.onSuccess(data);

                    } else {
                        String jsonErrResponse = response.errorBody().string();
                        processError(jsonErrResponse, listener, "Claim init failed");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    listener.onResponseFailure(new RuntimeException("Claim init failed"));
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                t.printStackTrace();
                listener.onNetworkFailure(new RuntimeException("Claim init failed"));
            }
        });
    }
'''

    new_method = '''    public void initiateClaim(JsonObject body, final ApiResponseListener listener) {

        Log.d(TAG, "Initiate Claiming...");
        final String url = getClaimBaseUrl() + AppConstants.URL_CLAIM_INITIATE;
        Log.i(TAG, "[CLAIM-DIAG] initiateClaim endpoint=" + url);

        apiInterface.initiateClaiming(url, accessToken, body).enqueue(new Callback<ResponseBody>() {

            @Override
            public void onResponse(Call<ResponseBody> call, Response<ResponseBody> response) {

                Log.i(TAG, "[CLAIM-DIAG] initiateClaim HTTP response=" + response.code());

                try {
                    if (response.isSuccessful()) {

                        String jsonResponse = response.body().string();
                        Bundle data = new Bundle();
                        data.putString(AppConstants.KEY_CLAIM_INIT_RESPONSE, jsonResponse);
                        listener.onSuccess(data);

                    } else {
                        String jsonErrResponse = response.errorBody().string();
                        processError(jsonErrResponse, listener, "Claim init failed");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "[CLAIM-DIAG] initiateClaim response processing failed: "
                            + e.getClass().getName() + ": " + e.getMessage(), e);
                    listener.onResponseFailure(e);
                }
            }

            @Override
            public void onFailure(Call<ResponseBody> call, Throwable t) {
                Log.e(TAG, "[CLAIM-DIAG] initiateClaim transport failed; endpoint=" + url
                        + ", type=" + t.getClass().getName()
                        + ", message=" + t.getMessage(), t);
                if (t instanceof Exception) {
                    listener.onNetworkFailure((Exception) t);
                } else {
                    listener.onNetworkFailure(new RuntimeException(t));
                }
            }
        });
    }
'''

    source = replace_once(
        source,
        old_method,
        new_method,
        "initiateClaim root-cause propagation",
    )

    TARGET.write_text(source, encoding="utf-8")
    print(f"Patched {TARGET}")


if __name__ == "__main__":
    main()
