package com.audioviz.demo

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.audioviz.audio.audioPlatformName
import com.audioviz.audio.createMicrophoneSource
import com.audioviz.compose.AudioVisualizer
import com.audioviz.compose.VisualizerState
import com.audioviz.compose.rememberVisualizerState
import com.audioviz.compose.shader.shaderBackendName
import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.capture.AudioSource
import com.audioviz.core.capture.AudioSourceState
import com.audioviz.core.capture.SyntheticAudioSource

/**
 * The sample application.
 *
 * It is intentionally more than a screenshot: every knob that matters for
 * tuning is exposed live, so the manual test procedure in the README ("speak
 * quietly, then normally, then loudly") can be run against any combination of
 * renderer, palette, character preset and sensitivity without a rebuild.
 */
@Composable
fun DemoApp() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        var renderer by remember { mutableStateOf(DemoRenderer.BLOB) }
        var palette by remember { mutableStateOf(DemoPalette.OCEAN) }
        var character by remember { mutableStateOf(DemoCharacter.DEFAULT) }
        var sourceKind by remember { mutableStateOf(DemoSource.MICROPHONE) }
        var sensitivity by remember { mutableStateOf(1f) }
        var gateMargin by remember { mutableStateOf(9f) }
        var showControls by remember { mutableStateOf(true) }

        val access = rememberMicrophoneAccess()

        val source: AudioSource = remember(sourceKind) {
            when (sourceKind) {
                DemoSource.MICROPHONE -> createMicrophoneSource()
                DemoSource.SYNTHETIC -> SyntheticAudioSource(levelDb = -26f)
            }
        }

        val analyzerConfig = remember(sensitivity, gateMargin) {
            AnalyzerConfig(
                sensitivity = sensitivity,
                gateOpenMarginDb = gateMargin,
                // Keep the hysteresis gap proportional as the threshold moves.
                gateCloseMarginDb = (gateMargin - 4f).coerceAtLeast(1f),
            )
        }

        val state = rememberVisualizerState(
            source = source,
            analyzerConfig = analyzerConfig,
            animationConfig = character.config,
            palette = palette.palette,
            autoStart = access.granted || sourceKind == DemoSource.SYNTHETIC,
        )

        val activeRenderer = remember(renderer) { renderer.create() }

        Box(Modifier.fillMaxSize()) {
            AudioVisualizer(
                renderer = activeRenderer,
                state = state,
                modifier = Modifier.fillMaxSize(),
                radiusFraction = if (renderer == DemoRenderer.RIBBON) 0.9f else 0.58f,
            )

            StatusBar(
                text = buildStatusLine(state.status.state, state.status.message, source.sampleRate),
                fps = state.framesPerSecond,
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
            )

            if (!access.granted && sourceKind == DemoSource.MICROPHONE) {
                PermissionPrompt(
                    denied = access.denied,
                    onRequest = { access.request() },
                    onUseDemoSignal = { sourceKind = DemoSource.SYNTHETIC },
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TextButton(onClick = { showControls = !showControls }) {
                    Text(if (showControls) "Hide controls" else "Show controls")
                }
                if (showControls) {
                    ControlPanel(
                        renderer = renderer,
                        onRenderer = { renderer = it },
                        palette = palette,
                        onPalette = { palette = it },
                        character = character,
                        onCharacter = { character = it },
                        sourceKind = sourceKind,
                        onSource = { sourceKind = it },
                        sensitivity = sensitivity,
                        onSensitivity = { sensitivity = it },
                        gateMargin = gateMargin,
                        onGateMargin = { gateMargin = it },
                        state = state,
                    )
                }
            }
        }
    }
}

private fun buildStatusLine(state: AudioSourceState, message: String?, sampleRate: Int): String {
    val base = when (state) {
        AudioSourceState.IDLE -> "idle"
        AudioSourceState.STARTING -> "starting"
        AudioSourceState.RUNNING -> "listening @ ${sampleRate} Hz"
        AudioSourceState.PERMISSION_DENIED -> "microphone permission denied"
        AudioSourceState.UNAVAILABLE -> "no microphone available"
        AudioSourceState.ERROR -> "error"
    }
    return if (message != null && state != AudioSourceState.RUNNING) "$base - $message" else base
}

@Composable
private fun StatusBar(text: String, fps: Float, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(10.dp)),
        color = Color.White.copy(alpha = 0.07f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(
                text = text,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "${audioPlatformName()} - ${shaderBackendName()} - ${fps.toInt()} fps",
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun PermissionPrompt(
    denied: Boolean,
    onRequest: () -> Unit,
    onUseDemoSignal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.padding(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.08f)),
    ) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (denied) "Microphone access was refused" else "This demo listens to your microphone",
                color = Color.White,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Row {
                TextButton(onClick = onRequest) { Text("Allow microphone") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onUseDemoSignal) { Text("Use demo signal") }
            }
        }
    }
}

@Composable
private fun ControlPanel(
    renderer: DemoRenderer,
    onRenderer: (DemoRenderer) -> Unit,
    palette: DemoPalette,
    onPalette: (DemoPalette) -> Unit,
    character: DemoCharacter,
    onCharacter: (DemoCharacter) -> Unit,
    sourceKind: DemoSource,
    onSource: (DemoSource) -> Unit,
    sensitivity: Float,
    onSensitivity: (Float) -> Unit,
    gateMargin: Float,
    onGateMargin: (Float) -> Unit,
    state: VisualizerState,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.55f)),
    ) {
        Column(
            Modifier
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LevelMeter(state)

            ChipRow("Shape", DemoRenderer.entries, renderer, { it.label }, onRenderer)
            Text(
                text = renderer.description,
                color = Color.White.copy(alpha = 0.5f),
                fontSize = 11.sp,
            )
            ChipRow("Palette", DemoPalette.entries, palette, { it.label }, onPalette)
            ChipRow("Character", DemoCharacter.entries, character, { it.label }, onCharacter)
            ChipRow("Source", DemoSource.entries, sourceKind, { it.label }, onSource)

            LabelledSlider(
                label = "Sensitivity",
                value = sensitivity,
                valueText = formatOneDecimal(sensitivity),
                range = 0.4f..2.2f,
                onChange = onSensitivity,
            )
            LabelledSlider(
                label = "Gate above noise floor",
                value = gateMargin,
                valueText = "${formatOneDecimal(gateMargin)} dB",
                range = 3f..24f,
                onChange = onGateMargin,
            )
        }
    }
}

@Composable
private fun <T> ChipRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { option ->
                FilterChip(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    label = { Text(labelOf(option), fontSize = 12.sp) },
                )
            }
        }
    }
}

@Composable
private fun LabelledSlider(
    label: String,
    value: Float,
    valueText: String,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
            Text(valueText, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

/**
 * Normalized level, raw level and gate state, repainted every frame.
 *
 * Worth having in any host during tuning: it makes it obvious whether a
 * non-reacting visual is a gate that never opens or a normalizer that has not
 * adapted yet.
 *
 * Note the shape of it — `state.frameTick` is read inside the `Canvas` draw
 * lambda, not during composition, so this repaints at frame rate without
 * recomposing the control panel around it.
 */
@Composable
private fun LevelMeter(state: VisualizerState) {
    Column {
        Text("Level / gate", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            state.frameTick
            val params = state.params

            val radius = size.height * 0.5f
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                cornerRadius = CornerRadius(radius, radius),
            )
            val level = params.level.coerceIn(0f, 1f)
            if (level > 0.001f) {
                drawRoundRect(
                    color = Color(0xFF4FA8FF).copy(alpha = 0.35f + 0.65f * params.gate),
                    size = Size(size.width * level, size.height),
                    cornerRadius = CornerRadius(radius, radius),
                )
            }
            // A tick for the transient, so onsets are visible as well as level.
            if (params.pulse > 0.02f) {
                val x = (size.width * params.pulse).coerceIn(1f, size.width - 1f)
                drawLine(
                    color = Color.White.copy(alpha = 0.5f * params.pulse),
                    start = Offset(x, 0f),
                    end = Offset(x, size.height),
                    strokeWidth = 2f,
                )
            }
        }
    }
}

private fun formatOneDecimal(value: Float): String {
    val scaled = (value * 10f + if (value >= 0f) 0.5f else -0.5f).toInt()
    return "${scaled / 10}.${(if (scaled < 0) -scaled else scaled) % 10}"
}
