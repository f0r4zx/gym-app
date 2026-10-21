# GymQuest

App de Android todo-en-uno para entrenar: cuenta repeticiones con la cámara, pelea contra jefes, graba
caminatas/carreras/bici con GPS en mapas 3D (también sin internet), lleva tu nutrición, agua, skincare y
sueño, y convierte todo en un personaje con niveles, misiones y recompensas.

- 📱 **Instalar la app:** [INSTALAR.md](INSTALAR.md)
- 🗺️ **Qué hace cada cosa y en qué estado está:** [docs/FEATURES.md](docs/FEATURES.md)
- 🌐 **Funciones sociales (necesitan servidor):** [docs/SERVIDOR.md](docs/SERVIDOR.md)

## Estructura
| Carpeta | Qué es |
|---|---|
| `core/` | Toda la lógica del juego en Kotlin puro (conteo de repeticiones, jefes, GPS, nutrición, economía…). Más de 600 pruebas automáticas. |
| `app/` | La app Android (Jetpack Compose, CameraX + ML Kit, MapLibre, WorkManager, Media3, Health Connect). |
| `.github/workflows/ci.yml` | En cada cambio: prueba `core`, compila la app, la prueba en un emulador y genera el APK. |

## Comandos
```
./gradlew :core:test                 # pruebas de la lógica
./gradlew :app:testDebugUnitTest     # pruebas de la app
./gradlew :app:assembleDebug         # APK de prueba
./gradlew :app:assembleRelease -PphoneOnly   # APK optimizado para teléfonos
```
