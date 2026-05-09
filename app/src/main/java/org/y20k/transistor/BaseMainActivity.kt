/*
 * BaseMainActivity.kt
 * Implements the BaseMainActivity abstract class
 * BaseMainActivity is the default activity that hosts the player fragment and the settings fragment
 * It also manages the player view and its functionality
 *
 * This file is part of
 * TRANSISTOR - Radio App for Android
 *
 * Copyright (c) 2015-25 - Y20K.org
 * Licensed under the MIT-License
 * http://opensource.org/licenses/MIT
 */


package org.y20k.transistor

import android.content.ComponentName
import android.content.SharedPreferences
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.y20k.transistor.core.Collection
import org.y20k.transistor.core.Station
import org.y20k.transistor.extensions.cancelSleepTimer
import org.y20k.transistor.extensions.play
import org.y20k.transistor.extensions.playStreamDirectly
import org.y20k.transistor.extensions.requestMetadataHistory
import org.y20k.transistor.extensions.requestSleepTimerRemaining
import org.y20k.transistor.extensions.requestSleepTimerRunning
import org.y20k.transistor.extensions.startSleepTimer
import org.y20k.transistor.helpers.AppThemeHelper
import org.y20k.transistor.helpers.CollectionHelper
import org.y20k.transistor.helpers.FileHelper
import org.y20k.transistor.helpers.ImportHelper
import org.y20k.transistor.helpers.PreferencesHelper
import org.y20k.transistor.ui.MainActivityLayoutHolder
import org.y20k.transistor.ui.PlayerState


/*
 * BaseMainActivity class
 */
abstract class BaseMainActivity : AppCompatActivity(), 
    SharedPreferences.OnSharedPreferenceChangeListener,
    PlayerSmallFragment.PlayerSmallFragmentListener,
    PlayerFullFragment.PlayerFullFragmentListener {


    /* Define log tag */
    private val TAG: String = BaseMainActivity::class.java.simpleName


    /* Main class variables */
    lateinit var layout: MainActivityLayoutHolder
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var controllerFuture: ListenableFuture<MediaController>
    private val controller: MediaController? get() = if (controllerFuture.isDone) controllerFuture.get() else null
    private var playerState: PlayerState = PlayerState()
    private val handler: Handler = Handler(Looper.getMainLooper())
    private var autoPlayExecuted = false
    
    // Fullscreen mode related
    private var isFullscreenMode = false
    private var fullPlayerFragment: PlayerFullFragment? = null


    /* Overrides onCreate from AppCompatActivity */
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // house keeping - if necessary
        if (PreferencesHelper.isHouseKeepingNecessary()) {
            ImportHelper.removeDefaultStationImageUris(this)
            if (PreferencesHelper.loadCollectionSize() != -1) {
                PreferencesHelper.saveEditStationsEnabled(true)
            }
            PreferencesHelper.saveHouseKeepingNecessaryState()
        }

        setContentView(R.layout.activity_main)
        layout = MainActivityLayoutHolder(findViewById(R.id.root_view))
        layout.playerCardClickListener = object : MainActivityLayoutHolder.PlayerCardClickListener {
            override fun onPlayerCardClicked() {
                enterFullscreenMode()
            }
        }

        playerState = PreferencesHelper.loadPlayerState()

        FileHelper.createNomediaFile(getExternalFilesDir(null))

        // custom back press handling
        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreenMode) {
                    exitFullscreenMode()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        setupPlaybackControls()

        PreferencesHelper.registerPreferenceChangeListener(this)
    }


    /* Overrides onStart from AppCompatActivity */
    override fun onStart() {
        super.onStart()
        initializeController()
    }


    /* Overrides onResume from AppCompatActivity */
    override fun onResume() {
        super.onResume()
        volumeControlStream = AudioManager.STREAM_MUSIC
        playerState = PreferencesHelper.loadPlayerState()
        togglePeriodicSleepTimerUpdateRequest()
    }


    /* Overrides onPause from AppCompatActivity */
    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(periodicSleepTimerUpdateRequestRunnable)
    }


    /* Overrides onStop from AppCompatActivity */
    override fun onStop() {
        super.onStop()
        releaseController()
    }


    /* Overrides onDestroy from AppCompatActivity */
    override fun onDestroy() {
        super.onDestroy()
        PreferencesHelper.unregisterPreferenceChangeListener(this)
    }


    /* Overrides onSharedPreferenceChanged from OnSharedPreferenceChangeListener */
    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            Keys.PREF_THEME_SELECTION -> {
                AppThemeHelper.setTheme(PreferencesHelper.loadThemeSelection())
            }
            Keys.PREF_USER_INTERFACE_TRANSPARENCY_EFFECT -> {
                layout.userInterfaceTransparencyEffectActive = PreferencesHelper.loadUserInterfaceTransparencyEffect()
            }
            Keys.PREF_ACTIVE_DOWNLOADS -> {
                layout.toggleDownloadProgressIndicator()
            }
            Keys.PREF_PLAYER_METADATA_HISTORY -> {
                requestMetadataUpdate()
            }
        }
    }


    /* Overrides onSupportNavigateUp from AppCompatActivity */
    override fun onSupportNavigateUp(): Boolean {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_host_container) as NavHostFragment
        val navController = navHostFragment.navController
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }


    /* Overrides dispatchKeyEvent from Activity */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    if (isFullscreenMode) {
                        onPreviousButtonTapped()
                    } else {
                        val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this)
                        if (collection.stations.isNotEmpty()) {
                            val newPosition = if (playerState.stationPosition > 0) {
                                playerState.stationPosition - 1
                            } else {
                                collection.stations.size - 1
                            }
                            onPlayButtonTapped(newPosition)
                        }
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (isFullscreenMode) {
                        onNextButtonTapped()
                    } else {
                        val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this)
                        if (collection.stations.isNotEmpty()) {
                            val newPosition = if (playerState.stationPosition < collection.stations.size - 1) {
                                playerState.stationPosition + 1
                            } else {
                                0
                            }
                            onPlayButtonTapped(newPosition)
                        }
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER, android.view.KeyEvent.KEYCODE_ENTER -> {
                    if (!isFullscreenMode) {
                        enterFullscreenMode()
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    onPlayButtonTapped(playerState.stationPosition)
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this)
                    if (collection.stations.isNotEmpty()) {
                        val newPosition = if (playerState.stationPosition > 0) {
                            playerState.stationPosition - 1
                        } else {
                            collection.stations.size - 1
                        }
                        onPlayButtonTapped(newPosition)
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this)
                    if (collection.stations.isNotEmpty()) {
                        val newPosition = if (playerState.stationPosition < collection.stations.size - 1) {
                            playerState.stationPosition + 1
                        } else {
                            0
                        }
                        onPlayButtonTapped(newPosition)
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }


    /* 获取MainFragment实例 */
    private fun getMainFragment(): MainFragment? {
        val navHostFragment = supportFragmentManager.findFragmentById(R.id.main_host_container) as? NavHostFragment
        return navHostFragment?.childFragmentManager?.fragments?.first() as? MainFragment
    }


    /* Implements PlayerSmallFragmentListener - Toggle play/pause and PlayerFullFragmentListener - Toggle play/pause */
    override fun onPlayButtonTapped() {
        onPlayButtonTapped(playerState.stationPosition)
    }


    /* Implements PlayerSmallFragmentListener - Toggle bottom sheet */
    override fun onPlayerTapped() {
        enterFullscreenMode()
    }


    /* Implements PlayerFullFragmentListener - Switch to previous station */
    override fun onPreviousButtonTapped() {
        val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this)
        if (collection.stations.isNotEmpty()) {
            val newPosition = if (playerState.stationPosition > 0) {
                playerState.stationPosition - 1
            } else {
                collection.stations.size - 1
            }
            onPlayButtonTapped(newPosition)
        }
    }


    /* Implements PlayerFullFragmentListener - Switch to next station */
    override fun onNextButtonTapped() {
        val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this)
        if (collection.stations.isNotEmpty()) {
            val newPosition = if (playerState.stationPosition < collection.stations.size - 1) {
                playerState.stationPosition + 1
            } else {
                0
            }
            onPlayButtonTapped(newPosition)
        }
    }

    /* Implements PlayerFullFragmentListener - Switch to favorite station */
    override fun onFavoriteStationTapped(position: Int) {
        onPlayButtonTapped(position)
    }


    /* Implements PlayerFullFragmentListener - Exit fullscreen */
    override fun onExitFullscreen() {
        exitFullscreenMode()
    }


    /* Enter fullscreen mode */
    private fun enterFullscreenMode() {
        if (isFullscreenMode) return

        isFullscreenMode = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this@BaseMainActivity)
        fullPlayerFragment = PlayerFullFragment().apply {
            if (collection.stations.isNotEmpty() && playerState.stationPosition >= 0 && playerState.stationPosition < collection.stations.size) {
                setInitialData(collection.stations[playerState.stationPosition], playerState.isPlaying)
            }
        }

        val fullscreenContainer = findViewById<View>(R.id.fullscreen_player_container)
        fullscreenContainer.visibility = View.VISIBLE

        supportFragmentManager.beginTransaction()
            .add(R.id.fullscreen_player_container, fullPlayerFragment!!)
            .commit()
    }


    /* Exit fullscreen mode */
    private fun exitFullscreenMode() {
        if (!isFullscreenMode) return

        isFullscreenMode = false
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        fullPlayerFragment?.let { fragment ->
            supportFragmentManager.beginTransaction()
                .remove(fragment)
                .commit()
        }
        fullPlayerFragment = null

        val fullscreenContainer = findViewById<View>(R.id.fullscreen_player_container)
        fullscreenContainer.visibility = View.GONE
    }


    /* Initializes the MediaController - handles connection to PlayerService under the hood */
    private fun initializeController() {
        controllerFuture = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlayerService::class.java))).buildAsync()
        controllerFuture.addListener({ setupController() }, MoreExecutors.directExecutor())
    }


    /* Releases MediaController */
    private fun releaseController() {
        MediaController.releaseFuture(controllerFuture)
    }


    /* Sets up the MediaController */
    private fun setupController() {
        val controller: MediaController = this.controller ?: return
        controller.addListener(playerListener)
        requestMetadataUpdate()
        handleStartIntent()
        setupPlaybackControls()
        layout.togglePlayButton(controller.isPlaying)

        if (!autoPlayExecuted && PreferencesHelper.loadAutoPlayLastStation()) {
            autoPlayExecuted = true
            val lastPosition = playerState.stationPosition
            if (lastPosition != -1) {
                onPlayButtonTapped(lastPosition)
            }
        }

        if (PreferencesHelper.loadAutoFullScreenPlayback() && playerState.stationPosition >= 0) {
            handler.postDelayed({
                enterFullscreenMode()
            }, 300)
        }
    }


    /* Sets up the general playback controls */
    private fun setupPlaybackControls() {
        layout.playButtonView.setOnClickListener {
            onPlayButtonTapped(playerState.stationPosition)
        }

        layout.playerSleepTimerStartButtonView.setOnClickListener {
            when (controller?.isPlaying) {
                true -> {
                    playerState.sleepTimerRunning = true
                    controller?.startSleepTimer()
                    togglePeriodicSleepTimerUpdateRequest()
                }
                else -> Toast.makeText(
                    this,
                    R.string.toast_message_sleep_timer_unable_to_start,
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        layout.playerSleepTimerCancelButtonView.setOnClickListener {
            layout.sheetSleepTimerRemainingTimeView.text = String()
            playerState.sleepTimerRunning = false
            controller?.cancelSleepTimer()
            togglePeriodicSleepTimerUpdateRequest()
        }
    }


    /* Handles play button tap */
    fun onPlayButtonTapped(stationPosition: Int) {
        if (controller?.isPlaying == true && stationPosition == playerState.stationPosition) {
            controller?.pause()
        } else {
            playerState.stationPosition = stationPosition
            controller?.play(this, stationPosition)
            getMainFragment()?.scrollToStationPosition(stationPosition)
        }
    }


    /* Updates the player views */
    fun updatePlayerViews(station: Station) {
        layout.updatePlayerViews(this, station, playerState.isPlaying)
        fullPlayerFragment?.updatePlayerViews(this, station, playerState.isPlaying)
    }


    /* Requests an update of the sleep timer from the player service */
    private fun requestSleepTimerUpdate() {
        val resultFuture: ListenableFuture<SessionResult>? =
            controller?.requestSleepTimerRemaining()
        resultFuture?.addListener(kotlinx.coroutines.Runnable {
            val timeRemaining: Long = resultFuture.get().extras.getLong(Keys.EXTRA_SLEEP_TIMER_REMAINING)
            layout.updateSleepTimer(this, timeRemaining)
        }, MoreExecutors.directExecutor())
    }


    /* Requests an update of the metadata history from the player service */
    private fun requestMetadataUpdate() {
        val resultFuture: ListenableFuture<SessionResult>? = controller?.requestMetadataHistory()
        resultFuture?.addListener(kotlinx.coroutines.Runnable {
            val metadata: ArrayList<String>? = resultFuture.get().extras.getStringArrayList(Keys.EXTRA_METADATA_HISTORY)
            layout.updateMetadata(metadata?.toMutableList())
            if (!metadata.isNullOrEmpty()) {
                fullPlayerFragment?.updateMetadata(metadata.last())
            }
        }, MoreExecutors.directExecutor())
    }


    /* Toggle periodic update request of Sleep Timer state from player service */
    private fun togglePeriodicSleepTimerUpdateRequest() {
        if (playerState.sleepTimerRunning && playerState.isPlaying) {
            handler.removeCallbacks(periodicSleepTimerUpdateRequestRunnable)
            handler.postDelayed(periodicSleepTimerUpdateRequestRunnable, 0)
        } else {
            handler.removeCallbacks(periodicSleepTimerUpdateRequestRunnable)
            layout.sleepTimerRunningViews.visibility = View.GONE
        }
    }


    /* Handles this activity's start intent */
    private fun handleStartIntent() {
        if (intent.action != null) {
            when (intent.action) {
                Keys.ACTION_START -> handleStartPlayer()
            }
        }
    }


    /* Handles START_PLAYER_SERVICE request from App Shortcut */
    private fun handleStartPlayer() {
        intent.action = ""
        if (intent.hasExtra(Keys.EXTRA_START_LAST_PLAYED_STATION)) {
            controller?.play(this, playerState.stationPosition)
        } else if (intent.hasExtra(Keys.EXTRA_STATION_UUID)) {
            val uuid: String = intent.getStringExtra(Keys.EXTRA_STATION_UUID) ?: String()
            CoroutineScope(IO).launch {
                val collection: Collection = FileHelper.readCollection(getApplication())
                withContext(Main) {
                    controller?.play(this@BaseMainActivity, CollectionHelper.getStationPosition(collection, uuid))
                }
            }
        } else if (intent.hasExtra(Keys.EXTRA_STREAM_URI)) {
            val streamUri: String = intent.getStringExtra(Keys.EXTRA_STREAM_URI) ?: String()
            controller?.playStreamDirectly(streamUri)
        }
    }


    /*
     * Runnable: Periodically requests sleep timer state
     */
    private val periodicSleepTimerUpdateRequestRunnable: Runnable = object : Runnable {
        override fun run() {
            requestSleepTimerUpdate()
            handler.postDelayed(this, 500)
        }
    }
    /*
     * End of declaration
     */


    /*
     * Player.Listener: Called when one or more player states changed.
     */
    private var playerListener: Player.Listener = object : Player.Listener {

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            super.onIsPlayingChanged(isPlaying)
            playerState.isPlaying = isPlaying
            layout.animatePlaybackButtonStateTransition(this@BaseMainActivity, isPlaying)
            togglePeriodicSleepTimerUpdateRequest()

            if (isPlaying) {
                layout.showPlayer()
                layout.showBufferingIndicator(buffering = false)
            } else {
                layout.updateSleepTimer(this@BaseMainActivity)
            }

            val resultFuture: ListenableFuture<SessionResult>? =
                controller?.requestSleepTimerRunning()
            resultFuture?.addListener(kotlinx.coroutines.Runnable {
                playerState.sleepTimerRunning = resultFuture.get().extras.getBoolean(Keys.EXTRA_SLEEP_TIMER_RUNNING, false)
            }, MoreExecutors.directExecutor())
            
            val collection = org.y20k.transistor.helpers.FileHelper.readCollection(this@BaseMainActivity)
            if (collection.stations.isNotEmpty() && playerState.stationPosition >= 0 && playerState.stationPosition < collection.stations.size) {
                val station = collection.stations[playerState.stationPosition]
                fullPlayerFragment?.updatePlayerViews(this@BaseMainActivity, station, isPlaying)
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            super.onPlayWhenReadyChanged(playWhenReady, reason)

            if (playWhenReady && controller?.isPlaying == false) {
                layout.showBufferingIndicator(buffering = true)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            super.onPlayerError(error)
            layout.togglePlayButton(false)
            layout.showBufferingIndicator(false)
        }
    }
    /*
     * End of declaration
     */

}