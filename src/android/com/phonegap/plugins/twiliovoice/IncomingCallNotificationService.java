package com.phonegap.plugins.twiliovoice;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ProcessLifecycleOwner;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.CallInvite;

import capacitor.android.plugins.R;

@RequiresApi(api = Build.VERSION_CODES.M)
public class IncomingCallNotificationService extends Service {

    private static final String TAG = IncomingCallNotificationService.class.getSimpleName();
    private Ringtone ringtone;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        Log.v(TAG, "OnStartCommand - " + action);
        if (ringtone == null) {
            Uri soundUri = Uri.parse(ContentResolver.SCHEME_ANDROID_RESOURCE + "://"+ getApplicationContext().getPackageName() + "/raw/ringing");
            ringtone = RingtoneManager.getRingtone(getApplicationContext(), soundUri);
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                ringtone.setVolume(.7f);
            }
        }
        if (action != null) {
            CallInvite callInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
            int notificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
            switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                    handleIncomingCall(callInvite, notificationId);
                    break;
                case Constants.ACTION_ACCEPT:
                    accept(callInvite, notificationId);
                    break;
                case Constants.ACTION_REJECT:
                    reject(callInvite);
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

    private Notification createNotification(CallInvite callInvite, int notificationId, int channelImportance) {
        Log.v(TAG, "Create Notification");
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        intent.setAction(Constants.ACTION_INCOMING_CALL_NOTIFICATION);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, notificationId, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        /*
         * Pass the notification id and call sid to use as an identifier to cancel the
         * notification later
         */
        Bundle extras = new Bundle();
        extras.putString(Constants.CALL_SID_KEY, callInvite.getCallSid());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return buildNotification(callInvite.getFrom() + " is calling.",
                    pendingIntent,
                    extras,
                    callInvite,
                    notificationId,
                    createChannel(channelImportance));
        } else {
            //noinspection deprecation
            return new NotificationCompat.Builder(this)
					.setSmallIcon(R.drawable.launcher_icon)
                    .setContentTitle("CEA Incoming Call")
                    .setContentText("Incoming Call From " + callInvite.getFrom())
                    .setAutoCancel(true)
                    .setExtras(extras)
                    .setContentIntent(pendingIntent)
                    .setGroup("test_app_notification")
                    .setColor(Color.rgb(214, 10, 37)).build();
        }
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
        Log.v(TAG, "Build Notification");

        Intent rejectIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        rejectIntent.setAction(Constants.ACTION_REJECT);
        rejectIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        rejectIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        PendingIntent piRejectIntent = PendingIntent.getService(getApplicationContext(), 0, rejectIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Intent acceptIntent = new Intent(getApplicationContext(), IncomingCallNotificationService.class);
        acceptIntent.setAction(Constants.ACTION_ACCEPT);
        acceptIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        acceptIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        PendingIntent piAcceptIntent = PendingIntent.getService(getApplicationContext(), 0, acceptIntent, PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder =
                new Notification.Builder(getApplicationContext(), channelId)
						.setSmallIcon(R.drawable.launcher_icon)
                        .setContentTitle(getString(R.string.app_name))
                        .setContentText(text)
                        .setCategory(Notification.CATEGORY_CALL)
                        .setExtras(extras)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent)
                        .addAction(android.R.drawable.ic_menu_call, "Accept", piAcceptIntent)
                        .addAction(android.R.drawable.ic_menu_delete, "Decline", piRejectIntent)
                        .setFullScreenIntent(pendingIntent, true);

        return builder.build();
    }

    @TargetApi(Build.VERSION_CODES.O)
    private String createChannel(int channelImportance) {
        Log.v(TAG, "Create Channel");
        NotificationChannel callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_HIGH_IMPORTANCE,
                "Primary Voice Channel", NotificationManager.IMPORTANCE_HIGH);
        String channelId = Constants.VOICE_CHANNEL_HIGH_IMPORTANCE;
        if (channelImportance == NotificationManager.IMPORTANCE_LOW) {
            callInviteChannel = new NotificationChannel(Constants.VOICE_CHANNEL_LOW_IMPORTANCE,
                    "Primary Voice Channel", NotificationManager.IMPORTANCE_LOW);
            channelId = Constants.VOICE_CHANNEL_LOW_IMPORTANCE;
        }
        callInviteChannel.setLightColor(Color.GREEN);
        callInviteChannel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.createNotificationChannel(callInviteChannel);

        return channelId;
    }

    private void accept(CallInvite callInvite, int notificationId) {
        Log.v(TAG, "Accept");
        ringtone.stop();
        endForeground();
        Intent activeCallIntent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        activeCallIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        activeCallIntent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        activeCallIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        activeCallIntent.setAction(Constants.ACTION_ACCEPT);
        startActivity(activeCallIntent);
    }

    private void reject(CallInvite callInvite) {
        Log.v(TAG, "Reject");
        ringtone.stop();
        endForeground();
        callInvite.reject(getApplicationContext());
    }

    private void handleCancelledCall(Intent intent) {
        Log.v(TAG, "Handle Cancel Call");
        endForeground();
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void handleIncomingCall(CallInvite callInvite, int notificationId) {
        Log.v(TAG, "Handle Incoming Call");
        PowerManager pm = (PowerManager) getApplicationContext().getSystemService(Context.POWER_SERVICE);
        boolean isScreenOn = Build.VERSION.SDK_INT >= 20 ? pm.isInteractive() : pm.isScreenOn();
        if (!isScreenOn) {
            PowerManager.WakeLock wl = pm.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "CEA:notificationLock");
            wl.acquire(10000);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ringtone.play();
            setCallInProgressNotification(callInvite, notificationId);
        }
        // sendCallInviteToActivity(callInvite, notificationId);
    }

    private void endForeground() {
        Log.v(TAG, "End Foreground");
        stopForeground(true);
    }

    @TargetApi(Build.VERSION_CODES.O)
    private void setCallInProgressNotification(CallInvite callInvite, int notificationId) {
        Log.v(TAG, "SetCallInProgressNotification");
        if (isAppVisible()) {
            Log.i(TAG, "setCallInProgressNotification - app is visible.");
            sendCallInviteToActivity(callInvite, notificationId);
            //startForeground(notificationId, createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_LOW));
        } else {
            Log.i(TAG, "setCallInProgressNotification - app is NOT visible.");
            startForeground(notificationId, createNotification(callInvite, notificationId, NotificationManager.IMPORTANCE_HIGH));
        }
    }

    /*
     * Send the CallInvite to the VoiceActivity. Start the activity if it is not running already.
     */
    private void sendCallInviteToActivity(CallInvite callInvite, int notificationId) {
        Log.v(TAG, "Send Call Invite To Activity");
        if (Build.VERSION.SDK_INT >= 29 && !isAppVisible()) {
            Log.v(TAG, "App Not Visible");
            return;
        }
        Log.v(TAG, "App Is Visible");
        Intent intent = new Intent(Constants.ACTION_INCOMING_CALL);
        intent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, notificationId);
        intent.putExtra(Constants.INCOMING_CALL_INVITE, callInvite);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private boolean isAppVisible() {
        return ProcessLifecycleOwner
                .get()
                .getLifecycle()
                .getCurrentState()
                .isAtLeast(Lifecycle.State.STARTED);
    }
}