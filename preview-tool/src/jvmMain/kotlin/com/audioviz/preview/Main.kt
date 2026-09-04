package com.audioviz.preview

import com.audioviz.core.analysis.AnalyzerConfig
import com.audioviz.core.anim.AnimationConfig
import com.audioviz.core.capture.SilentAudioSource
import com.audioviz.core.capture.SyntheticAudioSource
import com.audioviz.core.color.VisualPalette
import com.audioviz.core.geometry.RadialShape
import com.audioviz.core.geometry.RadialShapes
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

private const val CELL = 300
private const val GUTTER = 150

/**
 * Renders contact sheets of the visualizer's geometry, headlessly.
 *
 * Two sheets:
 *
 *  - `response.png` — one row per input level (silence, quiet, normal, loud),
 *    six frames across roughly two seconds. Reading down a column shows how the
 *    same instant differs with loudness; reading across a row shows the motion.
 *  - `shapes.png` — the same audio and the same animation parameters through
 *    five different [RadialShape]s. Every row is the identical pipeline; only
 *    the base geometry differs.
 */
fun main(args: Array<String>) {
    val outputDir = File(args.firstOrNull() ?: "build/preview").apply { mkdirs() }

    renderResponseSheet(File(outputDir, "response.png"))
    renderShapeSheet(File(outputDir, "shapes.png"))
    renderIdleSheet(File(outputDir, "idle.png"))

    println("wrote contact sheets to ${outputDir.absolutePath}")
}

/** One row per input level; columns are successive moments. */
private fun renderResponseSheet(target: File) {
    val levels = listOf<Pair<String, (FloatArray) -> Unit>>(
        "silence" to silence(),
        "quiet  -42 dBFS" to speech(-42f),
        "normal -28 dBFS" to speech(-28f),
        "loud   -14 dBFS" to speech(-14f),
    )
    val columns = 6
    val framesBetween = 21 // ~0.35 s at 60 fps

    val sheet = newSheet(GUTTER + columns * CELL, levels.size * CELL)
    val g = sheet.createGraphics().withQualityHints()

    levels.forEachIndexed { row, (label, fill) ->
        val driver = Driver()
        val painter = BlobPainter(RadialShapes.Circle, VisualPalette.OceanBlue)

        // Let the adaptive stages and the springs settle before sampling.
        repeat((4f * 60).toInt()) { painter.advance(driver.step(fill)) }

        for (column in 0 until columns) {
            var params = driver.step(fill)
            painter.advance(params)
            repeat(framesBetween - 1) {
                params = driver.step(fill)
                painter.advance(params)
            }
            val cell = g.create(GUTTER + column * CELL, row * CELL, CELL, CELL) as Graphics2D
            painter.paint(cell, params, CELL, CELL)
            cell.dispose()
        }
        g.drawLabel(label, 16, row * CELL + CELL / 2)
    }
    g.dispose()
    ImageIO.write(sheet, "png", target)
    println("  ${target.name}")
}

/** One row per shape, identical audio and identical parameters. */
private fun renderShapeSheet(target: File) {
    val shapes = listOf<Pair<String, RadialShape>>(
        "circle" to RadialShapes.Circle,
        "squircle" to RadialShapes.superellipse(4.2f),
        "hexagon" to RadialShapes.polygon(6, cornerSoftness = 0.18f),
        "star (6)" to RadialShapes.star(points = 6, innerRatio = 0.62f, softness = 0.5f),
        "superformula" to RadialShapes.superformula(m = 5f, n1 = 2.2f, n2 = 7f, n3 = 7f),
    )
    val columns = 5
    val framesBetween = 24

    val sheet = newSheet(GUTTER + columns * CELL, shapes.size * CELL)
    val g = sheet.createGraphics().withQualityHints()
    val fill = speech(-24f)

    shapes.forEachIndexed { row, (label, shape) ->
        val driver = Driver()
        val painter = BlobPainter(shape, VisualPalette.OceanBlue)
        repeat((4f * 60).toInt()) { painter.advance(driver.step(fill)) }

        for (column in 0 until columns) {
            var params = driver.step(fill)
            painter.advance(params)
            repeat(framesBetween - 1) {
                params = driver.step(fill)
                painter.advance(params)
            }
            val cell = g.create(GUTTER + column * CELL, row * CELL, CELL, CELL) as Graphics2D
            painter.paint(cell, params, CELL, CELL)
            cell.dispose()
        }
        g.drawLabel(label, 16, row * CELL + CELL / 2)
    }
    g.dispose()
    ImageIO.write(sheet, "png", target)
    println("  ${target.name}")
}

/**
 * Idle behaviour over twelve seconds of complete silence.
 *
 * The visual parameters are constant here — the motion comes entirely from the
 * shared noise field and the breath oscillators, so this sheet is the visual
 * form of "calm when silent, subtly alive when idle".
 */
private fun renderIdleSheet(target: File) {
    val columns = 6
    val framesBetween = 120 // 2 s at 60 fps
    val palettes = listOf(
        "Ocean" to VisualPalette.OceanBlue,
        "Ember" to VisualPalette.Ember,
        "Graphite" to VisualPalette.Graphite,
    )

    val sheet = newSheet(GUTTER + columns * CELL, palettes.size * CELL)
    val g = sheet.createGraphics().withQualityHints()
    val fill = silence()

    palettes.forEachIndexed { row, (label, palette) ->
        val driver = Driver()
        val painter = BlobPainter(RadialShapes.Circle, palette)
        repeat((3f * 60).toInt()) { painter.advance(driver.step(fill)) }

        for (column in 0 until columns) {
            var params = driver.step(fill)
            painter.advance(params)
            repeat(framesBetween - 1) {
                params = driver.step(fill)
                painter.advance(params)
            }
            val cell = g.create(GUTTER + column * CELL, row * CELL, CELL, CELL) as Graphics2D
            painter.paint(cell, params, CELL, CELL)
            cell.dispose()
        }
        g.drawLabel("$label / idle", 16, row * CELL + CELL / 2)
    }
    g.dispose()
    ImageIO.write(sheet, "png", target)
    println("  ${target.name}")
}

// ---------------------------------------------------------------------------

private fun speech(levelDb: Float): (FloatArray) -> Unit {
    val source = SyntheticAudioSource(levelDb = levelDb)
    return { buffer -> source.fill(buffer) }
}

private fun silence(): (FloatArray) -> Unit {
    val source = SilentAudioSource(roomToneDb = -72f)
    return { buffer -> source.fill(buffer) }
}

private fun newSheet(width: Int, height: Int): BufferedImage =
    BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

private fun Graphics2D.withQualityHints(): Graphics2D = apply {
    setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    color = Color(0x04, 0x07, 0x0F)
    fillRect(0, 0, 20_000, 20_000)
}

private fun Graphics2D.drawLabel(text: String, x: Int, centerY: Int) {
    font = Font(Font.MONOSPACED, Font.PLAIN, 15)
    color = Color(0xB0, 0xC4, 0xDE)
    drawString(text, x, centerY)
}

/** Unused configuration handles kept visible for anyone extending the tool. */
@Suppress("unused")
private val alternativeConfigurations = listOf(
    AnalyzerConfig.NoisyRoom,
    AnalyzerConfig.CloseMic,
    AnimationConfig.Expressive,
    AnimationConfig.Subtle,
)
