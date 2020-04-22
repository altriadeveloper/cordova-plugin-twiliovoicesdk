package com.phonegap.plugins.twiliovoice.fcm;

import android.content.Intent;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import android.util.Log;

import com.google.firebase.iid.FirebaseInstanceId;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.phonegap.plugins.twiliovoice.TwilioVoicePlugin;

public class VoiceFirebaseInstanceIDService extends FirebaseMessagingService {

    private static final String TAG = TwilioVoicePlugin.TAG;

    /**
     * Called if InstanceID token is updated. This may occur if the security of
     * the previous token had been compromised. Note that this is called when the InstanceID token
     * is initially generated so this is where you would retrieve the token.
     */
    @Override
    public void onNewToken(String s) {
        // Get updated InstanceID token.
        String refreshedToken = FirebaseInstanceId.getInstance().getToken();
        Log.d(TAG, "Refreshed token: " + refreshedToken);

        // Notify Activity of FCM token
        Intent intent = new Intent(TwilioVoicePlugin.ACTION_SET_FCM_TOKEN);
        intent.putExtra(TwilioVoicePlugin.KEY_FCM_TOKEN, refreshedToken);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }
}
