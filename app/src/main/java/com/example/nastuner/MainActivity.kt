package com.example.nastuner

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.example.nastuner.ui.theme.NASTunerTheme
import com.example.nastuner.data.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jtransforms.fft.DoubleFFT_1D
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.floor

// --- Constants ---
private const val A4_FREQUENCY = 440.0
private const val A4_INDEX = 57
private const val NOTE_LATCH_THRESHOLD = 3
// Visual window for the indicator (smaller = looks more precise)
private const val MAX_DEVIATION_HZ = 4.0

// --- Data Models ---
enum class Instrument(val icon: ImageVector, val instrumentName: String) {
    GUITAR(Icons.Outlined.MusicNote, "Guitarra"), BASS(Icons.Outlined.MusicNote, "Bajo"), UKULELE(Icons.Outlined.MusicNote, "Ukelele")
}
enum class NotationSystem { SCIENTIFIC, SOLFEGE }
enum class GuideUnit { HZ, CENTS }
data class VisualRange(val minor: Double, val major: Double)
enum class PrecisionMode(val threshold: Double, val displayName: String) {
    BEGINNER(2.0, "Beginner"),
    STANDARD(1.0, "Standard"),
    PRO(0.5, "Pro")
}
data class ColorPalette(val background: Color, val accent: Color)
data class Tuning(val name: String, val notes: List<String>)
enum class OutlineStyle { NONE, LIGHT, DARK }
enum class SfxStyle { CRYSTAL, BEEP }
data class AppSettings(
    var palette: ColorPalette = ColorPalette(Color(0xFF1C1C1C), Color(0xFFE53935)), // Vibrant red default
    var notationSystem: NotationSystem = NotationSystem.SCIENTIFIC,
    var micSensitivity: Float = 0.5f,
    var isColorblindMode: Boolean = false,
    var precisionMode: PrecisionMode = PrecisionMode.STANDARD,
    var adaptiveThreshold: Boolean = true,
    var guideUnit: GuideUnit = GuideUnit.HZ,
    var visualRange: VisualRange = VisualRange(2.0, 4.0),
    var noteLatchMs: Long = 2000L,
    var textOutline: OutlineStyle = OutlineStyle.NONE,
    var referenceA4Hz: Double = 440.0,
    // SFX settings
    var enableTuneSound: Boolean = false,
    var soundVolume: Float = 0.8f,
    var soundStyle: SfxStyle = SfxStyle.CRYSTAL
)
data class TuningData(
    val note: String = "...",
    val deviationInHz: Double = 0.0,
    val frequency: Double = 0.0,
    val confidence: Double = 0.0
)

// Typical frequency ranges per instrument (Hz)
val instrumentFrequencyRanges: Map<Instrument, Pair<Double, Double>> = mapOf(
    Instrument.GUITAR to (70.0 to 1320.0),   // Allow a bit below E2 for robustness
    Instrument.BASS to (28.0 to 392.0),      // Allow a bit below B0/E1
    Instrument.UKULELE to (230.0 to 520.0)   // Slightly wider for low/high variance
)

val instrumentTunings = mapOf(
    Instrument.GUITAR to listOf(
        Tuning("Estándar", listOf("E", "A", "D", "G", "B", "E")),
        Tuning("Drop D", listOf("D", "A", "D", "G", "B", "E")),
        Tuning("Drop C#", listOf("C#", "G#", "C#", "F#", "A#", "D#")),
        Tuning("Drop C", listOf("C", "G", "C", "F", "A", "D")),
        Tuning("Open G", listOf("D", "G", "D", "G", "B", "D")),
        Tuning("Open D", listOf("D", "A", "D", "F#", "A", "D")),
        Tuning("Open E", listOf("E", "B", "E", "G#", "B", "E")),
        Tuning("Open A", listOf("E", "A", "C#", "E", "A", "E")),
        Tuning("DADGAD", listOf("D", "A", "D", "G", "A", "D")),
        Tuning("D Standard", listOf("D", "G", "C", "F", "A", "D"))
    ),
    Instrument.BASS to listOf(
        Tuning("Estándar (4)", listOf("E", "A", "D", "G")),
        Tuning("Drop D (4)", listOf("D", "A", "D", "G")),
        Tuning("D Standard (4)", listOf("D", "G", "C", "F")),
        Tuning("Estándar (5)", listOf("B", "E", "A", "D", "G")),
        Tuning("Tenor (5)", listOf("E", "A", "D", "G", "C")),
        Tuning("Estándar (6)", listOf("B", "E", "A", "D", "G", "C")),
        Tuning("Drop C (4)", listOf("C", "G", "C", "F")),
        Tuning("BEAD (4)", listOf("B", "E", "A", "D")),
        Tuning("Half-Step Down (4)", listOf("D#", "G#", "C#", "F#")),
        Tuning("Full-Step Down (4)", listOf("D", "G", "C", "F"))
    ),
    Instrument.UKULELE to listOf(
        Tuning("Standard (GCEA)", listOf("G", "C", "E", "A")),
        Tuning("D-Tuning (ADF#B)", listOf("A", "D", "F#", "B")),
        Tuning("Baritone (DGBE)", listOf("D", "G", "B", "E")),
        Tuning("Low G", listOf("G", "C", "E", "A")),
        Tuning("Slack-Key (GCEG)", listOf("G", "C", "E", "G")),
        Tuning("G-Major", listOf("D", "G", "B", "E")),
        Tuning("Half-Step Up", listOf("G#", "C#", "F", "A#")),
        Tuning("Full-Step Up", listOf("A", "D", "F#", "B")),
        Tuning("Open C", listOf("G", "C", "E", "C")),
        Tuning("Slide", listOf("A", "C#", "E", "A"))
    )
)

val solfegeMap = mapOf("C" to "Do", "C#" to "Do#", "D" to "Re", "D#" to "Re#", "E" to "Mi", "F" to "Fa", "F#" to "Fa#", "G" to "Sol", "G#" to "Sol#", "A" to "La", "A#" to "La#", "B" to "Si")

val fullPaletteList = listOf(
    ColorPalette(Color(0xFF263238), Color(0xFF81D4FA)), // Deep Blue / Light Blue
    ColorPalette(Color(0xFF3E2723), Color(0xFFFBC02D)), // Deep Brown / Yellow
    ColorPalette(Color(0xFF212121), Color(0xFF00E676)), // Pure Black / Bright Green
    ColorPalette(Color(0xFF607D8B), Color(0xFFFF9800)), // Blue Grey / Orange
    ColorPalette(Color(0xFF424242), Color(0xFFF06292)), // Grey / Pink
    ColorPalette(Color(0xFF1B5E20), Color(0xFFC8E6C9)), // Dark Green / Light Green
    ColorPalette(Color(0xFF311B92), Color(0xFFD1C4E9)), // Deep Purple / Light Purple
    ColorPalette(Color(0xFF880E4F), Color(0xFFF8BBD0)), // Deep Magenta / Light Pink
    ColorPalette(Color(0xFF004D40), Color(0xFFB2DFDB)), // Deep Teal / Light Teal
    ColorPalette(Color(0xFFBF360C), Color(0xFFFFCCBC)), // Deep Orange / Light Orange
    ColorPalette(Color(0xFF4E342E), Color(0xFFD7CCC8)), // Dark Brown / Light Brown
    ColorPalette(Color(0xFF263238), Color(0xFFCFD8DC)), // Blue Grey / Lighter Grey
    ColorPalette(Color(0xFF827717), Color(0xFFFFF9C4)), // Dark Lime / Light Yellow
    ColorPalette(Color(0xFF006064), Color(0xFFB2EBF2)), // Dark Cyan / Light Cyan
    ColorPalette(Color(0xFFF57F17), Color(0xFFFFFDE7)), // Dark Yellow / Off-white
    ColorPalette(Color(0xFF3E2723), Color(0xFFBCAAA4)), // Brown / Taupe
    ColorPalette(Color(0xFF1A237E), Color(0xFFC5CAE9)), // Indigo / Light Blue
    ColorPalette(Color(0xFF558B2F), Color(0xFFF1F8E9)), // Green / Off-white
    ColorPalette(Color(0xFFD84315), Color(0xFFFFE0B2)), // Orange / Peach
    ColorPalette(Color(0xFF4A148C), Color(0xFFE1BEE7)),  // Purple / Lavender
    // Extra palettes to expand the pool
    ColorPalette(Color(0xFF0D47A1), Color(0xFFFFEB3B)), // Navy / Yellow
    ColorPalette(Color(0xFF1B5E20), Color(0xFFFFCDD2)), // Dark Green / Soft Pink
    ColorPalette(Color(0xFF263238), Color(0xFFFFAB91)), // Blue Grey / Light Coral
    ColorPalette(Color(0xFF37474F), Color(0xFFA5D6A7)), // Slate / Mint
    ColorPalette(Color(0xFF212121), Color(0xFFFF8A65)), // Black / Salmon
    ColorPalette(Color(0xFF3F51B5), Color(0xFFFFF176)), // Indigo / Light Yellow
    ColorPalette(Color(0xFF455A64), Color(0xFFFFCC80)), // Blue Grey / Apricot
    ColorPalette(Color(0xFF263238), Color(0xFFFFCC80)), // Blue Grey / Apricot (darker bg)
    ColorPalette(Color(0xFF6A1B9A), Color(0xFFFFF59D)), // Purple / Pale Yellow
    ColorPalette(Color(0xFF004D40), Color(0xFFFFF9C4)), // Teal / Pale Yellow
    ColorPalette(Color(0xFF263238), Color(0xFF80CBC4)), // Blue Grey / Teal
    ColorPalette(Color(0xFF1A237E), Color(0xFFFFCDD2)), // Indigo / Pink
    ColorPalette(Color(0xFF3E2723), Color(0xFFFFCC80)), // Brown / Apricot
    ColorPalette(Color(0xFF01579B), Color(0xFFFFF59D))  // Strong Blue / Soft Yellow
)

val colorblindSafePalette = ColorPalette(Color(0xFF01579B), Color(0xFFFFAB00)) // Strong Blue / Amber
val colorblindRecommendedPalettes = listOf(
    ColorPalette(Color(0xFF0B3D91), Color(0xFFFFC107)), // Deep Blue / Amber
    ColorPalette(Color(0xFF004D40), Color(0xFFFFEB3B)), // Teal / Yellow
    ColorPalette(Color(0xFF1A237E), Color(0xFFFFF176)), // Indigo / Light Yellow
    ColorPalette(Color(0xFF263238), Color(0xFFFFAB40)), // Blue Grey / Orange
    ColorPalette(Color(0xFF283593), Color(0xFFFFD54F)), // Blue / Golden
    ColorPalette(Color(0xFF006064), Color(0xFFFFF9C4)), // Cyan / Pale Yellow
    ColorPalette(Color(0xFF1B5E20), Color(0xFFFFF59D)), // Green / Warm Yellow
    ColorPalette(Color(0xFF4A148C), Color(0xFFFFF59D)), // Purple / Pale Yellow
    ColorPalette(Color(0xFF263238), Color(0xFFB0BEC5)), // Blue Grey / Light Grey
    ColorPalette(Color(0xFF37474F), Color(0xFFFFE082))  // Slate / Light Orange
)

// --- Actividad principal de la app (pantalla y orquestación) ---
class MainActivity : ComponentActivity() {
    private val audioAnalyzer = AudioAnalyzer()
    private lateinit var tunerSfx: TunerSfx
    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        // Si dan permiso de mic, partimos el analizador en segundo plano (IO)
        if (isGranted) { CoroutineScope(Dispatchers.IO).launch { audioAnalyzer.start() } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tunerSfx = TunerSfx(this)
        checkAndRequestPermission()
        setContent {
            var settings by remember { mutableStateOf(AppSettings()) }
            var selectedInstrument by remember { mutableStateOf(Instrument.GUITAR) }
            var selectedTuning by remember { mutableStateOf(instrumentTunings[selectedInstrument]!!.first()) }
            var manualStringIndex by remember { mutableStateOf<Int?>(null) }

            // Cargar ajustes/selecciones guardados (una vez)
            LaunchedEffect(Unit) {
                val ctx = applicationContext
                val loaded = AppPreferences.loadSettings(ctx)
                settings = loaded
                val sel = AppPreferences.loadSelection(ctx)
                selectedInstrument = sel.instrument
                selectedTuning = sel.tuning
                manualStringIndex = sel.manualIndex
                // Restaurar referencia A4 al tiro
                MusicTheory.setReferenceA4(loaded.referenceA4Hz)
                // Restaurar línea base de ruido ambiente si existe
                AppPreferences.loadAmbient(ctx)?.let { audioAnalyzer.setAmbientBase(it) }
            }

            LaunchedEffect(settings.micSensitivity) {
                // Pasar sensibilidad de mic al analizador
                audioAnalyzer.setSensitivity(settings.micSensitivity)
            }
            // Pasar duración de latch (estabilidad) al analizador
            LaunchedEffect(settings.noteLatchMs) {
                audioAnalyzer.setNoteLatchMs(settings.noteLatchMs)
            }
            // Actualizar A4 para cálculos de notas
            LaunchedEffect(settings.referenceA4Hz) {
                MusicTheory.setReferenceA4(settings.referenceA4Hz)
            }
            // Guardar ajustes cada vez que cambian
            LaunchedEffect(settings) {
                AppPreferences.saveSettings(applicationContext, settings)
            }

            NASTunerTheme {
                val tuningData by audioAnalyzer.tuningState.collectAsState()
                // Usar siempre la paleta seleccionada; en modo daltónico sugerimos paletas seguras
                val finalPalette = settings.palette
                Surface(modifier = Modifier.fillMaxSize(), color = finalPalette.background) {
                    TunerScreen(
                        tuningData = tuningData,
                        settings = settings,
                        onSettingsChange = { settings = it },
                        palette = finalPalette,
                        setAnalyzerBounds = { minHz, maxHz -> audioAnalyzer.setFrequencyBounds(minHz, maxHz) },
                        setExpectedFrequency = { f -> audioAnalyzer.setExpectedFrequency(f) },
                        calibrateAmbient = { durationMs -> audioAnalyzer.calibrateAmbient(durationMs) }, // Calibración de ruido ambiente
                        onAmbientCalibrated = { value ->
                            CoroutineScope(Dispatchers.IO).launch {
                                AppPreferences.saveAmbient(applicationContext, value)
                            }
                        },
                        calibrateA4 = { durationMs -> audioAnalyzer.autoCalibrateA4(durationMs) },
                        onStrictInTune = {
                            // Suena al primer cruce a "estrictamente afinado" (rising edge)
                            if (settings.enableTuneSound) {
                                tunerSfx.playInTune(settings.soundVolume, settings.soundStyle)
                            }
                        },
                        onTestTuneSound = {
                            // Botón de prueba (Ajustes > Sonido)
                            tunerSfx.playInTune(settings.soundVolume, settings.soundStyle)
                        },
                        selectedInstrument = selectedInstrument,
                        selectedTuning = selectedTuning,
                        manualStringIndex = manualStringIndex,
                        onInstrumentChange = { instr ->
                            selectedInstrument = instr
                            selectedTuning = instrumentTunings[instr]!!.first()
                            manualStringIndex = null
                            CoroutineScope(Dispatchers.IO).launch {
                                AppPreferences.saveSelection(applicationContext, AppPreferences.Selection(selectedInstrument, selectedTuning, manualStringIndex))
                            }
                        },
                        onTuningChange = { tuning ->
                            selectedTuning = tuning
                            manualStringIndex = null
                            CoroutineScope(Dispatchers.IO).launch {
                                AppPreferences.saveSelection(applicationContext, AppPreferences.Selection(selectedInstrument, selectedTuning, manualStringIndex))
                            }
                        },
                        onManualStringIndexChange = { idx ->
                            manualStringIndex = idx
                            CoroutineScope(Dispatchers.IO).launch {
                                AppPreferences.saveSelection(applicationContext, AppPreferences.Selection(selectedInstrument, selectedTuning, manualStringIndex))
                            }
                        }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermission() {
        when (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)) {
            PackageManager.PERMISSION_GRANTED -> CoroutineScope(Dispatchers.IO).launch { audioAnalyzer.start() }
            else -> requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioAnalyzer.stop()
    }
}

// --- UI Composables ---

@Composable
fun OutlinedText(
    text: String,
    fillColor: Color,
    outlineColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    modifier: Modifier = Modifier
) {
    // Render multiple copies slightly offset to simulate a clean outline
    val offsets = listOf(
        Offset(0f, 0f),
        Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f),
        Offset(1f, 1f), Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f)
    )
    Box(modifier = modifier) {
        offsets.forEach { off ->
            Text(
                text = text,
                color = outlineColor,
                fontSize = fontSize,
                fontWeight = fontWeight,
                modifier = Modifier.offset(x = off.x.dp, y = off.y.dp)
            )
        }
        Text(text = text, color = fillColor, fontSize = fontSize, fontWeight = fontWeight)
    }
}

@Composable
fun OutlinedIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    fillColor: Color,
    outlineColor: Color,
    size: Dp,
    modifier: Modifier = Modifier
) {
    val offsets = listOf(
        Offset(0f, 0f),
        Offset(1f, 0f), Offset(-1f, 0f), Offset(0f, 1f), Offset(0f, -1f),
        Offset(1f, 1f), Offset(-1f, -1f), Offset(1f, -1f), Offset(-1f, 1f)
    )
    Box(modifier = modifier.size(size)) {
        offsets.forEach { off ->
            Icon(imageVector, contentDescription, tint = outlineColor, modifier = Modifier.matchParentSize().offset(off.x.dp, off.y.dp))
        }
        Icon(imageVector, contentDescription, tint = fillColor, modifier = Modifier.matchParentSize())
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TunerScreen(
    tuningData: TuningData,
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    palette: ColorPalette,
    modifier: Modifier = Modifier,
    setAnalyzerBounds: (Double, Double) -> Unit = { _, _ -> },
    setExpectedFrequency: (Double?) -> Unit = { _ -> },
    calibrateAmbient: suspend (Long) -> Double = { _ -> 0.0 },
    onAmbientCalibrated: (Double) -> Unit = {},
    calibrateA4: suspend (Long) -> Double? = { _ -> null },
    onStrictInTune: () -> Unit = {},
    onTestTuneSound: () -> Unit = {},
    selectedInstrument: Instrument,
    selectedTuning: Tuning,
    manualStringIndex: Int?,
    onInstrumentChange: (Instrument) -> Unit,
    onTuningChange: (Tuning) -> Unit,
    onManualStringIndexChange: (Int?) -> Unit
) {
    // Controlled from Activity via persistence

    var instrumentMenuExpanded by remember { mutableStateOf(false) }
    var tuningMenuExpanded by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }

    // Auto-latch to a likely string in Auto mode
    var autoLatchedIndex by remember(selectedInstrument, selectedTuning) { mutableStateOf<Int?>(null) }
    var autoLatchScore by remember(selectedInstrument, selectedTuning) { mutableStateOf(0) }
    val autoLatchThreshold = 4

    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings = settings,
            onDismiss = { showSettingsDialog = false },
            onSettingsChange = onSettingsChange,
            calibrateAmbient = calibrateAmbient,
            onAmbientCalibrated = onAmbientCalibrated,
            calibrateA4 = calibrateA4,
            onTestTuneSound = onTestTuneSound
        )
    }

    val (displayNote, deviation) = remember(tuningData, manualStringIndex, selectedTuning, settings.notationSystem) {
        val targetNoteName = manualStringIndex?.let { selectedTuning.notes.getOrNull(it) }

        val finalNote = targetNoteName ?: tuningData.note
        val finalDeviation = if (targetNoteName != null && tuningData.frequency > 0) {
            MusicTheory.calculateDeviationInHz(tuningData.frequency, targetNoteName)
        } else {
            tuningData.deviationInHz
        }
        
        val noteText = if (settings.notationSystem == NotationSystem.SOLFEGE) solfegeMap[finalNote] ?: finalNote else finalNote
        Pair(noteText, finalDeviation)
    }

    val closestStringText = remember(tuningData.note, tuningData.frequency, selectedTuning) {
        val notes = selectedTuning.notes
        val detectedNote = tuningData.note
        if (tuningData.frequency > 0 && notes.contains(detectedNote)) {
            val indices = notes.indices.filter { notes[it] == detectedNote }
            val chosenIndex = if (indices.size == 1) {
                indices.first()
            } else {
                // Heuristic: for duplicated notes (e.g., E/E in guitar), pick high vs low by frequency
                if ((tuningData.frequency) > 200.0) indices.last() else indices.first()
            }
            val stringNumber = notes.size - chosenIndex
            "${stringNumber}ª Cuerda ($detectedNote)"
        } else {
            ""
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(id = R.drawable.nas_logo),
                            contentDescription = "Logo NASTuner",
                            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp))
                        )
                        Spacer(Modifier.width(8.dp))
                        // Auto-outline for title when accent/background are similar and both dark or both light
                        val accentLumT = palette.accent.luminance()
                        val bgLumT = palette.background.luminance()
                        val bothDarkT = accentLumT < 0.35f && bgLumT < 0.35f
                        val bothLightT = accentLumT > 0.70f && bgLumT > 0.70f
                        val lumCloseT = kotlin.math.abs(accentLumT - bgLumT) < 0.08f
                        val aiT = palette.accent.toArgb(); val biT = palette.background.toArgb()
                        val arT = (aiT shr 16) and 0xFF; val agT = (aiT shr 8) and 0xFF; val abT = aiT and 0xFF
                        val brT = (biT shr 16) and 0xFF; val bgcT = (biT shr 8) and 0xFF; val bbT = biT and 0xFF
                        val rgbSumDiffT = kotlin.math.abs(arT - brT) + kotlin.math.abs(agT - bgcT) + kotlin.math.abs(abT - bbT)
                        val colorsCloseT = rgbSumDiffT < 60
                        val recommendT = (aiT == biT) || ((lumCloseT || colorsCloseT) && (bothDarkT || bothLightT))
                        val outlineColorT = when {
                            recommendT && bothDarkT -> Color.White.copy(alpha = 0.85f)
                            recommendT && bothLightT -> Color.Black.copy(alpha = 0.85f)
                            else -> Color.Transparent
                        }
                        val titleFs = 20.sp
                        if (outlineColorT != Color.Transparent) {
                            OutlinedText(text = "NASTuner", fillColor = palette.accent, outlineColor = outlineColorT, fontSize = titleFs, fontWeight = FontWeight.Normal)
                        } else {
                            Text("NASTuner", color = palette.accent, fontSize = titleFs)
                        }
                    }
                },
                actions = {
                    // Outline for settings gear icon (not the button)
                    val accentLumA = palette.accent.luminance(); val bgLumA = palette.background.luminance()
                    val bothDarkA = accentLumA < 0.35f && bgLumA < 0.35f
                    val bothLightA = accentLumA > 0.70f && bgLumA > 0.70f
                    val aiA = palette.accent.toArgb(); val biA = palette.background.toArgb()
                    val recommendA = (aiA == biA)
                    IconButton(onClick = { showSettingsDialog = true }) {
                        if (recommendA) {
                            val outColor = if (bothDarkA) Color.White else Color.Black
                            OutlinedIcon(Icons.Default.Settings, "Ajustes", fillColor = palette.accent, outlineColor = outColor, size = 24.dp)
                        } else {
                            Icon(Icons.Default.Settings, "Ajustes", tint = palette.accent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        },
        containerColor = palette.background
    ) { paddingValues ->
        Column(
            modifier = modifier.fillMaxSize().padding(paddingValues),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceAround
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Auto-outline ONLY when accent and background are exactly the same color
                val accentLum = palette.accent.luminance()
                val bgLum = palette.background.luminance()
                val bothDark = accentLum < 0.35f && bgLum < 0.35f
                val bothLight = accentLum > 0.70f && bgLum > 0.70f
                val ai = palette.accent.toArgb(); val bi = palette.background.toArgb()
                val recommend = (ai == bi)
                val effectiveOutline = if (settings.textOutline == OutlineStyle.NONE && recommend) {
                    if (bothDark) OutlineStyle.LIGHT else if (bothLight) OutlineStyle.DARK else {
                        // If equal but not clearly dark/light, pick based on background luminance
                        if (bgLum < 0.5f) OutlineStyle.LIGHT else OutlineStyle.DARK
                    }
                } else settings.textOutline
                val outlineColor = when (effectiveOutline) {
                    OutlineStyle.LIGHT -> Color.White.copy(alpha = 0.95f)
                    OutlineStyle.DARK -> Color.Black.copy(alpha = 0.95f)
                    else -> Color.Transparent
                }
                Crossfade(targetState = displayNote, label = "noteCrossfade") { noteText ->
                    if (effectiveOutline != OutlineStyle.NONE) {
                        OutlinedText(text = noteText, fillColor = palette.accent, outlineColor = outlineColor, fontSize = 150.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(text = noteText, fontSize = 150.sp, fontWeight = FontWeight.Bold, color = palette.accent)
                    }
                }
                if (manualStringIndex == null && closestStringText.isNotEmpty()) {
                    if (effectiveOutline != OutlineStyle.NONE) {
                        OutlinedText(text = closestStringText, fillColor = palette.accent.copy(alpha = 0.8f), outlineColor = outlineColor, fontSize = 20.sp, fontWeight = FontWeight.Light)
                    } else {
                        Text(text = closestStringText, fontSize = 20.sp, fontWeight = FontWeight.Light, color = palette.accent.copy(alpha = 0.8f))
                    }
                }
            }

            TuningIndicator(
                deviationInHz = deviation,
                accentColor = palette.accent,
                backgroundColor = palette.background,
                settings = settings,
                tuningData = tuningData,
                onStrictInTune = onStrictInTune
            )
            
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 16.dp)) {
                StringSelector(selectedTuning, manualStringIndex, onManualStringIndexChange, palette.accent, palette.background)
                Spacer(Modifier.height(16.dp))
                InstrumentAndTuningRow(palette, selectedInstrument, instrumentMenuExpanded, { instrumentMenuExpanded = it }, selectedTuning, tuningMenuExpanded, { tuningMenuExpanded = it }) { newInstrument, newTuning ->
                    onInstrumentChange(newInstrument)
                    onTuningChange(newTuning)
                    onManualStringIndexChange(null)
                }
            }
        }
    }

    // Enganchar analizador con rangos del instrumento; usar frecuencia esperada si hay cuerda manual
    LaunchedEffect(selectedInstrument) {
        instrumentFrequencyRanges[selectedInstrument]?.let { (minHz, maxHz) ->
            setAnalyzerBounds(minHz, maxHz)
        }
    }
    // Actualizar frecuencia esperada: manda manual; si no, auto‑latch mejorado cuando está estable
    LaunchedEffect(manualStringIndex, selectedInstrument, selectedTuning, tuningData.frequency) {
        val bounds = instrumentFrequencyRanges[selectedInstrument]
        if (manualStringIndex != null && bounds != null) {
            val noteName = selectedTuning.notes.getOrNull(manualStringIndex!!)
            if (noteName != null) {
                val expected = MusicTheory.nearestFrequencyForNoteInRange(noteName, bounds.first, bounds.second, preferAroundHz = null)
                setExpectedFrequency(expected)
            } else {
                setExpectedFrequency(null)
            }
        } else {
            // Auto‑latch: elige cuerda probable por cents + penaliza saltos de octava + premio continuidad
            if (bounds != null && tuningData.frequency > 0) {
                val fDet = tuningData.frequency
                val baseFs = selectedTuning.notes.map { n -> MusicTheory.nearestFrequencyForNoteInRange(n, bounds.first, bounds.second) }
                fun foldToNear(base: Double, f: Double): Double {
                    var x = f
                    while (x > base * 1.7) x /= 2.0
                    while (x < base * 0.6) x *= 2.0
                    return x
                }
                val candidates = baseFs.mapIndexed { idx, baseF ->
                    val fNear = foldToNear(baseF, fDet)
                    val cents = if (fNear > 0.0 && baseF > 0.0) 1200.0 * kotlin.math.log2(fNear / baseF) else 1e9
                    val octaveJumps = kotlin.math.abs(kotlin.math.round(kotlin.math.log2(fDet / baseF))).toInt()
                    val continuityBonus = if (autoLatchedIndex == idx) -10.0 else 0.0
                    val score = kotlin.math.abs(cents) + (25.0 * octaveJumps) + continuityBonus
                    idx to score
                }
                val (bestIdx, _) = candidates.minByOrNull { it.second } ?: (null to null)
                if (bestIdx != null) {
                    if (autoLatchedIndex == bestIdx) {
                        autoLatchScore = (autoLatchScore + 1).coerceAtMost(autoLatchThreshold + 2)
                    } else {
                        autoLatchScore = (autoLatchScore - 1).coerceAtLeast(0)
                    }
                    if (autoLatchedIndex == null && autoLatchScore >= autoLatchThreshold) {
                        autoLatchedIndex = bestIdx
                    }
                    // Al latchear, fijar expectedFreq de esa cuerda
                    val activeIdx = autoLatchedIndex
                    if (activeIdx != null) {
                        val noteName = selectedTuning.notes[activeIdx]
                        val expected = MusicTheory.nearestFrequencyForNoteInRange(noteName, bounds.first, bounds.second, preferAroundHz = null)
                        setExpectedFrequency(expected)
                    } else {
                        setExpectedFrequency(null)
                    }
                }
                // En silencio, reiniciar latch
                if (tuningData.frequency <= 0) {
                    autoLatchedIndex = null
                    autoLatchScore = 0
                }
            } else {
                setExpectedFrequency(null)
                autoLatchedIndex = null
                autoLatchScore = 0
            }
        }
    }
}

@Composable
fun InstrumentAndTuningRow(
    palette: ColorPalette,
    selectedInstrument: Instrument,
    instrumentMenuExpanded: Boolean,
    onInstrumentMenuExpanded: (Boolean) -> Unit,
    selectedTuning: Tuning,
    tuningMenuExpanded: Boolean,
    onTuningMenuExpanded: (Boolean) -> Unit,
    onTuningSelected: (Instrument, Tuning) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Instrument Selector
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box {
                // Outline for instrument icon (not the button) when accent/background are similar
                val accentLumI = palette.accent.luminance(); val bgLumI = palette.background.luminance()
                val bothDarkI = accentLumI < 0.35f && bgLumI < 0.35f
                val bothLightI = accentLumI > 0.70f && bgLumI > 0.70f
                val aiI = palette.accent.toArgb(); val biI = palette.background.toArgb()
                val recommendI = (aiI == biI)
                IconButton(onClick = { onInstrumentMenuExpanded(true) }) {
                    if (recommendI) {
                        val outColor = if (bothDarkI) Color.White else Color.Black
                        OutlinedIcon(selectedInstrument.icon, "Seleccionar instrumento", fillColor = palette.accent, outlineColor = outColor, size = 48.dp)
                    } else {
                        Icon(selectedInstrument.icon, contentDescription = "Seleccionar instrumento", tint = palette.accent, modifier = Modifier.size(48.dp))
                    }
                }
                DropdownMenu(expanded = instrumentMenuExpanded, onDismissRequest = { onInstrumentMenuExpanded(false) }) {
                    Instrument.values().forEach { instrument ->
                        DropdownMenuItem(text = { Text(instrument.instrumentName) }, onClick = {
                            onTuningSelected(instrument, instrumentTunings[instrument]!!.first())
                            onInstrumentMenuExpanded(false)
                        })
                    }
                }
            }
            Text(text = selectedInstrument.instrumentName, color = palette.accent, fontSize = 14.sp)
        }

        Spacer(modifier = Modifier.width(24.dp))

        // Tuning Selector
        Box {
            // Tuning selector: outline inner content (text + dropdown icon), not the container
            val accentLumTn = palette.accent.luminance(); val bgLumTn = palette.background.luminance()
            val bothDarkTn = accentLumTn < 0.35f && bgLumTn < 0.35f
            val bothLightTn = accentLumTn > 0.70f && bgLumTn > 0.70f
            val aiTn = palette.accent.toArgb(); val biTn = palette.background.toArgb()
            val recommendTn = (aiTn == biTn)
            Row(
                modifier = Modifier
                    .clickable { onTuningMenuExpanded(true) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                val outlineColorTn = if (bothDarkTn) Color.White else Color.Black
                if (recommendTn) {
                    OutlinedText(text = selectedTuning.name, fillColor = palette.accent, outlineColor = outlineColorTn, fontSize = 24.sp, fontWeight = FontWeight.Normal)
                    OutlinedIcon(Icons.Default.ArrowDropDown, contentDescription = "Seleccionar afinación", fillColor = palette.accent, outlineColor = outlineColorTn, size = 48.dp)
                } else {
                    Text(selectedTuning.name, color = palette.accent, fontSize = 24.sp)
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Seleccionar afinación", tint = palette.accent, modifier = Modifier.size(48.dp))
                }
            }
            DropdownMenu(expanded = tuningMenuExpanded, onDismissRequest = { onTuningMenuExpanded(false) }) {
                instrumentTunings[selectedInstrument]?.forEach { tuning ->
                    DropdownMenuItem(
                        text = { 
                            Column {
                                Text(tuning.name)
                                Text(tuning.notes.joinToString(" "), style = MaterialTheme.typography.bodySmall, color = LocalContentColor.current.copy(alpha=0.7f))
                            }
                        }, 
                        onClick = {
                            onTuningSelected(selectedInstrument, tuning)
                            onTuningMenuExpanded(false)
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StringSelector(selectedTuning: Tuning, selectedStringIndex: Int?, onStringSelected: (Int?) -> Unit, accentColor: Color, backgroundColor: Color) {
    val strings = selectedTuning.notes.size

    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 16.dp)) {
        item {
            StringChip("Auto", selectedStringIndex == null, accentColor, backgroundColor) { onStringSelected(null) }
        }
        // Show from highest string number down to 1, mapping correctly to notes indices
        items(strings) { i ->
            val stringNumber = strings - i // e.g., 6..1 or 4..1
            val noteIndex = strings - stringNumber // maps 6->0, 1->5 for guitar, 4->0, 1->3 for ukulele
            StringChip("$stringNumber", selectedStringIndex == noteIndex, accentColor, backgroundColor) { onStringSelected(noteIndex) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StringChip(text: String, isSelected: Boolean, accentColor: Color, backgroundColor: Color, onClick: () -> Unit) {
    // Determine if auto-outline is recommended ONLY when accent/background are exactly the same color
    val accentLum = accentColor.luminance()
    val bgLum = backgroundColor.luminance()
    val bothDark = accentLum < 0.35f && bgLum < 0.35f
    val bothLight = accentLum > 0.70f && bgLum > 0.70f
    val ai = accentColor.toArgb(); val bi = backgroundColor.toArgb()
    val recommend = (ai == bi)
    Box(modifier = Modifier
        .clip(RoundedCornerShape(8.dp))
        .background(if (isSelected) accentColor else Color.Transparent)
        .clickable(onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        val fill = if (isSelected) Color.Black else accentColor
        if (recommend) {
            val outColor = if (bothDark) Color.White else Color.Black
            OutlinedText(text = text, fillColor = fill, outlineColor = outColor, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        } else {
            Text(text, color = fill, fontSize = 14.sp)
        }
    }
}


@Composable
fun SettingsDialog(currentSettings: AppSettings, onDismiss: () -> Unit, onSettingsChange: (AppSettings) -> Unit, calibrateAmbient: suspend (Long) -> Double, onAmbientCalibrated: (Double) -> Unit, calibrateA4: suspend (Long) -> Double?, onTestTuneSound: () -> Unit) {
    var tabIndex by remember { mutableStateOf(0) }
    val tabs = listOf("Color", "Sonido", "Visual")
    var colorShuffleSeed by remember { mutableStateOf(0) }

    // When dialog opens, refresh recommendations once regardless of starting tab
    LaunchedEffect(Unit) { colorShuffleSeed++ }

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 500.dp, max = 650.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF2C2C2C))) {
            // Ensure readable text on dark card
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column {
                    androidx.compose.material3.ScrollableTabRow(selectedTabIndex = tabIndex, edgePadding = 12.dp) {
                        tabs.forEachIndexed { index, title ->
                            Tab(selected = tabIndex == index, onClick = {
                                tabIndex = index
                                if (index == 0) colorShuffleSeed++
                            }, text = { Text(title) })
                        }
                    }
                    Column(modifier = Modifier.padding(16.dp)) {
                        when (tabIndex) {
                            0 -> ColorSettingsTab(currentSettings, onSettingsChange, colorShuffleSeed, onShuffle = { colorShuffleSeed++ })
                            1 -> SoundSettingsTab(currentSettings, onSettingsChange, calibrateAmbient, onAmbientCalibrated, calibrateA4, onTestTuneSound)
                            2 -> VisualSettingsTab(currentSettings, onSettingsChange)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColorSettingsTab(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit, shuffleSeed: Int, onShuffle: () -> Unit) {
    var colorTabIndex by remember { mutableStateOf(0) }
    val colorTabs = listOf("Elemento", "Fondo")
    val sourcePalettes = if (settings.isColorblindMode) colorblindRecommendedPalettes else fullPaletteList
    val shuffledPalettes = remember(shuffleSeed, settings.isColorblindMode) { sourcePalettes.shuffled(kotlin.random.Random(shuffleSeed)).take(3) }

    val onColorSelected: (Color) -> Unit = { color ->
        val newPalette = if (colorTabIndex == 0) settings.palette.copy(accent = color) else settings.palette.copy(background = color)
        // Recommend outline by default only if user hasn't set one and colors are exactly equal
        val accentLum = newPalette.accent.luminance()
        val bgLum = newPalette.background.luminance()
        val bothDark = accentLum < 0.35f && bgLum < 0.35f
        val bothLight = accentLum > 0.70f && bgLum > 0.70f
        val ai = newPalette.accent.toArgb(); val bi = newPalette.background.toArgb()
        val recommend = (ai == bi)
        val newOutline = if (settings.textOutline == OutlineStyle.NONE && recommend) {
            if (bothDark) OutlineStyle.LIGHT else if (bothLight) OutlineStyle.DARK else { if (bgLum < 0.5f) OutlineStyle.LIGHT else OutlineStyle.DARK }
        } else settings.textOutline
        onSettingsChange(settings.copy(palette = newPalette, textOutline = newOutline))
    }

    val fullColorPalette = remember {
        val hues = floatArrayOf(0f, 30f, 60f, 120f, 180f, 210f, 270f, 330f)
        val lightnessSteps = List(9) { i -> 0.9f - (i * 0.1f) }
        val grays = List(9) { i -> val l = 0.9f - (i * 0.1f); Color(l, l, l) }
        hues.flatMap { hue -> lightnessSteps.map { light -> Color.hsl(hue, 0.9f, light) } } + grays
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        TabRow(selectedTabIndex = colorTabIndex) { colorTabs.forEachIndexed { index, title -> Tab(selected = index == colorTabIndex, onClick = { colorTabIndex = index }, text = { Text(title) }) } }
        Spacer(Modifier.height(12.dp))

        LazyVerticalGrid(columns = GridCells.Fixed(9), modifier = Modifier.height(220.dp)) {
            items(fullColorPalette) { color ->
                Box(modifier = Modifier.padding(3.dp).aspectRatio(1f).clip(CircleShape).background(color).clickable { onColorSelected(color) }) {
                    val isSelected = (colorTabIndex == 0 && settings.palette.accent == color) || (colorTabIndex == 1 && settings.palette.background == color)
                    if (isSelected && !settings.isColorblindMode) {
                        Box(modifier = Modifier.padding(2.dp).clip(CircleShape).border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Paletas Recomendadas", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onShuffle) {
                Icon(Icons.Filled.Refresh, contentDescription = "Recargar paletas")
            }
        }
        LazyRow(modifier = Modifier.padding(vertical = 8.dp)) {
            items(shuffledPalettes) { palette ->
                Box(modifier = Modifier.padding(horizontal = 4.dp).clickable {
                    val accentLum = palette.accent.luminance()
                    val bgLum = palette.background.luminance()
                    val bothDark = accentLum < 0.35f && bgLum < 0.35f
                    val bothLight = accentLum > 0.70f && bgLum > 0.70f
                    val ai = palette.accent.toArgb(); val bi = palette.background.toArgb()
                    val recommend = (ai == bi)
                    val newOutline = if (settings.textOutline == OutlineStyle.NONE && recommend) {
                        if (bothDark) OutlineStyle.LIGHT else if (bothLight) OutlineStyle.DARK else { if (bgLum < 0.5f) OutlineStyle.LIGHT else OutlineStyle.DARK }
                    } else settings.textOutline
                    onSettingsChange(settings.copy(palette = palette, textOutline = newOutline))
                }) {
                    Row {
                        // Accent (Elemento) on left, Background on right
                        Box(modifier = Modifier.size(35.dp).clip(RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp)).background(palette.accent))
                        Box(modifier = Modifier.size(35.dp).clip(RoundedCornerShape(topEnd = 8.dp, bottomEnd = 8.dp)).background(palette.background))
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // Text outline option between recommended palettes and colorblind switch
        Text("Borde de letras", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(textOutline = OutlineStyle.NONE)) }) {
                RadioButton(selected = settings.textOutline == OutlineStyle.NONE, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text("Sin borde")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(textOutline = OutlineStyle.LIGHT)) }) {
                RadioButton(selected = settings.textOutline == OutlineStyle.LIGHT, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text("Borde claro")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(textOutline = OutlineStyle.DARK)) }) {
                RadioButton(selected = settings.textOutline == OutlineStyle.DARK, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text("Borde oscuro")
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Modo para daltónicos")
            Spacer(Modifier.weight(1f))
            Switch(checked = settings.isColorblindMode, onCheckedChange = { onSettingsChange(settings.copy(isColorblindMode = it)) })
        }
    }
}

@Composable
fun SoundSettingsTab(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit, calibrateAmbient: suspend (Long) -> Double, onAmbientCalibrated: (Double) -> Unit, calibrateA4: suspend (Long) -> Double?, onTestTuneSound: () -> Unit) {
    val scope = rememberCoroutineScope()
    var calibMessage by remember { mutableStateOf<String?>(null) }
    Column {
        Text("Sensibilidad del Micrófono", style = MaterialTheme.typography.titleMedium)
        Slider(value = settings.micSensitivity, onValueChange = { onSettingsChange(settings.copy(micSensitivity = it)) })
        Text(text = "${(settings.micSensitivity * 100).toInt()}%", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text("Sonido al afinar", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Activar")
            Spacer(Modifier.weight(1f))
            Switch(checked = settings.enableTuneSound, onCheckedChange = { onSettingsChange(settings.copy(enableTuneSound = it)) })
        }
        if (settings.enableTuneSound) {
            Spacer(Modifier.height(4.dp))
            Text("Volumen")
            Slider(value = settings.soundVolume, onValueChange = { onSettingsChange(settings.copy(soundVolume = it)) })
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Estilo")
                Spacer(Modifier.width(12.dp))
                listOf(SfxStyle.CRYSTAL to "Cristal", SfxStyle.BEEP to "Beep").forEach { (style, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier
                        .padding(end = 8.dp)
                        .clickable { onSettingsChange(settings.copy(soundStyle = style)) }) {
                        RadioButton(selected = settings.soundStyle == style, onClick = null)
                        Spacer(Modifier.width(6.dp))
                        Text(label)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onTestTuneSound) { Text("Probar sonido") }
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Modo de Precisión", style = MaterialTheme.typography.titleMedium)
        PrecisionMode.values().forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(precisionMode = mode)) }) {
                RadioButton(selected = settings.precisionMode == mode, onClick = null)
                Spacer(Modifier.width(8.dp))
                Text(text = "${mode.displayName} (±${mode.threshold} Hz)")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = settings.adaptiveThreshold, onCheckedChange = { onSettingsChange(settings.copy(adaptiveThreshold = it)) })
            Text("Tolerancia Adaptativa")
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text("Referencia A4", style = MaterialTheme.typography.titleMedium)
        Slider(
            value = settings.referenceA4Hz.toFloat(),
            onValueChange = { onSettingsChange(settings.copy(referenceA4Hz = it.toDouble())) },
            valueRange = 415f..466f,
            steps = (466 - 415 - 1)
        )
        Text(text = "${settings.referenceA4Hz.roundToInt()} Hz", modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)

        Spacer(modifier = Modifier.height(16.dp))
        Text("Calibración", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                calibMessage = "Calibrando ambiente..."
                scope.launch {
                    val ambient = calibrateAmbient(1500)
                    onAmbientCalibrated(ambient)
                    calibMessage = "Ambiente calibrado"
                }
            }) { Text("Calibrar ambiente") }
            Button(onClick = {
                calibMessage = "Autocalibrando A4... Toca un La (A) y mantenlo"
                scope.launch {
                    val a4 = calibrateA4(2000)
                    if (a4 != null) {
                        onSettingsChange(settings.copy(referenceA4Hz = a4))
                        calibMessage = "A4 calibrado a ${"%.1f".format(a4)} Hz"
                    } else {
                        calibMessage = "No se detectó un La (A) estable"
                    }
                }
            }) { Text("Autocalibrar A4") }
        }
        if (calibMessage != null) {
            Spacer(Modifier.height(8.dp))
            Text(calibMessage!!, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        }
    }
}

@Composable
fun NotationSettingsTab(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    Column {
        Text("Sistema de Notación", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(notationSystem = NotationSystem.SCIENTIFIC)) }) {
            RadioButton(selected = settings.notationSystem == NotationSystem.SCIENTIFIC, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text("C, D, E (Científica)")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(notationSystem = NotationSystem.SOLFEGE)) }) {
            RadioButton(selected = settings.notationSystem == NotationSystem.SOLFEGE, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text("Do, Re, Mi (Solfegio)")
        }
    }
}

@Composable
fun VisualSettingsTab(settings: AppSettings, onSettingsChange: (AppSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Notation settings moved here from the Notación tab
        Text("Sistema de Notación", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(notationSystem = NotationSystem.SCIENTIFIC)) }) {
            RadioButton(selected = settings.notationSystem == NotationSystem.SCIENTIFIC, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text("C, D, E (Científica)")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(notationSystem = NotationSystem.SOLFEGE)) }) {
            RadioButton(selected = settings.notationSystem == NotationSystem.SOLFEGE, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text("Do, Re, Mi (Solfegio)")
        }

        Spacer(Modifier.height(8.dp))
        Text("Unidades de guías", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().clickable {
            if (settings.guideUnit != GuideUnit.HZ) {
                // Map current cents selection to equivalent Hz by index (fallback to recommended index 1)
                val hzOptions = listOf(
                    VisualRange(1.0, 2.0),
                    VisualRange(2.0, 4.0),
                    VisualRange(3.0, 5.0),
                    VisualRange(5.0, 10.0)
                )
                val centOptions = listOf(
                    VisualRange(5.0, 10.0),
                    VisualRange(10.0, 20.0),
                    VisualRange(15.0, 30.0),
                    VisualRange(20.0, 40.0)
                )
                val idx = centOptions.indexOf(settings.visualRange).let { if (it >= 0) it else 1 }
                onSettingsChange(settings.copy(guideUnit = GuideUnit.HZ, visualRange = hzOptions[idx]))
            }
        }) {
            RadioButton(selected = settings.guideUnit == GuideUnit.HZ, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text("Hz")
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier=Modifier.fillMaxWidth().clickable {
            if (settings.guideUnit != GuideUnit.CENTS) {
                // Map current Hz selection to equivalent cents by index (fallback to recommended index 1)
                val hzOptions = listOf(
                    VisualRange(1.0, 2.0),
                    VisualRange(2.0, 4.0),
                    VisualRange(3.0, 5.0),
                    VisualRange(5.0, 10.0)
                )
                val centOptions = listOf(
                    VisualRange(5.0, 10.0),
                    VisualRange(10.0, 20.0),
                    VisualRange(15.0, 30.0),
                    VisualRange(20.0, 40.0)
                )
                val idx = hzOptions.indexOf(settings.visualRange).let { if (it >= 0) it else 1 }
                onSettingsChange(settings.copy(guideUnit = GuideUnit.CENTS, visualRange = centOptions[idx]))
            }
        }) {
            RadioButton(selected = settings.guideUnit == GuideUnit.CENTS, onClick = null)
            Spacer(Modifier.width(8.dp))
            Text("Cents")
        }

        Spacer(Modifier.height(8.dp))
        Text("Rango visual (líneas principales)", style = MaterialTheme.typography.titleMedium)
        val hzOptions = listOf(
            VisualRange(1.0, 2.0) to "±1 y ±2 Hz",
            VisualRange(2.0, 4.0) to "±2 y ±4 Hz (Recomendado)",
            VisualRange(3.0, 5.0) to "±3 y ±5 Hz",
            VisualRange(5.0, 10.0) to "±5 y ±10 Hz"
        )
        val centOptions = listOf(
            VisualRange(5.0, 10.0) to "±5 y ±10 cents",
            VisualRange(10.0, 20.0) to "±10 y ±20 cents (Recomendado)",
            VisualRange(15.0, 30.0) to "±15 y ±30 cents",
            VisualRange(20.0, 40.0) to "±20 y ±40 cents"
        )
        val options = if (settings.guideUnit == GuideUnit.HZ) hzOptions else centOptions
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (range, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(visualRange = range)) }) {
                    RadioButton(selected = settings.visualRange == range, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        // Latch duration selector
        Text("Estabilidad de nota", style = MaterialTheme.typography.titleMedium)
        val latchOptions = listOf(
            500L to "0.5 s",
            1000L to "1 s",
            2000L to "2 s (Recomendado)"
        )
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            latchOptions.forEach { (ms, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSettingsChange(settings.copy(noteLatchMs = ms)) }) {
                    RadioButton(selected = settings.noteLatchMs == ms, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Text(label)
                }
            }
        }
    }
}

@Composable
fun TuningIndicator(deviationInHz: Double, accentColor: Color, backgroundColor: Color, settings: AppSettings, tuningData: TuningData, onStrictInTune: () -> Unit) {
    val indicatorColor = accentColor.copy(alpha = 0.95f)
    val indicatorHeight = 250.dp

    // Dos niveles de umbral: visual (más permisivo) vs estricto (práctico)
    val baseThreshold = if (settings.adaptiveThreshold) {
        when {
            tuningData.frequency < 80 -> 2.5
            tuningData.frequency in 80.0..500.0 -> 1.5
            else -> 1.0
        }
    } else {
        settings.precisionMode.threshold
    }
    val strictThresholdHz = baseThreshold * 0.75 // stricter practical pass
    val visualThresholdHz = baseThreshold * 1.6 // more permissive for UI feel

    val isInTuneVisual = abs(deviationInHz) < visualThresholdHz
    val isInTuneStrict = abs(deviationInHz) < strictThresholdHz
    var prevStrict by remember { mutableStateOf(false) }
    LaunchedEffect(isInTuneStrict) {
        if (!prevStrict && isInTuneStrict) {
            onStrictInTune()
        }
        prevStrict = isInTuneStrict
    }

    val baseConfidence = tuningData.confidence.coerceIn(0.0, 1.0).toFloat()
    val animatedRadius by animateFloatAsState(
        targetValue = when {
            isInTuneStrict -> 30f + 10f * baseConfidence
            isInTuneVisual -> 24f + 9f * baseConfidence
            else -> 18f + 8f * baseConfidence
        },
        label = "radiusAnimation"
    )
    val animatedGlow by animateFloatAsState(
        targetValue = when {
            isInTuneStrict -> 1.1f + 0.6f * baseConfidence
            isInTuneVisual -> 0.7f + 0.6f * baseConfidence
            else -> 0.3f + 0.5f * baseConfidence
        },
        label = "glowAnimation"
    )
    val animatedStrokeWidth by animateFloatAsState(targetValue = if (isInTuneVisual) 8f else 5f, label = "strokeAnimation")

    // Selección de unidad de desviación (Hz o cents)
    val unitIsHz = settings.guideUnit == GuideUnit.HZ
    val minor = settings.visualRange.minor
    val major = settings.visualRange.major
    val targetHz = if (tuningData.frequency > 0) tuningData.frequency - deviationInHz else 0.0
    val deviationUnits = if (unitIsHz) deviationInHz else {
        if (tuningData.frequency > 0 && targetHz > 0.0) 1200.0 * log2(tuningData.frequency / targetHz) else 0.0
    }
    // Suavizado visual y zona muerta para reducir jitter (más conservador)
    val deadbandUnits = if (unitIsHz) 0.2 else 4.0
    var visualUnits by remember { mutableStateOf(0.0) }
    LaunchedEffect(deviationUnits) {
        val alpha = 0.15
        visualUnits = visualUnits + alpha * (deviationUnits - visualUnits)
    }
    val displayUnits = if (kotlin.math.abs(visualUnits) < deadbandUnits) 0.0 else visualUnits
    val normalizedOffset = (displayUnits / major).coerceIn(-1.0, 1.0).toFloat()
    // Límite adicional de velocidad para acotar el movimiento por cuadro
    var smoothedOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(normalizedOffset) {
        // Slightly higher damping at low confidence (more smoothing), more agile when confident
        val maxStep = 0.04f + 0.06f * baseConfidence
        val delta = (normalizedOffset - smoothedOffset).coerceIn(-maxStep, maxStep)
        smoothedOffset += delta
    }
    val animatedOffset by animateFloatAsState(
        targetValue = smoothedOffset,
        animationSpec = spring(dampingRatio = 1.2f, stiffness = Spring.StiffnessVeryLow),
        label = "offsetAnimation"
    )

    // Calcular un color guía que contraste con el fondo
    val bgLum = backgroundColor.luminance()
    val guideBase = if (bgLum < 0.5f) Color.White else Color.Black
    // Alfa adaptativo: fondos muy oscuros/muy claros -> alfa medio; fondos intermedios -> alfa mayor
    val guideAlpha = when {
        bgLum < 0.1f -> 0.6f
        bgLum < 0.3f -> 0.8f
        bgLum < 0.7f -> 0.7f
        bgLum < 0.9f -> 0.8f
        else -> 0.6f
    }
    val guideColor = guideBase.copy(alpha = guideAlpha)

    BoxWithConstraints(modifier = Modifier.height(indicatorHeight).fillMaxWidth(0.9f)) {
        val centerYDp = maxHeight / 2
    val mainGuides = listOf(-major, -minor, minor, major)
        val fineStep = if (unitIsHz) 1.0 else 5.0
        val fineGuides = buildList<Double> {
            var v = fineStep
            while (v < major) {
                if (kotlin.math.abs(v - minor) > 1e-6) add(v)
                v += fineStep
            }
        }

        Canvas(modifier = Modifier.matchParentSize()) {
            val centerY = size.height / 2
            val circleCenterX = size.width / 2
            val circleOffsetY = centerY - (animatedOffset * centerY)
            val circleCenter = Offset(x = circleCenterX, y = circleOffsetY)

            // Línea principal (precisión)
            val mainLineColor = indicatorColor
            drawLine(color = mainLineColor, start = Offset(x = 0f, y = centerY), end = Offset(x = size.width, y = centerY), strokeWidth = animatedStrokeWidth)

            // Halo extra cuando está visualmente afinado; más fuerte si está estrictamente afinado
            if (isInTuneVisual) {
                val haloAlpha = if (isInTuneStrict) (0.24f + 0.25f * baseConfidence) else (0.15f + 0.2f * baseConfidence)
                drawLine(color = accentColor.copy(alpha = haloAlpha), start = Offset(x = 0f, y = centerY - 6f), end = Offset(x = size.width, y = centerY - 6f), strokeWidth = 6f)
                drawLine(color = accentColor.copy(alpha = haloAlpha), start = Offset(x = 0f, y = centerY + 6f), end = Offset(x = size.width, y = centerY + 6f), strokeWidth = 6f)
            }

            // Líneas guía más cortas a la izquierda
            mainGuides.forEach { g ->
                val pos = (g / major).toFloat()
                val y = centerY - (centerY * pos)
                val leftShort = size.width * 0.12f
                drawLine(color = guideColor, start = Offset(x = leftShort, y = y), end = Offset(x = size.width, y = y), strokeWidth = if (abs(g) == major) 2f else 1.4f)
            }
            // Guías finas
            fineGuides.forEach { g ->
                val pos = (g / major).toFloat()
                val y = centerY - (centerY * pos)
                val leftShort = size.width * 0.2f
                drawLine(color = guideColor.copy(alpha = 0.6f), start = Offset(x = leftShort, y = y), end = Offset(x = size.width, y = y), strokeWidth = 0.8f)
                // mirror negative
                val y2 = centerY + (centerY * pos)
                drawLine(color = guideColor.copy(alpha = 0.6f), start = Offset(x = leftShort, y = y2), end = Offset(x = size.width, y = y2), strokeWidth = 0.8f)
            }

            // Halo y círculo
            if (animatedGlow > 0) {
                drawCircle(
                    brush = Brush.radialGradient(colors = listOf(accentColor.copy(alpha = 0.55f * animatedGlow), Color.Transparent), center = circleCenter, radius = animatedRadius * 3.2f * animatedGlow),
                    radius = animatedRadius * 3.0f * animatedGlow,
                    center = circleCenter
                )
            }
            drawCircle(color = accentColor, radius = animatedRadius, center = circleCenter)
        }

        // Dibujar etiquetas como composables alineadas a la izquierda
        mainGuides.forEach { g ->
            val pos = (g / major).toFloat()
            val yDp = centerYDp - (centerYDp * pos)
            val label = if (unitIsHz) {
                if (g > 0) "+${g.toInt()} Hz" else "${g.toInt()} Hz"
            } else {
                if (g > 0) "+${g.toInt()} ¢" else "${g.toInt()} ¢"
            }
            Text(text = label, color = guideColor, fontSize = 12.sp, modifier = Modifier.offset(x = 8.dp, y = yDp - 8.dp))
        }
    }

    // Pista direccional (sutil): muestra "⬆ Alto" cuando está agudo, "⬇ Bajo" cuando está grave, con alfa según magnitud de desviación
    val hintAlpha = ((kotlin.math.abs(displayUnits) / major).coerceIn(0.0, 1.0) * (0.2 + 0.6 * baseConfidence)).toFloat()
    val hintText = when {
        displayUnits > 0.25 * minor -> "Alto"
        displayUnits < -0.25 * minor -> "Bajo"
        else -> null
    }
    if (hintText != null) {
        val arrow = if (hintText == "Alto") "⬆" else "⬇"
        Text(
            text = "$arrow $hintText",
            color = accentColor.copy(alpha = hintAlpha),
            modifier = Modifier.fillMaxWidth().padding(end = 16.dp),
            textAlign = TextAlign.End
        )
    }
}

object MusicTheory {
    private var REF_A4 = 440.0
    val NOTES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    fun setReferenceA4(hz: Double) { if (hz.isFinite() && hz in 350.0..500.0) REF_A4 = hz }
    fun getReferenceA4(): Double = REF_A4
    fun estimateOctave(frequency: Double): Int {
        if (frequency <= 0.0) return 4
        val nEst = (A4_INDEX + 12 * log2(frequency / REF_A4)).roundToInt()
        return floor(nEst / 12.0).toInt()
    }
    
    fun frequencyToNote(frequency: Double): TuningData {
        if (frequency <= 0.0) return TuningData(frequency = frequency)
        val noteNumber = 12 * log2(frequency / REF_A4)
        val roundedNoteIndex = (A4_INDEX + noteNumber).roundToInt()
        val noteIndex = (roundedNoteIndex % 12).let { if (it < 0) it + 12 else it }
        val note = NOTES[noteIndex]
        val idealFrequency = REF_A4 * 2.0.pow((roundedNoteIndex - A4_INDEX) / 12.0)
        val deviationInHz = frequency - idealFrequency
        
        return TuningData(note, deviationInHz, frequency)
    }

    fun calculateDeviationInHz(frequency: Double, targetNoteName: String): Double {
        val targetNoteIndex = NOTES.indexOf(targetNoteName)
        if(targetNoteIndex == -1) return 0.0

        // Find nearest octave of the target note around the detected frequency
        val nEst = (A4_INDEX + 12 * log2(frequency / REF_A4)).roundToInt()
        val kBase = floor((nEst - targetNoteIndex) / 12.0).toInt()
        val candidates = listOf(
            targetNoteIndex + 12 * kBase,
            targetNoteIndex + 12 * (kBase + 1)
        )
        val targetIdealIndex = candidates.minByOrNull { idx ->
            val f = REF_A4 * 2.0.pow((idx - A4_INDEX) / 12.0)
            kotlin.math.abs(f - frequency)
        } ?: (targetNoteIndex + 12 * kBase)
        val targetIdealFrequency = REF_A4 * 2.0.pow((targetIdealIndex - A4_INDEX) / 12.0)

        return frequency - targetIdealFrequency
    }

    // Find nearest frequency for a note name within a range, optionally biased around a preferred frequency
    fun nearestFrequencyForNoteInRange(noteName: String, minHz: Double, maxHz: Double, preferAroundHz: Double? = null): Double {
        val idx = NOTES.indexOf(noteName)
        if (idx == -1) return 0.0
        val candidates = mutableListOf<Double>()
        // Scan octaves around A4 index
        for (k in -36..36) { // enough range
            val noteIndex = A4_INDEX + (idx - NOTES.indexOf("A")) + k
            val f = REF_A4 * 2.0.pow((noteIndex - A4_INDEX) / 12.0)
            if (f in minHz..maxHz) candidates.add(f)
        }
        if (candidates.isEmpty()) return 0.0
        val target = preferAroundHz ?: ((minHz + maxHz) / 2.0)
        return candidates.minByOrNull { kotlin.math.abs(it - target) } ?: candidates.first()
    }
}