package saien.updater.lab

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.Test

class LabUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun preparedUpdateExposesOnlyInstallAction() {
        var installs = 0
        compose.setContent {
            App(
                LabState(
                    release = 1,
                    status = "Ready to install. The app will close and reopen.",
                    canInstall = true,
                    events = listOf("Release feed verified", "Application prepared"),
                ),
                onInstall = { installs++ },
            )
        }
        compose.onNodeWithTag("release").assertTextEquals("1")
        compose.onNodeWithTag("check").assertIsNotEnabled()
        compose.onNodeWithTag("download").assertIsNotEnabled()
        compose.onNodeWithTag("install").assertIsEnabled().performClick()
        assertEquals(1, installs)
        val image = compose.onNodeWithTag("lab-root").captureToImage().toAwtImage()
        val file = File("build/reports/lab-ui.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}
