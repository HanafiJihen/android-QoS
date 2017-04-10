/*
 * TeleStax, Open Source Cloud Communications
 * Copyright 2011-2016, Telestax Inc and individual contributors
 * by the @authors tag.
 *
 * This program is free software: you can redistribute it and/or modify
 * under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation; either version 3 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *
 * For questions related to commercial use licensing, please contact sales@telestax.com.
 *
 */

package org.restcomm.app.utillib.Utils;

import android.app.ActivityManager;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.preference.PreferenceManager;

import org.restcomm.app.utillib.ICallbacks;

import org.json.JSONObject;

import java.util.List;

/**
 * Created by bscheurman on 16-03-18.
 */
public class Global {
    private static ICallbacks callbacks;
    public static UsageLimits usageLimits;
    public static long UPDATE_PERIOD = 60000 * 180L;
    public static long SCANAPP_PERIOD = 60000 * 5L;

    public static void setCallback (ICallbacks cb)
    {
        callbacks = cb;
        usageLimits = new UsageLimits(cb);
    }

    public static UsageLimits getUsageLimits ()
    {
        return usageLimits;
    }

    public static void startService (Context context, boolean bUI)
    {
        String packagename = context.getPackageName();

        // See if this app is yeilded to another app
        if (bUI) {
            PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PreferenceKeys.Miscellaneous.YEILDED_SERVICE, packagename).commit();
            Intent intent = new Intent(CommonIntentActionsOld.ACTION_START_UI);
            intent.putExtra("packagename", packagename);
            context.sendBroadcast(intent);
        }
        if (!isServiceYeilded (context)) {
            Intent bgServiceIntent = new Intent();
            //bgServiceIntent.setComponent(new ComponentName(context.getPackageName(), "com.cortxt.app.corelib.MainService"));
            bgServiceIntent.setComponent(new ComponentName(context.getPackageName(), "org.restcomm.app.qoslib.MainService"));
            LoggerUtil.logToFile(LoggerUtil.Level.ERROR, "Global", "isServiceYeilded", "MMC Service started for " + packagename);

            context.startService(bgServiceIntent);
        }
    }

    public static boolean isServiceYeilded (Context context)
    {
        String packagename = context.getPackageName();
        String yeilded = PreferenceManager.getDefaultSharedPreferences(context).getString(PreferenceKeys.Miscellaneous.YEILDED_SERVICE, null);
        if (yeilded != null && !packagename.equals(yeilded))
        {
            LoggerUtil.logToFile(LoggerUtil.Level.ERROR, "Global", "ISSERVICEYEILDED", "MMC Service for " + packagename + " yeilded to " + yeilded);
            return true;
        }

        return false;
    }

    public static void stopService (Context context)
    {
        Intent bgServiceIntent = new Intent();
        bgServiceIntent.setComponent(new ComponentName(context.getPackageName(), "com.cortxt.app.corelib.MainService"));
        context.startService(bgServiceIntent);
    }
    public static boolean isMainServiceRunning(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
            if ("com.cortxt.app.corelib.MainService".equals(service.service.getClassName())) {
                return true;
            }
        }
        return false;
    }
    public static boolean isOnline ()
    {
        if (callbacks != null)
            return callbacks.isOnline();
        return false;
    }

    public static JSONObject getServiceMode ()
    {
        if (callbacks != null)
            return callbacks.getServiceMode();
        return null;
    }

    public static String getString (Context context, String res)
    {
        String str = null;
        if (context == null && callbacks != null)
            context = callbacks.getContext ();
        if (context != null) {
            int resid = context.getResources().getIdentifier(res, "string", context.getPackageName());
            if (resid > 0)
                str = context.getString(resid);
        }
        return str;
    }

    public static int getInteger (Context context, String res)
    {
        int i = 0;
        if (context == null && callbacks != null)
            context = callbacks.getContext ();
        if (context != null)
        {
            int resid = context.getResources().getIdentifier(res, "integer", context.getPackageName());
            if (resid > 0)
                i = context.getResources().getInteger(resid);
            return i;
        }
        return i;
    }

    public static boolean checkPermission(Context context, String permission)
    {
        PackageManager pkMan = context.getPackageManager();
        int permissionValue = pkMan.checkPermission(permission, context.getPackageName());
        if (permissionValue == 0) {
            return true;
        }
        return false;
    }
    /**
     *
     An app can supply a custom category value for an added layer of customization to select configurations from event responses
     For example, the app can set category=VIP and then server can return special configuration when it sees an event with 'VIP'
      */
    public static String getAppCategory (Context context) {
        String category = PreferenceManager.getDefaultSharedPreferences(context).getString(PreferenceKeys.Miscellaneous.APP_CATEGORY, null);
        //category = "test";
        return category;
    }
    public static void setAppCategory (Context context, String val) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putString(PreferenceKeys.Miscellaneous.APP_CATEGORY, val);
    }

    public static String getAppName (Context context)
    {
        String appname = "";
        try{
            PackageManager packageManager = context.getApplicationContext().getPackageManager();
            ApplicationInfo applicationInfo = context.getApplicationInfo();
            String name = (String)((applicationInfo != null) ? packageManager.getApplicationLabel(applicationInfo) : context.getPackageName());

            appname = name;
        }
        catch (Exception e) {}
        return appname;
    }

    public static String getApiUrl (Context context)
    {
        return getString (context, "MMC_URL_LIN");
    }

    public static boolean isGpsRunning () // Needed for Engineering screen to show it
    {
        if (callbacks != null)
            return callbacks.isGpsRunning();
        return false;
    }
    public static boolean isInTracking ()
    {
        if (callbacks != null)
            return callbacks.isInTracking();
        return false;
    }
    static public boolean isMMCServiceRunning() {
        if (callbacks != null)
        {
            return true;
        }
        else
            return false;
    }

    public static boolean isHeadsetPlugged ()
    {
        if (callbacks != null)
            return callbacks.isHeadsetPlugged();
        return false;
    }

    public static boolean isTravelling ()
    {
        if (callbacks != null)
            return callbacks.isTravelling();
        return false;
    }

    public static String getApiKey (Context context)
    {
        //if (mApikey != null)
        //    return mApikey;
        if (context == null && callbacks != null)
            context = callbacks.getContext ();
        SharedPreferences securePref = PreferenceKeys.getSecurePreferences(context);
        String value = securePref.getString(PreferenceKeys.User.APIKEY, null);
        return value;
    }

    public static String getLogin (Context context)
    {
        //if (mApikey != null)
        //    return mApikey;
        if (context == null && callbacks != null)
            context = callbacks.getContext ();
        SharedPreferences securePref = PreferenceKeys.getSecurePreferences(context);
        String value = securePref.getString(PreferenceKeys.User.USER_EMAIL, null);
        return value;
    }

    public static int getUserID (Context context)
    {
        if (context == null && callbacks != null)
            context = callbacks.getContext ();
        SharedPreferences securePref = PreferenceKeys.getSecurePreferences(context);
        int value = securePref.getInt(PreferenceKeys.User.USER_ID, -1);
        return value;
    }

    public static boolean isOnline(Context context) {
        ConnectivityManager connectivityManager = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);

        if (connectivityManager != null)
        {
            NetworkInfo netinfo = connectivityManager.getActiveNetworkInfo();
            if (netinfo != null)
                return netinfo.isConnectedOrConnecting();
        }
        return false;
    }


    public static String getStackTrace (Exception e)
    {
        if (callbacks != null)
        {
            String s = callbacks.getStackTrace(e);
            return s;
        }
        return null;
    }

    public static int getAppImportance(String packageName, Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Service.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> runningProcesses = manager.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo info : runningProcesses) {
            String process = info.processName;
            if(process.equals(packageName)) {
                if(info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                    return 1;  //foreground
                }
                else if(info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_BACKGROUND) {
                    return 2; //background
                }
                else if(info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE) {
                    return 3; //visible - actively visible to the user, but not in the immediate foreground
                }
                else if(info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE) {
                    return 4; //visible - actively visible to the user, but not in the immediate foreground
                }
                else if(info.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_SERVICE) {
                    return 5; //visible - actively visible to the user, but not in the immediate foreground
                }
            }
        }
        return 0;
    }
}
