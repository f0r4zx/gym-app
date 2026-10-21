package app.gymquest.core.platform

/** How much a feature depends on having a connection. */
enum class NetworkNeed {
    /** Works fully offline. */
    NONE,

    /** Works offline with limits (cached data, actions queued until you are back online). */
    OPTIONAL,

    /** Cannot work without internet; it is switched off while offline. */
    REQUIRED,
}

enum class Feature(val label: String, val need: NetworkNeed, val offlineNote: String? = null) {
    // ---- Everything that matters for training works with no signal
    CAMERA_COUNTING("Contar repeticiones con la cámara", NetworkNeed.NONE),
    VOICE_COACH("Entrenador de voz", NetworkNeed.NONE),
    BOSS_FIGHTS("Jefes", NetworkNeed.NONE),
    MISSIONS("Misiones diarias", NetworkNeed.NONE),
    CHARACTER("Personaje, stats y ropa", NetworkNeed.NONE),
    GPS_TRACKING("Rastreo GPS", NetworkNeed.NONE),
    MAP_DOWNLOADED("Mapa de zonas descargadas", NetworkNeed.NONE),
    FOOD_LOG("Registro de comidas y calorías", NetworkNeed.NONE),
    FOOD_PHOTO_DEVICE("Foto de comida (reconocimiento en el teléfono)", NetworkNeed.NONE),
    WATER_SKINCARE("Agua y skincare", NetworkNeed.NONE),
    DIARY("Diario y calendario", NetworkNeed.NONE),
    SLEEP_BATTERY("Batería corporal y sueño", NetworkNeed.NONE),
    LOCAL_MUSIC("Música guardada en el teléfono y modo DJ", NetworkNeed.NONE),
    GROUP_MUSIC_NEARBY("Música grupal con amigos cerca (sin internet)", NetworkNeed.NONE),
    NOTIFICATIONS_LOCAL("Recordatorios", NetworkNeed.NONE),
    HEALTH_CONNECT("Health Connect", NetworkNeed.NONE),

    // ---- Work offline with limits
    MAP_ONLINE("Mapa fuera de las zonas descargadas", NetworkNeed.OPTIONAL, "Solo se ven las zonas que descargaste."),
    FRIENDS_LIST("Amigos", NetworkNeed.OPTIONAL, "Verás los datos de la última vez que hubo conexión."),
    LEADERBOARD("Ranking", NetworkNeed.OPTIONAL, "El ranking puede estar desactualizado."),
    PUBLIC_STREAK("Racha pública", NetworkNeed.OPTIONAL, "Tu racha se publica cuando vuelva la conexión."),
    CHAT_TEXT("Mensajes y stickers", NetworkNeed.OPTIONAL, "Se envían cuando vuelva la conexión."),
    STICKER_CREATION("Crear stickers", NetworkNeed.NONE),
    TERRITORY_VIEW("Mapa de territorio", NetworkNeed.OPTIONAL, "Se muestra la última versión guardada."),
    TERRITORY_CLAIM("Conquistar territorio", NetworkNeed.OPTIONAL, "La conquista se valida al reconectar."),
    DUEL_SUBMIT("Enviar resultado de un duelo", NetworkNeed.OPTIONAL, "Hazlo ahora y se envía al reconectar."),
    ROUTE_REPORTS_READ("Reportes en la ruta", NetworkNeed.OPTIONAL, "Solo los reportes ya descargados."),
    ROUTE_REPORTS_SEND("Enviar un reporte en la ruta", NetworkNeed.OPTIONAL, "Se publica cuando vuelva la conexión."),
    FOOD_PHOTO_AI("Foto de comida con IA", NetworkNeed.OPTIONAL, "Sin internet se usa el reconocimiento básico del teléfono."),
    ROUTE_PLANNING("Planear ruta a un destino", NetworkNeed.OPTIONAL, "Sin internet se traza una línea guía entre puntos."),
    SHARE_ROUTINE("Compartir rutina", NetworkNeed.OPTIONAL, "El código se genera sin red; enviarlo requiere conexión."),

    // ---- Need a connection
    MARKET("Mercado", NetworkNeed.REQUIRED),
    TRADES("Intercambios con otros jugadores", NetworkNeed.REQUIRED),
    GUILD_WAR_LIVE("Guerra de gremios en vivo", NetworkNeed.REQUIRED),
    GUILD_ADMIN("Administrar el gremio", NetworkNeed.REQUIRED),
    CHAT_IMAGES("Enviar fotos en el chat", NetworkNeed.REQUIRED),
    GROUP_MUSIC_CLOUD("Música grupal por internet", NetworkNeed.REQUIRED),
    STREAMING_MUSIC("Música en streaming", NetworkNeed.REQUIRED),
    DUEL_LIVE("Duelo en vivo", NetworkNeed.REQUIRED),
    ACCOUNT("Crear cuenta o iniciar sesión", NetworkNeed.REQUIRED),
    MAP_DOWNLOAD("Descargar mapas", NetworkNeed.REQUIRED),
}

data class Availability(val feature: Feature, val enabled: Boolean, val limited: Boolean, val message: String?)

object FeatureGate {
    fun check(feature: Feature, online: Boolean): Availability = when {
        feature.need == NetworkNeed.NONE -> Availability(feature, true, false, null)
        online -> Availability(feature, true, false, null)
        feature.need == NetworkNeed.OPTIONAL -> Availability(feature, true, true, feature.offlineNote)
        else -> Availability(feature, false, false, "Necesita internet.")
    }

    fun all(online: Boolean): List<Availability> = Feature.entries.map { check(it, online) }

    /** What the user loses while offline, for the "sin conexión" banner. */
    fun disabledOffline(): List<Feature> = Feature.entries.filter { it.need == NetworkNeed.REQUIRED }

    fun limitedOffline(): List<Feature> = Feature.entries.filter { it.need == NetworkNeed.OPTIONAL }
}
