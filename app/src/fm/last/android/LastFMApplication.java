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
package fm.last.android;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import android.app.AlertDialog;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import com.google.android.apps.analytics.GoogleAnalyticsTracker;

import fm.last.android.activity.Player;
import fm.last.android.player.IRadioPlayer;
import fm.last.android.player.RadioPlayerService;
import fm.last.android.sync.AccountAuthenticatorService;
import fm.last.api.LastFmServer;
import fm.last.api.Session;
import fm.last.api.Station;
import fm.last.api.WSError;
import fm.last.util.UrlUtil;

public class LastFMApplication extends Application {

	public Session session;
	public fm.last.android.player.IRadioPlayer player = null;
	private Context mCtx;
	public GoogleAnalyticsTracker tracker;

	private static LastFMApplication instance = null;

	public static LastFMApplication getInstance() {
		if(instance != null) {
			return instance;
		} else {
			return new LastFMApplication();
		}
	}

	@Override
	public void onCreate() {
		instance = this;

		String version;
		try {
			version = "/" + LastFMApplication.getInstance().getPackageManager().getPackageInfo("fm.last.android", 0).versionName;
		} catch (Exception e) {
			version = "";
		}
		
		UrlUtil.useragent = "MobileLastFM" + version + " (" + android.os.Build.MODEL + "; " + Locale.getDefault().getCountry().toLowerCase() + "; "
				+ "Android " + android.os.Build.VERSION.RELEASE + ")";

		// Populate our Session object
		SharedPreferences settings = getSharedPreferences(LastFm.PREFS, 0);
		String username = settings.getString("lastfm_user", "");
		String session_key = settings.getString("lastfm_session_key", "");
		String subscriber = settings.getString("lastfm_subscriber", "0");
		session = new Session(username, session_key, subscriber);
		tracker = GoogleAnalyticsTracker.getInstance();
		tracker.start(PrivateAPIKey.ANALYTICS_ID, this);
	}

	private ServiceConnection mConnection = new ServiceConnection() {

		public void onServiceConnected(ComponentName className, IBinder service) {
			player = fm.last.android.player.IRadioPlayer.Stub.asInterface(service);
		}

		public void onServiceDisconnected(ComponentName className) {
			player = null;
		}
	};

	public void bindPlayerService() {
		// start our media player service
		Intent mpIntent = new Intent(this, fm.last.android.player.RadioPlayerService.class);
		boolean b = bindService(mpIntent, mConnection, BIND_AUTO_CREATE);
		if (!b) {
			// something went wrong
			// mHandler.sendEmptyMessage(QUIT);
			System.out.println("Binding to service failed " + mConnection);
		}
	}

	public void unbindPlayerService() {
		if(player != null && player.asBinder().isBinderAlive())
			unbindService(mConnection);
		player = null;
	}

	public void playRadioStation(Context ctx, String url, boolean showPlayer) {
		mCtx = ctx;
		if (session != null && session.getKey().length() > 0) {
			final Intent out = new Intent(this, RadioPlayerService.class);
			out.setAction("fm.last.android.PLAY");
			out.putExtra("station", url);
			out.putExtra("session", (Parcelable) session);
			startService(out);
			if (showPlayer) {
				IntentFilter intentFilter = new IntentFilter();
				intentFilter.addAction(RadioPlayerService.STATION_CHANGED);
				intentFilter.addAction("fm.last.android.ERROR");

				BroadcastReceiver statusListener = new BroadcastReceiver() {

					@Override
					public void onReceive(Context context, Intent intent) {

						String action = intent.getAction();
						if (action.equals(RadioPlayerService.STATION_CHANGED)) {
							Intent i = new Intent(LastFMApplication.this, Player.class);
							i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
							startActivity(i);
						} else if (action.equals("fm.last.android.ERROR")) {
							WSError e = intent.getParcelableExtra("error");
							Log.e("Last.fm", "Tuning error: " + e.getMessage());
							presentError(mCtx, e);
						}
						unregisterReceiver(this);
					}
				};
				registerReceiver(statusListener, intentFilter);
			}
		}
	}

	public Station[] getRecentStations() {
		SQLiteDatabase db = null;
		try {
			db = this.openOrCreateDatabase(LastFm.DB_NAME, MODE_PRIVATE, null);
			Cursor c = db.rawQuery("SELECT * FROM " + LastFm.DB_TABLE_RECENTSTATIONS + " ORDER BY Timestamp DESC LIMIT 10", null);
			int urlColumn = c.getColumnIndex("Url");
			int nameColumn = c.getColumnIndex("Name");
			Station[] stations = new Station[c.getCount()];
			if (c.getCount() > 0) {
				c.moveToFirst();
				int i = 0;
				// Loop through all Results
				do {
					String name = c.getString(nameColumn);
					String url = c.getString(urlColumn);
					stations[i] = new Station(name, "", url, "");
					i++;
				} while (c.moveToNext());
			}
			c.close();
			db.close();
			return stations;
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		return null;
	}

	public Station getLastStation() {
		SQLiteDatabase db = null;
		Station result = null;
		try {
			db = this.openOrCreateDatabase(LastFm.DB_NAME, MODE_PRIVATE, null);
			Cursor c = db.rawQuery("SELECT * FROM " + LastFm.DB_TABLE_RECENTSTATIONS + " ORDER BY Timestamp DESC LIMIT 4", null);
			int urlColumn = c.getColumnIndex("Url");
			int nameColumn = c.getColumnIndex("Name");
			if (c.getCount() > 0) {
				c.moveToFirst();
				String name = c.getString(nameColumn);
				String url = c.getString(urlColumn);
				result = new Station(name, "", url, "");
			}
			c.close();
			db.close();
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
		return result;
	}

	public void appendRecentStation(String url, String name) {

		SQLiteDatabase db = null;
		try {
			db = this.openOrCreateDatabase(LastFm.DB_NAME, MODE_PRIVATE, null);
			db.execSQL("CREATE TABLE IF NOT EXISTS " + LastFm.DB_TABLE_RECENTSTATIONS
					+ " (Url VARCHAR UNIQUE NOT NULL PRIMARY KEY, Name VARCHAR NOT NULL, Timestamp INTEGER NOT NULL);");
			db.execSQL("DELETE FROM " + LastFm.DB_TABLE_RECENTSTATIONS + " WHERE Url = '" + url + "'");
			db.execSQL("INSERT INTO " + LastFm.DB_TABLE_RECENTSTATIONS + "(Url, Name, Timestamp) " + "VALUES ('" + url + "', '" + name + "', "
					+ System.currentTimeMillis() + ")");
			db.close();
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}

	public void fetchRecentStations() {
		LastFmServer server = AndroidLastFmServerFactory.getServer();

		// Is it worth it?
		if (session != null) {
			try {
				// Let me work it
				Station stations[] = server.getUserRecentStations(session.getName(), session.getKey());
				if (stations != null && stations.length > 0) {
					// I put my thing down, flip it, and reverse it
					List<Station> list = Arrays.asList(stations);
					Collections.reverse(list);
					stations = (Station[]) list.toArray();
					this.deleteDatabase(LastFm.DB_NAME);
					for (Station station : stations) {
						appendRecentStation(station.getUrl(), station.getName());
					}
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	@Override
	public void onTerminate() {
		session = null;
		instance = null;
		tracker.stop();
		super.onTerminate();
	}

	public void presentError(Context ctx, WSError error) {
		int title = 0;
		int description = 0;

		System.out.printf("Received a webservice error during method: %s: %s\n", error.getMethod(), error.getMessage());

		if (error.getMethod().startsWith("radio.")) {
			title = R.string.ERROR_STATION_TITLE;
			switch (error.getCode()) {
			case WSError.ERROR_NotEnoughContent:
				title = R.string.ERROR_INSUFFICIENT_CONTENT_TITLE;
				description = R.string.ERROR_INSUFFICIENT_CONTENT;
				break;

			case WSError.ERROR_NotEnoughFans:
				description = R.string.ERROR_INSUFFICIENT_FANS;
				break;

			case WSError.ERROR_NotEnoughMembers:
				description = R.string.ERROR_INSUFFICIENT_MEMBERS;
				break;

			case WSError.ERROR_NotEnoughNeighbours:
				description = R.string.ERROR_INSUFFICIENT_NEIGHBOURS;
				break;
			}
		}

		if (error.getMethod().equals("user.signUp")) {
			title = R.string.ERROR_SIGNUP_TITLE;
			switch (error.getCode()) {
			case WSError.ERROR_InvalidParameters:
				presentError(ctx, getResources().getString(title), error.getMessage());
				return;

			}
		}

		if (title == 0)
			title = R.string.ERROR_SERVER_UNAVAILABLE_TITLE;

		if (description == 0) {
			switch (error.getCode()) {
			case WSError.ERROR_AuthenticationFailed:
			case WSError.ERROR_InvalidSession:
				title = R.string.ERROR_SESSION_TITLE;
				description = R.string.ERROR_SESSION;
				break;
			case WSError.ERROR_InvalidAPIKey:
				title = R.string.ERROR_UPGRADE_TITLE;
				description = R.string.ERROR_UPGRADE;
				break;
			case WSError.ERROR_SubscribersOnly:
				title = R.string.ERROR_SUBSCRIPTION_TITLE;
				description = R.string.ERROR_SUBSCRIPTION;
				break;
			default:
				description = R.string.ERROR_SERVER_UNAVAILABLE;
				break;
			}
		}

		presentError(ctx, getResources().getString(title), getResources().getString(description));
	}

	public void presentError(Context ctx, String title, String description) {
		AlertDialog.Builder d = new AlertDialog.Builder(ctx);
		d.setTitle(title);
		d.setMessage(description);
		d.setIcon(android.R.drawable.ic_dialog_alert);
		d.setNeutralButton("OK", new DialogInterface.OnClickListener() {
			public void onClick(DialogInterface dialog, int whichButton) {
			}
		});
		d.show();
	}
	
	public void logout() {
		SharedPreferences settings = getSharedPreferences(LastFm.PREFS, 0);
		SharedPreferences.Editor editor = settings.edit();
		editor.remove("lastfm_user");
		editor.remove("lastfm_pass");
		editor.remove("lastfm_session_key");
		editor.remove("lastfm_subscriber");
		editor.commit();
		session = null;
		try {
			LastFMApplication.getInstance().bindService(new Intent(this, fm.last.android.player.RadioPlayerService.class), new ServiceConnection() {
				public void onServiceConnected(ComponentName comp, IBinder binder) {
					IRadioPlayer player = IRadioPlayer.Stub.asInterface(binder);
					try {
						if (player.isPlaying())
							player.stop();
					} catch (RemoteException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
					LastFMApplication.getInstance().unbindService(this);
				}

				public void onServiceDisconnected(ComponentName comp) {
				}
			}, 0);
			deleteDatabase(LastFm.DB_NAME);
			deleteFile("currentTrack.dat");
			deleteFile("queue.dat");
			if(Integer.decode(Build.VERSION.SDK) >= 6) {
				AccountAuthenticatorService.removeLastfmAccount(this);
			}
		} catch (Exception e) {
			System.out.println(e.getMessage());
		}
	}
}
