/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License.
 */
 
 package com.android.systemui.qs.tiles;
 
import android.app.UiModeManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.service.quicksettings.Tile;
import android.widget.Switch;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.logging.nano.MetricsProto;
import com.android.systemui.R;
import com.android.systemui.R.drawable;
import com.android.systemui.qs.QSHost;
import com.android.systemui.plugins.qs.QSTile.BooleanState;
import com.android.systemui.plugins.qs.QSTile;
import com.android.systemui.qs.tileimpl.QSTileImpl;
import com.android.systemui.qs.SecureSetting;
import com.android.systemui.statusbar.policy.ConfigurationController;

/**
 * Quick Settings tile for: Night Mode / Dark Theme / Dark Mode.
 *
 * The string id of this tile is "dark" because "night" was already
 * taken by {@link NightDisplayTile}.
 */
 
 public class UiModeNightTile extends QSTileImpl<BooleanState> 
        implements ConfigurationController.ConfigurationListener {

    private final Icon mIcon = ResourceIcon.get(com.android.internal.R.drawable.ic_qs_ui_mode_night);
    private UiModeManager mUiModeManager;

    public UiModeNightTile(QSHost host) {
        super(host);
        mUiModeManager = mContext.getSystemService(UiModeManager.class);
    }
    
    public void onUiModeChanged() {
        refreshState();
    } 
    
    @Override
    public BooleanState newTileState() {
        return new BooleanState();
    } 

    @Override
    protected void handleClick() {
        boolean newState = !mState.value;
        mUiModeManager.setNightMode(newState ? UiModeManager.MODE_NIGHT_YES
                : UiModeManager.MODE_NIGHT_NO);
        refreshState(newState);
    }

    @Override
    protected void handleUpdateState(BooleanState state, Object arg) {
        boolean nightMode = (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        state.value = nightMode;
        state.label = mContext.getString(R.string.quick_settings_ui_mode_night_label);
        state.contentDescription = state.label;
        state.icon = mIcon;
        state.expandedAccessibilityClassName = Switch.class.getName();
        state.state = state.value ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE;
    }
    
    @Override
    public int getMetricsCategory() {
        return MetricsProto.MetricsEvent.QS_UI_MODE_NIGHT;
    }
    
    @Override
    public Intent getLongClickIntent() {
        return new Intent(Settings.ACTION_DISPLAY_SETTINGS);
    }

    @Override
    protected void handleSetListening(boolean listening) {
    }
  
    @Override
    public CharSequence getTileLabel() {
        return getState().label;
    }  
    
}    
