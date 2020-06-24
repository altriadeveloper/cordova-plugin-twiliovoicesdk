package com.phonegap.plugins.twiliovoice;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.CallInvite;

import java.util.List;

import capacitor.android.plugins.R;

public class IncomingCallNotificationService extends Service {

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();
    private static final String NOTIFICATION_ID_KEY = "NOTIFICATION_ID";
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Log.d(TAG, "onStartCommand " + action);
        if (action != null) {
            CallInvite callInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
            int notificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
            switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                    handleIncomingCall(callInvite, notificationId);
                    break;
                case Constants.ACTION_CANCEL_CALL:
                    handleCancelledCall(intent);
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    /**
     * Build a notification.
     *
     * @param text          the text of the notification
     * @param pendingIntent the body, pending intent for the notification
     * @param extras        extras passed with the notification
     * @return the builder
     */
    @TargetApi(Build.VERSION_CODES.O)
    private Notification buildNotification(String text, PendingIntent pendingIntent, Bundle extras,
                                           final CallInvite callInvite,
                                           int notificationId,
                                           String channelId) {
        Log.d(TAG, "buildNotification " + text);

        Notification.Builder builder =
                new Notification.Builder(getApplicationContext(), channelId)
                        .setSmallIcon(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()))
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(text)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setFullScreenIntent(pendingIntent, true)
                        .setExtras(extras)
                        .setAutoCancel(true)
                        .setFullScreenIntent(pendingIntent, true);
        return builder.build();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private String createChannel(int channelImportance) {
        Log.d(TAG, "createChannel");
        NotificationChannel callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_HIGH_IMPORTANCE,
                "Primary Voice Channel", NotificationManager.IMPORTANCE_HIGH);
        String channelId = Constants.VOICE_CHANNEL_HIGH_IMPORTANCE;

        if (channelImportance == NotificationManager.IMPORTANCE_LOW) {
            callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_LOW_IMPORTANCE,
                    "Primary Voice Channel", NotificationManager.IMPORTANCE_LOW);
            channelId = Constants.VOICE_CHANNEL_LOW_IMPORTANCE;
        }
        callInviteChannel.setLightColor(Color.GREEN);
        callInviteChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(callInviteChannel);

        return channelId;
    }

    private void handleCancelledCall(Intent intent) {
        Log.d(TAG, "handleCancelledCall");
        endForeground();
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void handleIncomingCall(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "handleIncomingCall");
        sendCallInviteToActivity(callInvite, notificationId);
    }

    private void endForeground() {
        stopForeground(true);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void sendCallInviteToActivity(CallInvite callInvite, int notificationId) {
        Log.d(TAG, "sendCallInviteToActivity");
        if (isAppVisible()) {
            // Just notify open application of call.
            Intent intent = new Intent(Constants.ACTION_INCOMING_CALL);
            intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
            intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
        } else {
            if (isAppRunning()) {
                Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                intent.setAction(Constants.ACTION_INCOMING_CALL);
                intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
                intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                this.startActivity(intent);
                LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
            } else {
                Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                intent.setAction(Constants.ACTION_INCOMING_CALL);
                intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
                intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                this.startActivity(intent);
            }

            // Open application and notify it of call.
//            Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
//            intent.setAction(Constants.ACTION_INCOMING_CALL);
//            intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
//            intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
//            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
//            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
//            this.startActivity(intent);

//            Bundle extras = new Bundle();
//            extras.putInt(NOTIFICATION_ID_KEY, notificationId);
//            extras.putString(Constants.CALL_SID_KEY, callInvite.getCallSid());
//
//            PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_CANCEL_CURRENT);
//            Notification notification = new Notification.Builder(this, createChannel(NotificationManager.IMPORTANCE_HIGH))
//                    .setContentTitle(getResources().getString(R.string.app_name))
//                    .setContentText(callInvite.getFrom() + " is calling")
//                    .setSmallIcon(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()))
//                    .setContentIntent(pendingIntent)
//                    .setCategory(Notification.CATEGORY_CALL)
//                    .setFullScreenIntent(pendingIntent, true)
//                    .setExtras(extras)
//                    .setAutoCancel(true)
//                    .build();
//            NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
//            notificationManager.notify(notificationId, notification);
        }
    }

    private boolean isAppVisible() {
        return ProcessLifecycleOwner
                .get()
                .getLifecycle()
                .getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED);
    }

    private boolean isAppRunning() {
        final ActivityManager activityManager = (ActivityManager) this.getSystemService(Context.ACTIVITY_SERVICE);
        final List<ActivityManager.RunningAppProcessInfo> procInfos = activityManager.getRunningAppProcesses();
        if (procInfos != null)
        {
            for (final ActivityManager.RunningAppProcessInfo processInfo : procInfos) {
                if (processInfo.processName.equals(getPackageName())) {
                    return true;
                }
            }
        }
        return false;
    }
}