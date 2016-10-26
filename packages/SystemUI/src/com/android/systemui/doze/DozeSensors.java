/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.doze;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.MetricsProto;
import com.android.systemui.statusbar.phone.DozeParameters;

import android.annotation.AnyThread;
import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.hardware.TriggerEvent;
import android.hardware.TriggerEventListener;
import android.media.AudioAttributes;
import android.net.Uri;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;

public class DozeSensors {

    private static final boolean DEBUG = DozeService.DEBUG;

    private static final String TAG = "DozeSensors";

    private final Context mContext;
    private final SensorManager mSensorManager;
    private final TriggerSensor[] mSensors;
    private final ContentResolver mResolver;
    private final TriggerSensor mPickupSensor;
    private final DozeParameters mDozeParameters;
    private final AmbientDisplayConfiguration mConfig;
    private final PowerManager.WakeLock mWakeLock;
    private final Callback mCallback;

    private final Handler mHandler = new Handler();


    public DozeSensors(Context context, SensorManager sensorManager, DozeParameters dozeParameters,
            AmbientDisplayConfiguration config, PowerManager.WakeLock wakeLock, Callback callback) {
        mContext = context;
        mSensorManager = sensorManager;
        mDozeParameters = dozeParameters;
        mConfig = config;
        mWakeLock = wakeLock;
        mResolver = mContext.getContentResolver();

        mSensors = new TriggerSensor[] {
                new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION),
                        null /* setting */,
                        dozeParameters.getPulseOnSigMotion(),
                        DozeLog.PULSE_REASON_SENSOR_SIGMOTION),
                mPickupSensor = new TriggerSensor(
                        mSensorManager.getDefaultSensor(Sensor.TYPE_PICK_UP_GESTURE),
                        Settings.Secure.DOZE_PULSE_ON_PICK_UP,
                        config.pulseOnPickupAvailable(),
                        DozeLog.PULSE_REASON_SENSOR_PICKUP),
                new TriggerSensor(
                        findSensorWithType(config.doubleTapSensorType()),
                        Settings.Secure.DOZE_PULSE_ON_DOUBLE_TAP,
                        true /* configured */,
                        DozeLog.PULSE_REASON_SENSOR_DOUBLE_TAP)
        };
        mCallback = callback;
    }

    private Sensor findSensorWithType(String type) {
        if (TextUtils.isEmpty(type)) {
            return null;
        }
        List<Sensor> sensorList = mSensorManager.getSensorList(Sensor.TYPE_ALL);
        for (Sensor s : sensorList) {
            if (type.equals(s.getStringType())) {
                return s;
            }
        }
        return null;
    }

    public void setListen(boolean listen) {
        for (TriggerSensor s : mSensors) {
            s.setListening(listen);
            if (listen) {
                s.registerSettingsObserver(mSettingsObserver);
            }
        }
        if (!listen) {
            mResolver.unregisterContentObserver(mSettingsObserver);
        }
    }

    public void reregisterAllSensors() {
        for (TriggerSensor s : mSensors) {
            s.setListening(false);
        }
        for (TriggerSensor s : mSensors) {
            s.setListening(true);
        }
    }

    public void onUserSwitched() {
        for (TriggerSensor s : mSensors) {
            s.updateListener();
        }
    }

    private final ContentObserver mSettingsObserver = new ContentObserver(mHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri, int userId) {
            if (userId != ActivityManager.getCurrentUser()) {
                return;
            }
            for (TriggerSensor s : mSensors) {
                s.updateListener();
            }
        }
    };

    public void setDisableSensorsInterferingWithProximity(boolean disable) {
        mPickupSensor.setDisabled(disable);
    }

    private class TriggerSensor extends TriggerEventListener {
        final Sensor mSensor;
        final boolean mConfigured;
        final int mPulseReason;
        final String mSetting;

        private boolean mRequested;
        private boolean mRegistered;
        private boolean mDisabled;

        public TriggerSensor(Sensor sensor, String setting, boolean configured, int pulseReason) {
            mSensor = sensor;
            mSetting = setting;
            mConfigured = configured;
            mPulseReason = pulseReason;
        }

        public void setListening(boolean listen) {
            if (mRequested == listen) return;
            mRequested = listen;
            updateListener();
        }

        public void setDisabled(boolean disabled) {
            if (mDisabled == disabled) return;
            mDisabled = disabled;
            updateListener();
        }

        public void updateListener() {
            if (!mConfigured || mSensor == null) return;
            if (mRequested && !mDisabled && enabledBySetting() && !mRegistered) {
                mRegistered = mSensorManager.requestTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(TAG, "requestTriggerSensor " + mRegistered);
            } else if (mRegistered) {
                final boolean rt = mSensorManager.cancelTriggerSensor(this, mSensor);
                if (DEBUG) Log.d(TAG, "cancelTriggerSensor " + rt);
                mRegistered = false;
            }
        }

        private boolean enabledBySetting() {
            if (TextUtils.isEmpty(mSetting)) {
                return true;
            }
            return Settings.Secure.getIntForUser(mResolver, mSetting, 1,
                    UserHandle.USER_CURRENT) != 0;
        }

        @Override
        public String toString() {
            return new StringBuilder("{mRegistered=").append(mRegistered)
                    .append(", mRequested=").append(mRequested)
                    .append(", mDisabled=").append(mDisabled)
                    .append(", mConfigured=").append(mConfigured)
                    .append(", mSensor=").append(mSensor).append("}").toString();
        }

        @Override
        @AnyThread
        public void onTrigger(TriggerEvent event) {
            mWakeLock.acquire();
            try {
                if (DEBUG) Log.d(TAG, "onTrigger: " + triggerEventToString(event));
                boolean sensorPerformsProxCheck = false;
                if (mSensor.getType() == Sensor.TYPE_PICK_UP_GESTURE) {
                    int subType = (int) event.values[0];
                    MetricsLogger.action(
                            mContext, MetricsProto.MetricsEvent.ACTION_AMBIENT_GESTURE, subType);
                    sensorPerformsProxCheck =
                            mDozeParameters.getPickupSubtypePerformsProxCheck(subType);
                }

                mRegistered = false;
                mCallback.onSensorPulse(mPulseReason, sensorPerformsProxCheck);
                updateListener();  // reregister, this sensor only fires once
            } finally {
                mWakeLock.release();
            }
        }

        public void registerSettingsObserver(ContentObserver settingsObserver) {
            if (mConfigured && !TextUtils.isEmpty(mSetting)) {
                mResolver.registerContentObserver(
                        Settings.Secure.getUriFor(mSetting), false /* descendants */,
                        mSettingsObserver, UserHandle.USER_ALL);
            }
        }

        private String triggerEventToString(TriggerEvent event) {
            if (event == null) return null;
            final StringBuilder sb = new StringBuilder("TriggerEvent[")
                    .append(event.timestamp).append(',')
                    .append(event.sensor.getName());
            if (event.values != null) {
                for (int i = 0; i < event.values.length; i++) {
                    sb.append(',').append(event.values[i]);
                }
            }
            return sb.append(']').toString();
        }
    }

    public interface Callback {
        void onSensorPulse(int pulseReason, boolean sensorPerformedProxCheck);
    }
}
