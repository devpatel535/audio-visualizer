package com.audioviz.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Audio Reactive Visualizer",
        state = rememberWindowState(size = DpSize(960.dp, 760.dp)),
    ) {
        DemoApp()
    }
}
