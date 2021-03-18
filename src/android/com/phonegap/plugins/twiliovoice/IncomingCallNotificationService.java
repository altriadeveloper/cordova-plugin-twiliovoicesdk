package com.phonegap.plugins.twiliovoice;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
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
import com.twilio.voice.CancelledCallInvite;

import java.util.List;

import capacitor.android.plugins.R;
import dagger.Component;

@RequiresApi(api = Build.VERSION_CODES.M)
public class IncomingCallNotificationService extends Service {

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();
    private static final String NOTIFICATION_ID_KEY = "NOTIFICATION_ID";
    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Log.v(TAG, "onStartCommand " + action);
        if (action != null) {
            switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                    CallInvite callInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
                    int notificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
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

    private void handleCancelledCall(Intent intent) {
        Log.v(TAG, "handleCancelledCall");
        sendCallCancelToActivity(intent, 0);
        //TODO: May need to do more advance logic here.
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void handleIncomingCall(CallInvite callInvite, int notificationId) {
        Log.v(TAG, "handleIncomingCall");
        sendCallInviteToActivity(callInvite, notificationId);
    }

    private void endForeground() {
        stopForeground(true);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void sendCallInviteToActivity(CallInvite callInvite, int notificationId) {
        Log.v(TAG, "sendCallInviteToActivity");

        /*
        DEVICE UNLOCKED
            APP OPEN
                APP FOCUSED
                    APP LOGGED IN
                        -Dispatch Simple Call Intent to App     1
                        -Do Not Send Any Notifications          1a
                    APP LOGGED OUT
                        -Dispatch Simple Call Intent to App     1
                        -Do Not Send Any Notifications          1a
                APP MINIMIZED
                    APP LOGGED IN
                        -Send Start intent to App               2
                        -Bring App to Front                     2a
                        -Do Not Send Any Notification           1a
                    APP LOGGED OUT
                        -Send Start Intent to App               2
                        -Do Not Send Any Notification???        1a?
            APP CLOSED
                -Send Start Intent to App                       2
                -Do Not Send Any Notification????               1a??

        DEVICE LOCKED
            APP OPEN
                APP FOCUSED
                    -Broadcast Call To App
                    -Send Notification High Priority, Full Screen, CallCategory
                APP MINIMIZED
                    -Broadcast Call To App
                    -Send Notification High Priority, Full Screen, Open Existing
            APP CLOSED
                -Send Start Intent To App
                -Send Notification High Priority, Full Screen, Open New
         */
        if (!isDeviceLocked()) {
            if (isAppRunning()) {
                if (isAppVisible()) {
                    // UNLOCKED|OPEN|VISIBLE
                    Log.v(TAG, "SendCallInviteToActivity:UNLOCKED:OPEN:VISIBLE");
                    Intent intent = new Intent(Constants.ACTION_INCOMING_CALL);
                    intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
										intent.setExtrasClassLoader(CallInvite.class.getClassLoader());
                    intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                } else {
                    // UNLOCKED|OPEN|MINIMIZED
                    Log.v(TAG, "SendCallInviteToActivity:UNLOCKED:OPEN:MINIMIZED");
                    Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    intent.setAction(Constants.ACTION_INCOMING_CALL);
                    intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
										intent.setExtrasClassLoader(CallInvite.class.getClassLoader());
                    intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    intent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    this.startActivity(intent);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                }
            } else {
                // UNLOCKED|CLOSED
                Log.v(TAG, "SendCallInviteToActivity:UNLOCKED:CLOSED");
                Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                intent.setAction(Constants.ACTION_INCOMING_CALL);
                intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
								intent.setExtrasClassLoader(CallInvite.class.getClassLoader());
                intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                this.startActivity(intent);
            }
        } else {
            if (isAppRunning()) {
                if (isAppVisible()) {
                    // LOCKED|OPEN|VISIBLE
                    Log.v(TAG, "SendCallInviteToActivity:UOCKED:OPEN:VISIBLE");
                    Intent intent = new Intent(Constants.ACTION_INCOMING_CALL);
                    intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
										intent.setExtrasClassLoader(CallInvite.class.getClassLoader());
                    intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                    intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
                    LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
                    sendNotification("CEA Incoming Call", "Incoming Call From " + callInvite.getFrom(), notificationId, intent);
                } else {
                    // LOCKED|OPEN|MINIMIZED
                    Log.v(TAG, "SendCallInviteToActivity:LOCKED:OPEN:MINIMIZED");
                    Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                    intent.setAction(Constants.ACTION_INCOMING_CALL);
                    intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
										intent.setExtrasClassLoader(CallInvite.class.getClassLoader());
                    intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                    intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    sendNotification("CEA Incoming Call", "Incoming Call From " + callInvite.getFrom(), notificationId, intent);
                }
            } else {
                // LOCKED|CLOSED
                Log.v(TAG, "SendCallInviteToActivity:LOCKED:CLOSED");
                Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
                intent.setAction(Constants.ACTION_INCOMING_CALL);
                intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
								intent.setExtrasClassLoader(CallInvite.class.getClassLoader());
                intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
                sendNotification("CEA Incoming Call", "Incoming Call From " + callInvite.getFrom(), notificationId, intent);
            }
        }


        //endForeground();
        // ----- END NEW CODE

    }

    private void sendCallCancelToActivity(Intent intent, int notificationId) {
        Log.v(TAG, "sendCallCancelToActivity");
        endForeground();
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }


    @TargetApi(Build.VERSION_CODES.O)
    private String createChannel(int channelImportance) {
        Log.v(TAG, "createChannel");
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


    @RequiresApi(api = Build.VERSION_CODES.M)
    private void sendNotification(String title, String text, int notificationId, Intent intent) {
        Log.v(TAG, "sendNotification");
        // https://developer.android.com/training/notify-user/time-sensitive
        String channelId = createChannel(NotificationManager.IMPORTANCE_HIGH);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.launcher_icon)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pendingIntent, true);
        Notification incomingCallNotification = notificationBuilder.build();
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(notificationId, incomingCallNotification);
        // startForeground(notificationId, incomingCallNotification);
    }

    //    /**
//     * Build a notification.
//     *
//     * @param text          the text of the notification
//     * @param pendingIntent the body, pending intent for the notification
//     * @param extras        extras passed with the notification
//     * @return the builder
//     */
//    @TargetApi(Build.VERSION_CODES.O)
//    private Notification buildNotification(String text, PendingIntent pendingIntent, Bundle extras,
//                                           final CallInvite callInvite,
//                                           int notificationId,
//                                           String channelId) {
//        Log.v(TAG, "buildNotification " + text);
//
//        Notification.Builder builder =
//                new Notification.Builder(getApplicationContext(), channelId)
//                        .setSmallIcon(getResources().getIdentifier("ic_launcher", "mipmap", getPackageName()))
//                        .setContentTitle(getString(R.string.app_name))
//                        .setContentText(text)
//                        .setCategory(Notification.CATEGORY_CALL)
//                        .setFullScreenIntent(pendingIntent, true)
//                        .setExtras(extras)
//                        .setAutoCancel(true)
//                        .setFullScreenIntent(pendingIntent, true);
//        return builder.build();
//    }
    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP_MR1)
    private boolean isDeviceLocked() {
        // https://stackoverflow.com/questions/8317331/detecting-when-screen-is-locked
        KeyguardManager km = (KeyguardManager) this.getSystemService(Context.KEYGUARD_SERVICE);
        return km.isDeviceLocked() || km.isKeyguardLocked();
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