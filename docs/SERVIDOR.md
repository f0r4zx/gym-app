# Servidor para las funciones sociales (propuesta)

Lo que **no** funciona todavía porque necesita un servidor: amigos, gremios (jefes cooperativos y guerras),
ranking, territorio compartido, mercado entre jugadores, chat/stickers entre personas, rutas populares y
reportes en la ruta compartidos, música grupal por internet.

Las **reglas** ya están escritas y probadas en `:core` (quién conquista una zona, precios del mercado,
resultados de guerras, privacidad, cola de sincronización sin conexión). Falta la parte que guarda los datos
y conecta a las personas.

## Opciones
| Opción | Ventajas | Inconvenientes |
|---|---|---|
| **A. Servidor propio (Ktor, reutiliza `:core`)** | Las reglas del juego son las mismas en teléfono y servidor (anti-trampas); control total; barato (un VPS pequeño o un servicio como Fly.io/Render) | Alguien debe mantenerlo (actualizaciones, copias de seguridad); hay que decidir dónde alojarlo |
| **B. Firebase / Supabase** | Sin servidor que mantener; autenticación y base de datos listas; plan gratis para empezar | Las reglas anti-trampas habría que reescribirlas (Cloud Functions) y quedas atado al proveedor |

## Qué haría el servidor (A)
- Cuenta anónima por teléfono (sin contraseña al inicio) + perfil público según tu privacidad.
- Amigos y solicitudes, ranking semanal, racha pública.
- Gremios: miembros, jefe cooperativo, guerra entre gremios, botín.
- Territorio: recibe el recorrido, valida velocidad/duración (`ClaimValidator`), calcula zonas y pelea por ellas.
- Mercado: precio justo calculado, comisión, anti-manipulación.
- Chat con mensajes, stickers e imágenes (las imágenes solo con internet).
- Reportes de ruta compartidos y rutas populares.
- Sincronización: la app guarda todo local y envía cuando hay red (`Outbox`).

## Lo que necesito de ti
1. ¿Servidor propio (A) o servicio gestionado (B)?
2. Si es (A): ¿dónde lo alojamos y quién lo mantiene? (puedo dejarlo listo para desplegar con un solo comando).
3. Moderación del chat y las imágenes (reportar/bloquear ya está en `social/`; falta decidir quién revisa los reportes).
