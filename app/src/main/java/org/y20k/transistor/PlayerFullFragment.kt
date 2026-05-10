/*
 * PlayerFullFragment.kt
 * Implements the full screen player
 */

package org.y20k.transistor

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.y20k.transistor.core.Station
import org.y20k.transistor.helpers.CollectionHelper
import org.y20k.transistor.helpers.FileHelper
import org.y20k.transistor.helpers.PreferencesHelper
import android.util.DisplayMetrics

class PlayerFullFragment : Fragment() {

    private val TAG: String = PlayerFullFragment::class.java.simpleName
    private var listener: PlayerFullFragmentListener? = null
    private var initialStation: Station? = null
    private var initialIsPlaying: Boolean = false

    private var stationIcon: ImageView? = null
    private var playerStationName: TextView? = null
    private var playerStationMetadata: TextView? = null
    private var textViewStationInfo: TextView? = null
    private var textViewMetadata: TextView? = null
    private var buttonPrev: ImageButton? = null
    private var buttonPlay: ImageButton? = null
    private var buttonNext: ImageButton? = null
    private var buttonFullscreenExit: ImageButton? = null
    private var favoritesRecyclerView: RecyclerView? = null
    private var favoritesAdapter: FavoritesAdapter? = null

    interface PlayerFullFragmentListener {
        fun onPlayButtonTapped()
        fun onPreviousButtonTapped()
        fun onNextButtonTapped()
        fun onExitFullscreen()
        fun onFavoriteStationTapped(position: Int)
    }

    fun setInitialData(station: Station, isPlaying: Boolean) {
        initialStation = station
        initialIsPlaying = isPlaying
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val displayMode = PreferencesHelper.loadFullScreenDisplayMode()
        val layoutId = when (displayMode) {
            Keys.FULL_SCREEN_MODE_PORTRAIT -> R.layout.fragment_player_full_favorites
            Keys.FULL_SCREEN_MODE_LANDSCAPE -> R.layout.fragment_player_full_landscape
            else -> R.layout.fragment_player_full
        }

        val rootView = inflater.inflate(layoutId, container, false)

        stationIcon = rootView.findViewById(R.id.stationIcon)
        textViewStationInfo = rootView.findViewById(R.id.textViewStationInfo)
        textViewMetadata = rootView.findViewById(R.id.textViewMetadata)
        playerStationName = rootView.findViewById(R.id.playerStationName)
        playerStationMetadata = rootView.findViewById(R.id.playerStationMetadata)
        buttonPrev = rootView.findViewById(R.id.buttonPrev)
        buttonPlay = rootView.findViewById(R.id.buttonPlay)
        buttonNext = rootView.findViewById(R.id.buttonNext)
        buttonFullscreenExit = rootView.findViewById(R.id.buttonFullscreenExit)
        favoritesRecyclerView = rootView.findViewById(R.id.favoritesRecyclerView)

        buttonPrev?.setOnClickListener { listener?.onPreviousButtonTapped() }
        buttonPlay?.setOnClickListener { listener?.onPlayButtonTapped() }
        buttonNext?.setOnClickListener { listener?.onNextButtonTapped() }
        buttonFullscreenExit?.setOnClickListener { listener?.onExitFullscreen() }

        rootView.isFocusableInTouchMode = true
        rootView.requestFocus()
        rootView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { listener?.onPreviousButtonTapped(); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { listener?.onNextButtonTapped(); true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { listener?.onPlayButtonTapped(); true }
                    KeyEvent.KEYCODE_BACK -> { listener?.onExitFullscreen(); true }
                    else -> false
                }
            } else false
        }

        // Set up favorites list if needed
        if (displayMode == Keys.FULL_SCREEN_MODE_PORTRAIT) {
            favoritesRecyclerView?.layoutManager = LinearLayoutManager(requireContext())
            favoritesAdapter = FavoritesAdapter(object : FavoritesAdapter.FavoriteStationClickListener {
                override fun onFavoriteStationClick(stationPosition: Int) {
                    listener?.onFavoriteStationTapped(stationPosition)
                }
            })
            favoritesRecyclerView?.adapter = favoritesAdapter
            loadFavorites()
        }

        initialStation?.let { station ->
            updatePlayerViews(requireContext(), station, initialIsPlaying)
        }

        // Also set last known metadata from history
        val metadataHistory = PreferencesHelper.loadMetadataHistory()
        if (metadataHistory.isNotEmpty()) {
            updateMetadata(metadataHistory.last())
        }

        return rootView
    }

    private fun loadFavorites() {
        val collection = FileHelper.readCollection(requireContext())
        // Map from favorite stations in sorted collection to their positions in original collection
        val favoriteStationsWithPositions = mutableListOf<Pair<Station, Int>>()
        collection.stations.forEachIndexed { index, station ->
            if (station.starred) {
                favoriteStationsWithPositions.add(Pair(station, index))
            }
        }
        favoritesAdapter?.submitList(favoriteStationsWithPositions)
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is PlayerFullFragmentListener) {
            listener = context
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }

    fun updatePlayerViews(context: Context, station: Station, isPlaying: Boolean) {
        playerStationName?.text = station.name
        textViewStationInfo?.text = station.name

        if (isPlaying) {
            buttonPlay?.setImageResource(R.drawable.ic_stop_circle)
            buttonPlay?.contentDescription = getString(R.string.detail_stop)
        } else {
            buttonPlay?.setImageResource(R.drawable.ic_play_circle)
            buttonPlay?.contentDescription = getString(R.string.detail_play)
        }

        try {
            if (!station.image.isNullOrEmpty()) {
                com.bumptech.glide.Glide.with(context)
                    .load(station.image)
                    .error(R.drawable.ic_default_station_image_64dp)
                    .into(stationIcon!!)
            } else {
                com.bumptech.glide.Glide.with(context)
                    .load(R.drawable.ic_default_station_image_64dp)
                    .into(stationIcon!!)
            }
        } catch (e: Exception) {
            com.bumptech.glide.Glide.with(context)
                .load(R.drawable.ic_default_station_image_64dp)
                .into(stationIcon!!)
        }
    }

    fun updateMetadata(metadata: String?) {
        if (!metadata.isNullOrEmpty()) {
            playerStationMetadata?.text = metadata
            playerStationMetadata?.isSelected = true
            textViewMetadata?.text = metadata
            textViewMetadata?.isSelected = true
        }
    }

    // Adapter for favorites list
    private class FavoritesAdapter(
        private val clickListener: FavoriteStationClickListener
    ) : RecyclerView.Adapter<FavoritesAdapter.FavoriteStationViewHolder>() {

        private var favoriteStations: List<Pair<Station, Int>> = emptyList()

        interface FavoriteStationClickListener {
            fun onFavoriteStationClick(stationPosition: Int)
        }

        fun submitList(list: List<Pair<Station, Int>>) {
            favoriteStations = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FavoriteStationViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_favorite_station, parent, false)
            return FavoriteStationViewHolder(view)
        }

        override fun onBindViewHolder(holder: FavoriteStationViewHolder, position: Int) {
            val (station, originalPosition) = favoriteStations[position]
            holder.bind(station, originalPosition)
        }

        override fun getItemCount(): Int = favoriteStations.size

        inner class FavoriteStationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val stationIcon: ImageView = itemView.findViewById(R.id.favoriteStationIcon)
            private val stationName: TextView = itemView.findViewById(R.id.favoriteStationName)

            fun bind(station: Station, originalPosition: Int) {
                stationName.text = station.name
                try {
                    if (!station.image.isNullOrEmpty()) {
                        com.bumptech.glide.Glide.with(itemView.context)
                            .load(station.image)
                            .error(R.drawable.ic_default_station_image_64dp)
                            .into(stationIcon)
                    } else {
                        com.bumptech.glide.Glide.with(itemView.context)
                            .load(R.drawable.ic_default_station_image_64dp)
                            .into(stationIcon)
                    }
                } catch (e: Exception) {
                    com.bumptech.glide.Glide.with(itemView.context)
                        .load(R.drawable.ic_default_station_image_64dp)
                        .into(stationIcon)
                }
                itemView.setOnClickListener {
                    clickListener.onFavoriteStationClick(originalPosition)
                }
            }
        }
    }
}