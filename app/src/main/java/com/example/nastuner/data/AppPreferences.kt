package com.example.nastuner.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.compose.ui.graphics.toArgb
import com.example.nastuner.*
import kotlinx.coroutines.flow.first

private val Context.dataStore by preferencesDataStore(name = "nastuner_prefs")

object AppPreferences {
    private val KEY_ACCENT = intPreferencesKey("accent_argb")
    private val KEY_BG = intPreferencesKey("background_argb")
    private val KEY_NOTATION = stringPreferencesKey("notation_system")
    private val KEY_SENS = floatPreferencesKey("mic_sensitivity")
    private val KEY_COLORBLIND = booleanPreferencesKey("colorblind")
    private val KEY_PRECISION = stringPreferencesKey("precision_mode")
    private val KEY_GUIDE_UNIT = stringPreferencesKey("guide_unit")
    private val KEY_VR_MINOR = doublePreferencesKey("visual_range_minor")
    private val KEY_VR_MAJOR = doublePreferencesKey("visual_range_major")
    private val KEY_LATCH_MS = longPreferencesKey("note_latch_ms")
    private val KEY_TEXT_OUTLINE = stringPreferencesKey("text_outline")
    private val KEY_REF_A4 = doublePreferencesKey("reference_a4_hz")
    private val KEY_AMBIENT_RMS = doublePreferencesKey("ambient_rms_base")
    private val KEY_SFX_ENABLE = booleanPreferencesKey("sfx_enable")
    private val KEY_SFX_VOLUME = floatPreferencesKey("sfx_volume")
    private val KEY_SFX_STYLE = stringPreferencesKey("sfx_style")

    private val KEY_SEL_INSTRUMENT = stringPreferencesKey("selected_instrument")
    private val KEY_SEL_TUNING = stringPreferencesKey("selected_tuning_name")
    private val KEY_SEL_MANUAL_IDX = intPreferencesKey("manual_string_index")

    data class Selection(
        val instrument: Instrument = Instrument.GUITAR,
        val tuning: Tuning = instrumentTunings[Instrument.GUITAR]!!.first(),
        val manualIndex: Int? = null
    )

    suspend fun loadSettings(context: Context): AppSettings {
        val prefs = context.dataStore.data.first()
        val accent = prefs[KEY_ACCENT]
        val bg = prefs[KEY_BG]
        return AppSettings(
            palette = if (accent != null && bg != null) ColorPalette(androidx.compose.ui.graphics.Color(accent), androidx.compose.ui.graphics.Color(bg)) else AppSettings().palette,
            notationSystem = prefs[KEY_NOTATION]?.let { NotationSystem.valueOf(it) } ?: AppSettings().notationSystem,
            micSensitivity = prefs[KEY_SENS] ?: AppSettings().micSensitivity,
            isColorblindMode = prefs[KEY_COLORBLIND] ?: AppSettings().isColorblindMode,
            precisionMode = prefs[KEY_PRECISION]?.let { PrecisionMode.valueOf(it) } ?: AppSettings().precisionMode,
            guideUnit = prefs[KEY_GUIDE_UNIT]?.let { GuideUnit.valueOf(it) } ?: AppSettings().guideUnit,
            visualRange = VisualRange(
                prefs[KEY_VR_MINOR] ?: AppSettings().visualRange.minor,
                prefs[KEY_VR_MAJOR] ?: AppSettings().visualRange.major
            ),
            noteLatchMs = prefs[KEY_LATCH_MS]?.coerceAtMost(250L) ?: AppSettings().noteLatchMs,
            textOutline = prefs[KEY_TEXT_OUTLINE]?.let { OutlineStyle.valueOf(it) } ?: AppSettings().textOutline,
            referenceA4Hz = prefs[KEY_REF_A4] ?: AppSettings().referenceA4Hz,
            enableTuneSound = prefs[KEY_SFX_ENABLE] ?: AppSettings().enableTuneSound,
            soundVolume = prefs[KEY_SFX_VOLUME] ?: AppSettings().soundVolume,
            soundStyle = prefs[KEY_SFX_STYLE]?.let { SfxStyle.valueOf(it) } ?: AppSettings().soundStyle
        )
    }

    suspend fun saveSettings(context: Context, settings: AppSettings) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ACCENT] = settings.palette.accent.toArgb()
            prefs[KEY_BG] = settings.palette.background.toArgb()
            prefs[KEY_NOTATION] = settings.notationSystem.name
            prefs[KEY_SENS] = settings.micSensitivity
            prefs[KEY_COLORBLIND] = settings.isColorblindMode
            prefs[KEY_PRECISION] = settings.precisionMode.name
            prefs[KEY_GUIDE_UNIT] = settings.guideUnit.name
            prefs[KEY_VR_MINOR] = settings.visualRange.minor
            prefs[KEY_VR_MAJOR] = settings.visualRange.major
            prefs[KEY_LATCH_MS] = settings.noteLatchMs
            prefs[KEY_TEXT_OUTLINE] = settings.textOutline.name
            prefs[KEY_REF_A4] = settings.referenceA4Hz
            prefs[KEY_SFX_ENABLE] = settings.enableTuneSound
            prefs[KEY_SFX_VOLUME] = settings.soundVolume
            prefs[KEY_SFX_STYLE] = settings.soundStyle.name
        }
    }

    suspend fun loadSelection(context: Context): Selection {
        val prefs = context.dataStore.data.first()
        val instrument = prefs[KEY_SEL_INSTRUMENT]?.let { Instrument.valueOf(it) } ?: Instrument.GUITAR
        val tuningName = prefs[KEY_SEL_TUNING]
        val tuning = instrumentTunings[instrument]?.firstOrNull { it.name == tuningName } ?: instrumentTunings[instrument]!!.first()
        val manualIndex = prefs[KEY_SEL_MANUAL_IDX]?.takeIf { it in tuning.notes.indices }
        return Selection(instrument, tuning, manualIndex)
    }

    suspend fun saveSelection(context: Context, selection: Selection) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SEL_INSTRUMENT] = selection.instrument.name
            prefs[KEY_SEL_TUNING] = selection.tuning.name
            selection.manualIndex?.let { prefs[KEY_SEL_MANUAL_IDX] = it } ?: run { prefs.remove(KEY_SEL_MANUAL_IDX) }
        }
    }

    suspend fun saveAmbient(context: Context, ambientRmsBase: Double) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AMBIENT_RMS] = ambientRmsBase
        }
    }
    suspend fun loadAmbient(context: Context): Double? {
        val prefs = context.dataStore.data.first()
        return prefs[KEY_AMBIENT_RMS]
    }
}
