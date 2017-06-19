package com.mparticle.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.TelephonyManager;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.mparticle.MParticle;
import com.mparticle.internal.Constants.MessageKey;
import com.mparticle.internal.Constants.PrefKeys;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;
import java.util.TimeZone;

public class DeviceAttributes {

    //re-use this whenever an attribute can't be determined
    static final String UNKNOWN = "unknown";
    private JSONObject deviceInfo;
    private JSONObject appInfo;
    private boolean firstCollection = true;

    /** package-private **/ DeviceAttributes() {}

    /**
     * Generates a collection of application attributes that will not change during an app's process.
     *
     * This contains logic that MUST only be called once per app run.
     *
     * @param appContext the application context
     * @return a JSONObject of application-specific attributes
     */
    public JSONObject getStaticApplicationInfo(Context appContext) {
        JSONObject attributes = new JSONObject();
        SharedPreferences preferences = appContext.getSharedPreferences(Constants.PREFS_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        try {
            long now = System.currentTimeMillis();
            PackageManager packageManager = appContext.getPackageManager();
            String packageName = appContext.getPackageName();
            attributes.put(MessageKey.APP_PACKAGE_NAME, packageName);
            String versionCode = UNKNOWN;
            try {
                PackageInfo pInfo = appContext.getPackageManager().getPackageInfo(packageName, 0);
                versionCode = Integer.toString(pInfo.versionCode);
                attributes.put(MessageKey.APP_VERSION, pInfo.versionName);
            } catch (PackageManager.NameNotFoundException nnfe) { }

            attributes.put(MessageKey.APP_VERSION_CODE, versionCode);

            String installerPackageName = packageManager.getInstallerPackageName(packageName);
            if (installerPackageName != null) {
                attributes.put(MessageKey.APP_INSTALLER_NAME, installerPackageName);
            }
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                attributes.put(MessageKey.APP_NAME, packageManager.getApplicationLabel(appInfo));
            } catch (PackageManager.NameNotFoundException e) {
                // ignore missing data
            }

            attributes.put(MessageKey.BUILD_ID, MPUtility.getBuildUUID(versionCode));
            attributes.put(MessageKey.APP_DEBUG_SIGNING, MPUtility.isAppDebuggable(appContext));
            attributes.put(MessageKey.APP_PIRATED, preferences.getBoolean(PrefKeys.PIRATED, false));

            attributes.put(MessageKey.MPARTICLE_INSTALL_TIME, preferences.getLong(PrefKeys.INSTALL_TIME, now));
            if (!preferences.contains(PrefKeys.INSTALL_TIME)) {
                editor.putLong(PrefKeys.INSTALL_TIME, now);
            }
            UserConfig userConfig = ConfigManager.getUserConfig(appContext);
            int totalRuns = userConfig.getTotalRuns(0) + 1;
            userConfig.setTotalRuns(totalRuns);
            attributes.put(MessageKey.LAUNCH_COUNT, totalRuns);

            long useDate = userConfig.getLastUseDate(0);
            attributes.put(MessageKey.LAST_USE_DATE, useDate);
            userConfig.setLastUseDate(now);
            try {
                PackageInfo pInfo = packageManager.getPackageInfo(packageName, 0);
                int persistedVersion = preferences.getInt(PrefKeys.COUNTER_VERSION, -1);
                int countSinceUpgrade = userConfig.getLaunchesSinceUpgrade();
                long upgradeDate = preferences.getLong(PrefKeys.UPGRADE_DATE, now);

                if (persistedVersion < 0 || persistedVersion != pInfo.versionCode){
                    countSinceUpgrade = 0;
                    upgradeDate = now;
                    editor.putInt(PrefKeys.COUNTER_VERSION, pInfo.versionCode);
                    editor.putLong(PrefKeys.UPGRADE_DATE, upgradeDate);
                }
                countSinceUpgrade += 1;
                userConfig.setLaunchesSinceUpgrade(countSinceUpgrade);

                attributes.put(MessageKey.LAUNCH_COUNT_SINCE_UPGRADE, countSinceUpgrade);
                attributes.put(MessageKey.UPGRADE_DATE, upgradeDate);
            } catch (PackageManager.NameNotFoundException e) {
                // ignore missing data
            }

            attributes.put(MessageKey.ENVIRONMENT, MParticle.getInstance().getConfigManager().getEnvironment().getValue());
            attributes.put(MessageKey.INSTALL_REFERRER, preferences.getString(Constants.PrefKeys.INSTALL_REFERRER, null));

            boolean install = preferences.getBoolean(PrefKeys.FIRST_RUN_INSTALL, true);
            attributes.put(MessageKey.FIRST_SEEN_INSTALL, install);
            editor.putBoolean(PrefKeys.FIRST_RUN_INSTALL, false);
        } catch (Exception e) {
            // again difference devices can do terrible things, make sure that we don't bail out completely
            // and return at least what we've built so far.
        } finally {
            editor.apply();
        }
        return attributes;
    }

    public static void addAndroidId(JSONObject attributes, Context context) throws JSONException {
        if (!MParticle.isAndroidIdDisabled()) {
            String androidId = MPUtility.getAndroidID(context);
            attributes.put(MessageKey.DEVICE_ID, androidId);
            attributes.put(MessageKey.DEVICE_ANID, androidId);
            attributes.put(MessageKey.DEVICE_OPEN_UDID, MPUtility.getOpenUDID(context));
        }
    }

    /**
     * Generates a collection of device attributes that will not change during an app's process.
     *
     * This contains logic that MUST only be called once per app run.
     *
     * @param appContext the application context
     * @return a JSONObject of device-specific attributes
     */
    JSONObject getStaticDeviceInfo(Context appContext) {
        final JSONObject attributes = new JSONObject();

        try {
            // device/OS attributes
            attributes.put(MessageKey.BUILD_ID, android.os.Build.ID);
            attributes.put(MessageKey.BRAND, Build.BRAND);
            attributes.put(MessageKey.PRODUCT, android.os.Build.PRODUCT);
            attributes.put(MessageKey.DEVICE, android.os.Build.DEVICE);
            attributes.put(MessageKey.MANUFACTURER, android.os.Build.MANUFACTURER);
            attributes.put(MessageKey.PLATFORM, "Android");
            attributes.put(MessageKey.OS_VERSION, Build.VERSION.SDK);
            attributes.put(MessageKey.OS_VERSION_INT, Build.VERSION.SDK_INT);
            attributes.put(MessageKey.MODEL, android.os.Build.MODEL);
            attributes.put(MessageKey.RELEASE_VERSION, Build.VERSION.RELEASE);

            // device ID
            addAndroidId(attributes, appContext);

            attributes.put(MessageKey.DEVICE_BLUETOOTH_ENABLED, MPUtility.isBluetoothEnabled(appContext));
            attributes.put(MessageKey.DEVICE_BLUETOOTH_VERSION, MPUtility.getBluetoothVersion(appContext));
            attributes.put(MessageKey.DEVICE_SUPPORTS_NFC, MPUtility.hasNfc(appContext));
            attributes.put(MessageKey.DEVICE_SUPPORTS_TELEPHONY, MPUtility.hasTelephony(appContext));

            JSONObject rootedObject = new JSONObject();
            rootedObject.put(MessageKey.DEVICE_ROOTED_CYDIA, MPUtility.isPhoneRooted());
            attributes.put(MessageKey.DEVICE_ROOTED, rootedObject);

            // screen height/width
            WindowManager windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            windowManager.getDefaultDisplay().getMetrics(metrics);
            attributes.put(MessageKey.SCREEN_HEIGHT, metrics.heightPixels);
            attributes.put(MessageKey.SCREEN_WIDTH, metrics.widthPixels);
            attributes.put(MessageKey.SCREEN_DPI, metrics.densityDpi);

            // locales
            Locale locale = Locale.getDefault();
            attributes.put(MessageKey.DEVICE_COUNTRY, locale.getDisplayCountry());
            attributes.put(MessageKey.DEVICE_LOCALE_COUNTRY, locale.getCountry());
            attributes.put(MessageKey.DEVICE_LOCALE_LANGUAGE, locale.getLanguage());
            attributes.put(MessageKey.DEVICE_TIMEZONE_NAME, MPUtility.getTimeZone());
            attributes.put(MessageKey.TIMEZONE, TimeZone.getDefault().getRawOffset() / (1000 * 60 * 60));
            // network
            TelephonyManager telephonyManager = (TelephonyManager) appContext
                    .getSystemService(Context.TELEPHONY_SERVICE);
            int phoneType = telephonyManager.getPhoneType();
            if (phoneType != TelephonyManager.PHONE_TYPE_NONE) {
                // NOTE: network attributes can be empty if phone is in airplane
                // mode and will not be set
                String networkCarrier = telephonyManager.getNetworkOperatorName();
                if (0 != networkCarrier.length()) {
                    attributes.put(MessageKey.NETWORK_CARRIER, networkCarrier);
                }
                String networkCountry = telephonyManager.getNetworkCountryIso();
                if (0 != networkCountry.length()) {
                    attributes.put(MessageKey.NETWORK_COUNTRY, networkCountry);
                }
                // android combines MNC+MCC into network operator
                String networkOperator = telephonyManager.getNetworkOperator();
                if (6 == networkOperator.length()) {
                    attributes.put(MessageKey.MOBILE_COUNTRY_CODE, networkOperator.substring(0, 3));
                    attributes.put(MessageKey.MOBILE_NETWORK_CODE, networkOperator.substring(3));
                }

            }
            attributes.put(MessageKey.DEVICE_IS_TABLET, MPUtility.isTablet(appContext));
            attributes.put(MessageKey.DEVICE_IS_IN_DST, MPUtility.isInDaylightSavings());

        } catch (Exception e) {
            //believe it or not, difference devices can be missing build.prop fields, or have otherwise
            //strange version/builds of Android that cause unpredictable behavior
        }

        return attributes;
    }

    /**
     * For the following fields we always want the latest values
     */
    public void updateDeviceInfo(Context context, JSONObject deviceInfo) {
        deviceInfo.remove(MessageKey.LIMIT_AD_TRACKING);
        deviceInfo.remove(MessageKey.GOOGLE_ADV_ID);
        MPUtility.AndroidAdIdInfo adIdInfo = MPUtility.getGoogleAdIdInfo(context);
        String message = "Failed to collect Google Play Advertising ID, be sure to add Google Play services or com.google.android.gms:play-services-ads to your app's dependencies.";
        if (adIdInfo != null) {
            try {
                deviceInfo.put(MessageKey.LIMIT_AD_TRACKING, adIdInfo.isLimitAdTrackingEnabled);
                if (adIdInfo.isLimitAdTrackingEnabled && MParticle.getInstance().getConfigManager().getRestrictAAIDBasedOnLAT()) {
                    message = "Google Play Advertising ID available but ad tracking is disabled on this device.";
                } else {
                    deviceInfo.put(MessageKey.GOOGLE_ADV_ID, adIdInfo.id);
                    message = "Successfully collected Google Play Advertising ID.";
                }
            }catch (JSONException jse) {
                Logger.debug("Failed while building device-info object: ", jse.toString());
            }
        }
        if (firstCollection) {
            Logger.debug(message);
            firstCollection = false;
        }

        try {
            PushRegistrationHelper.PushRegistration registration = PushRegistrationHelper.getLatestPushRegistration(context);
            if (registration != null && !MPUtility.isEmpty(registration.instanceId)) {
                deviceInfo.put(Constants.MessageKey.PUSH_TOKEN, registration.instanceId);
                deviceInfo.put(Constants.MessageKey.PUSH_TOKEN_TYPE, Constants.GOOGLE_GCM);
            }

            deviceInfo.put(Constants.MessageKey.PUSH_SOUND_ENABLED, MParticle.getInstance().getConfigManager().isPushSoundEnabled());
            deviceInfo.put(Constants.MessageKey.PUSH_VIBRATION_ENABLED, MParticle.getInstance().getConfigManager().isPushVibrationEnabled());
        }catch (JSONException jse) {
            Logger.debug("Failed while building device-info object: ", jse.toString());
        }
    }

    public JSONObject getDeviceInfo(Context context){
        if (deviceInfo == null){
            deviceInfo = getStaticDeviceInfo(context);
        }
        updateDeviceInfo(context, deviceInfo);
        return deviceInfo;
    }

    public JSONObject getAppInfo(Context context) {
        if (appInfo == null) {
            appInfo = getStaticApplicationInfo(context);
        }
        return appInfo;
    }
}
