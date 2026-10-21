# GymQuest — mapa de funciones

Estado: ✅ lógica hecha y probada en `:core` · 🔨 en curso · ⏳ pendiente (capa Android o servidor).
La app Android (módulo `:app`: Compose, CameraX + ML Kit, MapLibre, WorkManager, Media3, Health Connect) está construida encima de `:core`.
📱 = ya está en la app y compila en CI; **no probado en un teléfono real todavía** (la cámara y el GPS necesitan calibración con personas reales).

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
| Mapas 3D interactivos y cámara que sigue tu rumbo; repetición 3D del recorrido | `tracking/FollowCamera`, `RoutePlayback` | ✅ lógica · 📱 mapa 3D con cámara que sigue |
| Descargar mapas para usar sin internet | `tracking/OfflineMapPlanner` | ✅ plan/tamaño · 📱 descarga por zonas (OfflineManager) |
| Rutas populares y compartir rutas | `tracking/Polyline`, `Routes` | ✅ lógica · ⏳ necesita servidor para compartir con otros |
| Ir a un destino a pie/bici/auto con reportes en la ruta (tráfico, obras, baches…) | `routes/`, `app/routing` | 📱 ruta por servidores públicos de OpenStreetMap (con internet; sin internet, línea guía) · reportes guardados en tu teléfono · ⏳ compartirlos con otros |
| Recuperar una actividad GPS si la app se cierra a mitad | `app/tracking/TrackJournal` | 📱 |
| Territorio de un jugador: circuitos cerrados dibujados en el mapa | `territory/` | 📱 tus zonas · ⏳ pelear por zonas con otros (servidor) |
| Crear stickers con tus fotos | `app/stickers` | 📱 (enviarlos en el chat necesita servidor) |
| Perfil (peso, estatura, edad, género, objetivo), TMB/TDEE, calorías y macros | `fitness/` | ✅ |
| Recomendar ejercicios por **edad** y objetivo (definir, volumen, resistencia…) | `AgeGuidance`, `ExerciseRecommender`, `WorkoutPlanner` | ✅ |
| Comidas recomendadas según objetivo y dieta, lista de compras | `nutrition/MealPlanner`, `FoodSuggester` | ✅ |
| Foto de comida → calorías (sin internet con etiquetas del teléfono; con internet con IA de visión) | `FoodMatcher`, `MealEstimates` | ✅ lógica · 📱 foto con etiquetas del teléfono · ⏳ IA de visión (necesita tu propia clave) |
| Agua, skincare (lavadas de cara, rutina AM/PM), peso y tendencia, coach del día | `fitness/Hydration`, `wellness/` | ✅ |
| Notificaciones (racha en riesgo, agua, skincare, amigos, ranking…) | `notifications/` | ✅ · 📱 recordatorios locales sin internet (los de amigos/ranking esperan al servidor) |
| Personaje que cambia con tu cuerpo, stats, ropa/recompensas | `avatar/` | ✅ stats, morph, 40 cosméticos · 📱 dibujo, ropa, tienda |
| Territorio: cierra una ruta y conquista la zona; círculos más grandes ganan | `territory/` | ✅ |
| Gremios, jefes cooperativos, guerras entre gremios, duelos (lagartijas/sentadillas/cuerda) | `guild/`, `competition/` | ✅ |
| Misiones diarias y semanales | `missions/` | ✅ · 📱 pantalla de misiones, retos semanales, bonus y racha |
| Mercado autorregulado e intercambio entre jugadores | `economy/` | ✅ |
| Racha pública, ranking de amigos, retos, pareja, chat | `social/` | ✅ |
| Reportes comunitarios en la ruta (tráfico, obras, baches) y destino | `routes/` | ✅ |
| Batería corporal: sueño, uso del teléfono, necesidad de sueño por edad | `wellness/Sleep.kt` | ✅ lógica · 📱 Health Connect + registro de pantalla + uso del teléfono |
| Entrenador de voz en tiempo real por audífonos ("baja", "espalda recta", "excelente") | `voice/` | ✅ lógica · 📱 voz (TTS) con bajada automática del volumen de la música |
| Música: playlists, modo DJ, BPM sync con tu cadencia (opcional) | `music/` | ✅ BPM, cadencia, DJ, grupo sincronizado · 📱 reproductor en segundo plano, DJ, análisis de BPM · ⏳ grupo (necesita red) |
| Compartir rutina de gym + diario/calendario | `sharing/` | ✅ · 📱 compartir por WhatsApp/etc. |
| Conectar Health Connect / Google Fit | `health/` (app) | 📱 sueño y pasos |
| Cola de sincronización offline-first | `sync/`, `platform/` | ✅ |

## Lo que necesita un servidor
Amigos, gremios, ranking, territorio, mercado y chat entre personas necesitan un backend. Las reglas
(quién conquista una zona, cómo se calcula el precio, quién gana una guerra) están en `:core` para
que el servidor las reutilice; mientras no exista, la pestaña Comunidad muestra qué funciona sin conexión y cómo te ven los demás (vista previa de privacidad); no hay jugadores falsos.

## Optimización
`platform/Performance.kt` baja la calidad (fps de análisis, modelo de pose, mapa 3D→2D) cuando el
teléfono se calienta, tiene poca batería o va justo de tiempo, y sube de nuevo solo si se mantiene bien.
La lógica de conteo cuesta menos de 1 ms por fotograma (hay pruebas de eso); lo pesado es el modelo de pose.

## Pruebas
`./gradlew :core:test` — más de 600 pruebas del módulo `:core`.
