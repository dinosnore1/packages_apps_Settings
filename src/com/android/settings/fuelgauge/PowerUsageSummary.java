/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.settings.fuelgauge;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.drawable.Drawable;
import android.hardware.SensorManager;
import android.os.BatteryStats;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.BatteryStats.Uid;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.Log;
import android.util.SparseArray;
import android.view.Menu;
import android.view.MenuItem;

import com.android.internal.app.IBatteryStats;
import com.android.internal.os.BatteryStatsImpl;
import com.android.internal.os.PowerProfile;
import com.android.settings.R;
import com.android.settings.fuelgauge.PowerUsageDetail.DrainType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Displays a list of apps and subsystems that consume power, ordered by how much power was
 * consumed since the last time it was unplugged.
 */
public class PowerUsageSummary extends PreferenceActivity implements Runnable {

    private static final boolean DEBUG = false;

    private static final String TAG = "PowerUsageSummary";

    private static final int MENU_STATS_TYPE = Menu.FIRST;
    private static final int MENU_STATS_REFRESH = Menu.FIRST + 1;

    IBatteryStats mBatteryInfo;
    BatteryStatsImpl mStats;
    private List<BatterySipper> mUsageList = new ArrayList<BatterySipper>();

    private PreferenceGroup mAppListGroup;

    private int mStatsType = BatteryStats.STATS_UNPLUGGED;

    private static final int MIN_POWER_THRESHOLD = 5;
    private static final int MAX_ITEMS_TO_LIST = 10;

    private double mMaxPower = 1;
    private double mTotalPower;

    private boolean mScaleByMax = true;

    private PowerProfile mPowerProfile;

    private HashMap<String,String> mNameCache = new HashMap<String,String>();
    private HashMap<String,Drawable> mIconCache = new HashMap<String,Drawable>();

    /** Queue for fetching name and icon for an application */
    private ArrayList<BatterySipper> mRequestQueue = new ArrayList<BatterySipper>();
    private Thread mRequestThread;
    private boolean mAbort;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        addPreferencesFromResource(R.xml.power_usage_summary);
        mBatteryInfo = IBatteryStats.Stub.asInterface(
                ServiceManager.getService("batteryinfo"));
        mAppListGroup = getPreferenceScreen();
        mPowerProfile = new PowerProfile(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mAbort = false;
        updateAppsList();
    }

    @Override
    protected void onPause() {
        synchronized (mRequestQueue) {
            mAbort = true;
        }
        mHandler.removeMessages(MSG_UPDATE_NAME_ICON);
        super.onPause();
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        PowerGaugePreference pgp = (PowerGaugePreference) preference;
        BatterySipper sipper = pgp.getInfo();
        Intent intent = new Intent(this, PowerUsageDetail.class);
        intent.putExtra(PowerUsageDetail.EXTRA_TITLE, sipper.name);
        intent.putExtra(PowerUsageDetail.EXTRA_PERCENT, sipper.getSortValue() * 100 / mTotalPower);
        if (sipper.uidObj != null) {
            intent.putExtra(PowerUsageDetail.EXTRA_UID, sipper.uidObj.getUid());
        }
        intent.putExtra(PowerUsageDetail.EXTRA_DRAIN_TYPE, sipper.drainType);
        switch (sipper.drainType) {
            case APP:
            {
                Uid uid = sipper.uidObj;
                int[] types = new int[] {
                    R.string.usage_type_cpu,
                    R.string.usage_type_cpu_foreground,
                    R.string.usage_type_gps,
                    R.string.usage_type_data_send,
                    R.string.usage_type_data_recv,
                    R.string.usage_type_audio,
                    R.string.usage_type_video,
                };
                double[] values = new double[] {
                    sipper.cpuTime,
                    sipper.cpuFgTime,
                    sipper.gpsTime,
                    uid != null? uid.getTcpBytesSent(mStatsType) : 0,
                    uid != null? uid.getTcpBytesReceived(mStatsType) : 0,
                    0,
                    0
                };
                intent.putExtra(PowerUsageDetail.EXTRA_DETAIL_TYPES, types);
                intent.putExtra(PowerUsageDetail.EXTRA_DETAIL_VALUES, values);

            }
            break;
            default:
            {
                int[] types = new int[] {
                    R.string.usage_type_on_time
                };
                double[] values = new double[] {
                    sipper.usageTime
                };
                intent.putExtra(PowerUsageDetail.EXTRA_DETAIL_TYPES, types);
                intent.putExtra(PowerUsageDetail.EXTRA_DETAIL_VALUES, values);
            }
        }
        startActivity(intent);

        return super.onPreferenceTreeClick(preferenceScreen, preference);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        if (DEBUG) {
            menu.add(0, MENU_STATS_TYPE, 0, R.string.menu_stats_total)
                    .setIcon(com.android.internal.R.drawable.ic_menu_info_details)
                    .setAlphabeticShortcut('t');
        }
        menu.add(0, MENU_STATS_REFRESH, 0, R.string.menu_stats_refresh)
                .setIcon(com.android.internal.R.drawable.ic_menu_refresh)
                .setAlphabeticShortcut('r');
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (DEBUG) {
            menu.findItem(MENU_STATS_TYPE).setTitle(mStatsType == BatteryStats.STATS_TOTAL
                    ? R.string.menu_stats_unplugged
                    : R.string.menu_stats_total);
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_STATS_TYPE:
                if (mStatsType == BatteryStats.STATS_TOTAL) {
                    mStatsType = BatteryStats.STATS_UNPLUGGED;
                } else {
                    mStatsType = BatteryStats.STATS_TOTAL;
                }
                updateAppsList();
                return true;
            case MENU_STATS_REFRESH:
                mStats = null;
                updateAppsList();
                return true;
            default:
                return false;
        }
    }

    private void updateAppsList() {
        if (mStats == null) {
            load();
        }
        mMaxPower = 0;
        mTotalPower = 0;

        mAppListGroup.removeAll();
        mUsageList.clear();
        processAppUsage();
        processMiscUsage();

        mAppListGroup.setOrderingAsAdded(false);

        Collections.sort(mUsageList);
        for (BatterySipper g : mUsageList) {
            if (g.getSortValue() < MIN_POWER_THRESHOLD) continue;
            double percent =  ((g.getSortValue() / mTotalPower) * 100);
            if (percent < 1) continue;
            PowerGaugePreference pref = new PowerGaugePreference(this, g.getIcon(), g);
            double scaleByMax = (g.getSortValue() * 100) / mMaxPower;
            g.percent = percent;
            pref.setTitle(g.name);
            pref.setPercent(percent);
            pref.setOrder(Integer.MAX_VALUE - (int) g.getSortValue()); // Invert the order
            pref.setGaugeValue(mScaleByMax ? scaleByMax : percent);
            if (g.uidObj != null) {
                pref.setKey(Integer.toString(g.uidObj.getUid()));
            }
            mAppListGroup.addPreference(pref);
            if (mAppListGroup.getPreferenceCount() > MAX_ITEMS_TO_LIST) break;
        }
        if (DEBUG) setTitle("Battery total uAh = " + ((mTotalPower * 1000) / 3600));
        synchronized (mRequestQueue) {
            if (!mRequestQueue.isEmpty()) {
                if (mRequestThread == null) {
                    mRequestThread = new Thread(this, "BatteryUsage Icon Loader");
                    mRequestThread.setPriority(Thread.MIN_PRIORITY);
                    mRequestThread.start();
                }
                mRequestQueue.notify();
            }
        }
    }

    private void processAppUsage() {
        SensorManager sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        final int which = mStatsType;
        final double powerCpuNormal = mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_NORMAL);
        final double averageCostPerByte = getAverageDataCost();
        long uSecTime = mStats.computeBatteryRealtime(SystemClock.elapsedRealtime(), which) * 1000;
        SparseArray<? extends Uid> uidStats = mStats.getUidStats();
        final int NU = uidStats.size();
        for (int iu = 0; iu < NU; iu++) {
            Uid u = uidStats.valueAt(iu);
            double power = 0;
            double highestDrain = 0;
            String packageWithHighestDrain = null;
            //mUsageList.add(new AppUsage(u.getUid(), new double[] {power}));
            Map<String, ? extends BatteryStats.Uid.Proc> processStats = u.getProcessStats();
            long cpuTime = 0;
            long cpuFgTime = 0;
            long gpsTime = 0;
            if (processStats.size() > 0) {
                // Process CPU time
                for (Map.Entry<String, ? extends BatteryStats.Uid.Proc> ent
                        : processStats.entrySet()) {
                    if (DEBUG) Log.i(TAG, "Process name = " + ent.getKey());
                    Uid.Proc ps = ent.getValue();
                    final long userTime = ps.getUserTime(which);
                    final long systemTime = ps.getSystemTime(which);
                    final long foregroundTime = ps.getForegroundTime(which);
                    cpuFgTime += foregroundTime * 10; // convert to millis
                    final long tmpCpuTime = (userTime + systemTime) * 10; // convert to millis
                    final double processPower = tmpCpuTime * powerCpuNormal;
                    cpuTime += tmpCpuTime;
                    power += processPower;
                    if (highestDrain < processPower) {
                        highestDrain = processPower;
                        packageWithHighestDrain = ent.getKey();
                    }

                }
                if (DEBUG) Log.i(TAG, "Max drain of " + highestDrain 
                        + " by " + packageWithHighestDrain);
            }
            if (cpuFgTime > cpuTime) {
                if (DEBUG && cpuFgTime > cpuTime + 10000) {
                    Log.i(TAG, "WARNING! Cputime is more than 10 seconds behind Foreground time");
                }
                cpuTime = cpuFgTime; // Statistics may not have been gathered yet.
            }
            power /= 1000;

            // Add cost of data traffic
            power += (u.getTcpBytesReceived(mStatsType) + u.getTcpBytesSent(mStatsType))
                    * averageCostPerByte;

            // Process Sensor usage
            Map<Integer, ? extends BatteryStats.Uid.Sensor> sensorStats = u.getSensorStats();
            for (Map.Entry<Integer, ? extends BatteryStats.Uid.Sensor> sensorEntry
                    : sensorStats.entrySet()) {
                Uid.Sensor sensor = sensorEntry.getValue();
                int sensorType = sensor.getHandle();
                BatteryStats.Timer timer = sensor.getSensorTime();
                long sensorTime = timer.getTotalTimeLocked(uSecTime, which) / 1000;
                double multiplier = 0;
                switch (sensorType) {
                    case Uid.Sensor.GPS:
                        multiplier = mPowerProfile.getAveragePower(PowerProfile.POWER_GPS_ON);
                        gpsTime = sensorTime;
                        break;
                    default:
                        android.hardware.Sensor sensorData =
                                sensorManager.getDefaultSensor(sensorType);
                        if (sensorData != null) {
                            multiplier = sensorData.getPower();
                            if (DEBUG) {
                                Log.i(TAG, "Got sensor " + sensorData.getName() + " with power = "
                                        + multiplier);
                            }
                        }
                }
                power += (multiplier * sensorTime) / 1000;
            }

            // Add the app to the list if it is consuming power
            if (power != 0) {
                BatterySipper app = new BatterySipper(packageWithHighestDrain, DrainType.APP, 0, u,
                        new double[] {power});
                app.cpuTime = cpuTime;
                app.gpsTime = gpsTime;
                app.cpuFgTime = cpuFgTime;
                mUsageList.add(app);
            }
            if (power > mMaxPower) mMaxPower = power;
            mTotalPower += power;
            if (DEBUG) Log.i(TAG, "Added power = " + power);
        }
    }

    private void addPhoneUsage(long uSecNow) {
        long phoneOnTimeMs = mStats.getPhoneOnTime(uSecNow, mStatsType) / 1000;
        double phoneOnPower = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                * phoneOnTimeMs / 1000;
        addEntry(getString(R.string.power_phone), DrainType.PHONE, phoneOnTimeMs,
                android.R.drawable.ic_menu_call, phoneOnPower);
    }

    private void addScreenUsage(long uSecNow) {
        double power = 0;
        long screenOnTimeMs = mStats.getScreenOnTime(uSecNow, mStatsType) / 1000;
        power += screenOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_ON);
        final double screenFullPower =
                mPowerProfile.getAveragePower(PowerProfile.POWER_SCREEN_FULL);
        for (int i = 0; i < BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS; i++) {
            double screenBinPower = screenFullPower * (i + 0.5f)
                    / BatteryStats.NUM_SCREEN_BRIGHTNESS_BINS;
            long brightnessTime = mStats.getScreenBrightnessTime(i, uSecNow, mStatsType) / 1000;
            power += screenBinPower * brightnessTime;
            if (DEBUG) {
                Log.i(TAG, "Screen bin power = " + (int) screenBinPower + ", time = "
                        + brightnessTime);
            }
        }
        power /= 1000; // To seconds
        addEntry(getString(R.string.power_screen), DrainType.SCREEN, screenOnTimeMs,
                android.R.drawable.ic_menu_view, power);
    }

    private void addRadioUsage(long uSecNow) {
        double power = 0;
        final int BINS = BatteryStats.NUM_SIGNAL_STRENGTH_BINS;
        long signalTimeMs = 0;
        for (int i = 0; i < BINS; i++) {
            long strengthTimeMs = mStats.getPhoneSignalStrengthTime(i, uSecNow, mStatsType) / 1000;
            power += strengthTimeMs / 1000
                    * mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ON, i);
            signalTimeMs += strengthTimeMs;
        }
        addEntry(getString(R.string.power_cell), DrainType.CELL, signalTimeMs,
                android.R.drawable.ic_menu_sort_by_size, power);
    }

    private void addWiFiUsage(long uSecNow) {
        long onTimeMs = mStats.getWifiOnTime(uSecNow, mStatsType) / 1000;
        long runningTimeMs = mStats.getWifiRunningTime(uSecNow, mStatsType) / 1000;
        double wifiPower = (onTimeMs * 0 /* TODO */
                * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)
            + runningTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ON)) / 1000;
        addEntry(getString(R.string.power_wifi), DrainType.WIFI, runningTimeMs,
                R.drawable.ic_wifi_signal_4, wifiPower);
    }

    private void addIdleUsage(long uSecNow) {
        long idleTimeMs = (uSecNow - mStats.getScreenOnTime(uSecNow, mStatsType)) / 1000;
        double idlePower = (idleTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_CPU_IDLE))
                / 1000;
        addEntry(getString(R.string.power_idle), DrainType.IDLE, idleTimeMs,
                android.R.drawable.ic_lock_power_off, idlePower);
    }

    private void addBluetoothUsage(long uSecNow) {
        long btOnTimeMs = mStats.getBluetoothOnTime(uSecNow, mStatsType) / 1000;
        double btPower = btOnTimeMs * mPowerProfile.getAveragePower(PowerProfile.POWER_BLUETOOTH_ON)
                / 1000;
        addEntry(getString(R.string.power_bluetooth), DrainType.IDLE, btOnTimeMs,
                com.android.internal.R.drawable.ic_volume_bluetooth_in_call, btPower);
    }

    private double getAverageDataCost() {
        final long WIFI_BPS = 1000000; // TODO: Extract average bit rates from system 
        final long MOBILE_BPS = 200000; // TODO: Extract average bit rates from system
        final double WIFI_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_WIFI_ACTIVE)
                / 3600;
        final double MOBILE_POWER = mPowerProfile.getAveragePower(PowerProfile.POWER_RADIO_ACTIVE)
                / 3600;
        final long mobileData = mStats.getMobileTcpBytesReceived(mStatsType) +
                mStats.getMobileTcpBytesSent(mStatsType);
        final long wifiData = mStats.getTotalTcpBytesReceived(mStatsType) +
                mStats.getTotalTcpBytesSent(mStatsType) - mobileData;
        final long radioDataUptimeMs = mStats.getRadioDataUptimeMs();
        final long mobileBps = radioDataUptimeMs != 0
                ? mobileData * 8 * 1000 / radioDataUptimeMs
                : MOBILE_BPS;

        double mobileCostPerByte = MOBILE_POWER / (mobileBps / 8);
        double wifiCostPerByte = WIFI_POWER / (WIFI_BPS / 8);
        if (wifiData + mobileData != 0) {
            return (mobileCostPerByte * mobileData + wifiCostPerByte * wifiData)
                    / (mobileData + wifiData);
        } else {
            return 0;
        }
    }

    private void processMiscUsage() {
        final int which = mStatsType;
        long uSecTime = SystemClock.elapsedRealtime() * 1000;
        final long uSecNow = mStats.computeBatteryRealtime(uSecTime, which);
        final long timeSinceUnplugged = uSecNow;
        if (DEBUG) {
            Log.i(TAG, "Uptime since last unplugged = " + (timeSinceUnplugged / 1000));
        }

        addPhoneUsage(uSecNow);
        addScreenUsage(uSecNow);
        addWiFiUsage(uSecNow);
        addBluetoothUsage(uSecNow);
        addIdleUsage(uSecNow); // Not including cellular idle power
        //addRadioUsage(uSecNow); // Cannot include this because airplane mode is not tracked yet
                                  // and we don't know if the radio is currently running on 2/3G.
    }

    private void addEntry(String label, DrainType drainType, long time, int iconId, double power) {
        if (power > mMaxPower) mMaxPower = power;
        mTotalPower += power;
        BatterySipper bs = new BatterySipper(label, drainType, iconId, null, new double[] {power});
        bs.usageTime = time;
        mUsageList.add(bs);
    }

    private void load() {
        try {
            byte[] data = mBatteryInfo.getStatistics();
            Parcel parcel = Parcel.obtain();
            parcel.unmarshall(data, 0, data.length);
            parcel.setDataPosition(0);
            mStats = com.android.internal.os.BatteryStatsImpl.CREATOR
                    .createFromParcel(parcel);
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException:", e);
        }
    }

    class BatterySipper implements Comparable<BatterySipper> {
        String name;
        Drawable icon;
        Uid uidObj;
        double value;
        double[] values;
        DrainType drainType;
        long usageTime;
        long cpuTime;
        long gpsTime;
        long cpuFgTime;
        double percent;

        BatterySipper(String label, DrainType drainType, int iconId, Uid uid, double[] values) {
            this.values = values;
            name = label;
            this.drainType = drainType;
            if (iconId > 0) {
                icon = getResources().getDrawable(iconId);
            }
            if (values != null) value = values[0];
            //if (uid > 0 && (mLabel == null || mIcon == null) // TODO:
            if ((label == null || iconId == 0) && uid != null) {
                getQuickNameIconForUid(uid);
            }
            uidObj = uid;
        }

        double getSortValue() {
            return value;
        }

        double[] getValues() {
            return values;
        }

        Drawable getIcon() {
            return icon;
        }

        public int compareTo(BatterySipper other) {
            // Return the flipped value because we want the items in descending order
            return (int) (other.getSortValue() - getSortValue());
        }

        void getQuickNameIconForUid(Uid uidObj) {
            final int uid = uidObj.getUid();
            final String uidString = Integer.toString(uid);
            if (mNameCache.containsKey(uidString)) {
                name = mNameCache.get(uidString);
                icon = mIconCache.get(uidString);
                return;
            }
            PackageManager pm = getPackageManager();
            final Drawable defaultActivityIcon = pm.getDefaultActivityIcon();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null) {
                name = Integer.toString(uid);
            } else {
                //name = packages[0];
            }
            icon = pm.getDefaultActivityIcon();
            synchronized (mRequestQueue) {
                mRequestQueue.add(this);
            }
        }

        /**
         * Sets name and icon
         * @param uid Uid of the application
         */
        void getNameIcon() {
            PackageManager pm = getPackageManager();
            final int uid = uidObj.getUid();
            final Drawable defaultActivityIcon = pm.getDefaultActivityIcon();
            String[] packages = pm.getPackagesForUid(uid);
            if (packages == null) {
                name = Integer.toString(uid);
                return;
            }

            String[] packageLabels = new String[packages.length];
            System.arraycopy(packages, 0, packageLabels, 0, packages.length);

            int preferredIndex = -1;
            // Convert package names to user-facing labels where possible
            for (int i = 0; i < packageLabels.length; i++) {
                // Check if package matches preferred package
                if (packageLabels[i].equals(name)) preferredIndex = i;
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(packageLabels[i], 0);
                    CharSequence label = ai.loadLabel(pm);
                    if (label != null) {
                        packageLabels[i] = label.toString();
                    }
                    if (ai.icon != 0) {
                        icon = ai.loadIcon(pm);
                        break;
                    }
                } catch (NameNotFoundException e) {
                }
            }
            if (icon == null) icon = defaultActivityIcon;

            if (packageLabels.length == 1) {
                name = packageLabels[0];
            } else {
                // Look for an official name for this UID.
                for (String pkgName : packages) {
                    try {
                        PackageInfo pi = pm.getPackageInfo(pkgName, 0);
                        if (pi.sharedUserLabel != 0) {
                            CharSequence nm = pm.getText(pkgName,
                                    pi.sharedUserLabel, pi.applicationInfo);
                            if (nm != null) {
                                name = nm.toString();
                                if (pi.applicationInfo.icon != 0) {
                                    icon = pi.applicationInfo.loadIcon(pm);
                                }
                                break;
                            }
                        }
                    } catch (PackageManager.NameNotFoundException e) {
                    }
                }
            }
            final String uidString = Integer.toString(uidObj.getUid());
            mNameCache.put(uidString, name);
            mIconCache.put(uidString, icon);
            mHandler.sendMessage(mHandler.obtainMessage(MSG_UPDATE_NAME_ICON, this));
        }
    }

    public void run() {
        while (true) {
            BatterySipper bs;
            synchronized (mRequestQueue) {
                if (mRequestQueue.isEmpty() || mAbort) {
                    mRequestThread = null;
                    return;
                }
                bs = mRequestQueue.remove(0);
            }
            bs.getNameIcon();
        }
    }

    private static final int MSG_UPDATE_NAME_ICON = 1;

    Handler mHandler = new Handler() {

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_UPDATE_NAME_ICON:
                    BatterySipper bs = (BatterySipper) msg.obj;
                    PowerGaugePreference pgp = 
                            (PowerGaugePreference) findPreference(
                                    Integer.toString(bs.uidObj.getUid()));
                    if (pgp != null) {
                        pgp.setIcon(bs.icon);
                        pgp.setPercent(bs.percent);
                        pgp.setTitle(bs.name);
                    }
                    break;
            }
            super.handleMessage(msg);
        }
    };
}