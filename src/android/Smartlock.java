package org.apache.cordova.smartlock;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentSender;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.gms.auth.api.credentials.Credential;
import com.google.android.gms.auth.api.credentials.CredentialRequestResult;
import com.google.android.gms.common.api.CommonStatusCodes;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;

public class Smartlock extends CordovaPlugin {

    private final static String[] actions = {"request", "save", "delete"};
    private static final String TAG = "Smartlock";
    private static SmartlockManager smartlockManager;

    private boolean isCallPocessing = false;
    private static final int RC_READ = 11;
    private static final int RC_SAVE = 10;
    private static CallbackContext callbackContext;


    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        this.smartlockManager = new SmartlockManager(this.cordova.getActivity());

    }

    public boolean execute(final String action, JSONArray args, CallbackContext callbackContext) {
        if (isCallPocessing) {
            sendConcurrentError();
            return true;
        }

        isCallPocessing = true;
        this.cordova.setActivityResultCallback(this);
        this.callbackContext = callbackContext;
        this.connect(action, args, callbackContext);
        return Arrays.asList(actions).contains(action);
    }

    private void connect(final String action, JSONArray args, CallbackContext callbackContext) {
        this.smartlockManager.connect(new SmartlockManager.ReadyListener() {
            @Override
            public void ready() {
                doAction(action, args, callbackContext);
            }

            @Override
            public void fail() {
                sendError(callbackContext, PluginError.SMARTLOCK__COMMON__GOOGLE_API_UNAVAILABLE);
            }
        });
    }

    private void doAction(final String action, JSONArray args, CallbackContext callbackContext) {
        if (action.equals("request")) {
            smartlockManager.executeRequest(new ResultCallback<CredentialRequestResult>() {
                @Override
                public void onResult(CredentialRequestResult credentialRequestResult) {
                    Status status = credentialRequestResult.getStatus();
                    Log.d(TAG, "Get status " + credentialRequestResult);
                    if (status.isSuccess()) {
                        Credential credential = credentialRequestResult.getCredential();
                        sendRequestSuccess(callbackContext, credential);
                    } else if (status.getStatusCode() == CommonStatusCodes.RESOLUTION_REQUIRED) {
                        resolveResult(callbackContext, status, RC_READ);
                    } else if (status.getStatusCode() == CommonStatusCodes.SIGN_IN_REQUIRED) {
                        Log.d(TAG, "Sign in required");
                        sendError(callbackContext, PluginError.SMARTLOCK__REQUEST__ACCOUNTS_NOT_FOUND);
                    } else {
                        Log.w(TAG, "Unrecognized status code: " + status.getStatusCode());
                        sendError(callbackContext, PluginError.SMARTLOCK__COMMON__UNKOWN);
                    }
                }
            });
            return;
        }

        if (action.equals("save")){
            Credential credential = null;
            try {
                credential = parseSaveRequest(args);
                smartlockManager.executeSave(credential, new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        if (status.isSuccess()) {
                            Log.d(TAG, "Save success");
                            sendSuccess(callbackContext);
                        } else {
                            resolveResult(callbackContext, status, RC_SAVE);
                        }
                    }
                });
            } catch (JSONException e) {
                sendError(callbackContext, PluginError.SMARTLOCK__SAVE__BAD_REQUEST);
            }
            return;
        }

        if (action.equals("delete")){
            Credential credential = null;
            try {
                credential = parseDeleteRequest(args);
                smartlockManager.executeDelete(credential, new ResultCallback<Status>() {
                    @Override
                    public void onResult(Status status) {
                        if (status.isSuccess()) {
                            Log.d(TAG, "Delete success");
                            sendSuccess(callbackContext);
                        } else {
                            sendError(callbackContext, PluginError.SMARTLOCK__DELETE);
                        }
                    }
                });
            } catch (JSONException e) {
                sendError(callbackContext, PluginError.SMARTLOCK__SAVE__BAD_REQUEST);
            }
            return;
        }

        if (action.equals("disableAutoSignIn")){
            smartlockManager.executeDisableAutoSignIn(new ResultCallback<Status>() {
                @Override
                public void onResult(Status status) {
                    if (status.isSuccess()) {
                        Log.d(TAG, "DisableAutoSignIn success");
                        sendSuccess(callbackContext);
                    } else {
                        sendError(callbackContext, PluginError.SMARTLOCK__DISABLE_AUTO_SIGN_IN);
                    }
                }
            });
            return;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Log.d(TAG, "Result code on activity result: " + resultCode);

        switch (requestCode) {
            case RC_READ:
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "Request success");
                    Credential credential = data.getParcelableExtra(Credential.EXTRA_KEY);
                    sendRequestSuccess(callbackContext, credential);
                } else {
                    Log.e(TAG, "Request failed");
                    sendError(callbackContext, PluginError.SMARTLOCK__REQUEST__DIALOG_CANCELLED);
                }
                break;
            case RC_SAVE:
                if (resultCode == Activity.RESULT_OK) {
                    Log.d(TAG, "Save success");
                    sendSuccess(callbackContext);
                } else {
                    Log.e(TAG, "Save Failed");
                    sendError(callbackContext, PluginError.SMARTLOCK__SAVE);
                }
                break;
        }
    }

    private Credential parseSaveRequest(JSONArray args) throws JSONException {
        JSONObject argsObject;
        argsObject = args.getJSONObject(0);
        String id = (String) argsObject.get("id");

        String password = (String) argsObject.get("password");
        if (TextUtils.isEmpty(password)) {
            throw new JSONException("Password cant be empty");
        }


        String name;
        String profileUri;
        try {
            name = (String) argsObject.get("name");
        } catch (JSONException e) {
            name = "";
        }
        try {
            profileUri = (String) argsObject.get("profileUri");
        } catch (JSONException e) {
            profileUri = "";
        }

        return new Credential.Builder(id)
                .setName(name)
                .setPassword(password)
                .setProfilePictureUri(Uri.parse(profileUri))
                .build();
    }

    private Credential parseDeleteRequest(JSONArray args) throws JSONException {
        JSONObject argsObject;
        argsObject = args.getJSONObject(0);
        String id = (String) argsObject.get("id");

        return new Credential.Builder(id)
                .build();
    }

    private void resolveResult(CallbackContext callbackContext, Status status, int requestCode) {
        if (status.hasResolution()) {
            try {
                status.startResolutionForResult(this.cordova.getActivity(), requestCode);
            } catch (IntentSender.SendIntentException e) {
                // Weird Resolution error on request/save
                sendError(callbackContext, PluginError.SMARTLOCK__COMMON__RESOLUTION_PROMPT_FAIL);
            }
        } else {
            sendError(callbackContext, PluginError.SMARTLOCK__COMMON__UNKOWN);
        }
    }

    private void sendSuccess(CallbackContext callbackContext) {
        this.cordova.getActivity().runOnUiThread(() -> callbackContext.success());
        isCallPocessing = false;
    }

    private void sendRequestSuccess(CallbackContext callbackContext, Credential credential) {
        JSONObject response = new JSONObject();
        try {
            response.put("id", credential.getId());
            response.put("name", credential.getName());
            response.put("password", credential.getPassword());
        } catch (JSONException e) {}

        this.cordova.getActivity().runOnUiThread(() -> callbackContext.success(response));
        isCallPocessing = false;
    }

    private void sendError(CallbackContext callbackContext, PluginError error) {
        sendError(callbackContext, error.getValue(), error.getMessage());
        if (!error.equals(PluginError.SMARTLOCK__COMMON__CONCURRENT_NOT_ALLOWED)) {
            isCallPocessing = false;
        }
    }

    private void sendError(CallbackContext callbackContext, int code, String message) {
        JSONObject resultJson = new JSONObject();
        try {
            resultJson.put("code", code);
            resultJson.put("message", message);

            PluginResult result = new PluginResult(PluginResult.Status.ERROR, resultJson);
            this.cordova.getActivity().runOnUiThread(() ->
                    callbackContext.sendPluginResult(result));
        } catch (JSONException e) {
            Log.e(TAG, e.getMessage(), e);
        }
    }

    private void sendConcurrentError() {
        sendError(callbackContext, PluginError.SMARTLOCK__COMMON__CONCURRENT_NOT_ALLOWED);
    }

}
