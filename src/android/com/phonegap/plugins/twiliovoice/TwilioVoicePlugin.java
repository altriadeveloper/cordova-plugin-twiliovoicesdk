package com.phonegap.plugins.twiliovoice;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.google.firebase.iid.FirebaseInstanceId;
import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.ConnectOptions;
import com.twilio.voice.RegistrationException;
import com.twilio.voice.RegistrationListener;
import com.twilio.voice.UnregistrationListener;
import com.twilio.voice.Voice;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import capacitor.android.plugins.R;

public class TwilioVoicePlugin extends CordovaPlugin {
    //region Fields
    public final static String TAG = "TwilioVoicePlugin";
    // Google Play Services Request Magic Number
    private static final int PLAY_SERVICES_RESOLUTION_REQUEST = 9000;

    // * Cordova Fields
    private boolean initialized = false;
    private Activity mainActivity;
    private Context applicationContext;
    private Context webviewContext;
    private Intent mainIntent;
    private CallbackContext savedCallbackContext;

    // * Twilio Fields
    private boolean shouldRegisterForPush;
    private JSONArray initDeviceSetupArgs;
    private String accessToken;
    private String fcmToken;

    private Call activeCall;
    private CallInvite activeCallInvite;
    private Intent incomingCallIntent;
    private Timer callDurationTimer;
    private Instant callStartTime;
    // * Android System Fields
    private AudioManager audioManager;
    private RegistrationListener registrationListener = registrationListener();
    private Call.Listener callListener = callListener();
    private VoiceBroadcastReceiver voiceBroadcastReceiver;

    private NotificationManager notificationManager;
    private int currentNotificationId = 1;
    private String currentNotificationText;

    private boolean isReceiverRegistered = false;
    private int savedAudioMode = AudioManager.MODE_INVALID;

    // * UI
    private AlertDialog alertDialog;
    private Snackbar callStatusSnackbar;
    private Boolean isRinging = false;
    //endregion

    //region Cordova Implementation
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void initialize(CordovaInterface cordova, CordovaWebView webView) {
        super.initialize(cordova, webView);
        Log.v(TAG, "Initializing");

        mainActivity = cordova.getActivity();
        applicationContext = mainActivity.getApplicationContext();
        mainIntent = mainActivity.getIntent();

        // Configuration

        webviewContext = TwilioVoicePlugin.this.webView.getContext();
        notificationManager = (NotificationManager) webviewContext.getSystemService(Activity.NOTIFICATION_SERVICE);

        // Register Intent For Call Actions
        voiceBroadcastReceiver = new VoiceBroadcastReceiver();
        registerReceiver();

        // Initialize Audio
        SoundPoolManager.getInstance(applicationContext);
        audioManager = (AudioManager) applicationContext.getSystemService(applicationContext.AUDIO_SERVICE);

        // If incoming intent is launched from call notification, handle call
        if (mainIntent.getAction().equals(Constants.ACTION_INCOMING_CALL)) {
            Log.v(TAG, "Intent Launched With Incoming Call Action");
            incomingCallIntent = mainIntent;
            handleIncomingCallIntent(incomingCallIntent);
        }
        if (mainIntent.getAction().equals(Constants.ACTION_CANCEL_CALL)) {
            incomingCallIntent = null;
            handleIncomingCallIntent(incomingCallIntent);
        }
    }

    @Override
    public Object onMessage(String id, Object data) {
        return super.onMessage(id, data);
    }

    @RequiresApi(api = Build.VERSION_CODES.O_MR1)
    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.v(TAG, "onNewIntent");
        final String action = intent.getAction();
        Log.v(TAG, "Intent Action: " + action);
        KeyguardManager keyguardManager = (KeyguardManager) mainActivity.getSystemService(Context.KEYGUARD_SERVICE);
        switch (action) {
            case Constants.ACTION_INCOMING_CALL:
                Log.v(TAG, "NEW Intent Launched With Incoming Call Action");
                mainActivity.setShowWhenLocked(true);
                mainActivity.setTurnScreenOn(true);
                keyguardManager.requestDismissKeyguard(mainActivity, null);
                if (incomingCallIntent == null) {
                    Log.v(TAG, "No IncomingCallIntent Exists Before");
                    incomingCallIntent = intent;
                    handleIncomingCallIntent(incomingCallIntent);
                }
                break;
            case Constants.ACTION_ACCEPT:
                mainActivity.setShowWhenLocked(true);
                mainActivity.setTurnScreenOn(true);
                keyguardManager.requestDismissKeyguard(mainActivity, null);
                if (activeCallInvite == null) {
                    Log.v(TAG, "No IncomingCallIntent Exists Before");
                    incomingCallIntent = intent;
                    handleIncomingCallIntent(intent);
                    activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                    Log.v(TAG, "Accepted Call Intent");
                    SoundPoolManager.getInstance(mainActivity).stopRinging();
                    isRinging = false;
                    activeCallInvite.accept(applicationContext, callListener);
                    callStartTime = Instant.now();

                    activeCallInvite = null;
                    incomingCallIntent = null;

                    clearCallUIElements();

                    if (callStatusSnackbar != null) {
                        callStatusSnackbar.show();
                    }
                }
                break;
            case Constants.ACTION_REJECT:
                mainActivity.setShowWhenLocked(true);
                mainActivity.setTurnScreenOn(true);
                keyguardManager.requestDismissKeyguard(mainActivity, null);
                if (activeCallInvite == null) {
                    Log.v(TAG, "No IncomingCallIntent Exists Before");
                    activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);

                    Log.v(TAG, "Canceled Call Intent");
                    SoundPoolManager.getInstance(mainActivity).stopRinging();
                    isRinging = false;
                    if (activeCallInvite != null) {
                        activeCallInvite.reject(applicationContext);
                        javascriptCallback("oncallinvitecanceled", savedCallbackContext);
                    }

                    resetCallStatus();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onResume(boolean multitasking) {
        super.onResume(multitasking);
        Log.v(TAG, "onResume");
    }

    @Override
    public void onPause(boolean multitasking) {
        super.onPause(multitasking);
        Log.v(TAG, "onPause");
                // TODO: This might not be a good idea.   Maybe put the call on hold?
        // clearCallUIElements();
    }

    @Override
    public void onDestroy() {
        Log.v(TAG, "onDestroy");
        resetCallStatus();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initializeWithAccessTokenAndShouldRegisterForPush(final JSONArray arguments, final CallbackContext callbackContext) throws JSONException {
        Log.v(TAG, "Initializing With Access Token And ShouldRegisterForPush");
        shouldRegisterForPush = arguments.getBoolean(1);
        initializeWithAccessToken(arguments, callbackContext);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initializeWithAccessToken(final JSONArray arguments, final CallbackContext callbackContext) throws JSONException {
        Log.v(TAG, "Initializing With Access Token");
        savedCallbackContext = callbackContext;
        accessToken = arguments.getString(0);

        getPushToken();

        if (incomingCallIntent != null) {
            Log.v(TAG, "Handle an incoming call");
            handleIncomingCallIntent(incomingCallIntent);
            incomingCallIntent = null;
        }

        if (accessToken != null) {
            javascriptCallback("onclientinitialized", savedCallbackContext);
        }
    }

    private void acceptCallInvite(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "acceptCallInvite");
        if (activeCallInvite == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                activeCallInvite.accept(cordova.getActivity(), callListener);
                callbackContext.success();
            }
        });
    }

    private void call(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "call");
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                try {
                    String accessToken = arguments.optString(0);
                    JSONObject options = arguments.optJSONObject(1);
                    Map<String, String> map = getMap(options);

                    ConnectOptions connectOptions = new ConnectOptions.Builder(accessToken)
                            .params(map)
                            .build();

                    if (activeCall != null && activeCall.getState().equals(Call.State.CONNECTED)) {
                        activeCall.disconnect();
                    }
                    activeCall = Voice.connect(mainActivity, connectOptions, callListener);
                    // activeCall = Voice.connect(cordova.getActivity(), accessToken, map, callListener);
                    Log.v(TAG, "Placing call with params: " + map.toString());
                } catch (Exception ex) {
                    javascriptErrorCallback(42, "Error making call", savedCallbackContext);
                }
            }
        });
    }

    private void cancelNotification(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "cancelNotification");
        notificationManager.cancelAll();
        callbackContext.success();
    }

    private void disconnect(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "disconnect");
        if (activeCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                activeCall.disconnect();
                callbackContext.success();
            }
        });
    }

    private void isCallMuted(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "isCallMuted");
        if (activeCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.OK, false));
            return;
        }
        PluginResult result = new PluginResult(PluginResult.Status.OK, activeCall.isMuted());
        callbackContext.sendPluginResult(result);
    }

    private void muteCall(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "muteCall");
        if (activeCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                activeCall.mute(true);
                callbackContext.success();
            }
        });
    }

    private void registerCurrentDeviceForPush(final JSONArray arguments, final CallbackContext callbackContext) throws JSONException {
        Log.v(TAG, "registerCurrentDeviceForPush");
        String oldDevicePushToken = arguments.getString(0);
        if (oldDevicePushToken != null && oldDevicePushToken != "null") {
            unregisterOldPushDevice(oldDevicePushToken);
        }
        Voice.register(accessToken, Voice.RegistrationChannel.FCM, fcmToken, registrationListener);
        JSONObject pushTokenProperties = new JSONObject();
        pushTokenProperties.put("pushDeviceToken", fcmToken);
        javascriptCallback("onpushdevicetokenregistered", pushTokenProperties, savedCallbackContext);
    }

    private void rejectCallInvite(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "rejectCallInvite");
        if (activeCallInvite == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                activeCallInvite.reject(cordova.getActivity());
                callbackContext.success();
            }
        });
    }

    private void sendDigits(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "sendDigits");
        if (arguments == null || arguments.length() < 1 || activeCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                activeCall.sendDigits(arguments.optString(0));
                callbackContext.success();
            }
        });
    }

    private void setSpeaker(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "setSpeaker");
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                String mode = arguments.optString(0);
                if (mode.equals("on")) {
                    Log.v(TAG, "SPEAKER");
                    audioManager.setMode(AudioManager.MODE_NORMAL);
                    audioManager.setSpeakerphoneOn(true);
                } else {
                    Log.v(TAG, "EARPIECE");
                    audioManager.setMode(AudioManager.MODE_IN_CALL);
                    audioManager.setSpeakerphoneOn(false);
                }
            }
        });
    }

    private void showNotification(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "!!!!!!!!!!!!!!!!!showNotification");
        notificationManager.cancelAll();
        currentNotificationText = arguments.optString(0);

        PackageManager pm = webviewContext.getPackageManager();
        Intent notificationIntent = pm.getLaunchIntentForPackage(webviewContext.getPackageName());
        notificationIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        notificationIntent.putExtra("notificationTag", "BVNotification");

        PendingIntent pendingIntent = PendingIntent.getActivity(webviewContext, 0, notificationIntent, 0);
        int notification_icon = webviewContext.getResources().getIdentifier("notification", "drawable", webviewContext.getPackageName());
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(webviewContext)
                        .setSmallIcon(notification_icon)
                        .setContentTitle("Incoming Call")
                        .setContentText(currentNotificationText)
                        .setContentIntent(pendingIntent);
        notificationManager.notify(currentNotificationId, mBuilder.build());

        callbackContext.success();
    }

    private void unmuteCall(final JSONArray arguments, final CallbackContext callbackContext) {
        Log.v(TAG, "unmuteCall");
        if (activeCall == null) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.ERROR));
            return;
        }
        cordova.getThreadPool().execute(new Runnable() {
            public void run() {
                activeCall.mute(false);
                callbackContext.success();
            }
        });
    }

    //region Core Interface Methods
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public boolean execute(final String action, final JSONArray args, final CallbackContext callbackContext) throws JSONException {
        switch (action) {
            case "initializeWithAccessToken":
                initializeWithAccessToken(args, callbackContext);
                break;
            case "initializeWithAccessTokenAndShouldRegisterForPush":
                initializeWithAccessTokenAndShouldRegisterForPush(args, callbackContext);
                break;
            case "registerCurrentDeviceForPush":
                registerCurrentDeviceForPush(args, callbackContext);
                break;
            case "call":
                call(args, callbackContext);
                break;
            case "acceptCallInvite":
                acceptCallInvite(args, callbackContext);
                break;
            case "disconnect":
                disconnect(args, callbackContext);
                break;
            case "sendDigits":
                sendDigits(args, callbackContext);
                break;
            case "muteCall":
                muteCall(args, callbackContext);
                break;
            case "unmuteCall":
                unmuteCall(args, callbackContext);
                break;
            case "isCallMuted":
                isCallMuted(args, callbackContext);
                break;
            case "callStatus":
                // TODO: Implement?
                break;
            case "rejectCallInvite":
                rejectCallInvite(args, callbackContext);
                break;
            case "showNotification":
                showNotification(args, callbackContext);
                break;
            case "cancelNotification":
                cancelNotification(args, callbackContext);
                break;
            case "setSpeaker":
                setSpeaker(args, callbackContext);
                break;
            default:
                return false;
        }
        return true;
    }

    private void javascriptCallback(String event, JSONObject arguments, CallbackContext callbackContext) {
        Log.v(TAG, "javascriptCallback " + event);
        if (callbackContext == null) {
            Log.v(TAG, "No callback context for call: " + event);
            String callText;
            if (arguments != null) {
                // callText = String.format("document.dispatchEvent(new CustomEvent('%s', { detail: %s }))", event.substring(2), arguments.toString());
                callText = String.format("document.dispatchEvent(new CustomEvent('%s', %s))", event, arguments.toString());
            } else {
                callText = String.format("document.dispatchEvent(new CustomEvent('%s'))", event);
            }
            Log.v(TAG, callText);
            WebView nativeWebView = (WebView) webView.getView();
            if (nativeWebView != null) {
                Log.v(TAG, "Native WebView Exists");
                nativeWebView.evaluateJavascript(callText, null);
            }
            return; // TODO: Or should use the persisted one?
        }

        JSONObject options = new JSONObject();
        try {
            options.putOpt("callback", event);
            options.putOpt("arguments", arguments);
        } catch (JSONException e) {
            callbackContext.sendPluginResult(new PluginResult(PluginResult.Status.JSON_EXCEPTION));
        }

        PluginResult result = new PluginResult(PluginResult.Status.OK, options);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);
    }

    private void javascriptCallback(String event, CallbackContext callbackContext) {
        Log.v(TAG, "javascriptCallback " + event);
        javascriptCallback(event, null, callbackContext);
    }

    private void javascriptErrorCallback(int errorCode, String errorMessage, CallbackContext callbackContext) {
        Log.v(TAG, "javascriptErrorCallback " + errorMessage);
        JSONObject object = new JSONObject();
        try {
            object.putOpt("message", errorMessage);
        } catch (JSONException e) {
            callbackContext.sendPluginResult(new PluginResult(
                    PluginResult.Status.JSON_EXCEPTION));
            return;
        }
        PluginResult result = new PluginResult(PluginResult.Status.ERROR, object);
        result.setKeepCallback(true);
        if (callbackContext != null) {
            callbackContext.sendPluginResult(result);
        } else {
            Log.v(TAG, "JavascriptErrorCallback:" + errorMessage);
        }
    }
    //endregion
    //endregion

    //region Private Methods
    private void unregisterOldPushDevice(String oldDevicePushToken) {
        Log.v(TAG, "unregisterOldPushDevice");
        if (accessToken != null && oldDevicePushToken != null && oldDevicePushToken != "null") {
            Voice.unregister(accessToken, Voice.RegistrationChannel.FCM, oldDevicePushToken, new UnregistrationListener() {
                @Override
                public void onUnregistered(String accessToken, String fcmToken) {
                    Log.v(TAG, "UnregisterOldPushDevice OnUnregistered");
                }

                @Override
                public void onError(RegistrationException registrationException, String accessToken, String fcmToken) {
                    Log.v(TAG, "UnregisterOldPushDevice OnError" + registrationException.getMessage());
                }
            });
        }
    }

    private String getCallState(Call.State callState) {
        Log.v(TAG, "getCallState");
        if (callState == Call.State.CONNECTED) {
            return "connected";
        } else if (callState == Call.State.CONNECTING) {
            return "connecting";
        } else if (callState == Call.State.DISCONNECTED) {
            return "disconnected";
        }
        return null;
    }

    private void setAudioFocus(boolean setFocus) {
        Log.v(TAG, "setAudioFocus");
        if (audioManager != null) {
            if (setFocus) {
                savedAudioMode = audioManager.getMode();
                // Request audio focus before making any device switch.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    AudioAttributes playbackAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build();
                    AudioFocusRequest focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                            .setAudioAttributes(playbackAttributes)
                            .setAcceptsDelayedFocusGain(true)
                            .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                                @Override
                                public void onAudioFocusChange(int i) {
                                }
                            })
                            .build();
                    audioManager.requestAudioFocus(focusRequest);
                } else {
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL,
                            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                }
                /*
                 * Start by setting MODE_IN_COMMUNICATION as default audio mode. It is
                 * required to be in this mode when playout and/or recording starts for
                 * best possible VoIP performance. Some devices have difficulties with speaker mode
                 * if this is not set.
                 */
                audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            } else {
                audioManager.setMode(savedAudioMode);
                audioManager.abandonAudioFocus(null);
            }
        }
    }

    public Map<String, String> getMap(JSONObject object) {
        Log.v(TAG, "getMap");
        if (object == null) {
            return null;
        }

        Map<String, String> map = new HashMap<String, String>();

        @SuppressWarnings("rawtypes")
        Iterator keys = object.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            map.put(key, object.optString(key));
        }
        return map;
    }
    //endregion

    //region Twilio Handlers
    @RequiresApi(api = Build.VERSION_CODES.O)
    private void handleIncomingCallIntent(Intent intent) {
        Log.v(TAG, "handleIncomingCallIntent -" + intent.getAction());

        switch (intent.getAction()) {
            case Constants.ACTION_ACCEPT:
                activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                if (activeCallInvite != null && !isRinging) {

                    NotificationManager mNotifyMgr =
                            (NotificationManager) cordova.getActivity().getSystemService(Activity.NOTIFICATION_SERVICE);
                    mNotifyMgr.cancel(intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0));
                    JSONObject callInviteProperties = new JSONObject();
                    try {
                        callInviteProperties.putOpt("from", activeCallInvite.getFrom());
                        callInviteProperties.putOpt("to", activeCallInvite.getTo());
                        callInviteProperties.putOpt("callSid", activeCallInvite.getCallSid());
                        String callInviteState = "pending";
                        callInviteProperties.putOpt("state", callInviteState);
                    } catch (JSONException e) {
                        Log.e(TAG, e.getMessage(), e);
                    }
                    Log.v(TAG, "oncallinvitereceived");
                    javascriptCallback("oncallinvitereceived", callInviteProperties, savedCallbackContext);

                    showIncomingCallDialog();

                    String baseCallText = "Call with " + activeCallInvite.getFrom();
                    if (incomingCallIntent != null || savedCallbackContext == null) {
                        // Show Snackbar for call management.
                        callStatusSnackbar = Snackbar.make(webView.getView(), baseCallText, BaseTransientBottomBar.LENGTH_INDEFINITE)
                                .setAction("End Call", new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        Log.v(TAG, "Snack bar end call clicked");
                                        if (activeCall != null) {
                                            activeCall.disconnect();
                                            callStatusSnackbar.dismiss();
                                            callStatusSnackbar = null;

                                            callDurationTimer.cancel();
                                        }
                                    }
                                });
                        callDurationTimer = new Timer();
                        callDurationTimer.scheduleAtFixedRate(new TimerTask() {
                            public void run() {
                                if (callStatusSnackbar != null && callStartTime != null) {
                                    Instant currentTime = Instant.now();
                                    Duration callDuration = Duration.between(callStartTime, currentTime);
                                    final long totalSeconds = callDuration.get(ChronoUnit.SECONDS);
                                    final long minutes = totalSeconds / 60;
                                    final long seconds = totalSeconds % 60;
                                    String durationTimer = String.format("%d:%02d", minutes, seconds);
                                    mainActivity.runOnUiThread(() -> {
                                        callStatusSnackbar.setText(baseCallText + " - " + durationTimer);
                                    });
                                }
                            }
                        }, 0, 1000);
                    }
                }
                break;
            case Constants.ACTION_INCOMING_CALL:
                activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                if (activeCallInvite != null && !isRinging) {
                    isRinging = true;
                    SoundPoolManager.getInstance(cordova.getActivity()).playRinging();
                    NotificationManager mNotifyMgr =
                            (NotificationManager) cordova.getActivity().getSystemService(Activity.NOTIFICATION_SERVICE);
                    mNotifyMgr.cancel(intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0));
                    JSONObject callInviteProperties = new JSONObject();
                    try {
                        callInviteProperties.putOpt("from", activeCallInvite.getFrom());
                        callInviteProperties.putOpt("to", activeCallInvite.getTo());
                        callInviteProperties.putOpt("callSid", activeCallInvite.getCallSid());
                        String callInviteState = "pending";
                        callInviteProperties.putOpt("state", callInviteState);
                    } catch (JSONException e) {
                        Log.e(TAG, e.getMessage(), e);
                    }
                    Log.v(TAG, "oncallinvitereceived");
                    javascriptCallback("oncallinvitereceived", callInviteProperties, savedCallbackContext);

                    showIncomingCallDialog();

                    String baseCallText = "Call with " + activeCallInvite.getFrom();
                    if (incomingCallIntent != null || savedCallbackContext == null) {
                        // Show Snackbar for call management.
                        callStatusSnackbar = Snackbar.make(webView.getView(), baseCallText, BaseTransientBottomBar.LENGTH_INDEFINITE)
                                .setAction("End Call", new View.OnClickListener() {
                                    @Override
                                    public void onClick(View v) {
                                        Log.v(TAG, "Snack bar end call clicked");
                                        if (activeCall != null) {
                                            activeCall.disconnect();
                                            callStatusSnackbar.dismiss();
                                            callStatusSnackbar = null;

                                            callDurationTimer.cancel();
                                        }
                                    }
                                });
                        callDurationTimer = new Timer();
                        callDurationTimer.scheduleAtFixedRate(new TimerTask() {
                            public void run() {
                                if (callStatusSnackbar != null && callStartTime != null) {
                                    Instant currentTime = Instant.now();
                                    Duration callDuration = Duration.between(callStartTime, currentTime);
                                    final long totalSeconds = callDuration.get(ChronoUnit.SECONDS);
                                    final long minutes = totalSeconds / 60;
                                    final long seconds = totalSeconds % 60;
                                    String durationTimer = String.format("%d:%02d", minutes, seconds);
                                    mainActivity.runOnUiThread(() -> {
                                        callStatusSnackbar.setText(baseCallText + " - " + durationTimer);
                                    });
                                }
                            }
                        }, 0, 1000);
                    }
                }
                break;
            case Constants.ACTION_CANCEL_CALL:
                activeCallInvite = null;
                isRinging = false;
                if (activeCall != null) {
                    activeCall.disconnect();
                    activeCall = null;
                }
                SoundPoolManager.getInstance(cordova.getActivity()).stopRinging();
                Log.v(TAG, "oncallinvitecanceled");
                javascriptCallback("oncallinvitecanceled", savedCallbackContext);

                clearCallUIElements();
                break;
        }
    }

    private void getPushToken() {
        Log.v(TAG, "getPushToken");

        FirebaseInstanceId.getInstance().getInstanceId().addOnSuccessListener(cordova.getActivity(), instanceIdResult -> {
            String fcmToken = instanceIdResult.getToken();
            this.fcmToken = fcmToken;
            Voice.register(accessToken, Voice.RegistrationChannel.FCM, fcmToken, registrationListener);
        });

    }
    //endregion

    //region Alert Dialog Implementation

    @RequiresApi(api = Build.VERSION_CODES.O)
    private DialogInterface.OnClickListener answerCallClickListener() {
        Log.v(TAG, "answerCallClickListener");
        return (dialog, which) -> {
            Log.v(TAG, "Clicked accept");
            SoundPoolManager.getInstance(mainActivity).stopRinging();
            isRinging = false;
            activeCallInvite.accept(applicationContext, callListener);
            callStartTime = Instant.now();

            activeCallInvite = null;
            incomingCallIntent = null;

            clearCallUIElements();

            if (callStatusSnackbar != null) {
                callStatusSnackbar.show();
            }
        };
    }

    private DialogInterface.OnClickListener cancelCallClickListener() {
        Log.v(TAG, "cancelCallClickListener");
        return (dialogInterface, i) -> {
            Log.v(TAG, "Clicked Cancel");
            SoundPoolManager.getInstance(mainActivity).stopRinging();
            isRinging = false;
            if (activeCallInvite != null) {
                activeCallInvite.reject(applicationContext);
                javascriptCallback("oncallinvitecanceled", savedCallbackContext);
            }

            resetCallStatus();
        };
    }


    @RequiresApi(api = Build.VERSION_CODES.O)
    private void showIncomingCallDialog() {
        Log.v(TAG, "showIncomingCallDialog");
        if (activeCallInvite != null && activeCall == null) {
            Log.v(TAG, "Showing Incoming Call Dialog");
            alertDialog = createIncomingCallDialog(webviewContext,
                    activeCallInvite,
                    mainActivity.getResources().getIdentifier("ic_launcher", "mipmap", mainActivity.getPackageName()),
                    answerCallClickListener(),
                    cancelCallClickListener());

            alertDialog.show();
        }
    }

    public static AlertDialog createIncomingCallDialog(
            Context context,
            CallInvite callInvite,
            int iconIdentifier,
            DialogInterface.OnClickListener answerCallClickListener,
            DialogInterface.OnClickListener cancelClickListener) {
        Log.v(TAG, "createIncomingCallDialog");
        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(context);
        alertDialogBuilder.setIcon(iconIdentifier);
        alertDialogBuilder.setTitle("Incoming Call");
        alertDialogBuilder.setPositiveButton("Accept", answerCallClickListener);
        alertDialogBuilder.setNegativeButton("Reject", cancelClickListener);
        alertDialogBuilder.setMessage(callInvite.getFrom() + " is calling.");
        return alertDialogBuilder.create();
    }

    private void clearCallUIElements() {
        // Close all dialogs and alerts
        Log.v(TAG, "clearCallUIElements");
        if (alertDialog != null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }
        if (callStatusSnackbar != null && callStatusSnackbar.isShownOrQueued()) {
            callStatusSnackbar.dismiss();
        }
        // Cancel notifications
        notificationManager.cancelAll();
    }

    private void resetCallStatus() {
        Log.v(TAG, "resetCallStatus");
                // Stop Ringing
        SoundPoolManager.getInstance(mainActivity).stopRinging();
        isRinging = false;
        clearCallUIElements();
        // Clear out active calls, call invites, cancels, etc
        if (activeCall != null) {
            activeCall.disconnect();
            activeCall = null;
        }
        if (activeCallInvite != null) {
            activeCallInvite.reject(applicationContext);
            activeCallInvite = null;
        }
        if (incomingCallIntent != null) {
            incomingCallIntent = null;
        }

    }
    //endregion

    //region Nested Classes
    private void registerReceiver() {
        Log.v(TAG, "registerReceiver");
        if (!isReceiverRegistered && voiceBroadcastReceiver != null) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Constants.ACTION_INCOMING_CALL);
            intentFilter.addAction(Constants.ACTION_INCOMING_CALL_NOTIFICATION);
//            intentFilter.addAction(Constants.ACTION_ACCEPT);
//            intentFilter.addAction(Constants.ACTION_REJECT);
            intentFilter.addAction(Constants.ACTION_CANCEL_CALL);
            intentFilter.addAction(Constants.ACTION_FCM_TOKEN);
            LocalBroadcastManager.getInstance(this.cordova.getActivity()).registerReceiver(voiceBroadcastReceiver, intentFilter);

            isReceiverRegistered = true;
        }
    }

    private Call.Listener callListener() {
        return new Call.Listener() {

            @Override
            public void onConnectFailure(@NonNull Call call, @NonNull CallException callException) {
                Log.v(TAG, "Call Listener OnConnectFailure");
                activeCall = null;
                setAudioFocus(false);
                javascriptErrorCallback(callException.getErrorCode(), callException.getMessage(), savedCallbackContext);

                clearCallUIElements();
            }

            @Override
            public void onRinging(@NonNull Call call) {
                Log.v(TAG, "Call Listener Ringing");
                mainActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
            }

            @Override
            public void onConnected(@NonNull Call call) {
                Log.v(TAG, "Call Listener OnConnected");
                activeCall = call;
                JSONObject callProperties = new JSONObject();
                try {
                    callProperties.putOpt("from", call.getFrom());
                    callProperties.putOpt("to", call.getTo());
                    callProperties.putOpt("callSid", call.getSid());
                    callProperties.putOpt("isMuted", call.isMuted());
                    String callState = getCallState(call.getState());
                    callProperties.putOpt("state", callState);
                    setAudioFocus(true);
                } catch (JSONException e) {
                    Log.e(TAG, e.getMessage(), e);
                }
                javascriptCallback("oncalldidconnect", callProperties, savedCallbackContext);

                if (alertDialog != null && alertDialog.isShowing()) {
                    alertDialog.dismiss();
                }

                mainActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
                Log.v(TAG, "Call Listener OnReconnecting");
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                Log.v(TAG, "Call Listener OnReconnected");
            }

            @Override
            public void onDisconnected(@NonNull Call call, @Nullable CallException callException) {
                Log.v(TAG, "Call Listener OnDisconnected");
                activeCall = null;
                setAudioFocus(false);
                javascriptCallback("oncalldiddisconnect", savedCallbackContext);
                mainActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                clearCallUIElements();
            }

        };
    }


    private RegistrationListener registrationListener() {
        return new RegistrationListener() {
            @Override
            public void onRegistered(@NonNull String accessToken, @NonNull String fcmToken) {
                Log.v(TAG, "Successfully registered FCM " + fcmToken);
            }

            @Override
            public void onError(@NonNull RegistrationException error,
                                @NonNull String accessToken,
                                @NonNull String fcmToken) {
                String message = String.format(
                        Locale.US,
                        "Registration Error: %d, %s",
                        error.getErrorCode(),
                        error.getMessage());
                Log.e(TAG, message);

            }
        };
    }

    private class VoiceBroadcastReceiver extends BroadcastReceiver {

        @RequiresApi(api = Build.VERSION_CODES.O)
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && (action.equals(Constants.ACTION_INCOMING_CALL) || action.equals(Constants.ACTION_CANCEL_CALL))) {
                /*
                 * Handle the incoming or cancelled call invite
                 */
                handleIncomingCallIntent(intent);
            }
        }
    }
    //endregion
}