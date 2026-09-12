package saien.updater.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

data class LabState(
    val release: Long = 0,
    val status: String = "Run the acceptance script to prepare an isolated application.",
    val progress: Float? = null,
    val canCheck: Boolean = false,
    val canDownload: Boolean = false,
    val canInstall: Boolean = false,
    val events: List<String> = emptyList(),
)

@Composable
fun App(
    state: LabState = LabState(),
    onCheck: () -> Unit = {},
    onDownload: () -> Unit = {},
    onInstall: () -> Unit = {},
) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize().testTag("lab-root")) {
            Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Updater Lab", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        "An isolated app for testing updates",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp)) {
                    Column(
                        Modifier.padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("INSTALLED RELEASE", style = MaterialTheme.typography.labelMedium)
                        Text(
                            state.release.toString(),
                            Modifier.testTag("release"),
                            style = MaterialTheme.typography.displayMedium,
                        )
                        Text(state.status, Modifier.testTag("status"))
                        state.progress?.let {
                            LinearProgressIndicator(
                                progress = { it },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onCheck, Modifier.testTag("check"), enabled = state.canCheck) {
                        Text("Check updates")
                    }
                    Button(onDownload, Modifier.testTag("download"), enabled = state.canDownload) {
                        Text("Download")
                    }
                    Button(onInstall, Modifier.testTag("install"), enabled = state.canInstall) {
                        Text("Install & relaunch")
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("SESSION", style = MaterialTheme.typography.labelMedium)
                    state.events.takeLast(6).forEach {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
