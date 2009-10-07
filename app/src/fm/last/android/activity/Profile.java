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
package fm.last.android.activity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Stack;
import java.util.WeakHashMap;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ListActivity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.os.RemoteException;
import android.preference.PreferenceActivity;
import android.preference.PreferenceManager;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.ListView;
import android.widget.ViewFlipper;
import android.widget.AdapterView.OnItemClickListener;
import fm.last.android.AndroidLastFmServerFactory;
import fm.last.android.LastFMApplication;
import fm.last.android.LastFm;
import fm.last.android.R;
import fm.last.android.activity.Event.EventActivityResult;
import fm.last.android.adapter.EventListAdapter;
import fm.last.android.adapter.LastFMStreamAdapter;
import fm.last.android.adapter.ListAdapter;
import fm.last.android.adapter.ListEntry;
import fm.last.android.adapter.SeparatedListAdapter;
import fm.last.android.player.IRadioPlayer;
import fm.last.android.player.RadioPlayerService;
import fm.last.android.utils.ImageCache;
import fm.last.android.utils.UserTask;
import fm.last.android.widget.AdArea;
import fm.last.android.widget.ProfileBubble;
import fm.last.android.widget.TabBar;
import fm.last.api.Album;
import fm.last.api.Artist;
import fm.last.api.Event;
import fm.last.api.LastFmServer;
import fm.last.api.RadioPlayList;
import fm.last.api.Session;
import fm.last.api.Station;
import fm.last.api.Tag;
import fm.last.api.Tasteometer;
import fm.last.api.Track;
import fm.last.api.User;
import fm.last.api.ImageUrl;
import fm.last.api.WSError;

//TODO: refactor all the UserTasks

public class Profile extends ListActivity
{		
	//Java doesn't let you treat enums as ints easily, so we have to have this mess 
	private static final int PROFILE_TOPARTISTS = 0;
	private static final int PROFILE_TOPALBUMS = 1;
	private static final int PROFILE_TOPTRACKS = 2;
	private static final int PROFILE_RECENTLYPLAYED = 3;
	private static final int PROFILE_EVENTS = 4;
	private static final int PROFILE_FRIENDS = 5;
	private static final int PROFILE_TAGS = 6;
	
	private static final int DIALOG_ALBUM = 0 ;
	private static final int DIALOG_TRACK = 1;

    private SeparatedListAdapter mMainAdapter;
    private ListAdapter mProfileAdapter;
    private LastFMStreamAdapter mMyStationsAdapter;
    private LastFMStreamAdapter mMyRecentAdapter;
    private LastFMStreamAdapter mMyPlaylistsAdapter;
    private User mUser;
    private String mUsername; // store this separate so we have access to it before User obj is retrieved
    private boolean isAuthenticatedUser;
	LastFmServer mServer = AndroidLastFmServerFactory.getServer();

	TabBar mTabBar;
	ViewFlipper mViewFlipper;
	ViewFlipper mNestedViewFlipper;
	ListView mProfileList;
	ProfileBubble mProfileBubble;
    private Stack<Integer> mViewHistory;
	
	View previousSelectedView = null;
	
	ListView mDialogList;
	private ListAdapter mDialogAdapter;
	
	//Animations
	Animation mPushRightIn;
	Animation mPushRightOut;
	Animation mPushLeftIn;
	Animation mPushLeftOut;
	
	ListView[] mProfileLists = new ListView[7];

	private ImageCache mImageCache = null;

    private Button mNewStationButton;
    private EventActivityResult mOnEventActivityResult;
    
    private Track mTrackInfo; // For the profile actions' dialog
    private Album mAlbumInfo; // Ditto
	
	private IntentFilter mIntentFilter;
    
	private boolean mIsPlaying = false;
	
    @Override
    public void onCreate( Bundle icicle )
    {
        super.onCreate( icicle );
        requestWindowFeature( Window.FEATURE_NO_TITLE );
        setContentView( R.layout.home );
        Session session = LastFMApplication.getInstance().map.get( "lastfm_session" );
        if( session == null )
            logout();
        mUsername = getIntent().getStringExtra("lastfm.profile.username");
        if( mUsername == null ) {
            mUsername = session.getName();
            isAuthenticatedUser = true;
        } 
        else 
            isAuthenticatedUser = false;
        
        if( isAuthenticatedUser ) {
            Button b = mNewStationButton = new Button(this);
            b.setBackgroundResource( R.drawable.start_a_new_station_button );
            b.setTextColor(0xffffffff);
    		b.setTextSize(TypedValue.COMPLEX_UNIT_PT, 7);
    		b.setFocusable( false ); //essential!
    		b.setClickable( false ); // getListView() clicklistener handles this as the other routes had bugs
    		b.setGravity( 3 | 16 ); //sorry not to use constants, I got lame and couldn't figure it out
    		b.setTypeface( Typeface.create( Typeface.DEFAULT, Typeface.BOLD ) );
    		b.setText(R.string.home_newstation);
   	 		b.setTag("header");
    		getListView().addHeaderView(b, null, true);
    		getListView().setItemsCanFocus( true );
        } else {
            mProfileBubble = new ProfileBubble(this);
            mProfileBubble.setTag("header");
            mProfileBubble.setClickable( false );
            getListView().addHeaderView(mProfileBubble, null, false);
        }
                
        mViewHistory = new Stack<Integer>();
		mTabBar = (TabBar) findViewById(R.id.TabBar);
		mViewFlipper = (ViewFlipper) findViewById(R.id.ViewFlipper);
		mNestedViewFlipper = (ViewFlipper) findViewById(R.id.NestedViewFlipper);
		mNestedViewFlipper.setAnimateFirstView(false);
		mNestedViewFlipper.setAnimationCacheEnabled(false);
		
		mTabBar.setViewFlipper(mViewFlipper);
		if(isAuthenticatedUser) {
		    mTabBar.addTab("Radio", R.drawable.radio);
		    mTabBar.addTab("Profile", R.drawable.profile);
		} else {
		    mTabBar.addTab(mUsername + "'s Radio", R.drawable.radio);
            mTabBar.addTab(mUsername + "'s Profile", R.drawable.profile);
		}
       
        mMyRecentAdapter = new LastFMStreamAdapter( this );

        new LoadUserTask().execute((Void)null);
        SetupMyStations();
        SetupRecentStations();
        RebuildMainMenu();
        
        mProfileList = (ListView)findViewById(R.id.profile_list_view);
    	String[] mStrings = new String[]{"Top Artists", "Top Albums", "Top Tracks", "Recently Played", "Events", "Friends", "Tags"}; // this order must match the ProfileActions enum
        mProfileAdapter = new ListAdapter(Profile.this, mStrings);
        mProfileList.setAdapter(mProfileAdapter); 
        mProfileList.setOnItemClickListener(mProfileClickListener);

        //TODO should be functions and not member variables, caching is evil
		mProfileLists[PROFILE_TOPARTISTS] = (ListView) findViewById(R.id.topartists_list_view);
		mProfileLists[PROFILE_TOPARTISTS].setOnItemClickListener( mArtistListItemClickListener );
		
		mProfileLists[PROFILE_TOPALBUMS] = (ListView) findViewById(R.id.topalbums_list_view);
        mProfileLists[PROFILE_TOPALBUMS].setOnItemClickListener( mAlbumListItemClickListener );

        mProfileLists[PROFILE_TOPTRACKS] = (ListView) findViewById(R.id.toptracks_list_view);
        mProfileLists[PROFILE_TOPTRACKS].setOnItemClickListener( mTrackListItemClickListener );

        mProfileLists[PROFILE_RECENTLYPLAYED] = (ListView) findViewById(R.id.recenttracks_list_view);
        mProfileLists[PROFILE_RECENTLYPLAYED].setOnItemClickListener( mTrackListItemClickListener );
        
        mProfileLists[PROFILE_EVENTS] = (ListView) findViewById(R.id.profileevents_list_view);
        mProfileLists[PROFILE_EVENTS].setOnItemClickListener( mEventItemClickListener );

        mProfileLists[PROFILE_FRIENDS] = (ListView) findViewById(R.id.profilefriends_list_view);
        mProfileLists[PROFILE_FRIENDS].setOnItemClickListener( mUserItemClickListener );
        
        mProfileLists[PROFILE_TAGS] = (ListView) findViewById(R.id.profiletags_list_view);
        mProfileLists[PROFILE_TAGS].setOnItemClickListener( mTagListItemClickListener );

        // Loading animations
        mPushLeftIn = AnimationUtils.loadAnimation(this, R.anim.push_left_in);
		mPushLeftOut = AnimationUtils.loadAnimation(this, R.anim.push_left_out);
		mPushRightIn = AnimationUtils.loadAnimation(this, R.anim.push_right_in);
		mPushRightOut = AnimationUtils.loadAnimation(this, R.anim.push_right_out);
        
		mIntentFilter = new IntentFilter();
		mIntentFilter.addAction( RadioPlayerService.PLAYBACK_ERROR );
		mIntentFilter.addAction( RadioPlayerService.STATION_CHANGED );
		mIntentFilter.addAction( "fm.last.android.ERROR" );
		
		if( getIntent().getBooleanExtra("lastfm.profile.new_user", false ) )
			startActivity( new Intent( Profile.this, NewStation.class ) );
    }
    
    
    @Override
    protected void onSaveInstanceState( Bundle outState )
    {
    	// the event list adapter (a SeparatedListAdapter) doesn't serialise,
    	// so move away from it if we happen to be looking at it now.
    	// FIXME: make the SeparatedListAdapter serialize.
    	if( mNestedViewFlipper.getDisplayedChild() == (PROFILE_EVENTS + 1))
    		mNestedViewFlipper.setDisplayedChild(mViewHistory.pop());
    	
     	outState.putInt( "selected_tab", mTabBar.getActive());
     	outState.putInt( "displayed_view", mNestedViewFlipper.getDisplayedChild());
     	outState.putSerializable("view_history", mViewHistory); 
     	
     	HashMap< Integer, ListAdapter > adapters = new HashMap< Integer, ListAdapter>(mProfileLists.length);
		for( int i = 0 ; i < mProfileLists.length; i++ )
		{
	     	ListView lv = mProfileLists[ i ];
	     	if( lv.getAdapter() == null )
	     		continue;
	     	if( lv.getAdapter().getClass() == ListAdapter.class )
	     		adapters.put( i, (ListAdapter)lv.getAdapter());
		}
     	
     	outState.putSerializable("adapters", adapters);
        outState.putSerializable("info_album", mAlbumInfo);
        outState.putSerializable("info_track", mTrackInfo);
    }
    
    @SuppressWarnings("unchecked")
	@Override
    protected void onRestoreInstanceState( Bundle state )
    {
    	mTabBar.setActive( state.getInt( "selected_tab" ));
    	mNestedViewFlipper.setDisplayedChild( state.getInt("displayed_view"));
    	
    	if( state.containsKey("view_history") )
    		try {
    			Object viewHistory = (Stack<Integer>) state.getSerializable("view_history");
    			if( viewHistory instanceof Stack )
    				mViewHistory = (Stack<Integer>)viewHistory;
    			else
    			{
    				//For some reason when the process gets killed and then resumed,
    				//the serializable becomes an ArrayList
    				for( Integer i : (ArrayList<Integer>)state.getSerializable("view_history") )
    					mViewHistory.push( i );
    			}
    		} catch( ClassCastException e) {
    				
    		}
    	
    	//Restore the adapters and disable the spinner for all the profile lists
    	HashMap< Integer, ListAdapter > adapters = (HashMap<Integer, ListAdapter>)state.getSerializable("adapters");
    	
    	for( int key : adapters.keySet() )
    	{
    		ListAdapter adapter = adapters.get(key); 
			if( adapter != null )
			{
				adapter.setContext( this );
				adapter.setImageCache( getImageCache() );
		    	adapter.disableLoadBar();
				adapter.refreshList();
		    	mProfileLists[key].setAdapter( adapter );
			}
    	}
    	
     	
    	mAlbumInfo = (Album)state.getSerializable("info_album");
        mTrackInfo = (Track)state.getSerializable("info_track");
    }
    
    private class LoadUserTask extends UserTask<Void, Void, Boolean> {
        Tasteometer tasteometer;
        @Override
    	public void onPreExecute() {
        	mMyPlaylistsAdapter = null;
        }
    	
        @Override
        public Boolean doInBackground(Void...params) {
        	RadioPlayList[] playlists;
            boolean success = false;
            Session session = LastFMApplication.getInstance().map.get( "lastfm_session" );
            LastFMApplication.getInstance().fetchRecentStations();
            SetupRecentStations();
            //Check our subscriber status
            LastFmServer server = AndroidLastFmServerFactory.getServer();
            try {
				User user = server.getUserInfo(session.getKey());
				if(user != null) {
					String subscriber = user.getSubscriber();
			        SharedPreferences settings = getSharedPreferences( LastFm.PREFS, 0 );
		            SharedPreferences.Editor editor = settings.edit();
		            editor.putString( "lastfm_subscriber", subscriber);
		            editor.commit();
			    	session = new Session(session.getName(), session.getKey(), subscriber);
			        LastFMApplication.getInstance().map.put( "lastfm_session", session );
				}
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
    		try {
    		    if( mUsername == null) {
    				mUser = mServer.getUserInfo( session.getKey() );
    				playlists = mServer.getUserPlaylists( session.getName() );
    		    } else {
    		        mUser = mServer.getAnyUserInfo( mUsername );
    		        tasteometer = mServer.tasteometerCompare(mUsername, session.getName(), 8);
    		        playlists = mServer.getUserPlaylists( mUsername );
    		    }
    		    if(playlists.length > 0) {
    		    	mMyPlaylistsAdapter = new LastFMStreamAdapter(Profile.this);
    		    	for(RadioPlayList playlist : playlists) {
    		    		if(playlist.isStreamable())
    		    			mMyPlaylistsAdapter.putStation(playlist.getTitle(), "lastfm://playlist/" + playlist.getId() + "/shuffle");
    		    	}
    		    }
    			success = true;
    		} catch (IOException e) {
    			e.printStackTrace();
    		}
            return success;
        }

        @Override
        public void onPostExecute(Boolean result) {
            Session session = LastFMApplication.getInstance().map.get( "lastfm_session" );
            if(session != null) {
	            if( !isAuthenticatedUser ) {
	                mProfileBubble.setUser(Profile.this.mUser);
	                SetupCommonArtists(tasteometer);
	            }
	            if(session.getSubscriber().equals("1")&&mMyPlaylistsAdapter != null && mMyPlaylistsAdapter.getCount() > 0)
	            	mMainAdapter.addSection( mUsername + "'s Playlists", mMyPlaylistsAdapter);
	            mMainAdapter.notifyDataSetChanged();
            }
        }
    }
    
    void SetupCommonArtists(Tasteometer ts)
    {
        mMyRecentAdapter.resetList();

        for (String name : ts.getResults())
        {
            String url = "lastfm://artist/"+Uri.encode(name)+"/similarartists";
            mMyRecentAdapter.putStation( name, url );
        }

        mMyRecentAdapter.updateModel();
    }

    private void RebuildMainMenu() 
    {
        Session session = LastFMApplication.getInstance().map.get( "lastfm_session" );
        mMainAdapter = new SeparatedListAdapter(this);
        if(isAuthenticatedUser) {
            mMainAdapter.addSection( getString(R.string.home_mystations), mMyStationsAdapter );
            if(mMyRecentAdapter.getCount() > 0)
            	mMainAdapter.addSection( getString(R.string.home_recentstations), mMyRecentAdapter );
            if(session.getSubscriber().equals("1")&&mMyPlaylistsAdapter != null && mMyPlaylistsAdapter.getCount() > 0) {
            	mMainAdapter.addSection( "Your Playlists", mMyPlaylistsAdapter);
            }
        } else {
            mMainAdapter.addSection( mUsername + "'s Stations", mMyStationsAdapter );        
            mMainAdapter.addSection( getString(R.string.home_commonartists), mMyRecentAdapter );
            if(session.getSubscriber().equals("1")&&mMyPlaylistsAdapter != null && mMyPlaylistsAdapter.getCount() > 0) {
            	mMainAdapter.addSection( mUsername + "'s Playlists", mMyPlaylistsAdapter);
            }
        }
        if(mMyStationsAdapter != null && mMyStationsAdapter.getCount() > 0)
        	mMyStationsAdapter.updateNowPlaying();
        if(mMyRecentAdapter != null && mMyRecentAdapter.getCount() > 0)
        	mMyRecentAdapter.updateNowPlaying();
        if(mMyPlaylistsAdapter != null && mMyPlaylistsAdapter.getCount() > 0)
        	mMyPlaylistsAdapter.updateNowPlaying();
        setListAdapter( mMainAdapter );
        mMainAdapter.notifyDataSetChanged();
    }
    
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
    	if( requestCode == 0 && resultCode == RESULT_OK ) {
	    	int status = data.getExtras().getInt("status", -1);
	    	if (mOnEventActivityResult != null && status != -1) {
	    		mOnEventActivityResult.onEventStatus(status);
	    	}
    	}
    }
    
    @Override
    public void onResume() {
    	if(LastFMApplication.getInstance().map.get("lastfm_session") == null) {
    		finish(); //We shouldn't really get here, but sometimes the window stack keeps us around
    	} else {
			registerReceiver( mStatusListener, mIntentFilter );
		
	    	SetupRecentStations();
	    	RebuildMainMenu();
	    	mMainAdapter.disableLoadBar();
	
	    	for( ListView list : mProfileLists )
	    	{
	    		try { 
	    			((ListAdapter)list.getAdapter()).disableLoadBar();
	    		} catch (Exception e) { 
	    			// FIXME: this is ugly, but sometimes adapters aren't the shape we expect.
	    		}
	    	}
	
	        if( mDialogAdapter != null )
	        	mDialogAdapter.disableLoadBar();
	        
	        mIsPlaying = false;
	        
            LastFMApplication.getInstance().bindService(new Intent(LastFMApplication.getInstance(),fm.last.android.player.RadioPlayerService.class ),
                    new ServiceConnection() {
                    public void onServiceConnected(ComponentName comp, IBinder binder) {
                            IRadioPlayer player = IRadioPlayer.Stub.asInterface(binder);
        					try {
        						mIsPlaying = player.isPlaying();
        					} catch (RemoteException e) {
        						// TODO Auto-generated catch block
        						e.printStackTrace();
        					}
        					LastFMApplication.getInstance().unbindService(this);
                    }

                    public void onServiceDisconnected(ComponentName comp) {
                    }
            }, Context.BIND_AUTO_CREATE);

    	}
		super.onResume();
    }
    
	@Override
	protected void onPause() {
		unregisterReceiver( mStatusListener );
		super.onPause();
	}

	private BroadcastReceiver mStatusListener = new BroadcastReceiver()
	{

		@Override
		public void onReceive( Context context, Intent intent )
		{

			String action = intent.getAction();
			if ( action.equals( RadioPlayerService.PLAYBACK_ERROR )  || action.equals("fm.last.android.ERROR"))
			{
				// see also repeated code one page above in OnResume
				RebuildMainMenu();
		    	mMainAdapter.disableLoadBar();

		    	for( ListView list : mProfileLists )
		    	{
		    		if( list.getAdapter() != null )
		    			((ListAdapter)list.getAdapter()).disableLoadBar();
		    	}

		        if( mDialogAdapter != null )
		        	mDialogAdapter.disableLoadBar();
			}
			else if( action.equals( RadioPlayerService.STATION_CHANGED))
			{
				//Update now playing buttons after the service is re-bound
		    	SetupRecentStations();
		    	RebuildMainMenu();
			}
		}
	};
	
    private void SetupRecentStations()
    {
        if(!isAuthenticatedUser)
            return;
        mMyRecentAdapter.resetList();
        Station[] stations = LastFMApplication.getInstance().getRecentStations();
        if(stations != null) {
	        for (Station station : stations)
	        {
	            String name = station.getName();
	            String url = station.getUrl();
	            mMyRecentAdapter.putStation( name, url );
	        }
        }
        mMyRecentAdapter.updateModel();

    }

    private void SetupMyStations()
    {
        Session session = LastFMApplication.getInstance().map.get( "lastfm_session" );
        mMyStationsAdapter = new LastFMStreamAdapter( this );
        if(isAuthenticatedUser) {
	        mMyStationsAdapter.putStation( getString(R.string.home_mylibrary), 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/personal" );
	        if(session.getSubscriber().equals("1"))
	        	mMyStationsAdapter.putStation( getString(R.string.home_myloved), 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/loved" );
	        mMyStationsAdapter.putStation( getString(R.string.home_myrecs), 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/recommended" );
	        mMyStationsAdapter.putStation( getString(R.string.home_myneighborhood), 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/neighbours" );
        } else {
	        mMyStationsAdapter.putStation( mUsername + "'s Library", 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/personal" );
	        if(session.getSubscriber().equals("1"))
	        	mMyStationsAdapter.putStation( mUsername + "'s Loved Tracks", 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/loved" );
	        mMyStationsAdapter.putStation( getString(R.string.home_myrecs), 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/recommended" );
	        mMyStationsAdapter.putStation( mUsername + "'s Neighbourhood", 
	        		"lastfm://user/" + Uri.encode( mUsername ) + "/neighbours" );
        }
        
        mMyStationsAdapter.updateModel();
    }

    public void onListItemClick( ListView l, View v, int p, long id )
    {
    	// mMainAdapter seems to want position-1 :-/
    	final ListView list = l;
    	final int position = p;
    	
        if( !mMainAdapter.isEnabled( position-1 ))
        	return;
        
        if (v == mNewStationButton && v != null)
        {
        	Intent intent = new Intent( Profile.this, NewStation.class );
            startActivity( intent );
            return;
        }
        
        LastFMApplication.getInstance().bindService(new Intent(LastFMApplication.getInstance(),fm.last.android.player.RadioPlayerService.class ),
                new ServiceConnection() {
                public void onServiceConnected(ComponentName comp, IBinder binder) {
                        IRadioPlayer player = IRadioPlayer.Stub.asInterface(binder);
    					try {
			            	String adapter_station = mMainAdapter.getStation(position-1);
					        String current_station = player.getStationUrl();
    			        	if ( player.isPlaying() && adapter_station.equals( current_station ) ) {
					            Intent intent = new Intent( Profile.this, Player.class );
					            startActivity( intent );
    			        	} else {
    			        		list.setEnabled(false);
    			        		mMainAdapter.enableLoadBar(position-1);
    			        		LastFMApplication.getInstance().playRadioStation(Profile.this,adapter_station, true);
    			        	}
    					} catch (Exception e) {
    						// TODO Auto-generated catch block
    						e.printStackTrace();
    					}
    					LastFMApplication.getInstance().unbindService(this);
                }

                public void onServiceDisconnected(ComponentName comp) {
                }
        }, Context.BIND_AUTO_CREATE);
    }
        
    public boolean onKeyDown(int keyCode, KeyEvent event)
    {
        if( keyCode == KeyEvent.KEYCODE_BACK )
        {
            if( mTabBar.getActive() == R.drawable.profile && !mViewHistory.isEmpty() )
            {
            	setPreviousAnimation();
                mProfileAdapter.disableLoadBar();
                mNestedViewFlipper.setDisplayedChild(mViewHistory.pop());
                return true;
            }
            if(event.getRepeatCount() == 0) {
                finish();
                return true;
            }
        }
        return false;
    }
    
    private void setNextAnimation(){
    	mNestedViewFlipper.setInAnimation(mPushLeftIn);
		mNestedViewFlipper.setOutAnimation(mPushLeftOut);
    }
    
    private void setPreviousAnimation(){
    	mNestedViewFlipper.setInAnimation(mPushRightIn);
		mNestedViewFlipper.setOutAnimation(mPushRightOut);
    }
    
    private OnItemClickListener mProfileClickListener = new OnItemClickListener()
    {

    	
        public void onItemClick(AdapterView<?> arg0, View arg1, int position, long id) 
        {
        	setNextAnimation();
            mProfileAdapter.enableLoadBar(position);
            switch ( position )
            {
            case PROFILE_TOPARTISTS: //"Top Artists"
                new LoadTopArtistsTask().execute((Void)null);
                break;
            case PROFILE_TOPALBUMS: //"Top Albums"
                new LoadTopAlbumsTask().execute((Void)null);
                break;
            case PROFILE_TOPTRACKS: //"Top Tracks"
                new LoadTopTracksTask().execute((Void)null);
                break;
            case PROFILE_RECENTLYPLAYED: //"Recently Played"
                new LoadRecentTracksTask().execute((Void)null);
                break;
            case PROFILE_EVENTS: //"Events"
                new LoadEventsTask().execute((Void)null);
                break;
            case PROFILE_FRIENDS: //"Friends"
                new LoadFriendsTask().execute((Void)null);
                break;
            case PROFILE_TAGS: //"Tags"
                new LoadTagsTask().execute((Void)null);
                break;
            default: 
                break;
            
            }
            
        }
        
    };
    
    private OnItemClickListener mArtistListItemClickListener = new OnItemClickListener() 
    {
		public void onItemClick(AdapterView<?> l, View v, int position, long id) {
			try {
	            Artist artist = (Artist)l.getAdapter().getItem(position);
	        	ListAdapter la = (ListAdapter) l.getAdapter();
	        	la.enableLoadBar(position);
	            LastFMApplication.getInstance().playRadioStation(Profile.this,"lastfm://artist/"+Uri.encode(artist.getName())+"/similarartists", true);
			} catch (ClassCastException e) {
				// fine.
			}
		}
    	
    };
    
    private OnItemClickListener mAlbumListItemClickListener = new OnItemClickListener() 
    {
	    public void onItemClick(AdapterView<?> l, View v,
	            int position, long id) {
	    	try {
		        Album album = (Album) l.getAdapter().getItem(position);
		        showAlbumDialog(album);
	    	} catch (ClassCastException e) {
	    		// (Album) cast can fail, like when the list contains a string saying: "no items"
	    	}
	    }
    	
    };
    
    private OnItemClickListener mTrackListItemClickListener = new OnItemClickListener()
    {
        public void onItemClick(AdapterView<?> l, View v, int position, long id) 
        {
        	try {
        		Track track = (Track) l.getAdapter().getItem(position);
        		showTrackDialog(track);
        	} catch (ClassCastException e) {
        		// (Track) cast can fail, like when the list contains a string saying: "no items"
        	}
        }

    };
    
    private OnItemClickListener mTagListItemClickListener = new OnItemClickListener() 
    {
		public void onItemClick(AdapterView<?> l, View v, int position, long id) 
		{
			try {
		        Session session = LastFMApplication.getInstance().map.get( "lastfm_session" );
				Tag tag = (Tag)l.getAdapter().getItem(position);
				
	        	ListAdapter la = (ListAdapter) l.getAdapter();
	        	la.enableLoadBar(position);
	        	
				if(session.getSubscriber().equals("1"))
					LastFMApplication.getInstance().playRadioStation(Profile.this,"lastfm://usertags/"+mUsername+"/"+Uri.encode(tag.getName()), true);
				else
					LastFMApplication.getInstance().playRadioStation(Profile.this,"lastfm://globaltags/"+Uri.encode(tag.getName()), true);
			} catch (ClassCastException e) {
				// when the list item is not a tag
			}
		}
		
	};
	
	
    private OnItemClickListener mEventItemClickListener = new OnItemClickListener(){

        public void onItemClick(final AdapterView<?> parent, final View v,
                final int position, long id) 
        {
        	try {
	            final Event event = (Event) parent.getAdapter().getItem(position);
	    	    mOnEventActivityResult = new EventActivityResult() {
	    	    	public void onEventStatus(int status) 
	    	    	{
	    	    		event.setStatus(String.valueOf(status));
	    	    		mOnEventActivityResult = null;
	    	    	}
	    	    };
	            startActivityForResult( fm.last.android.activity.Event.intentFromEvent(Profile.this, event), 0 );
        	} catch (ClassCastException e) {
        		// when the list item is not an event
        	}
        }

    };
    
    private OnItemClickListener mUserItemClickListener = new OnItemClickListener() {
        public void onItemClick(AdapterView<?> l, View v,
                int position, long id) {
        	try {
	        	ListAdapter la = (ListAdapter) l.getAdapter();
	        	la.enableLoadBar(position);
	        	
	            User user = (User) la.getItem(position);
	            Intent profileIntent = new Intent(Profile.this, fm.last.android.activity.Profile.class);
	            profileIntent.putExtra("lastfm.profile.username", user.getName());
	            startActivity(profileIntent);
        	} catch (ClassCastException e) {
        		// when the list item is not a User        		
        	}
        }
    };

    
    private class LoadTopArtistsTask extends UserTask<Void, Void, ArrayList<ListEntry>> {
        
        public ArrayList<ListEntry> doInBackground(Void...params) {

            try {
                Artist[] topartists = mServer.getUserTopArtists(mUser.getName(), "overall");
                if(topartists.length == 0 )
                    return null;
                ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
                for(int i=0; i< ((topartists.length < 10) ? topartists.length : 10); i++)
                {
                	String url = null;
                	try 
                	{
                		ImageUrl[] urls = topartists[i].getImages();
                		url = urls[0].getUrl();
                	}
                	catch (ArrayIndexOutOfBoundsException e)
                	{}
                                	
                    ListEntry entry = new ListEntry(topartists[i], 
                            R.drawable.artist_icon, 
                            topartists[i].getName(), 
                            url,
                            R.drawable.list_icon_station);
                    iconifiedEntries.add(entry);
                }
                return iconifiedEntries;
            } catch (Exception e) {
            	e.printStackTrace();
            }
            return null;
        }

        @Override
        public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
            if(iconifiedEntries != null) {
                ListAdapter adapter = new ListAdapter(Profile.this, getImageCache());
                adapter.setSourceIconified(iconifiedEntries);
                mProfileLists[PROFILE_TOPARTISTS].setAdapter(adapter);
            } else {
                String[] strings = new String[]{"No Top Artists"};
    	        ListAdapter adapter = new ListAdapter( Profile.this, strings );
    	        adapter.disableDisclosureIcons();
    	        adapter.setDisabled();
                mProfileLists[PROFILE_TOPARTISTS].setAdapter(adapter);
            }
            mViewHistory.push(mNestedViewFlipper.getDisplayedChild()); // Save the current view
            mNestedViewFlipper.setDisplayedChild(PROFILE_TOPARTISTS + 1);
        }
    }

    private class LoadTopAlbumsTask extends UserTask<Void, Void, ArrayList<ListEntry>> {
        
        @Override
        public ArrayList<ListEntry> doInBackground(Void...params) {

            try {
                Album[] topalbums = mServer.getUserTopAlbums(mUser.getName(), "overall");
                if(topalbums.length == 0 )
                    return null;
                ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
                for(int i=0; i< ((topalbums.length < 10) ? topalbums.length : 10); i++)
                {
                	String url = null;
                	try 
                	{
                		ImageUrl[] urls = topalbums[i].getImages();
                		url = urls[ urls.length > 1 ? 1 : 0 ].getUrl();
                	}
                	catch (ArrayIndexOutOfBoundsException e)
                	{}
                	
                    ListEntry entry = new ListEntry(topalbums[i], 
                            R.drawable.no_artwork, 
                            topalbums[i].getTitle(),  
                            url,
                            topalbums[i].getArtist());
                    iconifiedEntries.add(entry);
                }
                return iconifiedEntries;
            } catch (Exception e) {
            	e.printStackTrace();
            }
            return null;
        }

        @Override
        public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
            if(iconifiedEntries != null) {
                ListAdapter adapter = new ListAdapter(Profile.this, getImageCache());
                adapter.setSourceIconified(iconifiedEntries);
                mProfileLists[PROFILE_TOPALBUMS].setAdapter(adapter);
            } else {
                String[] strings = new String[]{"No Top Albums"};
    	        ListAdapter adapter = new ListAdapter( Profile.this, strings );
    	        adapter.disableDisclosureIcons();
    	        adapter.setDisabled();
                mProfileLists[PROFILE_TOPALBUMS].setAdapter(adapter);
            }
            mViewHistory.push(mNestedViewFlipper.getDisplayedChild()); // Save the current view
            mNestedViewFlipper.setDisplayedChild(PROFILE_TOPALBUMS + 1); 
        }
    }
    
    private class LoadTopTracksTask extends UserTask<Void, Void, ArrayList<ListEntry>> {

        @Override
        public ArrayList<ListEntry> doInBackground(Void...params) {
            try {
                Track[] toptracks = mServer.getUserTopTracks(mUser.getName(), "overall");
                if(toptracks.length == 0 )
                    return null;
                ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
                for(int i=0; i< ((toptracks.length < 10) ? toptracks.length : 10); i++){
                    ListEntry entry = new ListEntry(toptracks[i],
                            R.drawable.song_icon,
                            toptracks[i].getName(), 
                            toptracks[i].getImages().length == 0 ? "" : toptracks[i].getImages()[0].getUrl(), // some tracks don't have images
                            toptracks[i].getArtist().getName());
                    iconifiedEntries.add(entry);
                }
                return iconifiedEntries;
            } catch (IOException e) {
                e.printStackTrace();
            } catch (Exception e) {
            	e.printStackTrace();
            }
            return null;
        }

        @Override
        public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
            if(iconifiedEntries != null) {
                ListAdapter adapter = new ListAdapter(Profile.this, getImageCache());
                adapter.setSourceIconified(iconifiedEntries);
                mProfileLists[PROFILE_TOPTRACKS].setAdapter(adapter);
            } else {
                String[] strings = new String[]{"No Top Tracks"};
    	        ListAdapter adapter = new ListAdapter( Profile.this, strings );
    	        adapter.disableDisclosureIcons();
    	        adapter.setDisabled();
                mProfileLists[PROFILE_TOPTRACKS].setAdapter(adapter);
            }
            mViewHistory.push(mNestedViewFlipper.getDisplayedChild()); // Save the current view
            mNestedViewFlipper.setDisplayedChild(PROFILE_TOPTRACKS + 1);
        }
    }
    
    private class LoadRecentTracksTask extends UserTask<Void, Void, ArrayList<ListEntry>> {

        @Override
        public ArrayList<ListEntry> doInBackground(Void...params) {
            try {
                Track[] recenttracks = mServer.getUserRecentTracks(mUser.getName(), 10);
                if(recenttracks.length == 0)
                	return null;
                
                ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
                for(Track track : recenttracks) {
                    ListEntry entry = new ListEntry(track,
                            R.drawable.song_icon,
                            track.getName(), 
                            track.getImages().length == 0 ? "" : track.getImages()[0].getUrl(), // some tracks don't have images
                            track.getArtist().getName());
                    iconifiedEntries.add(entry);
                }
                return iconifiedEntries;
            } catch (Exception e) {
            	e.printStackTrace();
            }
            return null;
        }

        @Override
        public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
            if(iconifiedEntries != null) {
                ListAdapter adapter = new ListAdapter(Profile.this, getImageCache());
                adapter.setSourceIconified(iconifiedEntries);
                mProfileLists[PROFILE_RECENTLYPLAYED].setAdapter(adapter);
            } else {
                String[] strings = new String[]{"No Recent Tracks"};
    	        ListAdapter adapter = new ListAdapter( Profile.this, strings );
    	        adapter.disableDisclosureIcons();
    	        adapter.setDisabled();
                mProfileLists[PROFILE_RECENTLYPLAYED].setAdapter(adapter);
            }
            mViewHistory.push(mNestedViewFlipper.getDisplayedChild()); // Save the current view
            mNestedViewFlipper.setDisplayedChild(PROFILE_RECENTLYPLAYED + 1);
        }
    }
    
    private class LoadEventsTask extends UserTask<Void, Void, EventListAdapter> {

        @Override
        public EventListAdapter doInBackground(Void...params) {

            try {
                fm.last.api.Event[] events = mServer.getUserEvents(mUser.getName());
                if (events.length > 0) {
                    EventListAdapter result = new EventListAdapter(Profile.this);
                	result.setEventsSource(events);
                	return result;
                }
            } catch (IOException e) {
                e.printStackTrace();
            } catch (NullPointerException e) {
            	e.printStackTrace();
            }
            return null;
        }

        @Override
        public void onPostExecute(EventListAdapter result) {
            if (result != null) {
                mProfileLists[PROFILE_EVENTS].setAdapter(result);
                //mEventsList.setOnScrollListener(mEventsAdapter.getOnScrollListener());
            } else {
                String[] strings = new String[]{"No Upcoming Events"};
    	        ListAdapter adapter = new ListAdapter( Profile.this, strings );
    	        adapter.disableDisclosureIcons();
    	        adapter.setDisabled();
                mProfileLists[PROFILE_EVENTS].setAdapter(adapter);
            }
            mViewHistory.push(mNestedViewFlipper.getDisplayedChild()); // Save the current view
            mNestedViewFlipper.setDisplayedChild(PROFILE_EVENTS + 1);
        }
    }
    
    private class LoadTagsTask extends UserTask<Void, Void, ArrayList<ListEntry>> {
    	
        @Override
        public ArrayList<ListEntry> doInBackground(Void...params) {
    		try {
    			Tag[] tags = mServer.getUserTopTags(mUsername, 10);
    			if (tags.length == 0)
    				return null;
    			
    			ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
    			for(int i=0; i< ((tags.length < 10) ? tags.length : 10); i++){
    				ListEntry entry = new ListEntry(tags[i], 
    						-1,
    						tags[i].getName(), 
    						R.drawable.list_icon_station);
    				iconifiedEntries.add(entry);
    			}
    			return iconifiedEntries;
            } catch (Exception e) {
            	e.printStackTrace();
            }
            return null;
        }

        @Override
        public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
        	if(iconifiedEntries != null) {
    			ListAdapter adapter = new ListAdapter(Profile.this, getImageCache());
    			adapter.setSourceIconified(iconifiedEntries);
        		mProfileLists[PROFILE_TAGS].setAdapter(adapter);
        	} else {
                String[] strings = new String[]{"No Tags"};
    	        ListAdapter adapter = new ListAdapter( Profile.this, strings );
    	        adapter.disableDisclosureIcons();
    	        adapter.setDisabled();
                mProfileLists[PROFILE_TAGS].setAdapter(adapter);
        	}
            mViewHistory.push(mNestedViewFlipper.getDisplayedChild()); // Save the current view
            mNestedViewFlipper.setDisplayedChild(PROFILE_TAGS + 1);
        }
    }

    private class LoadFriendsTask extends UserTask<Void, Void, ArrayList<ListEntry>> {

        @Override
        public ArrayList<ListEntry> doInBackground(Void...params) {
            try {
                User[] friends = mServer.getFriends(mUser.getName(), null, null).getFriends();
                if(friends.length == 0 )
                    return null;
                ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>();
                for(int i=0; i < friends.length; i++){
                    ListEntry entry = new ListEntry(friends[i],
                            R.drawable.profile_unknown,
                            friends[i].getName(), 
                            friends[i].getImages().length == 0 ? "" : friends[i].getImages()[0].getUrl()); // some tracks don't have images
                    iconifiedEntries.add(entry);
                }
                return iconifiedEntries;
            } catch (Exception e) {
            	e.printStackTrace();
            }
            return null;
        }

        @Override
        public void onPostExecute(ArrayList<ListEntry> iconifiedEntries) {
            if(iconifiedEntries != null) {
                ListAdapter adapter = new ListAdapter(Profile.this, getImageCache());
                adapter.setSourceIconified(iconifiedEntries);
                mProfileLists[PROFILE_FRIENDS].setAdapter(adapter);
            } else {
                String[] strings = new String[]{"No Friends Retrieved"};
    	        ListAdapter adapter = new ListAdapter( Profile.this, strings );
    	        adapter.disableDisclosureIcons();
    	        adapter.setDisabled();
                mProfileLists[PROFILE_FRIENDS].setAdapter(adapter);
            }
            mViewHistory.push(mNestedViewFlipper.getDisplayedChild()); // Save the current view
            mNestedViewFlipper.setDisplayedChild(PROFILE_FRIENDS + 1);
        }
    }
       
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        
        // Parameters for menu.add are:
        // group -- Not used here.
        // id -- Used only when you want to handle and identify the click yourself.
        // title
        MenuItem logout = menu.add(Menu.NONE, 0, Menu.NONE, "Logout");
        logout.setIcon(R.drawable.logout);

        MenuItem settings = menu.add(Menu.NONE, 1, Menu.NONE, "Settings");
        settings.setIcon(android.R.drawable.ic_menu_preferences);

        MenuItem nowPlaying = menu.add(Menu.NONE, 2, Menu.NONE, "Now Playing");
		nowPlaying.setIcon( R.drawable.view_artwork );
		
		menu.add(Menu.NONE, 3, Menu.NONE, "Bootstrap");
        return true;
    }
    
	@Override
	public boolean onPrepareOptionsMenu(Menu menu)  {
		menu.findItem(2).setEnabled( mIsPlaying );
		
		return super.onPrepareOptionsMenu(menu);
	}
    
    
    public boolean onOptionsItemSelected(MenuItem item) {
    	Intent intent;
        switch (item.getItemId()) {
        case 0:
        	logout();
        	finish();
        	break;
        case 1:
            intent = new Intent( Profile.this, Preferences.class );
            startActivity( intent );
            return true;
        case 2:
            intent = new Intent( Profile.this, Player.class );
            startActivity( intent );
            return true;
        case 3:
			Intent i = new Intent("fm.last.android.scrobbler.BOOTSTRAP");
			sendBroadcast(i);
			return true;
        }
        return false;
    }
    
    private void showTrackDialog(Track track)
    {
        mTrackInfo = track;
        showDialog(DIALOG_TRACK);
    }
    private void showAlbumDialog(Album album)
    {
        mAlbumInfo = album;
        showDialog(DIALOG_ALBUM);
    }
    
    protected Dialog onCreateDialog(int id) 
    {
        final int dialogId = id;
        mDialogList = new ListView(Profile.this);
        mDialogAdapter = new ListAdapter(Profile.this, getImageCache());

        ArrayList<ListEntry> entries = prepareProfileActions(id);
        mDialogAdapter.setSourceIconified(entries);
        mDialogAdapter.setIconsUnscaled();
        mDialogAdapter.disableLoadBar();
        mDialogList.setAdapter(mDialogAdapter);
        mDialogList.setDivider( new ColorDrawable( 0xffd9d7d7 ) );
        mDialogList.setOnItemClickListener(new OnItemClickListener() {
            public void onItemClick(AdapterView<?> l, View v, int position, long id) 
            {
            	if(dialogId == DIALOG_TRACK) {
                    switch (position)
                    {
                        case 0: // Similar
                            mDialogAdapter.enableLoadBar(position);
                            playSimilar(dialogId);
                            break;
                        case 1: // Share
                     	   shareItem();
                     	   break;
                        case 2: // Tag
                            tagItem(dialogId);
                            break;
                        case 3: // Add to Playlist
                      	   addItemToPlaylist();
                      	   break;
                        case 4: // Buy on Amazon
                     	   buyAmazon(dialogId);
                     	   break;
                    }
            	}
            	if(dialogId == DIALOG_ALBUM) {
                    switch (position)
                    {
                        case 0: // Similar
                            mDialogAdapter.enableLoadBar(position);
                            playSimilar(dialogId);
                            break;
                        case 1: // Amazon
                            buyAmazon(dialogId);
                            break;
                    }
            	}
            	
            	((Dialog)mDialogList.getTag()).dismiss();
            }
            });
        
        AlertDialog dialog = new AlertDialog.Builder(Profile.this).setTitle("Select Action").setView(mDialogList).create();
        mDialogList.setTag( dialog );
        
        return dialog; 
    }
    
    void buyAmazon(int type)
    {
        String query = null;
        int searchType = 0;
        if (type == DIALOG_ALBUM)
        {
            query = mAlbumInfo.getArtist() + " " + mAlbumInfo.getTitle();
            searchType = 1;
        } 
        else if( type == DIALOG_TRACK) 
        {
            query = mTrackInfo.getArtist().getName() + " " + mTrackInfo.getName();
            searchType = 0;
        }
        if( query != null ) {
            try {
                Intent intent = new Intent( Intent.ACTION_SEARCH );
                intent.setComponent(new ComponentName("com.amazon.mp3","com.amazon.mp3.android.client.SearchActivity"));
                intent.putExtra("actionSearchString", query);
                intent.putExtra("actionSearchType", searchType);
                startActivity( intent );
            } catch (Exception e) {
				LastFMApplication.getInstance().presentError(Profile.this, "Amazon Unavailable", "The Amazon MP3 store is not currently available on this device.");
            }
        }
    }
    
    void shareItem() {
        Intent intent = new Intent( this, Share.class );
        intent.putExtra(Share.INTENT_EXTRA_ARTIST, mTrackInfo.getArtist().getName());
        intent.putExtra(Share.INTENT_EXTRA_TRACK, mTrackInfo.getName());
        startActivity( intent );
    }
    
    void addItemToPlaylist() {
        Intent intent = new Intent( this, AddToPlaylist.class );
        intent.putExtra(Share.INTENT_EXTRA_ARTIST, mTrackInfo.getArtist().getName());
        intent.putExtra(Share.INTENT_EXTRA_TRACK, mTrackInfo.getName());
        startActivity( intent );
    }
    
    void tagItem(int type)
    {
        if( type != DIALOG_TRACK)
            return; //temporary until the Tag activity supports albums.
        String artist = null;
        String track = null;
        if (type == DIALOG_ALBUM)
        {
            artist = mAlbumInfo.getArtist();
                        
        } 
        else if( type == DIALOG_TRACK) 
        {
            artist = mTrackInfo.getArtist().getName();
            track = mTrackInfo.getName();
        }
        if( artist != null )
        {
            Intent myIntent = new Intent(this, fm.last.android.activity.Tag.class);
            myIntent.putExtra("lastfm.artist", artist);
            myIntent.putExtra("lastfm.track", track);
            startActivity(myIntent);
        }
    }
    
    void playSimilar(int type)
    {
        String artist = null;
        if (type == DIALOG_ALBUM)
        {
            artist = mAlbumInfo.getArtist();
                        
        } 
        else if( type == DIALOG_TRACK) 
        {
            artist = mTrackInfo.getArtist().getName();
        }
        
        if( artist != null)
            LastFMApplication.getInstance().playRadioStation(Profile.this,"lastfm://artist/"+Uri.encode(artist)+"/similarartists", true);
//        dismissDialog(type);
        
    }
    
	private boolean isAmazonInstalled() {
		PackageManager pm = getPackageManager();
		boolean result = false;
		try {
			pm.getPackageInfo("com.amazon.mp3", PackageManager.GET_ACTIVITIES);
			result = true;
		} catch (Exception e) {
			result = false;
		}
		return result;
	}

    ArrayList<ListEntry> prepareProfileActions(int type)
    {
        ArrayList<ListEntry> iconifiedEntries = new ArrayList<ListEntry>(); 
        
        ListEntry entry = new ListEntry(R.string.dialog_similar, R.drawable.radio, getResources().getString(R.string.dialog_similar));
        iconifiedEntries.add(entry);

        if( type == DIALOG_TRACK) {
            entry = new ListEntry(R.string.dialog_share, R.drawable.share_dark, getResources().getString(R.string.dialog_share));
            iconifiedEntries.add(entry);

            entry = new ListEntry(R.string.dialog_tagtrack, R.drawable.tag_dark, getResources().getString(R.string.dialog_tagtrack));
            iconifiedEntries.add(entry);

            entry = new ListEntry(R.string.dialog_addplaylist, R.drawable.playlist_dark, getResources().getString(R.string.dialog_addplaylist));
            iconifiedEntries.add(entry);
        }

        if(isAmazonInstalled()) {
        	entry = new ListEntry(R.string.dialog_amazon, R.drawable.shopping_cart_dark, getResources().getString(R.string.dialog_amazon)); // TODO need amazon icon
        	iconifiedEntries.add(entry);
        }
        return iconifiedEntries;        
    }
    
    private void logout()
    {
        SharedPreferences settings = getSharedPreferences( LastFm.PREFS, 0 );
        SharedPreferences.Editor editor = settings.edit();
        editor.remove( "lastfm_user" );
        editor.remove( "lastfm_pass" );
        editor.remove( "lastfm_session_key" );
        editor.remove( "lastfm_subscriber" );
        editor.commit();
        LastFMApplication.getInstance().map.remove("lastfm_session");
        try
        {
            IRadioPlayer player = LastFMApplication.getInstance().player;
            if(player != null) {
        		player.stop();
        		//player.resetScrobbler();
            }
        	deleteDatabase(LastFm.DB_NAME);
            deleteFile("currentTrack.dat");
            deleteFile("queue.dat");
        }
        catch ( Exception e )
        {
            System.out.println( e.getMessage() );
        }
        Intent intent = new Intent( Profile.this, LastFm.class );
        startActivity( intent );
        finish();
    }
    
    private ImageCache getImageCache(){
        if(mImageCache == null){
            mImageCache = new ImageCache();
        }
        return mImageCache;
    }


}
