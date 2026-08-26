package com.codefall.rain

import android.app.Activity
import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Settings screen with a live preview of the rain across the top. */
class MainActivity : Activity() {

    private lateinit var preview: RainSurfaceView
    private lateinit var controls: LinearLayout
    private lateinit var config: RainConfig

    /** Re-applies every control's displayed state after Randomize / Reset. */
    private val refreshers = mutableListOf<() -> Unit>()

    private val green = 0xFF00FF41.toInt()
    private val pale = 0xFFB9FFCB.toInt()
    private val dim = 0xFF6FA982.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = RainConfig.load(this)
        preview = findViewById(R.id.preview)
        controls = findViewById(R.id.controls)
        preview.setConfig(config)

        buildControls()

        findViewById<Button>(R.id.btnStart).setOnClickListener {
            persist()
            startActivity(Intent(this, ScreensaverActivity::class.java))
        }
        findViewById<Button>(R.id.btnRandomize).setOnClickListener {
            config.randomize()
            refreshAll()
        }
        findViewById<Button>(R.id.btnClassic).setOnClickListener {
            config.toClassic()
            refreshAll()
            toast(getString(R.string.classic_applied))
        }
        findViewById<Button>(R.id.btnReset).setOnClickListener {
            val d = RainConfig()
            // Keep the user's screensaver-behaviour choices; reset only the visuals.
            d.showClock = config.showClock
            d.clock24h = config.clock24h
            d.keepScreenOn = config.keepScreenOn
            d.message = config.message
            d.messageInterval = config.messageInterval
            config = d
            refreshAll()
        }
    }

    private fun refreshAll() {
        refreshers.forEach { it() }
        preview.setConfig(config)
        persist()
    }

    private fun persist() = config.save(this)

    private fun onConfigChanged() {
        preview.setConfig(config)
        persist()
    }

    private fun buildControls() {
        addSection(getString(R.string.section_look))
        addSpinner(
            getString(R.string.color_theme),
            ColorTheme.entries.map { it.label },
            { config.theme.ordinal }
        ) { config.theme = ColorTheme.fromOrdinal(it); onConfigChanged() }

        addSpinner(
            getString(R.string.glyph_set),
            GlyphSet.entries.map { it.label },
            { config.glyphSet.ordinal }
        ) { config.glyphSet = GlyphSet.fromOrdinal(it); onConfigChanged() }

        addSlider(
            getString(R.string.glyph_size), 10f, 36f,
            { config.glyphSize }, { "%.0f dp".format(it) }
        ) { config.glyphSize = it; onConfigChanged() }

        addSlider(
            getString(R.string.density), 0.2f, 1f,
            { config.density }, { "%d%%".format((it * 100).roundToInt()) }
        ) { config.density = it; onConfigChanged() }

        addSwitch(getString(R.string.mirror_glyphs), { config.mirrorGlyphs }) {
            config.mirrorGlyphs = it; onConfigChanged()
        }

        addSection(getString(R.string.section_motion))
        addSlider(
            getString(R.string.speed), 0.1f, 4f,
            { config.speed }, { "%.2fx".format(it) }
        ) { config.speed = it; onConfigChanged() }

        addSlider(
            getString(R.string.trail), 0.2f, 2f,
            { config.trailLength }, { "%.2fx".format(it) }
        ) { config.trailLength = it; onConfigChanged() }

        addSlider(
            getString(R.string.mutation), 0f, 1f,
            { config.mutationRate }, { "%d%%".format((it * 100).roundToInt()) }
        ) { config.mutationRate = it; onConfigChanged() }

        addSwitch(getString(R.string.settle_trail), { config.settleTrail }) {
            config.settleTrail = it; onConfigChanged()
        }

        addSwitch(getString(R.string.tilt_enabled), { config.tiltEnabled }) { on ->
            if (on && !TiltSource(this) {}.isAvailable) {
                toast(getString(R.string.no_tilt_sensor))
            }
            config.tiltEnabled = on
            onConfigChanged()
        }

        addSlider(
            getString(R.string.tilt_strength), 0f, 2f,
            { config.tiltStrength }, { "%.2fx".format(it) }
        ) { config.tiltStrength = it; onConfigChanged() }

        addSection(getString(R.string.section_fx))
        addSlider(
            getString(R.string.glow), 0f, 1f,
            { config.glow }, { "%d%%".format((it * 100).roundToInt()) }
        ) { config.glow = it; onConfigChanged() }

        addSlider(
            getString(R.string.scanlines), 0f, 1f,
            { config.scanlines }, { "%d%%".format((it * 100).roundToInt()) }
        ) { config.scanlines = it; onConfigChanged() }

        addSlider(
            getString(R.string.glitch), 0f, 1f,
            { config.glitch }, { "%d%%".format((it * 100).roundToInt()) }
        ) { config.glitch = it; onConfigChanged() }

        addSection(getString(R.string.section_message))
        addTextField(
            getString(R.string.message_label),
            getString(R.string.message_hint),
            { config.message }
        ) { config.message = it; onConfigChanged() }

        addSlider(
            getString(R.string.message_interval), 5f, 120f,
            { config.messageInterval }, { "%.0f s".format(it) }
        ) { config.messageInterval = it; onConfigChanged() }

        addSection(getString(R.string.section_saver))
        addSwitch(getString(R.string.show_clock), { config.showClock }) {
            config.showClock = it; onConfigChanged()
        }
        addSwitch(getString(R.string.clock_24h), { config.clock24h }) {
            config.clock24h = it; onConfigChanged()
        }
        addSwitch(getString(R.string.keep_screen_on), { config.keepScreenOn }) {
            config.keepScreenOn = it; onConfigChanged()
        }

        addFlatButton(getString(R.string.set_as_daydream)) { openDaydreamSettings() }
        addFlatButton(getString(R.string.set_as_wallpaper)) { openWallpaperPicker() }
    }

    private fun openDaydreamSettings() {
        persist()
        val intents = listOf(
            Intent(Settings.ACTION_DREAM_SETTINGS),
            Intent(Settings.ACTION_DISPLAY_SETTINGS)
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        toast(getString(R.string.no_daydream))
    }

    private fun openWallpaperPicker() {
        persist()
        try {
            val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                ComponentName(this, CodefallWallpaperService::class.java)
            )
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (_: ActivityNotFoundException) {
                toast(getString(R.string.no_wallpaper))
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    // ---- control builders -------------------------------------------------

    private fun dp(v: Float) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics
    ).toInt()

    private fun addSection(title: String) {
        val tv = TextView(this).apply {
            text = title
            setTextColor(green)
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.24f
            alpha = 0.85f
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(18f)
        lp.bottomMargin = dp(2f)
        controls.addView(tv, lp)

        val rule = View(this).apply { setBackgroundColor(0x3300FF41) }
        val rlp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1f))
        rlp.bottomMargin = dp(6f)
        controls.addView(rule, rlp)
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(pale)
        textSize = 14f
        typeface = Typeface.MONOSPACE
    }

    private fun addSlider(
        name: String,
        min: Float,
        max: Float,
        get: () -> Float,
        format: (Float) -> String,
        set: (Float) -> Unit
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val name0 = label(name)
        val value = TextView(this).apply {
            setTextColor(green)
            textSize = 13f
            typeface = Typeface.MONOSPACE
        }
        header.addView(
            name0,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(value)
        row.addView(header)

        val steps = 1000
        val bar = SeekBar(this).apply {
            this.max = steps
            progressTintList = android.content.res.ColorStateList.valueOf(green)
            thumbTintList = android.content.res.ColorStateList.valueOf(green)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                progressBackgroundTintList =
                    android.content.res.ColorStateList.valueOf(0x5500FF41)
            }
        }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + (max - min) * (progress.toFloat() / steps)
                value.text = format(v)
                if (fromUser) set(v)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })
        val blp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        blp.topMargin = dp(-2f)
        row.addView(bar, blp)

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(6f)
        controls.addView(row, lp)

        val refresh = {
            val v = get().coerceIn(min, max)
            bar.progress = (((v - min) / (max - min)) * steps).roundToInt()
            value.text = format(v)
        }
        refresh()
        refreshers += refresh
    }

    private fun addSpinner(
        name: String,
        items: List<String>,
        get: () -> Int,
        set: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(
            label(name),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )

        val spinner = Spinner(this)
        val adapter = ArrayAdapter(this, R.layout.spinner_item, items)
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(get().coerceIn(0, items.size - 1), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (pos != get()) set(pos)
            }

            override fun onNothingSelected(p: AdapterView<*>?) = Unit
        }
        row.addView(
            spinner,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4f)
        controls.addView(row, lp)

        refreshers += {
            val want = get().coerceIn(0, items.size - 1)
            if (spinner.selectedItemPosition != want) spinner.setSelection(want, false)
        }
    }

    private fun addSwitch(name: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            text = name
            setTextColor(pale)
            textSize = 14f
            typeface = Typeface.MONOSPACE
            isChecked = get()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                thumbTintList = android.content.res.ColorStateList.valueOf(green)
                trackTintList = android.content.res.ColorStateList.valueOf(dim)
            }
            setOnCheckedChangeListener { _, checked -> if (checked != get()) set(checked) }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(10f)
        controls.addView(sw, lp)

        refreshers += { if (sw.isChecked != get()) sw.isChecked = get() }
    }

    private fun addTextField(
        name: String,
        hint: String,
        get: () -> String,
        set: (String) -> Unit
    ) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(label(name))

        val field = EditText(this).apply {
            this.hint = hint
            setText(get())
            setTextColor(green)
            setHintTextColor(dim)
            textSize = 15f
            typeface = Typeface.MONOSPACE
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                backgroundTintList = android.content.res.ColorStateList.valueOf(green)
            }
            addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString() ?: ""
                    if (v != get()) set(v)
                }

                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            })
        }
        row.addView(
            field,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(4f)
        controls.addView(row, lp)

        refreshers += {
            // Only overwrite when it actually differs, so the caret isn't yanked
            // to the end on every unrelated refresh.
            if (field.text.toString() != get()) field.setText(get())
        }
    }

    private fun addFlatButton(text: String, onClick: () -> Unit) {
        val b = Button(this).apply {
            this.text = text
            setTextColor(green)
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setBackgroundResource(R.drawable.btn_secondary)
            setOnClickListener { onClick() }
        }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46f))
        lp.topMargin = dp(12f)
        controls.addView(b, lp)
    }

    override fun onResume() {
        super.onResume()
        // The screensaver may have been launched with different settings; reload.
        config = RainConfig.load(this)
        refreshers.forEach { it() }
        preview.setConfig(config)
        preview.onResumeRendering()
    }

    override fun onPause() {
        super.onPause()
        persist()
        preview.onPauseRendering()
    }

    override fun onDestroy() {
        super.onDestroy()
        preview.release()
    }
}
