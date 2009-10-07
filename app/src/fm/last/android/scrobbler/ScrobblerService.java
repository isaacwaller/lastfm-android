/***************************************************************************
 *   Copyright 2005-2009 Last.fm Ltd.                                      *
 *   Portions contributed by Casey Link, Lukasz Wisniewski,                *
 *   Mike Jennings, and Michael Novak Jr.                                  *
 *                                                                         *
 *   This program is free software; you can redistribute it and/or modify  *
 *   it under the terms of the GNU General Public License as published by  *
 *   the Free Software Foundation; either version 2 of the License, or     *
 *   (at your option) any later version.                                   *
 *                                                                         *
 *   This program is distributed in the hope that it will be useful,       *
 *   but WITHOUT ANY WARRANTY; without even the implied warranty of        *
 *   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
 *   GNU General Public License for more details.                          *
 *                                                                         *
 *   You should have received a copy of the GNU General Public License     *
 *   along with this program; if not, write to the                         *
 *   Free Software Foundation, Inc.,                                       *
 *   51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.         *
 ***************************************************************************/
package fm.last.android.scrobbler;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.concurrent.ArrayBlockingQueue;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager.NameNotFoundException;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.IBinder;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import fm.last.android.AndroidLastFmServerFactory;
import fm.last.android.LastFMApplication;
import fm.last.android.R;
import fm.last.android.utils.UserTask;
import fm.last.api.AudioscrobblerService;
import fm.last.api.LastFmServer;
import fm.last.api.RadioTrack;
import fm.last.api.Session;

/**
 * A Last.fm scrobbler for Android
 * 
 * @author Sam Steele <sam@last.fm>
 * 
 * This is a scrobbler that can scrobble both our radio player as well as the built-in media player
 * and other 3rd party apps that broadcast fm.last.android.metachanged notifications.  We can't
 * rely on com.android.music.metachanged due to a bug in the built-in media player that does not
 * broadcast this notification when playing the first track, only when starting the next track.
 * 
 * Scrobbles and Now Playing data are serialized between launches, and will be sent when the track or
 * network state changes.  This service has a very short lifetime and is only started for a few seconds at
 * a time when there's work to be done.  This server is started when music state or network state change.
 * 
 * Scrobbles are submitted to the server after Now Playing info is sent, or when a network connection becomes
 * available.
 * 
 * Sample code for a 3rd party to integrate with us:
 * 
 * Intent i = new Intent("fm.last.android.metachanged");
 * i.putExtra("artist", {artist name});
 * i.putExtra("album", {album name});
 * i.putExtra("track", {track name});
 * i.putExtra("duration", {track duration in milliseconds});
 * sendBroadcast(i);
 * 
 * To love the currently-playing track:
 * Intent i = new Intent("fm.last.android.LOVE");
 * sendBroadcast(i);
 * 
 * To ban the currently-playing track:
 * Intent i = new Intent("fm.last.android.BAN");
 * sendBroadcast(i);
 *
 */
public class ScrobblerService extends Service {
	private Session mSession;
	public static final String LOVE = "fm.last.android.LOVE";
	public static final String BAN = "fm.last.android.BAN";
	AudioscrobblerService mScrobbler;
	SubmitTracksTask mSubmissionTask = null;
	NowPlayingTask mNowPlayingTask = null;
	ArrayBlockingQueue<ScrobblerQueueEntry> mQueue;
	ScrobblerQueueEntry mCurrentTrack = null;
	
	public static final String META_CHANGED = "fm.last.android.metachanged";
	public static final String PLAYBACK_FINISHED = "fm.last.android.playbackcomplete";
	public static final String PLAYBACK_STATE_CHANGED = "fm.last.android.playstatechanged";
	public static final String STATION_CHANGED = "fm.last.android.stationchanged";
	public static final String PLAYBACK_ERROR = "fm.last.android.playbackerror";
	public static final String UNKNOWN = "fm.last.android.unknown";
	
	@Override
	public void onCreate() {
		super.onCreate();

		LastFmServer server = AndroidLastFmServerFactory.getServer();
    	mSession = LastFMApplication.getInstance().map.get( "lastfm_session" );

    	if (mSession != null && PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble", true)) {
			String version = "0.1";
			try {
				version = getPackageManager().getPackageInfo("fm.last.android", 0).versionName;
			} catch (NameNotFoundException e) {
			}
			mScrobbler = server.createAudioscrobbler( mSession, version );
        } else {
        	//User not authenticated, shutting down...
        	stopSelf();
        	return;
        }
        
        try {
			if(getFileStreamPath("currentTrack.dat").exists()) {
	            FileInputStream fileStream = openFileInput("currentTrack.dat");
	            ObjectInputStream objectStream = new ObjectInputStream(fileStream);
	            Object obj = objectStream.readObject();
	            if(obj instanceof ScrobblerQueueEntry) {
	            	mCurrentTrack = (ScrobblerQueueEntry)obj;
	            }
	            objectStream.close();
	            fileStream.close();
			}
        } catch (Exception e) {
        	mCurrentTrack = null;
        }

        mQueue = new ArrayBlockingQueue<ScrobblerQueueEntry>(200);
        
        try {
			if(getFileStreamPath("queue.dat").exists()) {
	            FileInputStream fileStream = openFileInput("queue.dat");
	            ObjectInputStream objectStream = new ObjectInputStream(fileStream);
	            Object obj = objectStream.readObject();
	            if(obj instanceof Integer) {
	            	Integer count = (Integer)obj;
	            	for(int i = 0; i < count.intValue(); i++) {
	                    obj = objectStream.readObject();
	                    if(obj instanceof ScrobblerQueueEntry)
	                    	mQueue.add((ScrobblerQueueEntry)obj);
	            	}
	            }
	            objectStream.close();
	            fileStream.close();
	            Log.i("Last.fm", "Loaded " + mQueue.size() + " queued tracks");
			}
        } catch (Exception e) {
        	e.printStackTrace();
        }
	}

	public void onDestroy() {
		super.onDestroy();
		
		try {
	        Intent intent = new Intent("fm.last.android.scrobbler.FLUSH");
	        PendingIntent alarmIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
	        AlarmManager am = (AlarmManager)getSystemService(Context.ALARM_SERVICE);
	        am.cancel(alarmIntent); //cancel any pending alarm intents
			if(mQueue.size() > 0) {
		        //schedule an alarm to wake the device and try again in an hour
		        am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 3600000, alarmIntent);
			}
			if(getFileStreamPath("currentTrack.dat").exists())
				deleteFile("currentTrack.dat");
			if(mCurrentTrack != null) {
				FileOutputStream filestream = openFileOutput("currentTrack.dat", 0);
				ObjectOutputStream objectstream = new ObjectOutputStream(filestream);
				objectstream.writeObject(mCurrentTrack);
				objectstream.close();
				filestream.close();
			}
		} catch (Exception e) {
			if(getFileStreamPath("currentTrack.dat").exists())
				deleteFile("currentTrack.dat");
			Log.e("LastFm", "Unable to save current track state");
			e.printStackTrace();
		}

		try {
			if(getFileStreamPath("queue.dat").exists())
				deleteFile("queue.dat");
			if(mQueue.size() > 0) {
				Log.i("Last.fm", "Writing " + mQueue.size() + " queued tracks");
				FileOutputStream filestream = openFileOutput("queue.dat", 0);
				ObjectOutputStream objectstream = new ObjectOutputStream(filestream);
				objectstream.writeObject(new Integer(mQueue.size()));
				while(mQueue.size() > 0) {
					ScrobblerQueueEntry e = mQueue.take();
					objectstream.writeObject(e);
				}
				objectstream.close();
				filestream.close();
			}
		} catch (Exception e) {
			if(getFileStreamPath("queue.dat").exists())
				deleteFile("queue.dat");
			Log.e("LastFm", "Unable to save queue state");
			e.printStackTrace();
		}
	}
	
	/*
	 * This will check the distance between the start time and the current time to determine
	 * whether this is a skip or a played track, and will add it to our scrobble queue.
	 */
	public void enqueueCurrentTrack() {
		if(mCurrentTrack != null) {
			long playTime = (System.currentTimeMillis() / 1000) - mCurrentTrack.startTime;
			boolean played = (playTime > (mCurrentTrack.duration / 2000)) || (playTime > 240);
			if(!played && mCurrentTrack.rating.length() == 0 && mCurrentTrack.trackAuth.length() > 0) {
				mCurrentTrack.rating = "S";
			}
			if(played || mCurrentTrack.rating.length() > 0) {
				Log.i("LastFm", "Enqueuing track (Rating:" + mCurrentTrack.rating + ")");
		   		mQueue.add(mCurrentTrack);
			}
	   		mCurrentTrack = null;
		}
	}
	
    @Override
    public void onStart(Intent intent, int startId) {
    	final Intent i = intent;
    	if(mScrobbler == null) {
    		stopIfReady();
    		return;
    	}
    	
    	/*
         * The Android media player doesn't send a META_CHANGED notification for the first track,
         * so we'll have to catch PLAYBACK_STATE_CHANGED and check to see whether the player
         * is currently playing.  We'll then send our own META_CHANGED intent to the scrobbler.
         */
		if((intent.getAction().equals("com.android.music.playstatechanged") &&
				intent.getIntExtra("id", -1) != -1) || intent.getAction().equals("com.android.music.metachanged")) {
			if(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_music_player", true)) {
	            bindService(new Intent().setClassName("com.android.music", "com.android.music.MediaPlaybackService"),
	                    new ServiceConnection() {
	                    public void onServiceConnected(ComponentName comp, IBinder binder) {
	                            com.android.music.IMediaPlaybackService s =
	                                    com.android.music.IMediaPlaybackService.Stub.asInterface(binder);
	                            
	                            try {
									if(s.isPlaying()) {
										i.setAction(META_CHANGED);
										i.putExtra("position", s.position());
										i.putExtra("duration", (int)s.duration());
										handleIntent(i);
									} else { //Media player was paused
										mCurrentTrack = null;
										NotificationManager nm = ( NotificationManager ) getSystemService( NOTIFICATION_SERVICE );
										nm.cancel( 1338 );
										stopSelf();
									}
								} catch (RemoteException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
								unbindService(this);
	                    }
	
	                    public void onServiceDisconnected(ComponentName comp) {}
	            }, 0);
			} else {
				//Clear the current track in case the user has disabled scrobbling of the media player
				//during the middle of this track.
				mCurrentTrack = null;
				stopIfReady();
			}
		} else if((intent.getAction().equals("com.htc.music.playstatechanged") &&
				intent.getIntExtra("id", -1) != -1) || intent.getAction().equals("com.htc.music.metachanged")) {
			if(PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_music_player", true)) {
	            bindService(new Intent().setClassName("com.htc.music", "com.htc.music.MediaPlaybackService"),
	                    new ServiceConnection() {
	                    public void onServiceConnected(ComponentName comp, IBinder binder) {
	                            com.htc.music.IMediaPlaybackService s =
	                                    com.htc.music.IMediaPlaybackService.Stub.asInterface(binder);
	                            
	                            try {
									if(s.isPlaying()) {
										i.setAction(META_CHANGED);
										i.putExtra("position", s.position());
										i.putExtra("duration", (int)s.duration());
										i.putExtra("track", s.getTrackName());
										i.putExtra("artist", s.getArtistName());
										i.putExtra("album", s.getAlbumName());
										handleIntent(i);
									} else { //Media player was paused
										mCurrentTrack = null;
										NotificationManager nm = ( NotificationManager ) getSystemService( NOTIFICATION_SERVICE );
										nm.cancel( 1338 );
										stopSelf();
									}
								} catch (RemoteException e) {
									// TODO Auto-generated catch block
									e.printStackTrace();
								}
								unbindService(this);
	                    }
	
	                    public void onServiceDisconnected(ComponentName comp) {}
	            }, 0);
			} else {
				//Clear the current track in case the user has disabled scrobbling of the media player
				//during the middle of this track.
				mCurrentTrack = null;
				stopIfReady();
			}
		} else {
			handleIntent(i);
		}
    }
    
    public void handleIntent(Intent intent) {
        if(intent.getAction().equals(META_CHANGED)) {
        	if(mCurrentTrack != null) {
        		enqueueCurrentTrack();
        	}
        	mCurrentTrack = new ScrobblerQueueEntry();
        	
        	mCurrentTrack.startTime = System.currentTimeMillis() / 1000;
        	long position = intent.getLongExtra("position", 0) / 1000;
        	if(position > 0) {
        		mCurrentTrack.startTime -= position;
        	}
        	mCurrentTrack.title = intent.getStringExtra("track");
        	mCurrentTrack.artist = intent.getStringExtra("artist");
        	mCurrentTrack.album = intent.getStringExtra("album");
        	mCurrentTrack.duration = intent.getIntExtra("duration", 0);
        	if(mCurrentTrack.title == null || mCurrentTrack.artist == null) {
        		mCurrentTrack = null;
        		stopIfReady();
        		return;
        	}
        	String auth = intent.getStringExtra("trackAuth");
			if(auth != null && auth.length() > 0) {
				mCurrentTrack.trackAuth = auth;
			}
			boolean scrobbleRealtime = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_realtime", true);
			if(scrobbleRealtime || auth != null) {
				ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
				NetworkInfo ni = cm.getActiveNetworkInfo();
				boolean scrobbleWifiOnly = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_wifi_only", false);
				if(!scrobbleWifiOnly || (scrobbleWifiOnly && ni.getType() == ConnectivityManager.TYPE_WIFI) || auth != null) {
					mNowPlayingTask = new NowPlayingTask(mCurrentTrack.toRadioTrack());
					mNowPlayingTask.execute(mScrobbler);
				}
			}

			if(auth == null) {
				NotificationManager nm = ( NotificationManager ) getSystemService( NOTIFICATION_SERVICE );
				Notification notification = new Notification(
						R.drawable.as_statusbar, "Scrobbling: "
						+ mCurrentTrack.title + " by "
						+ mCurrentTrack.artist, System.currentTimeMillis() );
				Intent metaIntent = new Intent(this, fm.last.android.activity.Metadata.class);
				metaIntent.putExtra("artist", mCurrentTrack.artist);
				metaIntent.putExtra("track", mCurrentTrack.title);
				PendingIntent contentIntent = PendingIntent.getActivity( this, 0,
						metaIntent, 0 );
				String info = mCurrentTrack.title + " - " + mCurrentTrack.artist;
				notification.setLatestEventInfo( this, "Now Scrobbling",
						info, contentIntent );
				notification.flags |= Notification.FLAG_ONGOING_EVENT;
				nm.notify( 1338, notification );
			}
		}
		if(intent.getAction().equals(PLAYBACK_FINISHED) 
				|| intent.getAction().equals("com.android.music.playbackcomplete") 
				|| intent.getAction().equals("com.htc.music.playbackcomplete")) {
			enqueueCurrentTrack();
			NotificationManager nm = ( NotificationManager ) getSystemService( NOTIFICATION_SERVICE );
			nm.cancel( 1338 );
		}
		if(intent.getAction().equals(LOVE) && mCurrentTrack != null) {
			mCurrentTrack.rating = "L";
			Toast.makeText(this, "The track was marked as Loved.", Toast.LENGTH_SHORT).show();
		}
		if(intent.getAction().equals(BAN) && mCurrentTrack != null) {
			mCurrentTrack.rating = "B";
        	Toast.makeText(this, "The track was marked as Banned.", Toast.LENGTH_SHORT).show();
		}
		if(intent.getAction().equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
			if(intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
				stopSelf();
			} else {
				boolean scrobbleWifiOnly = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_wifi_only", false);
				NetworkInfo ni = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
				if(!scrobbleWifiOnly || (scrobbleWifiOnly && ni.getType() == ConnectivityManager.TYPE_WIFI)) {
					if(mCurrentTrack != null && !mCurrentTrack.postedNowPlaying && mNowPlayingTask == null) {
						mNowPlayingTask = new NowPlayingTask(mCurrentTrack.toRadioTrack());
						mNowPlayingTask.execute(mScrobbler);
					}
			   		if(mQueue.size() > 0 && mSubmissionTask == null) {
				   		mSubmissionTask = new SubmitTracksTask();
				   		mSubmissionTask.execute(mScrobbler);
			   		}
				}
			}
		}
		if(intent.getAction().equals("fm.last.android.scrobbler.FLUSH")) {
			ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
			NetworkInfo ni = cm.getActiveNetworkInfo();
			boolean scrobbleWifiOnly = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("scrobble_wifi_only", false);
			if(!scrobbleWifiOnly || (scrobbleWifiOnly && ni.getType() == ConnectivityManager.TYPE_WIFI)) {
		   		if(mQueue.size() > 0 && mSubmissionTask == null) {
			   		mSubmissionTask = new SubmitTracksTask();
			   		mSubmissionTask.execute(mScrobbler);
		   		}
			}
		}
		if(intent.getAction().equals("fm.last.android.scrobbler.BOOTSTRAP")) {
			String [] cols={MediaStore.Audio.Media.TITLE,MediaStore.Audio.Media.ARTIST,MediaStore.Audio.Media.ALBUM};
			Cursor cursor = getContentResolver().query( MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols, "is_music = 1",null,null);
			Log.i("Last.fm", "Got " + cursor.getCount() + " results");
			while(cursor.moveToNext()) {
				Log.i("Last.fm", "Title: " + cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)));
				Log.i("Last.fm", "Artist: " + cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)));
				Log.i("Last.fm", "Album: " + cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)));
			}
		}
		stopIfReady();
    }
    
    public void stopIfReady() {
		if(mSubmissionTask == null && mNowPlayingTask == null)
			stopSelf();
    }
    
    /* We don't currently offer any bindable functions.  Perhaps in the future we can add
     * a function to get the queue size / last scrobbler result / etc.
     */
	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}
	
	private class NowPlayingTask extends UserTask<AudioscrobblerService, Void, Boolean> {
		RadioTrack mTrack;
		
		public NowPlayingTask(RadioTrack track) {
			mTrack = track;
		}

		@Override
		public void onPreExecute() {
			/* If we have any scrobbles in the queue, try to send them now */
	   		if(mSubmissionTask == null && mQueue.size() > 0) {
		   		mSubmissionTask = new SubmitTracksTask();
		   		mSubmissionTask.execute(mScrobbler);
	   		}
		}
		
		public Boolean doInBackground(AudioscrobblerService... scrobbler) {
			boolean success = false;
			try
			{
				scrobbler[0].nowPlaying(mTrack);
				success = true;
			}
			catch ( Exception e )
			{
				success = false;
			}
			return success;
		}

		@Override
		public void onPostExecute(Boolean result) {
			if(mCurrentTrack != null)
				mCurrentTrack.postedNowPlaying = result;
			mNowPlayingTask = null;
	   		stopIfReady();
		}
	}

	private class SubmitTracksTask extends UserTask<AudioscrobblerService, Void, Boolean> {

		public Boolean doInBackground(AudioscrobblerService... scrobbler) {
			boolean success = false;
			try
			{
				Log.i("LastFm", "Going to submit " + mQueue.size() + " tracks");
				LastFmServer server = AndroidLastFmServerFactory.getServer();
				while(mQueue.size() > 0) {
					ScrobblerQueueEntry e = mQueue.peek();
					if(e != null && e.title != null && e.artist != null) {
						if(e.rating.equals("L")) {
							server.loveTrack(e.artist, e.title, mSession.getKey());
						}
						if(e.rating.equals("B")) {
							server.banTrack(e.artist, e.title, mSession.getKey());
						}
						scrobbler[0].submit(e.toRadioTrack(), e.startTime, e.rating);
					}
					mQueue.take();
				}
				success = true;
			}
			catch ( Exception e )
			{
				Log.e("LastFm", "Unable to submit track: " + e.toString());
				e.printStackTrace();
				success = false;
			}
			return success;
		}

		@Override
		public void onPostExecute(Boolean result) {
			mSubmissionTask = null;
			stopIfReady();
		}
	}
}
