package app.gymquest

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToLog
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

    /** The first node whose text contains [words]; labels carry an inline icon, so they are matched by the words only. */
    private fun text(words: String): SemanticsNodeInteraction = rule.onAllNodesWithText(words, substring = true).onFirst()

    /** Runs one part of the walk-through; on failure it says which part and dumps what was on screen. */
    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            runCatching { rule.onRoot().printToLog("SMOKE") }
            throw AssertionError("Paso fallido: $name -> ${e.message?.take(400)}", e)
        }
    }

    private fun next() {
        rule.onNodeWithText("Siguiente").performClick()
        rule.waitForIdle()
    }

    @Test
    fun onboardingThenEveryTab() {
        step("bienvenida") {
            rule.onNodeWithText("¡Bienvenido a GymQuest!").assertIsDisplayed()
            rule.onNodeWithText("Tu nombre o apodo").performTextInput("Tester")
            rule.onNodeWithText("Edad").performTextInput("28")
            next()
        }
        step("cuerpo") {
            rule.onNodeWithText("Estatura").performTextInput("175")
            rule.onNodeWithText("Peso actual").performTextInput("72")
            next()
        }
        step("objetivo y rutina") {
            rule.onNodeWithText("¿Cuál es tu objetivo?").assertIsDisplayed()
            next()
            rule.onNodeWithText("Tu rutina actual").assertIsDisplayed()
            next()
        }
        step("equipo y piel") {
            rule.onNodeWithText("Equipo y comida").assertIsDisplayed()
            next()
            rule.onNodeWithText("Piel y descanso").assertIsDisplayed()
            next()
        }
        step("resumen") {
            rule.onNodeWithText("Tu plan está listo, Tester").assertIsDisplayed()
            rule.onNodeWithText("¡Empezar mi aventura!").performClick()
            rule.waitForIdle()
        }
        step("inicio") {
            text("Hola, Tester").assertIsDisplayed()
        }
        step("vida: agua") {
            rule.onNodeWithText("Vida").performClick()
            rule.onNodeWithText("Vida y bienestar").assertIsDisplayed()
            rule.onNodeWithText("+1 vaso").performClick()
            text("1 de").assertIsDisplayed()
        }
        step("vida: comida") {
            text("Comida").performClick()
            rule.onNodeWithText("Agregar comida").assertIsDisplayed()
        }
        step("vida: piel") {
            text("Piel").performClick()
            rule.onNodeWithText("Rutina de hoy").assertIsDisplayed()
        }
        step("vida: peso") {
            text("Peso").performClick()
            rule.onNodeWithText("Peso de hoy").assertIsDisplayed()
        }
        step("vida: sueño") {
            text("Sueño").performClick()
            rule.onNodeWithText("Tu cuerpo necesita").assertIsDisplayed()
        }
        step("entrenar") {
            rule.onNodeWithText("Entrenar").performClick()
            text("Mi plan y recomendaciones").assertIsDisplayed()
            text("Jefes").performScrollTo().assertIsDisplayed()
        }
        step("comunidad") {
            rule.onNodeWithText("Comunidad").performClick()
            rule.onNodeWithText("Así te ven los demás").assertIsDisplayed()
        }
        step("mapa") {
            rule.onNodeWithText("Mapa").performClick()
            rule.waitForIdle()
            text("Empezar").assertIsDisplayed()
        }
        step("personaje") {
            rule.onNodeWithText("Inicio").performClick()
            text("Nivel 1").performClick()
            rule.onNodeWithText("Mi personaje").assertIsDisplayed()
            rule.onNodeWithText("Estadísticas").performScrollTo().assertIsDisplayed()
        }
    }
}
