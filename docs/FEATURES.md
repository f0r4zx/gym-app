# GymQuest — mapa de funciones

Estado: ✅ lógica hecha y probada en `:core` · 🔨 en curso · ⏳ pendiente (capa Android o servidor).
La app Android (Compose, cámara, mapas, base de datos local) se construye encima de `:core`.

## Principio: funciona con o sin internet
Todo lo que corre en el teléfono es **local primero**: cámara y conteo, juego/jefes, GPS, mapa
descargado, comidas, agua, skincare, entrenos. Lo social (amigos, gremios, ranking, territorio,
mercado) se guarda en una cola local y se sincroniza cuando hay red.

| Pedido | Módulo `:core` | Estado |
|---|---|---|
| Contar repeticiones con la cámara (sentadillas, lagartijas, dominadas, curls, press, abdominales, burpees, plancha, cuerda…) con el teléfono en cualquier lugar | `exercise/`, `pose/`, `sim/` | ✅ 18 ejercicios, ~140 tests |
| Guía de encuadre ("aléjate", "gírate de lado"), cuenta atrás, pausa si sales del cuadro | `FramingAnalyzer`, `WorkoutSession` | ✅ |
| Jefes que se derrotan con repeticiones, combos, críticos, furia | `game/BossBattle`, `Bosses` | ✅ 13 jefes |
| XP, niveles, logros, rachas | `game/Progression`, `Achievements`, `Streaks` | ✅ |
| GPS tipo Strava (caminar, correr, bici, senderismo): distancia, ritmo, parciales, desnivel, calorías, pausa automática | `tracking/ActivityRecorder` | ✅ |
| Mapas 3D interactivos y cámara que sigue tu rumbo; repetición 3D del recorrido | `tracking/FollowCamera`, `RoutePlayback` | ✅ lógica · ⏳ vista |
| Descargar mapas para usar sin internet | `tracking/OfflineMapPlanner` | ✅ plan/tamaño · ⏳ descarga |
| Rutas populares y compartir rutas | `tracking/Polyline`, `Routes` | ✅ |
| Perfil (peso, estatura, edad, género, objetivo), TMB/TDEE, calorías y macros | `fitness/` | ✅ |
| Recomendar ejercicios por **edad** y objetivo (definir, volumen, resistencia…) | `AgeGuidance`, `ExerciseRecommender`, `WorkoutPlanner` | ✅ |
| Comidas recomendadas según objetivo y dieta, lista de compras | `nutrition/MealPlanner`, `FoodSuggester` | ✅ |
| Foto de comida → calorías (sin internet con etiquetas del teléfono; con internet con IA de visión) | `FoodMatcher`, `MealEstimates` | ✅ lógica · ⏳ cámara/IA |
| Agua, skincare (lavadas de cara, rutina AM/PM), peso y tendencia, coach del día | `fitness/Hydration`, `wellness/` | ✅ |
| Notificaciones (racha en riesgo, agua, skincare, amigos, ranking…) | `notifications/` | ✅ |
| Personaje que cambia con tu cuerpo, stats, ropa/recompensas | `avatar/` | 🔨 |
| Territorio: cierra una ruta y conquista la zona; círculos más grandes ganan | `territory/` | 🔨 |
| Gremios, jefes cooperativos, guerras entre gremios, duelos (lagartijas/sentadillas/cuerda) | `guild/`, `duel/` | 🔨 |
| Misiones diarias y semanales | `missions/` | 🔨 |
| Mercado autorregulado e intercambio entre jugadores | `economy/` | 🔨 |
| Racha pública, ranking de amigos, retos, pareja, chat | `social/` | 🔨 |
| Reportes comunitarios en la ruta (tráfico, obras, baches) y destino | `routes/` | 🔨 |
| Batería corporal: sueño, uso del teléfono, necesidad de sueño por edad | `wellness/` | 🔨 |
| Entrenador de voz en tiempo real por audífonos ("baja", "espalda recta", "excelente") | `voice/` | 🔨 |
| Música: playlists, modo DJ, BPM sync con tu cadencia (opcional) | `music/` | 🔨 |
| Compartir rutina de gym + diario/calendario | `sharing/` | 🔨 |
| Conectar Health Connect / Google Fit | capa Android | ⏳ |
| Cola de sincronización offline-first | `sync/` | 🔨 |

## Lo que necesita un servidor
Amigos, gremios, ranking, territorio, mercado y chat entre personas necesitan un backend. Las reglas
(quién conquista una zona, cómo se calcula el precio, quién gana una guerra) están en `:core` para
que el servidor las reutilice; mientras no exista, la app corre en **modo local** con datos de demostración.
