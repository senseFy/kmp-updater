package saien.updater.lab.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Path
import saien.updater.lab.App
import saien.updater.lab.LabController

fun main() = application {
    val controller = remember {
        val executable = System.getProperty("jpackage.app-path")?.let(Path::of)
        LabController(executable?.parent?.parent?.parent)
    }
    val state by controller.state.collectAsState()
    LaunchedEffect(Unit) { controller.start(::exitApplication) }
    DisposableEffect(Unit) { onDispose { controller.close() } }
    Window(
        onCloseRequest = ::exitApplication,
        title = "Updater Lab",
        state = rememberWindowState(width = 760.dp, height = 590.dp),
    ) {
        App(state, controller::check, controller::download, controller::install)
    }
}
