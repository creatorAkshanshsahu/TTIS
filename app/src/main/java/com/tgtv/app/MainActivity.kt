package com.tgtv.app

import android.util.Base64
import android.graphics.Color
import android.os.Bundle
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.*
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.drm.*
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.*
import coil.load
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    data class Channel(val id: String, val name: String, val category: String, val logo: String)

    private val BASE = "https://livetgtv.lovable.app/api/public/channels"
    private val UA = "Mozilla/5.0 (Linux; Android 9; TV) AppleWebKit/537.36 Chrome/110 Safari/537.36"
    private val io = Executors.newFixedThreadPool(3)
    private var all = listOf<Channel>()
    private var shown = listOf<Channel>()
    private var cats = listOf<String>()
    private var selCat = 0
    private var current = -1
    private var retries = 0
    private var player: ExoPlayer? = null

    private lateinit var grid: RecyclerView
    private lateinit var chips: RecyclerView
    private lateinit var status: TextView
    private lateinit var layer: View
    private lateinit var pv: PlayerView
    private lateinit var now: TextView
    private lateinit var spinner: View

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        setContentView(R.layout.activity_main)
        grid = findViewById(R.id.grid); chips = findViewById(R.id.chips); status = findViewById(R.id.status)
        layer = findViewById(R.id.playerLayer); pv = findViewById(R.id.playerView)
        now = findViewById(R.id.nowPlaying); spinner = findViewById(R.id.spinner)
        grid.layoutManager = GridLayoutManager(this, 5)
        grid.adapter = gridAdapter
        chips.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        chips.adapter = chipAdapter
        loadChannels()
    }

    private fun get(u: String): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", UA)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun loadChannels() {
        status.text = "Loading channels…"
        io.execute {
            try {
                val arr = JSONObject(get(BASE)).getJSONArray("channels")
                val list = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Channel(o.getString("id"), o.getString("name"), o.optString("category", "Other"), o.optString("logo", ""))
                }
                runOnUiThread {
                    all = list
                    cats = listOf("All") + list.map { it.category }.distinct().sorted()
                    chipAdapter.notifyDataSetChanged(); applyFilter(0)
                }
            } catch (e: Exception) { runOnUiThread { status.text = "Couldn't load channels: ${e.message}" } }
        }
    }

    private fun applyFilter(i: Int) {
        selCat = i
        shown = if (i == 0) all else all.filter { it.category == cats[i] }
        status.text = "${shown.size} channels"
        gridAdapter.notifyDataSetChanged(); chipAdapter.notifyDataSetChanged()
    }

    // ---------- Playback ----------
    private fun hexToB64Url(hex: String): String {
        val bytes = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun play(index: Int) {
        if (shown.isEmpty()) return
        current = (index + shown.size) % shown.size
        val ch = shown[current]
        layer.visibility = View.VISIBLE; spinner.visibility = View.VISIBLE
        now.text = ch.name
        releasePlayer()
        io.execute {
            try {
                // fresh token every time (they expire quickly)
                val o = JSONObject(get("$BASE/${ch.id}"))
                val url = o.getJSONArray("sources").getString(0)
                val drm = o.optJSONObject("drm")
                runOnUiThread { if (shown.getOrNull(current)?.id == ch.id) start(url, drm) }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_LONG).show(); spinner.visibility = View.GONE }
            }
        }
    }

    private fun start(url: String, drm: JSONObject?) {
        val token = Regex("__hdnea__=([^&]+)").find(url)?.groupValues?.get(1)
        val hdrs = HashMap<String, String>()
        if (token != null) hdrs["Cookie"] = "__hdnea__=$token"
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("plaYtv/7.1.5 (Linux;Android 13) ExoPlayerLib/2.11.7")
            .setDefaultRequestProperties(hdrs)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000).setReadTimeoutMs(15000)
        val factory = DashMediaSource.Factory(http)
        if (drm != null && drm.has("keyId")) {
            // ClearKey license built locally as JWK (hex -> base64url)
            val license = """{"keys":[{"kty":"oct","k":"${hexToB64Url(drm.getString("key"))}","kid":"${hexToB64Url(drm.getString("keyId"))}"}],"type":"temporary"}"""
            val mgr = DefaultDrmSessionManager.Builder()
                .setUuidAndExoMediaDrmProvider(C.CLEARKEY_UUID, FrameworkMediaDrm.DEFAULT_PROVIDER)
                .setMultiSession(true)
                .build(LocalMediaDrmCallback(license.toByteArray()))
            factory.setDrmSessionManagerProvider { mgr }
        }
        val item = MediaItem.Builder().setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(8000).build()).build()
        val p = ExoPlayer.Builder(this).build()
        p.setMediaSource(factory.createMediaSource(item))
        p.playWhenReady = true
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) { spinner.visibility = View.GONE; retries = 0 }
                if (s == Player.STATE_BUFFERING) spinner.visibility = View.VISIBLE
            }
            override fun onPlayerError(e: PlaybackException) {
                if (retries++ < 3) play(current)
                else { spinner.visibility = View.GONE; 
                    val c = e.cause
                    val extra = if (c is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) " HTTP ${c.responseCode}\n${c.dataSpec.uri.path}" else ""
                    Toast.makeText(this@MainActivity, "Playback error: ${e.errorCodeName}$extra", Toast.LENGTH_LONG).show() }
            }
        })
        pv.player = p; player = p; p.prepare()
        now.postDelayed({ if (player === p) now.animate().alpha(0f).setDuration(400).start() }, 4000)
        now.alpha = 1f
    }

    private fun releasePlayer() { pv.player = null; player?.release(); player = null }

    private fun closePlayer() { releasePlayer(); layer.visibility = View.GONE; retries = 0; grid.requestFocus() }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (layer.visibility == View.VISIBLE && e.action == KeyEvent.ACTION_DOWN) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_BACK -> { closePlayer(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP -> { retries = 0; play(current - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> { retries = 0; play(current + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { now.alpha = 1f; return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    override fun onStop() { super.onStop(); closePlayer() }

    // ---------- UI adapters ----------
    private val gridAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = shown.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val v = LayoutInflater.from(p.context).inflate(R.layout.item_channel, p, false)
            v.setOnFocusChangeListener { x, f -> x.animate().scaleX(if (f) 1.08f else 1f).scaleY(if (f) 1.08f else 1f).setDuration(150).start(); if (f) x.elevation = 20f else x.elevation = 0f }
            return object : RecyclerView.ViewHolder(v) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val c = shown[i]
            h.itemView.findViewById<TextView>(R.id.name).text = c.name
            h.itemView.findViewById<ImageView>(R.id.logo).load(c.logo)
            h.itemView.setOnClickListener { retries = 0; play(h.bindingAdapterPosition) }
        }
    }

    private val chipAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = cats.size
        override fun onCreateViewHolder(p: ViewGroup, t: Int): RecyclerView.ViewHolder {
            val tv = TextView(p.context).apply {
                layoutParams = RecyclerView.LayoutParams(-2, -2).apply { setMargins(0, 0, 16, 0) }
                setPadding(40, 18, 40, 18); textSize = 15f; isFocusable = true; isClickable = true
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setBackgroundResource(R.drawable.chip_bg)
            }
            return object : RecyclerView.ViewHolder(tv) {}
        }
        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            val tv = h.itemView as TextView
            tv.text = cats[i]; tv.isSelected = i == selCat
            tv.setTextColor(if (i == selCat) Color.BLACK else Color.WHITE)
            tv.setOnFocusChangeListener { _, f -> if (f) tv.setTextColor(Color.WHITE) else tv.setTextColor(if (i == selCat) Color.BLACK else Color.WHITE) }
            tv.setOnClickListener { applyFilter(h.bindingAdapterPosition) }
        }
    }
}


