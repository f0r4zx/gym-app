package app.gymquest.core.social

enum class Visibility { PUBLIC, FRIENDS, PRIVATE }

/** How the viewer is connected to the profile owner. */
enum class Relation { SELF, FRIEND, GUILDMATE, STRANGER, BLOCKED }

/**
 * What each part of your profile shows to whom. The defaults share what is fun to share (your
 * streak and level) and keep the sensitive things (body data, live location) private.
 */
data class PrivacySettings(
    val streak: Visibility = Visibility.PUBLIC,
    val level: Visibility = Visibility.PUBLIC,
    val stats: Visibility = Visibility.FRIENDS,
    val weeklyPoints: Visibility = Visibility.FRIENDS,
    val routes: Visibility = Visibility.FRIENDS,
    val diary: Visibility = Visibility.PRIVATE,
    val bodyData: Visibility = Visibility.PRIVATE,
    val liveLocation: Visibility = Visibility.PRIVATE,
    val lastActive: Visibility = Visibility.FRIENDS,
    val allowFriendRequests: Boolean = true,
)

data class ProfileData(
    val userId: String,
    val name: String,
    val title: String,
    val level: Int,
    val currentStreak: Int,
    val longestStreak: Int,
    val power: Int,
    val weeklyPoints: Double,
    val guildTag: String?,
    val weightKg: Double?,
    val lastActiveMs: Long?,
    val bossesDefeated: Int,
)

/** The version of a profile that a particular viewer is allowed to see. Hidden fields are null. */
data class ProfileView(
    val userId: String,
    val name: String,
    val title: String?,
    val level: Int?,
    val currentStreak: Int?,
    val longestStreak: Int?,
    val power: Int?,
    val weeklyPoints: Double?,
    val guildTag: String?,
    val weightKg: Double?,
    val lastActiveMs: Long?,
    val bossesDefeated: Int?,
)

object Privacy {
    fun canSee(visibility: Visibility, relation: Relation): Boolean = when (relation) {
        Relation.SELF -> true
        Relation.BLOCKED -> false
        Relation.FRIEND, Relation.GUILDMATE -> visibility != Visibility.PRIVATE
        Relation.STRANGER -> visibility == Visibility.PUBLIC
    }

    /** Null when the viewer is blocked: the profile does not exist for them. */
    fun view(data: ProfileData, settings: PrivacySettings, relation: Relation): ProfileView? {
        if (relation == Relation.BLOCKED) return null
        fun <T> show(v: Visibility, value: T): T? = if (canSee(v, relation)) value else null
        return ProfileView(
            userId = data.userId,
            name = data.name,
            title = show(settings.level, data.title),
            level = show(settings.level, data.level),
            currentStreak = show(settings.streak, data.currentStreak),
            longestStreak = show(settings.streak, data.longestStreak),
            power = show(settings.stats, data.power),
            weeklyPoints = show(settings.weeklyPoints, data.weeklyPoints),
            guildTag = data.guildTag,
            weightKg = show(settings.bodyData, data.weightKg),
            lastActiveMs = show(settings.lastActive, data.lastActiveMs),
            bossesDefeated = show(settings.stats, data.bossesDefeated),
        )
    }

    fun canSendFriendRequest(target: PrivacySettings, relation: Relation): Boolean =
        target.allowFriendRequests && relation == Relation.STRANGER
}

/** Live location sharing for group runs: opt-in, time-limited and only for the people you choose. */
data class LocationShare(val userId: String, val startedMs: Long, val expiresMs: Long, val audience: Set<String>) {
    init {
        require(expiresMs - startedMs <= MAX_DURATION_MS) { "Máximo 4 horas compartiendo ubicación" }
    }

    fun isActive(nowMs: Long) = nowMs in startedMs until expiresMs
    fun canSee(viewerId: String, nowMs: Long) = isActive(nowMs) && (viewerId == userId || viewerId in audience)

    companion object {
        const val MAX_DURATION_MS = 4 * 3_600_000L
    }
}

/** An outing planned with friends: where to meet, what route, who is coming. */
enum class Rsvp { GOING, MAYBE, NOT_GOING }

data class PlannedOuting(
    val id: String,
    val organizerId: String,
    val title: String,
    val activity: app.gymquest.core.tracking.ActivityType,
    val meetingPoint: app.gymquest.core.tracking.GeoPoint,
    val routePolyline: String?,
    val startMs: Long,
    val maxParticipants: Int = 20,
    val playlistId: String? = null,
    val responses: Map<String, Rsvp> = mapOf(),
) {
    val going: List<String> get() = responses.filterValues { it == Rsvp.GOING }.keys.toList()

    fun respond(userId: String, answer: Rsvp): PlannedOuting? {
        if (answer == Rsvp.GOING && userId !in going && going.size >= maxParticipants) return null
        return copy(responses = responses + (userId to answer))
    }

    fun isFull() = going.size >= maxParticipants
}
