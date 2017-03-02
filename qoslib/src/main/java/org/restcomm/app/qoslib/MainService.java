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

package org.restcomm.app.qoslib;

import java.sql.Date;
import java.util.Calendar;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningServiceInfo;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Toast;

import org.restcomm.app.qoslib.Services.Intents.IntentHandler;
import org.restcomm.app.qoslib.Utils.LibCallbacks;
import org.restcomm.app.utillib.ContentProvider.ContentValuesGenerator;
import org.restcomm.app.utillib.ContentProvider.Provider;
import org.restcomm.app.utillib.ContentProvider.TablesEnum;
import org.restcomm.app.utillib.DataObjects.PhoneState;
import org.restcomm.app.utillib.ICallbacks;
import org.restcomm.app.utillib.Reporters.ReportManager;
import org.restcomm.app.qoslib.Services.LibPhoneStateListener;
import org.restcomm.app.utillib.Utils.Global;
import org.restcomm.app.utillib.Utils.GpsListener;
//import com.cortxt.com.mmcextension.firebase.MyFirebaseInstanceIDService;
import org.restcomm.app.mmcextension.VQ.VQManager;
import org.restcomm.app.qoslib.Services.TrackingManager;
import org.restcomm.app.qoslib.Services.Events.EventManager;
import org.restcomm.app.utillib.DataObjects.EventObj;
import org.restcomm.app.qoslib.Services.Intents.IntentDispatcher;
import org.restcomm.app.qoslib.Services.Location.GpsManagerOld;
import org.restcomm.app.qoslib.Utils.QosAPI;
import org.restcomm.app.utillib.DataObjects.DeviceInfo;
import org.restcomm.app.qoslib.Utils.RTWebSocket;
import org.restcomm.app.qoslib.UtilsOld.AccessPointHistory;
import org.restcomm.app.qoslib.UtilsOld.CellHistory;
import org.restcomm.app.utillib.DataObjects.ConnectionHistory;
import org.restcomm.app.qoslib.UtilsOld.DataMonitorStats;
import org.restcomm.app.utillib.Utils.DeviceInfoOld;
import org.restcomm.app.utillib.DataObjects.EventType;
import org.restcomm.app.utillib.Utils.PreferenceKeys;
import org.restcomm.app.utillib.Utils.LoggerUtil;
//import com.cortxt.com.mmcextension.datamonitor.DMService;
import org.restcomm.app.mmcextension.TravelDetector;
import org.restcomm.app.utillib.Utils.UsageLimits;
import org.restcomm.app.mmcextension.MMCSystemUtil;

import com.restcomm.app.qoslib.R;
import com.securepreferences.SecurePreferences;

//import com.securepreferences.SecurePreferences;


public class MainService extends Service {

	private ReportManager mReportManager;
	//private DMService dataMonitor;
	private static boolean headsetPlugged = false;
	private static String mApikey = null;
    private boolean btHeadset = false;
	private boolean bSentRestart = false;
	private boolean bRestartNextIdle = false;
	//meta-data variables
	public static final String TAG = MainService.class.getSimpleName();

	//internal registries
	private EventManager eventManager;


	//helper objects

	private static ConnectivityManager connectivityManager;
	private LibPhoneStateListener phoneStateListener;
	private static GpsManagerOld gpsManager, netLocationManager;
	private IntentDispatcher intentDispatcher;
	private IntentHandler intentHandler;
	private RTWebSocket webSocketManager;
	private VQManager mVQManager;
	public static TrackingManager trackingManager;
	private LibCallbacks mmcCallbacks; // communication with extension library which implements premium features
	private PowerManager powerManager;
	private PowerManager.WakeLock wakeLockScreen, wakeLockPartial;
	//other private variables
	public Handler handler = new Handler();

	private int lastKnownSatellites = 0;
	private boolean bRadioActive = false;  // Remeber if we are running the system/radiolog service
	private TravelDetector travelDetector;
	private CellHistory cellHistory;
	private AccessPointHistory accessPointHistory;
	private ConnectionHistory connectionHistory;
	private StartupGpsTimerTask startupGpsTimerTask;
	private Timer startupGpsTimer = new Timer();
	private EventActiveTimerTask eventActiveTask;
	private Timer eventActiveTimer = null;

	private int iUserID = 0;

	private boolean mmcActive = false;
	private boolean serviceRunning = false;
	private DataMonitorStats dataMonitorStats;
	private PhoneState mPhoneState = null;

	@Override
	public void onCreate() {
		super.onCreate();

		LoggerUtil.setDebuggable(this.isDebuggable());
		LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "onCreate", getPackageName() + " SERVICE WAS STARTED. isMainServiceRunning = " + isMainServiceRunning());

		try {
			mmcCallbacks = new LibCallbacks(this);
			mReportManager = ReportManager.getInstance(getApplicationContext());
			mPhoneState = new PhoneState (this);
			connectionHistory = new ConnectionHistory ();
			accessPointHistory = new AccessPointHistory(this);
			mReportManager.setService(mmcCallbacks, mPhoneState, connectionHistory);

			mReportManager.getCarrierLogo(null);

			eventManager = new EventManager(this);
			dataMonitorStats = new DataMonitorStats(this, handler);
			webSocketManager = new RTWebSocket(this);
			mVQManager = new VQManager(mmcCallbacks, mPhoneState);

			intentDispatcher = new IntentDispatcher(this);
			intentHandler = new IntentHandler(this, dataMonitorStats);
			//killMMCZombies();

			LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "OnCreate", "MMC startup");

			// start all our helpers and managers
			connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
			phoneStateListener = new LibPhoneStateListener(this, mPhoneState);
			gpsManager = new GpsManagerOld(this);
			netLocationManager = new GpsManagerOld(this);

			trackingManager = new TrackingManager(this);



			serviceRunning = true;

			powerManager = (PowerManager) getSystemService(POWER_SERVICE);
			wakeLockScreen = powerManager.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "MMC wakelock");
			modifyWakeLock ();
			registerPhoneStateListener();

			setAlarmManager ();
			int intervalDM = PreferenceManager.getDefaultSharedPreferences(MainService.this).getInt(PreferenceKeys.Miscellaneous.MANAGE_DATAMONITOR, 0);
			int appscansecDM = PreferenceManager.getDefaultSharedPreferences(MainService.this).getInt(PreferenceKeys.Miscellaneous.APPSCAN_DATAMONITOR, 60*5);

			set15MinuteAlarmManager(intervalDM * 60 * 1000, appscansecDM);
			travelDetector = new TravelDetector (mmcCallbacks);
			cellHistory = new CellHistory ((TelephonyManager)getSystemService(Context.TELEPHONY_SERVICE));

			String msg = Global.getAppName(this) + " " + getString (R.string.MMC_Notification_message);
			makeApplicationForeground(false, msg);

			if (this.getConnectivityManager() != null)
			{
				NetworkInfo networkInfo = this.getConnectivityManager().getActiveNetworkInfo();
				wifiStateChange (networkInfo);
			}

			//this call is to update the lastKnownMCCMNC
			getMCCMNC();

			iUserID = getUserID(MainService.this);

			// GPS event to server on startup to:
			// assure cust-ops gps is working, checkpoint in % coverage calculation, warm up GPS
			// and to center the map on user
			long last_time = PreferenceManager.getDefaultSharedPreferences(MainService.this).getLong(PreferenceKeys.Miscellaneous.LAST_TIME, 0);
			// need a startup event if we dont know whether to hide rankings
			boolean needEvent = false;
			if (!PreferenceManager.getDefaultSharedPreferences(MainService.this).contains(PreferenceKeys.Miscellaneous.HIDE_RANKING))
				needEvent = true;
			// run a gps location on first time startup (but not if it was recently running)
			if (System.currentTimeMillis() - 60000 * 60 * 4 > last_time || needEvent)
			{
				brieflyRunLocation (30, LocationManager.GPS_PROVIDER, true, null);  // Initially run the GPS for 20 seconds, then it will cycle the GPS and trigger the first update event
				EventObj evt = eventManager.registerSingletonEvent(EventType.EVT_STARTUP);
				// STARTUP event is triggered and uploads right away without GPS
				// and without waiting for SHUTDOWN event
				eventManager.temporarilyStageEvent(evt, null, null);
			}
			// in case app was killed in the middle of tracking, it will resume
			trackingManager.resumeTracking();


			// intentFilter is MMCIntentHandlerOld, it will declare and handle a large number of Intents
			registerReceiver(intentHandler, intentHandler.declareIntentFilters());

			// many features are interested in whether the Screen is turned off,
			// because Signal and network state may be unknown and should be ignored when off
			boolean screenOn = powerManager.isScreenOn();
			dataMonitorStats.setScreen(screenOn);
			mPhoneState.screenChanged(screenOn);
			eventManager.screenChanged(screenOn);

			verifyRegistration();

			if (getApiKey(this) != null)
				MMCSystemUtil.checkFirebaseRegistration (this);

			//		mReportManager.checkPlayServices(this, true);
        }
		catch (Exception e) {
			LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "OnCreate", "Exception occured during startup", e);
		}


	}


	@Override
	public void onTaskRemoved(Intent rootIntent) {
		if (bSentRestart || Build.VERSION.SDK_INT != 19)
			return;
		bSentRestart = true;
		LoggerUtil.logToFile(LoggerUtil.Level.WARNING, TAG, "onTaskRemoved", "SERVICE WILL RESTART");
		restartSelf();
	}
	

	@Override
	public void onDestroy() {
		super.onDestroy();
		
		LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "onDestroy", "");
		
		mReportManager.stop();


		PreferenceManager.getDefaultSharedPreferences(this).edit().putBoolean(PreferenceKeys.Miscellaneous.FIRST_BUCKET, false).commit();
		serviceRunning = false;


		if (bRadioActive == true)  // Stop the Raw Radio Service
		{
			MMCSystemUtil.startRilReader(this, false, false);
			bRadioActive = false;
		}

		unregisterReceiver(intentHandler);
		unRegisterPhoneStateListener();

		travelDetector.stop();

		netLocationManager.unregisterAllListeners();//.stopGps();
		gpsManager.unregisterAllListeners();
		keepAwake(false, false);

		eventManager.stop();
		trackingManager.cancelScheduledEvents();
		if (startupGpsTimerTask != null)
			startupGpsTimerTask.cancel();

		stopAlarmManager();
		stop15MinAlarmManager();

		//dataMonitor.onDestroy();
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}
	
	@Override
	public void onTrimMemory(int level) {
		if (level != 20)
			LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "onTrimMemory", "NOWS A GOOD TIME TO FREE SOME MEMORY: Level: " + level);
		
	}
	
	@Override
	public boolean onUnbind(Intent intent) {
		LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "onUnbind", " ");
		return super.onUnbind(intent);
	}
	
	@Override
	public void onConfigurationChanged (Configuration newConfig) {
		//MMCLogger.logToFile(MMCLogger.Level.ERROR, TAG, "onConfigurationChanged", newConfig.toString());
		
	}
	
	@Override
	public int onStartCommand (Intent intent, int flags, int startId) {
		LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "onStartCommand", "");
		if ((intent != null) && (intent.getBooleanExtra("ALARM_RESTART_SERVICE_DIED", false)))
	    {
			LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "onStartCommand after ALARM_RESTART_SERVICE_DIED", "");
	        if (isMainServiceRunning())
	        {
	        	LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "Service already running - return immediately...", "");
	            return START_STICKY;
	        }
	    }

        if ((intent != null) && intent.hasExtra (IntentHandler.GCM_MESSAGE_EXTRA)) {
            LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "onStartCommand with GCM_MESSAGE_EXTRA", "");
            Intent mmcintent = new Intent(IntentHandler.GCM_MESSAGE);
            String msg = intent.getStringExtra(IntentHandler.GCM_MESSAGE_EXTRA);
            mmcintent.putExtra(IntentHandler.GCM_MESSAGE_EXTRA, msg);
            this.sendBroadcast(mmcintent);
        }
		
		return START_STICKY;
		//return super.onStartCommand(intent, flags, startId);
	}

	private boolean isMainServiceRunning() {
		ActivityManager manager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
		for (RunningServiceInfo service : manager.getRunningServices(Integer.MAX_VALUE)) {
			if (MainService.class.getCanonicalName().equals(service.service.getClassName())) {
				return true;
			}
		}
		return false;
	}

	public void restartNextIdle ()
	{
		if (mmcActive == false && uploadEventCount <= 0)
			restartSelf ();
		else
			bRestartNextIdle = true;
	}

	public void restartSelf ()
	{
		LoggerUtil.logToFile(LoggerUtil.Level.WARNING, TAG, "restartSelf", getPackageName() + " SERVICE WILL RESTART");
		this.stopSelf();
		SecurePreferences securePrefs = MainService.getSecurePreferences(this);
		boolean stopped = securePrefs.getBoolean(PreferenceKeys.Miscellaneous.STOPPED_SERVICE, false);
		// If this MMC app is yeilding to another MMC app, it will stop when safe, but not restart
		if (Global.isServiceYeilded(this))
			stopped = true;
		if (stopped)  // if service is supposed to remain stopped, dont restart
			return;

		Intent restartService = new Intent(getApplicationContext(),
				this.getClass());
		restartService.setPackage(getPackageName());
		PendingIntent restartServicePI = PendingIntent.getService(
				getApplicationContext(), 1, restartService,
				PendingIntent.FLAG_ONE_SHOT);
		AlarmManager alarmService = (AlarmManager)getApplicationContext().getSystemService(Context.ALARM_SERVICE);
		alarmService.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() +10000, restartServicePI);
		System.exit(0);
	}

	public boolean isServiceRunning ()
	{
		return serviceRunning;
	}

	public void setAlarmManager ()// get a Calendar object with current time
	{

		if (Global.UPDATE_PERIOD == 0)
		{
			stopAlarmManager ();
			return;
		}
		// Get the AlarmManager service
		AlarmManager alarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

		// This is a 3hr wakeup alarm scheduled to occur at midnight and every 3 hours to trigger an update event even when thge phone is sleeping
		Intent intent2 = new Intent(IntentHandler.ACTION_ALARM_3HOUR);
		PendingIntent pendingIntent2 = PendingIntent.getBroadcast(this, 0, intent2, PendingIntent.FLAG_CANCEL_CURRENT);
		long wakeupAt = getNext3HRUpdateTime ();
		alarmManager.cancel( pendingIntent2 );
		 
		alarmManager.setRepeating( 
 				AlarmManager.RTC_WAKEUP, 
 				wakeupAt, 
 				Global.UPDATE_PERIOD,
 				pendingIntent2);
	}
	
	public void stopAlarmManager ()
	{
		try
		{
			AlarmManager alarmMgr = (AlarmManager) getSystemService( ALARM_SERVICE );
			
			Intent intent2 = new Intent(IntentHandler.ACTION_ALARM_3HOUR);
			PendingIntent alarm2 = PendingIntent.getBroadcast( 
					this, 
					0, 
					intent2, 
					PendingIntent.FLAG_CANCEL_CURRENT);
			alarmMgr.cancel( alarm2 );
			
			LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "stopAlarmManager", "stopped both alarm managers");
		}catch (Exception e)
		{
			LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "stopAlarmManager", "Exception", e);
		}
	}

	public void modifyWakeLock ()
	{
		if (wakeLockPartial != null && wakeLockPartial.isHeld())
			return;

		if(Build.VERSION.SDK_INT <= Build.VERSION_CODES.ICE_CREAM_SANDWICH)
		{
			wakeLockPartial = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "MMC Partial wakelock");
		}
		else
		{
			int screenOnUpdate = PreferenceManager.getDefaultSharedPreferences(MainService.this).getInt(PreferenceKeys.Miscellaneous.SCREEN_ON_UPDATE, 0);

			if (screenOnUpdate == 1)
				wakeLockPartial = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "MMC Partial wakelock");
			else
				wakeLockPartial = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MMC Partial wakelock");
		}

	}
	/*
	 * Wake the phone to a dim screen when the GPS is running so that it can measure signal during an event
	 * Then call with bWake = false to sleep again
	 */
	public void keepAwake (boolean bWake, boolean bPartial)
	{
		try
		{
			synchronized (this)
			{
				if (!this.getResources().getBoolean(R.bool.ALLOW_FULL_WAKELOCK))
					bPartial = true;
				if (EventObj.isDisabledEvent(this, EventObj.DISABLE_SCREENON))
					bPartial = true;
				LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "keepAwake", "Wake=" + bWake);
				while (wakeLockScreen.isHeld() && (!bPartial))
					wakeLockScreen.release();
				while (wakeLockPartial.isHeld())
					wakeLockPartial.release();
				if (bWake)
				{
					if (bPartial) //  && !wakeLockPartial.isHeld())
						wakeLockPartial.acquire();
					else if (!bPartial) //  && !wakeLockScreen.isHeld())
						wakeLockScreen.acquire();
				}

				if (connectivityManager == null)
					connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
			}

		}
		catch (Exception e)
		{
			LoggerUtil.logToFile(LoggerUtil.Level.WTF, TAG, "keepAwake", "wakeLock " + bWake + " exception:", e);
		}
	}

	public void manageDataMonitor(int setting, Integer appscan_seconds) {
		
		final int off = 0;		
		int intervalDM = PreferenceManager.getDefaultSharedPreferences(MainService.this).getInt(PreferenceKeys.Miscellaneous.MANAGE_DATAMONITOR, 0);
		int appscansecDM = PreferenceManager.getDefaultSharedPreferences(MainService.this).getInt(PreferenceKeys.Miscellaneous.APPSCAN_DATAMONITOR, 60*5);
		int dormantMode = PreferenceManager.getDefaultSharedPreferences(MainService.this).getInt(PreferenceKeys.Miscellaneous.USAGE_DORMANT_MODE, 0);

		if (dormantMode > 0)
			setting = 0;
		else if (setting == -1) // re-enable app scan according to setting
		{
			setting = intervalDM;
			appscan_seconds = appscansecDM;
			intervalDM = -1;
		}
		if (setting != intervalDM || (appscan_seconds != null && appscan_seconds != appscansecDM))
		{
			PreferenceManager.getDefaultSharedPreferences(MainService.this).edit().putInt(PreferenceKeys.Miscellaneous.MANAGE_DATAMONITOR, setting).commit();
			if (appscan_seconds != null)
				PreferenceManager.getDefaultSharedPreferences(MainService.this).edit().putInt(PreferenceKeys.Miscellaneous.APPSCAN_DATAMONITOR, appscan_seconds).commit();
			
			if(setting == 0) {
				stop15MinAlarmManager();			
			}
			else { //change alarm time, request is in minutes
				int alarmInterval = setting * 60 * 1000; // minutes * seconds * milliseconds
				set15MinuteAlarmManager(alarmInterval, appscan_seconds);
			}
		}
	}
	
	public void set15MinuteAlarmManager (int interval, Integer appscan_sec)
	{
		int dormantMode = PreferenceManager.getDefaultSharedPreferences(MainService.this).getInt(PreferenceKeys.Miscellaneous.USAGE_DORMANT_MODE, 0);
		if (dormantMode > 0)
			return;

		if (interval == 0)
			interval = 30*60*1000;
		if (appscan_sec == null || appscan_sec < 30)
			appscan_sec = 5*60;

		AlarmManager alarmMgr = (AlarmManager) getSystemService(Service.ALARM_SERVICE);

		if(interval > 0) {

		    Intent intent = new Intent(IntentHandler.ACTION_ALARM_15MINUTE);
			PendingIntent alarm = PendingIntent.getBroadcast(this,0,intent, PendingIntent.FLAG_CANCEL_CURRENT);	
			
			//default: AlarmManager.INTERVAL_FIFTEEN_MINUTES will do alarm every 15 minutes after the first alarm (set in onCreateOld)
			alarmMgr.setRepeating(AlarmManager.RTC_WAKEUP, getFirst15MinUpdateTime(interval), interval, alarm);


			if (appscan_sec != null)
				Global.SCANAPP_PERIOD = appscan_sec*1000;
			else
				Global.SCANAPP_PERIOD = 60000 * 5L;

			// Test if new UsageStats works
			// If so, we can reduce the wakeups which are needed to constantly check who is running foreground
//			if (Build.VERSION.SDK_INT >= 21) {
//				try {
//					UsageStatsManager usageStatsManager = (UsageStatsManager) this.getSystemService("usagestats");// Context.USAGE_STATS_SERVICE);
//					List<UsageStats> queryUsageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_BEST, System.currentTimeMillis() - 3600000, System.currentTimeMillis());
//					if (queryUsageStats.size() > 1 && Global.SCANAPP_PERIOD < 60000 * 10L)
//						Global.SCANAPP_PERIOD = 60000 * 10L;
//				} catch (Exception e){}
//			}

			intent = new Intent(IntentHandler.ACTION_ALARM_SCANAPPS);
			alarm = PendingIntent.getBroadcast(this,0,intent, PendingIntent.FLAG_CANCEL_CURRENT);	
			
			//default: AlarmManager.INTERVAL_FIFTEEN_MINUTES will do alarm every 15 minutes after the first alarm (set in onCreateOld)
			if (EventObj.isDisabledStat(this, EventObj.DISABLESTAT_APPS))
				alarmMgr.cancel(alarm);
			else {
				// if we dont need app foreground time, just use normal interval to scan apps and save battery
				if (EventObj.isDisabledStat(this, EventObj.DISABLESTAT_APPS_FORETIME))
					Global.SCANAPP_PERIOD = interval;
				//alarmMgr.setInexactRepeating(AlarmManager.RTC, System.currentTimeMillis() + 5000, Global.SCANAPP_PERIOD, alarm);
				alarmMgr.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 5000, alarm);
			}
		}
		else {

			LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "set15MinuteAlarmManager", "Stats alarm was set to 0 in onCreate: alarm not on");
			// If we're not using ACTION_ALARM_SCANAPPS alarm every 5 minutes for scanning apps, we may still want to use it as a heartbeat
//			boolean useHeartbeat = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("KEY_GCM_HEARTBEAT", false);
//			if (useHeartbeat)
//			{
//				// re-using the same timer here to avoid 2 timers in the event that both heartbeat and scanapps are in effect
//				// 2 independent timers might wake up device twice as often, doubling the battery impact, so I'm forcing it to use one for both cases
//				Intent intent = new Intent(IntentHandler.ACTION_ALARM_SCANAPPS);
//				PendingIntent alarm = PendingIntent.getBroadcast(this,0,intent, PendingIntent.FLAG_CANCEL_CURRENT);
//				alarmMgr.setRepeating(AlarmManager.RTC, System.currentTimeMillis() + 5000, 60000 * 5L, alarm);
//			}
		}
	}
	
	public void stop15MinAlarmManager ()
	{
		AlarmManager alarmMgr = (AlarmManager) getSystemService( ALARM_SERVICE );
		
		Intent intent2 = new Intent(IntentHandler.ACTION_ALARM_15MINUTE);
		PendingIntent alarm2 = PendingIntent.getBroadcast( 
				this, 
				0, 
				intent2, 
				PendingIntent.FLAG_CANCEL_CURRENT);
		alarmMgr.cancel( alarm2 );
		
		LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "stopAlarmManager", "stopped 15 min alarm manager");
	}	

	public static boolean isOnline() {
		if (connectivityManager != null)
		{
			NetworkInfo netinfo = connectivityManager.getActiveNetworkInfo();
			if (netinfo != null)
				return netinfo.isConnectedOrConnecting();
		}
		return false;	
	}

	public static String getApiKey (Context context)
	{
		if (mApikey != null)
			return mApikey;
		SharedPreferences securePref = MainService.getSecurePreferences(context);
		String value = securePref.getString(PreferenceKeys.User.APIKEY, null);
		mApikey = value;
		return value;
	}


	public static int getUserID (Context context)
	{
		SharedPreferences securePref = MainService.getSecurePreferences(context);
		int value = securePref.getInt(PreferenceKeys.User.USER_ID, -1);
		return value;
	}

	private static com.securepreferences.SecurePreferences securePrefs = null;
	public static com.securepreferences.SecurePreferences getSecurePreferences (Context context)
	{
		if (securePrefs == null)
			securePrefs = new com.securepreferences.SecurePreferences(context);
		return securePrefs;
	}
	
	public boolean isScreenOn ()
	{
		return mPhoneState.isScreenOn();
	}

	/**
	 * Add <code>phoneStateListener</code> as a listener to the many events related to the state
	 * of the phone.
	 */
	//MMCNetworkActiveListener wifilistener = new MMCNetworkActiveListener();
	private void registerPhoneStateListener() {
		int events = 0;
		events = PhoneStateListener.LISTEN_CELL_LOCATION 		|
					//PhoneStateListener.LISTEN_CELL_INFO	 			|
					PhoneStateListener.LISTEN_DATA_CONNECTION_STATE |
					PhoneStateListener.LISTEN_SIGNAL_STRENGTHS		|
					PhoneStateListener.LISTEN_CALL_STATE			|
					PhoneStateListener.LISTEN_DATA_ACTIVITY			|
					PhoneStateListener.LISTEN_SERVICE_STATE;

		TelephonyManager telephonyManager = (TelephonyManager)getSystemService(TELEPHONY_SERVICE);
		telephonyManager.listen(phoneStateListener, events);

	}

	
	/**
	 * Set <code>phoneStateListener</code> to stop listening to updates.
	 */
	private void unRegisterPhoneStateListener() {
		TelephonyManager telephonyManager = (TelephonyManager)getSystemService(TELEPHONY_SERVICE);
		telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
	}
	// Response: Check the version of the Service Mode module and offer to upgrade it if a new one is bundled
	public void onSvcModeVersion ( int version) {
		MMCSystemUtil.onSvcModeVersion(version);
	}

	public int getLastNumSatellites ()
	{
		return lastKnownSatellites;
	}
	public void setLastSatellites (int sats)
	{
		lastKnownSatellites = sats;
	}
	/*
	 * clear last known location when the gps starts, so that if the gps fails to get a fix, we know it failed afterward
	 */
	public void clearLastGoodLocation () 
	{
		// If last location was recent, leave it alone
		if (lastLocation != null && lastLocation.getTime() + 120000 < System.currentTimeMillis()) {
			lastLocation = null;
			intentDispatcher.updateLocation(null);
		}
	}
	
	/*
	 * last location
	 */
	public static Location getLastLocation(){
		return lastLocation;  
	}
	/*
	 * set last location
	 */
	public void setLastLocation (Location location) 
	{
		lastLocation = location;
	}

	public int getLastUserID() {
		return iUserID;
	}

	public void setBatteryCharging (boolean bCharging)
	{
		if (getUsageLimits().getDormantMode() > 0)
			return;
		if (bCharging != DeviceInfoOld.batteryCharging)
		{
			DeviceInfoOld.batteryCharging = bCharging;
			//MMCLogger.logToFile(MMCLogger.Level.DEBUG, TAG, "setBatteryCharging", "charging = " + bCharging);
			
			// battery charger state may affect travel detector settings
			if (getTravelDetector() != null)
				getTravelDetector().updateTravelPreference ();
		}
		if (accessPointHistory == null)
			return;
		if(bCharging) {		
			accessPointHistory.beginEvent (AccessPointHistory.TYPE_BATTERY, 0);
		}	
		else 
			accessPointHistory.endEvent (AccessPointHistory.TYPE_BATTERY);
	}
	
	public boolean updateTravelPreference ()
	{
		if (getTravelDetector() != null)
			return getTravelDetector().updateTravelPreference ();
		else 
			return false;
	}

	public Provider getDBProvider () //getContentResolver()
	{
		return mReportManager.getDBProvider();
	}
	//@Override
	public static Provider getDBProvider (Context context) //getContentResolver()
	{
		final ReportManager reportmanager = ReportManager.getInstance(context);
		return reportmanager.getDBProvider();
	}
	
	private static Location lastLocation = null;
	//private long lastEventId = 0;
	public void processNewFilteredLocation(Location location, int satellites) {
		//push the new location into the database
		// Only store locations that have changed by more than 0.0001 degrees (about 10 meters)
		// This is so a new sample is only added to an event if the location changed significantly
		if (location != null && lastLocation != null) // && lastEventId == stagedEventId)
	    {
			// dont store the same location twice
	    	if (Math.abs(lastLocation.getLatitude() - location.getLatitude()) < 0.00005 &&
	    			Math.abs(lastLocation.getLongitude() - location.getLongitude()) < 0.00005 &&
	    			Math.abs(lastLocation.getAccuracy() - location.getAccuracy()) < 8)
	    		return;

	    	if (location.getLongitude() == 0.0 && location.getLatitude() == 0.0)
	    		return;

	    	// mark event as inaccurate if there was a large jump
	    	if ((Math.abs(lastLocation.getLatitude() - location.getLatitude()) > 0.0014 ||
	    			Math.abs(lastLocation.getLongitude() - location.getLongitude()) > 0.0014) &&
	    			location.getAccuracy() < 50)
	    		location.setAccuracy(location.getAccuracy() + 80);
	    }
		if (location == null)
		{
			location = MainService.getLastLocation();
			if (location == null || location.getTime() + 60000 < System.currentTimeMillis())
				location = null;
		}
	    lastLocation = location;
	    location.setTime(System.currentTimeMillis());
		ContentValues values = ContentValuesGenerator.generateFromLocation(location, 0, satellites);
		getDBProvider(this).insert(TablesEnum.LOCATIONS.getContentUri(), values);
	}
	
	public ReportManager getReportManager ()
	{
		return mReportManager;
	}

	public void startRadioLog (boolean bStart, String reason, EventType eventType)
	{
		synchronized (this)
		{
			try
			{
				QosAPI.checkHostApp(this);
				LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "startRadioLog", "bStart="+ bStart + " reason: " + reason);
				if (bStart == true)
				{
					boolean allowSvc = true;// this.getResources().getBoolean(R.bool.ALLOW_SVCMODE);
					if (bRadioActive == false && getUseRadioLog()) // && reason != null) //  && reason.equals("call"))
			        {
						boolean useServiceMode = false;
						if (allowSvc == true) {
							boolean svcmodeActive = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("KEY_SETTINGS_SVCMODE", false);

							if (svcmodeActive)
								useServiceMode = true;
						}

						if (useServiceMode || (reason != null && reason.equals("call")))
						{
							LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "startRadioLog", "start service mode");

							getPhoneState().setServicemode (null);
							boolean useLogcat = false;
							if (reason != null && reason.equals("call"))
								useLogcat = true;
							MMCSystemUtil.startRilReader (this, true, useLogcat);
							bRadioActive = true;
						}
			        }
					if (reason != null && reason.equals("call"))
						reason = null;
					mmcActive = true;

					LoggerUtil.logToFile(LoggerUtil.Level.DEBUG, TAG, "startRadioLog", "makeApplicationForeground");
					makeApplicationForeground(true, reason);
					// schedule to check in 5 seconds for no-events, then stop radio log
					if (eventActiveTimer == null)
					{
						connectionHistory.start ();
						eventActiveTimer = new Timer ();
						eventActiveTask = new EventActiveTimerTask();
						eventActiveTimer.schedule(eventActiveTask, 1000, 1000);
					}
				}
				getPhoneStateListener().processLastSignal();

			}
			catch (Exception e)
			{
				LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "startRadioLog", "exception", e);
			}
		}
	}


	public boolean isMonitoringActive ()
	{
		return mmcActive;
	}
	
	/* 
	 * Periodically check notifications for dropped calls to expire and remove them after 1 day
	 */
	private void checkNotificationExpiry ()
	{
		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		long expiry = PreferenceManager.getDefaultSharedPreferences(this).getLong(PreferenceKeys.Monitoring.NOTIFICATION_EXPIRY, 0);
		if (expiry > 0 && expiry < System.currentTimeMillis())
		{
			notificationManager.cancel(LibPhoneStateListener.MMC_DROPPED_NOTIFICATION);
		}
	}
	
	class EventActiveTimerTask extends TimerTask {
		@Override
		public void run() {
			handler.post(new Runnable() {  
				
                // @Override  
                 public void run() {  
                	 if (!mmcActive)
                	 {
                		 try {
                			 if (eventActiveTimer != null)
                			 {
                				 eventActiveTimer.cancel();
                				 eventActiveTimer = null;
                				 eventActiveTask = null;
                			 }
                		 }
                		 catch (Exception e) {}
                		 return;
                	 }
                	 String neighbors = cellHistory.updateNeighborHistory (null, null);
                	 if (neighbors != null && neighbors.length() > 2)
                		 intentDispatcher.updateNeighbors (neighbors);
					 connectionHistory.updateRxTx ();
                	 
                 }
			});
		}
	}
	private long timeEngg = 0;
	public void setEnggQueryTime ()
	{
		timeEngg = System.currentTimeMillis();
		getPhoneStateListener().processLastSignal ();
	}
	public boolean isMMCActiveOrRunning ()
	{
		if (mmcActive)
			return true;
		if (timeEngg > 0 && timeEngg + 120000 > System.currentTimeMillis())
			return true;
		if (webSocketManager.isConnected())
			return true;
		if (mReportManager.manualTransitEvent != null || mReportManager.manualPlottingEvent != null)
			return true;
		timeEngg = 0;
		return false;

	}
	public boolean isMMCActive () {
		if (mmcActive)
			return true;
		return false;
	}

	private int uploadEventCount = 0;
	// keep reference count of events being uploaded, so service can't stop while uploading
	public void uploadingEvent (boolean increment)
	{
		if (increment)
			uploadEventCount ++;
		else {
			if (uploadEventCount > 0)
				uploadEventCount--;
			else
				LoggerUtil.logToFile(LoggerUtil.Level.WTF, TAG, "uploadingEvent", "DECREMENT with count = " + uploadEventCount);
			if (uploadEventCount <= 0) {
				if (bRestartNextIdle == true && !getTravelDetector().isTravelling() && mmcActive == false) {
					bRestartNextIdle = false;
					restartSelf();
				}
			}
		}
	}
	public int getUploadCount ()
	{
		return uploadEventCount;
	}
	/**
	 * Put the service in Idle state, after evenru event has finished recording samples
	 */
	public void goIdle ()
	{
		LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "StopRadioTimerTask", "MMC IDLE ");
		
		if (getPhoneState().isOffHook())
			return;
		// Unbind from the RadioLogService
		if (mmcActive == true)
		{
			QosAPI.checkHostApp(this);
			mmcActive = false;
			if (bRadioActive == true)  // Stop the Raw Radio Service
			{
				getPhoneState().setServicemode(null);
				MMCSystemUtil.startRilReader(this, false, false);
				bRadioActive = false;
			}

			try
			{
				if (!isInTracking())
                    keepAwake(false, false);
				makeApplicationForeground(false, null);
				if (eventActiveTask != null)
				{
					if (eventActiveTimer != null)
						eventActiveTimer.cancel ();
					eventActiveTask = null;
					eventActiveTimer = null;
				}
				netLocationManager.unregisterAllListeners();
		   	 	gpsManager.unregisterAllListeners();
				if (bRestartNextIdle == true && !getTravelDetector().isTravelling() && uploadEventCount <= 0)
		   	 	{
					bRestartNextIdle = false;
						restartSelf();
		   	 	}
				closeSvcPanel();
				getPhoneStateListener().closedServicePanel = false; // allow panel to be opened again next time
			}catch (Exception e){}
		}
	}

	private static AlertDialog svcPanel;
	//static NerdScreen avcgpanelAct;
	public static void createSvcPanel (Context context) {
		boolean show = PreferenceManager.getDefaultSharedPreferences(context).getBoolean("KEY_SETTINGS_SVCMODE_PANEL", false);
		if (show == false)
			return;
		PackageManager pkMan = context.getPackageManager();
		int systemAlertPermissionValue = pkMan.checkPermission("android.permission.SYSTEM_ALERT_WINDOW", context.getPackageName());
		if (systemAlertPermissionValue == 0) {

			AlertDialog.Builder b = new AlertDialog.Builder(context);
			b.setCancelable(true);
			b.setOnCancelListener(new DialogInterface.OnCancelListener() {
				@Override
				public void onCancel(DialogInterface dialog) {
					svcPanel.dismiss();
					svcPanel = null;
					LibPhoneStateListener.closedServicePanel = true;
				}
			});

			b.setTitle("Service Mode");
			b.setMessage("-\n-\n-\n-\n-\n-\n-\n-\n-\n");


			svcPanel = b.create();
			svcPanel.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
			svcPanel.getWindow().setGravity(Gravity.BOTTOM);

			svcPanel.show();
		}
	}

	public static void updateSvcPanel (Context context, String values, String name)
	{
		if (svcPanel == null)
			createSvcPanel (context);
		if (svcPanel == null)
			return;
		svcPanel.setTitle (name);
		svcPanel.setMessage(values);
	}


	public static void closeSvcPanel ()
	{
		if (svcPanel != null)
		{
			svcPanel.dismiss();
			svcPanel = null;

		}
	}

	public UsageLimits getUsageLimits ()
	{
		return Global.usageLimits;
	}

	public boolean getUseRadioLog ()
	{
		if (EventObj.isDisabledEvent(this, EventObj.DISABLE_RILREADER))
			return false;
		if (MMCSystemUtil.isServiceModeEnabled())
			return true;
		String pname = this.getPackageName();
		int permissionForReadLogs = this.getPackageManager().checkPermission(android.Manifest.permission.READ_LOGS, pname); //0 means allowed
		if (permissionForReadLogs == 0)
			return true;
		return false;
	}



	public void updateNumberOfSatellites(int numberOfSatellites, int numberOfSatellitesUsedInFix) {
		intentDispatcher.updateLiveStatusSatelliteCount(numberOfSatellites, numberOfSatellitesUsedInFix);
	}

	/*
	 * Initially run the GPS briefly, then it will cycle the GPS off and back on again, triggering the initial 'Update' event
	 * The GPS cycle can be helpful in case the GPS is 'stuck' on the first attempt
	 * the most likely cause is expired A-GPS assistance data (ephemeris or almanac) in the cache, which may cause it to ignore satellites
	 */
	public void brieflyRunLocation (int timeoutSeconds, String provider, boolean triggerUpdate, GpsListener listener)
	{
		GpsListener locListener = listener;
		if (listener == null)
			locListener = new GpsListener();
		locListener.setFirstFixTimeout(0); // using our own timeout to force gps off after timeoutSeconds
		locListener.setOperationTimeout(0);
		locListener.setProvider (provider);
		
		if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
			netLocationManager.registerListener(locListener);
		}
		else {
			gpsManager.registerListener(locListener);
		}
		//now set the timer to schedule the newly-staged-event's un-staging
		startupGpsTimerTask = new StartupGpsTimerTask();
		startupGpsTimerTask.trigger = triggerUpdate;
		startupGpsTimerTask.listener = locListener;
		int timeout = timeoutSeconds * 1000;
		if (timeoutSeconds == 0) 
			timeout = 200;
		startupGpsTimer.schedule(startupGpsTimerTask, timeout);	
	}
	
	
	/**
	 * Stop the first Gps after a few seconds, and then trigger the first 'Update' event
	 * This is because on the first time running, the GPS might get stuck and not return any fix the first time
	 * But then the Gps may work better the second time. 
	 * This is likely due to assisted-gps caching an expired ephemeris or almanac
	 */
	class StartupGpsTimerTask extends TimerTask {
		public boolean trigger = false;
		public GpsListener listener = null;
		@Override
		public void run() {
			handler.post(new Runnable() {  
                // @Override  
                 public void run() {  
                	 if (listener.getProvider().equals(LocationManager.NETWORK_PROVIDER))
                	 	 netLocationManager.unregisterListener(listener);
                	 else
                	 {
                		 gpsManager.startupGpsFinished (listener);
                		 gpsManager.unregisterListener(listener);
                	 }
                	 if (trigger)
                		 getEventManager().triggerUpdateEvent(false, true);
                 }
			});
		}
	}

	// Called every few hours to make sure user is still registered properly
	public void verifyRegistration ()
	{
		String apikey = getApiKey(this);

		if (mReportManager != null)
		{
			new Thread(new Runnable() {
				@Override
				public void run() {
					try {
						if (!mReportManager.isAuthorized() && getLogin(MainService.this) != null) {
							mApikey = null;
							mReportManager.authorizeDevice(getLogin(MainService.this), QosAPI.getPassword(MainService.this), false);
							// Ensure registered for Firebase Cloud Messages
							MMCSystemUtil.checkFirebaseRegistration (MainService.this);

						//	mReportManager.checkPlayServices(MainService.this, true);
						}
					} catch (Exception e) {
					}
				}
			}).start();
		}
		else
		{
			apikey = null;
		}

		// Also if a dropped call notification has been shown for a hours, expire and remove it
		checkNotificationExpiry();
	}

	public void requestCsvEmail(){
		new Thread(
				new Runnable() {
					@Override
					public void run() {
                        int userID = getUserID(MainService.this);
						String apikey = getApiKey(MainService.this);
						
						try {
							String carrier = ((TelephonyManager)getSystemService(TELEPHONY_SERVICE)).getNetworkOperatorName();

							int[] MCCMNC = getMCCMNC();
							String manufacturer = DeviceInfoOld.getManufacturer();
							String model = DeviceInfoOld.getPhoneModel();
							String device = DeviceInfoOld.getDevice();
							String appname = Global.getAppName(MainService.this);

							String strResponse = getReportManager().requestCsvEmail(userID, carrier, MCCMNC[0], MCCMNC[1], manufacturer, model, device, appname, apikey);
							handler.post(new Runnable() {
				                // @Override  
				                 public void run() {  
				                	 String msg = getString(R.string.GenericText_EmailRequested);
										Toast toast = Toast.makeText(MainService.this, msg, Toast.LENGTH_LONG);
										toast.show();
									 LoggerUtil.logToFile(LoggerUtil.Level.ERROR, TAG, "requestCsvEmail", "Sent request");
								 }
							});
							
						} catch (Exception e) {
							LoggerUtil.logToFile(LoggerUtil.Level.WTF, TAG, "requestCsvEmail", "Exception cannnot request email: ", e);
						}
					}
				}
			).start();
	}	
	


    public DeviceInfo getDevice() {
		return mReportManager.getDevice();
	}

	public int[] getMCCMNC() {
		return mReportManager.getMCCMNC();
	}

    public void broadcastGpsStatus(boolean status) {
		intentDispatcher.updateGpsStatus(status);
	}
	
	public long getNext3HRUpdateTime ()
    {
        long lNextUpdate = 0;
        Calendar evtCal = Calendar.getInstance();
        evtCal.setTime (new Date(System.currentTimeMillis()));
        int hour = evtCal.get (Calendar.HOUR);
        int min = evtCal.get (Calendar.MINUTE);
        int sec = evtCal.get (Calendar.SECOND);
        // calculate how many seconds until the next 3HR in local time
        int delay = 3600 * (2-(hour%3));   // example: local 16:00, next update at 18:00. hrs = (2-(16%3)) = 2-1 = 1 hr
                                                                                        // + sec = 3600-(60*0+0) = +3600 sec
                                        // example: local 18:02:10, next update at 21:00. hrs = (2-(18%3)) = 2 hr
        delay += 3600 - (60*min + sec);    // + sec = 3600-(60*2+10) = +3470 sec
        Random r = new Random(); 
        long spreadDelay = (long)r.nextInt(900);
        delay += spreadDelay;
		if (Global.UPDATE_PERIOD > 3600 * 4 * 1000)
			delay = (int)(Global.UPDATE_PERIOD/1000 + spreadDelay);

        lNextUpdate = System.currentTimeMillis() + delay*1000;
        return lNextUpdate;
    }

	public long getFirst15MinUpdateTime(int interval) {
        int intervalMinutes = interval /60 /1000;
        Calendar evtCal = Calendar.getInstance();
        evtCal.setTime (new Date(System.currentTimeMillis()));    
        int min = evtCal.get (Calendar.MINUTE);
        //currentTime + how many minutes till the next 15 min interval       
        return System.currentTimeMillis() + (1000 * 60 * (intervalMinutes-(min%intervalMinutes)));
    }

	private boolean isDebuggable () {
		boolean DEBUGGABLE = ( 0 != ( this.getApplicationInfo().flags &= ApplicationInfo.FLAG_DEBUGGABLE));
		String serverURL = this.getString(R.string.MMC_URL_LIN);
		if (serverURL.contains("dev."))
			DEBUGGABLE = true;
		return DEBUGGABLE;
	}

	/*
	 * ==========================================================================
	 * Start getters for minions
	 */
	
	public EventManager getEventManager(){
		return this.eventManager;
	}

	public RTWebSocket getWebSocketManager(){
		return this.webSocketManager;
	}

	public DataMonitorStats getDataMonitorStats () {
		return this.dataMonitorStats;
	}
	
	public LibPhoneStateListener getPhoneStateListener(){
		return this.phoneStateListener;
	}
	public PhoneState getPhoneState(){
		return this.mPhoneState;
	}

	public ConnectivityManager getConnectivityManager(){
		return this.connectivityManager;
	}

	public VQManager getVQManager() { return mVQManager; }

	public ICallbacks getCallbacks(){
		return (ICallbacks) this.mmcCallbacks;
	}

	public static GpsManagerOld getGpsManager(){
		return gpsManager;
	}
	public static GpsManagerOld getNetLocationManager(){
		return netLocationManager;
	}

	public boolean isTravelling ()
	{
		if (getTravelDetector() == null)
			return false;
		if (getTravelDetector().isTravelling() || getTravelDetector().isConfirmed())
			return true;
		return false;
	}
	public static boolean isInTracking (){
		if (trackingManager == null)
			return false;
		return trackingManager.isTracking();
	}
	public static String getDriveTestTrigger (){
		if (trackingManager == null)
			return "";
		if (trackingManager.getDriveTestTrigger() == null)
			return "";
		return trackingManager.getDriveTestTrigger();
	}
	public static void setDriveTestTrigger(String trig)
	{
		trackingManager.setDriveTestTrigger (trig);
	}
	public static void triggerDriveTest (String reason, boolean start)
	{
		synchronized (trackingManager) {
			if (trackingManager == null || start == isInTracking())
				return;
			trackingManager.triggerDriveTest(reason, start);
		}
	}
	public static int getTestScriptIndex (){
		if (trackingManager == null)
			return -1;
		return trackingManager.getTestScriptIndex();
	}
	public static boolean isTestScriptWaiting (){
		if (trackingManager == null)
			return false;
		return trackingManager.isAdvancedTrackingWaiting();
	}
	
	public TravelDetector getTravelDetector ()
	{
		return this.travelDetector;
	}
	
	public CellHistory getCellHistory ()
	{
		return this.cellHistory;
	}
	
	public ConnectionHistory getConnectionHistory ()
	{
		return this.connectionHistory;
	}
	
	public AccessPointHistory getAccessPointHistory ()
	{
		return this.accessPointHistory;
	}
	
	public TrackingManager getTrackingManager ()
	{
		return trackingManager;
	}

	public static String getLogin (Context context) {return QosAPI.getLogin(context);}
	public static void setLogin(Context context, String login) {
		QosAPI.setLogin(context, login);}
	public static void setLoginToIMEI (Context context) {
		QosAPI.setLoginToIMEI(context);}

	public static int startDriveTest (Context context, int minutes, boolean coverage, int speed, int connectivity, int sms, int video, int audio, int web, int vq, int youtube, int ping)
	{ return QosAPI.startDriveTest(context, minutes, coverage, speed, connectivity, sms, video, audio, web, vq, youtube, ping);}

	public IntentDispatcher getIntentDispatcher(){
		return this.intentDispatcher;
	}
	
	public void makeApplicationForeground(boolean bForeground, String message) {


		boolean iconAlways = PreferenceManager.getDefaultSharedPreferences(MainService.this).getBoolean(PreferenceKeys.Miscellaneous.ICON_ALWAYS, false);
		
		if (DeviceInfoOld.getPlatform() != 3 && !android.os.Build.BRAND.toLowerCase().contains("blackberry")) //  && message != null && message.length() > 0)
		{
			if (message != null && message.equals("background"))
			{
				message = null;
			}
			//String title = getString(R.string.app_label) + " ";
			String notifyToLaunch = (this.getResources().getString(R.string.NOTIFY_CLICK_TO_LAUNCH));
			notifyToLaunch = notifyToLaunch.length() > 0 ? notifyToLaunch : "Dashboard";
			PackageManager packageManager = this.getApplicationContext().getPackageManager();
			ApplicationInfo applicationInfo = this.getApplicationContext().getApplicationInfo();
			String title = (String)packageManager.getApplicationLabel(applicationInfo) + " ";

			if (bForeground == false && message == null)
			{
				message = null; // "MyMobileCoverage is idle";
				if (this.trackingManager.isTracking())
					title += getString(R.string.status_IdleRecording);// " idle (recording mode)";
				else
					title += getString(R.string.status_Idle);// " is idle";
			}
			else if (bForeground == true && message == null)
				title += getString(R.string.status_Active); // " is active";
			
			int icon = 0;
			int customIcon = (this.getResources().getInteger(R.integer.CUSTOM_NOTIFIER));
			if (!bForeground)
			{
				if (customIcon == 0)
					icon = R.drawable.ic_stat_mmcinactive;
				else
					icon = R.drawable.ic_stat_notification_icon_innactive;
			}
			else
			{
				if (customIcon == 0)
				{
					if (iconAlways == false)
						icon = R.drawable.ic_stat_mmcinactive; // use the more subtle icon if not iconAlways
					else
						icon = R.drawable.ic_stat_mmcactive;
				}
				else
					icon = R.drawable.ic_stat_notification_icon;
			}
			// special wifi icon to protect against wifi stopping the service
			if (message != null && message.indexOf("wifi") == 0)
			{
				if (iconAlways == true || mmcActive)
					return;
				if (message.equals("wifitrue"))
				{
					//icon = android.R.drawable.stat_notify_sync;
					title += getString(R.string.status_inWiFi);
				}
				else if (message.equals("wififalse"))
				{
					//icon = R.drawable.stat_notify_wifi_in_range;
					title += getString(R.string.status_noWiFi);
				}
				message = null;
			}

			// broadcast intent about going foreground or background
			Intent intent = new Intent(IntentHandler.MMC_SERVICE_FOREGROUND);
			intent.putExtra(IntentHandler.MMC_FOREGROUND, bForeground);
			if (message == null)
				intent.putExtra(IntentHandler.MMC_FOREGROUND_MESSAGE, title);
			else
				intent.putExtra(IntentHandler.MMC_FOREGROUND_MESSAGE, message);
			this.sendBroadcast(intent);
			boolean allowForeground = (this.getResources().getBoolean(R.bool.ALLOW_SERVICE_FOREGROUND));
			if (allowForeground == false)
				return;

			if (bForeground || iconAlways == true)
			{
				Notification notification = new Notification(icon, message, System.currentTimeMillis());
				notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
				PendingIntent pendingIntent = null;
				if (!notifyToLaunch.toLowerCase().equals("none")) {
					Intent notificationIntent = new Intent();//, "com.cortxt.app.mmcui.Activities.Dashboard");
					if (notifyToLaunch.length() == 0)
						notifyToLaunch = "com.cortxt.app.uilib.Activities.Dashboard";
					notificationIntent.setClassName(MainService.this, notifyToLaunch);
					notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
					pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
				}
				notification.setLatestEventInfo(this, title, message, pendingIntent);
				startForeground(R.integer.MMC_NOTIFICATION_INT, notification);
			}
			else
				this.stopForeground(true);
		}

	}

	/*
	 * When setting is changed for 'Always show top icon', update the notification icon according to foreground activity
	 */
	public void setIconBehavior ()
	{
		makeApplicationForeground(mmcActive, null);
	}
	

	/**
	 * Deletes db records older than 30 days.
	 * @see Provider#pruneDB()
	 */
	public void pruneDB() {
		new Thread(
			new Runnable() {
				@Override
				public void run() {
					Provider provider = getDBProvider(MainService.this);//ContentResolver().acquireContentProviderClient(Tables.AUTHORITY).getLocalContentProvider();
					provider.pruneDB();

					getReportManager().getLocalStorageReporter().pruneDB ();
				}
			}
		).start();
	}
	/*
	 * Stop private helper methods
	 * ===========================================================================
	 * Start helper classes
	 */

	public void trackAccessPoints(int roamValue) {
		//Is connection WiFi(10), WiMax(11), Bluetooth(13)
		int activeConnection = PhoneState.ActiveConnection(this);
		if (accessPointHistory == null)
			return;
		if(activeConnection == 10) {
			WifiManager wifiManager = (WifiManager)getSystemService(Context.WIFI_SERVICE);
			int rssi =  wifiManager.getConnectionInfo().getRssi();
			accessPointHistory.beginEvent(AccessPointHistory.TYPE_WIFI, rssi);	
			
		}	
		//No longer in WiFi, WiMax,  or roaming, so take the start time and find the end time
		else 
		{
			accessPointHistory.endEvent(AccessPointHistory.TYPE_WIFI);
		}
		
		if(activeConnection == 11) {
			accessPointHistory.beginEvent(AccessPointHistory.TYPE_WIMAX, 0);		}	
		//No longer in WiFi, WiMax, or roaming, so take the start time and find the end time
		else 
			accessPointHistory.endEvent(AccessPointHistory.TYPE_WIMAX);
		
		if(activeConnection == 13) {
			accessPointHistory.beginEvent(AccessPointHistory.TYPE_BLUETOOTH, 0);		}	
		//No longer in WiFi, WiMax,Bluetooth, or roaming, so take the start time and find the end time
		else 
			accessPointHistory.endEvent(AccessPointHistory.TYPE_BLUETOOTH);
		
		if(roamValue == 1) {
			accessPointHistory.beginEvent(AccessPointHistory.TYPE_ROAMING, 0);		
		}
		else if(roamValue == 2){
			accessPointHistory.endEvent(AccessPointHistory.TYPE_ROAMING);
		}
	}

	
	public boolean bWifiConnected = false;
	public void wifiStateChange(NetworkInfo networkInfo) {
		
		boolean wasConnected = bWifiConnected;
		if (networkInfo == null || this.getTravelDetector() == null)
			return;
		// Attempts to send any queued events, if we are now connected to wifi
		if (networkInfo.getState() == NetworkInfo.State.CONNECTED && networkInfo.getType() == ConnectivityManager.TYPE_WIFI)
  		{
			bWifiConnected = true;
			getEventManager().sendQueuedEvents();
			boolean changedWifi = PreferenceManager.getDefaultSharedPreferences(MainService.this).getBoolean(PreferenceKeys.Miscellaneous.CHANGED_SEND_ON_WIFI, false);
		}
		else if (networkInfo.getState() != NetworkInfo.State.CONNECTED && networkInfo.getType() == ConnectivityManager.TYPE_WIFI)
			bWifiConnected = false;

		if (webSocketManager.isConnected() == true) {
			// Force intents to be resent for last known location, signal and cell
			MainService.this.getEventManager().signalSnapshot(null);
		}
		// Android Bug kills and restarts our service after connection to or from WiFi
		// KitKat doesn't even restart it. The service dies!!!
		if (Build.VERSION.SDK_INT == 19) // protect the app from KitKat bug 
		if (bWifiConnected != wasConnected)
		{
				makeApplicationForeground(true, "wifi" + bWifiConnected);
				// Outage needs to last longer than 2 seconds to actually trigger
				handler.postDelayed(new Runnable() {
					  @Override
					  public void run() {
						  makeApplicationForeground(false, "wifi");}}, 20000);

		}
		
		// dont check for travel while connected to wifi
		this.getTravelDetector().updateTravelPreference();
	}
	
	public void setHeadsetState (int state)
	{
		headsetPlugged = state == 1 ? true : false;
	}
	public static boolean isHeadsetPlugged ()
	{
		return headsetPlugged;
	}

	public void setBTHeadsetState (int state)
	{
		btHeadset = state == 1 ? true : false;
	}
	public boolean isBTHeadset ()
	{
		return btHeadset;
	}

}
