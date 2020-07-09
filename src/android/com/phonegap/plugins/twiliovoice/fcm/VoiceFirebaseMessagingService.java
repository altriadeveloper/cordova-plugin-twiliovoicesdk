package com.phonegap.plugins.twiliovoice.fcm;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import com.phonegap.plugins.twiliovoice.Constants;
import com.phonegap.plugins.twiliovoice.IncomingCallNotificationService;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;
import com.twilio.voice.CancelledCallInvite;
import com.twilio.voice.MessageListener;
import com.twilio.voice.Voice;

import capacitor.android.plugins.R;

public class VoiceFirebaseMessagingService extends FirebaseMessagingService {

    private static final String TAG = "VoiceFCMService";
    private static final String NOTIFICATION_ID_KEY = "NOTIFICATION_ID";

    private NotificationManager notificationManager;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    /**
     * Called when message is received.
     *
     * @param remoteMessage Object representing the message received from Firebase Cloud Messaging.
     */
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        Log.v(TAG, "Received onMessageReceived()");
        Log.v(TAG, "Bundle data: " + remoteMessage.getData());
        Log.v(TAG, "From: " + remoteMessage.getFrom());

        // Check if message contains a data payload.
        if (remoteMessage.getData().size() > 0) {
            boolean valid = Voice.handleMessage(this, remoteMessage.getData(), new MessageListener() {
                @Override
                public void onCallInvite(@NonNull CallInvite callInvite) {
                    final int notificationId = (int) System.currentTimeMillis();
                    VoiceFirebaseMessagingService.this.handleInvite(callInvite, notificationId);
                }

                @Override
                public void onCancelledCallInvite(@NonNull CancelledCallInvite cancelledCallInvite, @Nullable CallException callException) {
                    VoiceFirebaseMessagingService.this.handleCanceledCallInvite(cancelledCallInvite);
                }
            });

            if (!valid) {
                Log.e(TAG, "The message was not a valid Twilio Voice SDK payload: " +
                        remoteMessage.getData());
            }
        }
    }

    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        Log.v(TAG, "onNewToken");
        Intent intent = new Intent(Constants.ACTION_FCM_TOKEN);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void notify(CallInvite callInvite, int notificationId) {
        String callSid = callInvite.getCallSid();
        Log.v(TAG, "notify " + callSid);
        Notification notification = null;

        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        intent.setAction(Constants.ACTION_INCOMING_CALL);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        extras.putInt(NOTIFICATION_ID_KEY, notificationId);
        extras.putString(Constants.CALL_SID_KEY, callSid);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_HIGH_IMPORTANCE,
                    "Primary Voice Channel", NotificationManager.IMPORTANCE_DEFAULT);
            callInviteChannel.setLightColor(Color.RED);
            callInviteChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            notificationManager.createNotificationChannel(callInviteChannel);

            notification = buildNotification(callInvite.getFrom() + " is calling", pendingIntent, extras);
            notificationManager.notify(notificationId, notification);
        } else {
            int iconIdentifier = getResources().getIdentifier("icon", "mipmap", getPackageName());
            int incomingCallAppNameId = (int) getResources().getIdentifier("incoming_call_app_name", "string", getPackageName());
            String contentTitle = getString(incomingCallAppNameId);

            if (contentTitle == null) {
                contentTitle = "Incoming Call";
            }
            final String from = callInvite.getFrom() + " is calling";

            NotificationCompat.Builder notificationBuilder =
                    new NotificationCompat.Builder(this)
                            .setSmallIcon(iconIdentifier)
                            .setContentTitle(contentTitle)
                            .setContentText(from)
                            .setAutoCancel(true)
                            .setExtras(extras)
                            .setContentIntent(pendingIntent)
                            .setGroup("voice_app_notification")
                            .setColor(Color.rgb(225, 0, 0));

            notificationManager.notify(notificationId, notificationBuilder.build());
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    public Notification buildNotification(String text, PendingIntent pendingIntent, Bundle extras) {
        Log.v(TAG, "BuildNotification " + text);
        int iconIdentifier = getResources().getIdentifier("icon", "mipmap", getPackageName());
        int incomingCallAppNameId = getResources().getIdentifier("incoming_call_app_name", "string", getPackageName());
        String contentTitle = getString(incomingCallAppNameId);
        return new Notification.Builder(getApplicationContext(), Constants.VOICE_CHANNEL_HIGH_IMPORTANCE)
                .setSmallIcon(R.drawable.launcher_icon)
                .setContentTitle(contentTitle)
                .setContentText(text)
                .setContentIntent(pendingIntent)
                .setExtras(extras)
                .setAutoCancel(true)
                .build();
    }

    private void handleInvite(CallInvite callInvite, int notificationId) {
        Log.v(TAG,"handleInvite");
        Intent intent = new Intent(this, IncomingCallNotificationService.class);
        intent.setAction(Constants.ACTION_INCOMING_CALL);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);

        startService(intent);
    }

    private void handleCanceledCallInvite(CancelledCallInvite cancelledCallInvite) {
        Log.v(TAG, "handleCancelCallInvite");
        Intent intent = new Intent(this, IncomingCallNotificationService.class);
        intent.setAction(Constants.ACTION_CANCEL_CALL);
        intent.putExtra(Constants.CANCELLED_CALL_INVITE, cancelledCallInvite);

        startService(intent);
    }
}