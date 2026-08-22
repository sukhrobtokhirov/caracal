package dev.caracal.app

import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WindowIconTest {
    // `Window(icon = painterResource("caracal.png"))` resolves against the classpath
    // at run time and fails there, not here — a renamed file or a resources directory
    // that stopped being one would ship an installer whose window opens onto an
    // exception. The same reason BuildInfoTest reads its own resource back.
    @Test
    fun `the window icon is on the classpath under the name Main asks for`() {
        val bytes = javaClass.getResourceAsStream("/caracal.png")
        assertNotNull(bytes, "app/src/main/resources/caracal.png did not reach the classpath")

        val image = bytes.use { ImageIO.read(it) }
        assertNotNull(image, "the icon is on the classpath but is not a readable image")
        assertEquals(image.width, image.height, "an icon the platform scales should be square")
        assertTrue(image.width >= 256, "too small for a Retina title bar: ${image.width}px")
        assertTrue(image.colorModel.hasAlpha(), "the tile's rounded corners need transparency")
    }
}
