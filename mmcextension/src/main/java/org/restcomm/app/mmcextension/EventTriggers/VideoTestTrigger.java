package org.restcomm.app.mmcextension.EventTriggers;

import android.media.MediaPlayer;
import android.os.Handler;
import android.webkit.WebView;

import org.restcomm.app.utillib.DataObjects.EventType;
import org.restcomm.app.utillib.ICallbacks;

public class VideoTestTrigger {
	public static final String TAG = VideoTestTrigger.class.getSimpleName();


	public VideoTestTrigger(ICallbacks ownercb, Handler handler) {
	}
	
	public void stop() {
	}
	
	public void killTest (int testType)
	{
	}
	/**
	 * Runs a speed test in a background thread and reports the results if network is not wifi
	 * @param updateUI whether or not to update the UI about progress and results
	 */
	public void runTest(boolean updateUI, int trigger, EventType testType) {
	}
	
	
	// Make static for now to share MediaPlayer with Activity so it can display video in its view
	public static MediaPlayer getMediaPlayer ()
	{
		return null;
	}
    public static void setMediaPlayer (MediaPlayer mp)
    {
    }

    public static void setWebView (WebView _web)
    {
    }
}



