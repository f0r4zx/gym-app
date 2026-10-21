package app.gymquest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * Walks through the real app on an emulator: onboarding, then every tab. It does not test the
 * camera or GPS (no hardware), but it proves the app starts, saves data and does not crash.
 */
class SmokeTest {
    // The app asks for permission to show reminders on first launch; grant it up front so the
    // system dialog does not cover the screen during the test.
    @get:Rule(order = 0)
    val permissions = object : TestWatcher() {
        override fun starting(description: Description) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            runCatching {
                instrumentation.uiAutomation.grantRuntimePermission(instrumentation.targetContext.packageName, "android.permission.POST_NOTIFICATIONS")
            }
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    private fun next() {
        rule.onNodeWithText("Siguiente").performClick()
        rule.waitForIdle()
    }

    @Test
    fun onboardingThenEveryTab() {
        // ---- Onboarding
        rule.onNodeWithText("¡Bienvenido a GymQuest!").assertIsDisplayed()
        rule.onNodeWithText("Tu nombre o apodo").performTextInput("Tester")
        rule.onNodeWithText("Edad").performTextInput("28")
        next()
        rule.onNodeWithText("Estatura").performTextInput("175")
        rule.onNodeWithText("Peso actual").performTextInput("72")
        next()
        rule.onNodeWithText("¿Cuál es tu objetivo?").assertIsDisplayed()
        next()
        rule.onNodeWithText("Tu rutina actual").assertIsDisplayed()
        next()
        rule.onNodeWithText("Equipo y comida").assertIsDisplayed()
        next()
        rule.onNodeWithText("Piel y descanso").assertIsDisplayed()
        next()
        rule.onNodeWithText("Tu plan está listo, Tester").assertIsDisplayed()
        rule.onNodeWithText("¡Empezar mi aventura!").performClick()
        rule.waitForIdle()

        // ---- Home
        rule.onNodeWithText("Hola, Tester", substring = true).assertIsDisplayed()

        // ---- Life: water, food, skin, weight, sleep
        rule.onNodeWithText("Vida").performClick()
        rule.onNodeWithText("Vida y bienestar").assertIsDisplayed()
        rule.onNodeWithText("+1 vaso").performClick()
        rule.onNodeWithText("🥤 1 de", substring = true).assertIsDisplayed()
        rule.onNodeWithText("🍽️ Comida").performClick()
        rule.onNodeWithText("Agregar comida").assertIsDisplayed()
        rule.onNodeWithText("🧴 Piel").performClick()
        rule.onNodeWithText("Rutina de hoy").assertIsDisplayed()
        rule.onNodeWithText("⚖️ Peso").performClick()
        rule.onNodeWithText("Peso de hoy").assertIsDisplayed()
        rule.onNodeWithText("😴 Sueño").performClick()
        rule.onNodeWithText("Tu cuerpo necesita").assertIsDisplayed()

        // ---- Train, Community
        rule.onNodeWithText("Entrenar").performClick()
        rule.onNodeWithText("⚔️ Jefes").assertIsDisplayed()
        rule.onNodeWithText("Comunidad").performClick()
        rule.onNodeWithText("Así te ven los demás").assertIsDisplayed()

        // ---- Map (loads the map view; the style may need internet but must not crash)
        rule.onNodeWithText("Mapa").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("▶ Empezar").assertIsDisplayed()

        // ---- Back home, character screen
        rule.onNodeWithText("Inicio").performClick()
        rule.onNodeWithText("Nivel 1", substring = true).performClick()
        rule.onNodeWithText("Mi personaje").assertIsDisplayed()
        rule.onNodeWithText("Estadísticas").performScrollTo().assertIsDisplayed()
    }
}
