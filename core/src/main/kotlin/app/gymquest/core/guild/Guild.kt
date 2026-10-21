package app.gymquest.core.guild

import kotlin.math.pow

enum class GuildRole { LEADER, OFFICER, MEMBER }

data class GuildMember(val userId: String, val displayName: String, val role: GuildRole, val joinedAtMs: Long)

data class Guild(
    val id: String,
    val name: String,
    val tag: String,
    val members: List<GuildMember>,
    val xp: Long = 0,
    val treasury: Long = 0,
    val rating: Int = 1000,
    val createdAtMs: Long = 0,
) {
    val level: Int get() = GuildRules.levelFor(xp)
    val leader: GuildMember get() = members.first { it.role == GuildRole.LEADER }
    fun member(userId: String): GuildMember? = members.firstOrNull { it.userId == userId }
    fun has(userId: String) = members.any { it.userId == userId }
}

sealed interface GuildError {
    data class InvalidName(val reason: String) : GuildError
    data class InvalidTag(val reason: String) : GuildError
    data object LevelTooLow : GuildError
    data object Full : GuildError
    data object AlreadyMember : GuildError
    data object NotMember : GuildError
    data object NoPermission : GuildError
    data object CannotTargetLeader : GuildError
    data object CannotTargetSelf : GuildError
    data object InsufficientFunds : GuildError
    data object InvalidAmount : GuildError
}

sealed interface Outcome<out T> {
    data class Ok<T>(val value: T) : Outcome<T>
    data class Err(val error: GuildError) : Outcome<Nothing>
}

private fun <T> ok(v: T): Outcome<T> = Outcome.Ok(v)
private fun err(e: GuildError): Outcome<Nothing> = Outcome.Err(e)

/** Membership, permissions and levelling. All functions are pure: they return the updated guild. */
object GuildRules {
    const val MIN_LEADER_LEVEL = 5
    private val reservedNames = setOf("admin", "administrador", "moderador", "gymquest", "soporte", "oficial")

    fun maxMembers(guildLevel: Int): Int = (20 + guildLevel * 2).coerceAtMost(50)

    fun xpToReach(level: Int): Long = if (level <= 1) 0 else (1_000.0 * (level - 1).toDouble().pow(1.5)).toLong()

    fun levelFor(xp: Long): Int {
        var level = 1
        while (level < 30 && xp >= xpToReach(level + 1)) level++
        return level
    }

    /** Members get a small XP boost from a strong guild: +1% per level, up to +15%. */
    fun xpBonus(guildLevel: Int): Double = 1.0 + (guildLevel - 1).coerceIn(0, 15) * 0.01

    fun validateName(name: String): GuildError.InvalidName? {
        val n = name.trim()
        return when {
            n.length !in 3..24 -> GuildError.InvalidName("El nombre debe tener entre 3 y 24 caracteres.")
            !n.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' } -> GuildError.InvalidName("Solo letras, números, espacios, _ y -.")
            n.lowercase() in reservedNames || reservedNames.any { n.lowercase().startsWith("$it ") } -> GuildError.InvalidName("Ese nombre está reservado.")
            else -> null
        }
    }

    fun validateTag(tag: String): GuildError.InvalidTag? =
        if (tag.length in 2..4 && tag.all { it.isUpperCase() || it.isDigit() }) null
        else GuildError.InvalidTag("La etiqueta lleva de 2 a 4 letras mayúsculas o números.")

    fun create(id: String, leaderId: String, leaderName: String, leaderLevel: Int, name: String, tag: String, nowMs: Long): Outcome<Guild> {
        validateName(name)?.let { return err(it) }
        validateTag(tag)?.let { return err(it) }
        if (leaderLevel < MIN_LEADER_LEVEL) return err(GuildError.LevelTooLow)
        return ok(Guild(id, name.trim(), tag, listOf(GuildMember(leaderId, leaderName, GuildRole.LEADER, nowMs)), createdAtMs = nowMs))
    }

    fun join(g: Guild, userId: String, name: String, nowMs: Long): Outcome<Guild> = when {
        g.has(userId) -> err(GuildError.AlreadyMember)
        g.members.size >= maxMembers(g.level) -> err(GuildError.Full)
        else -> ok(g.copy(members = g.members + GuildMember(userId, name, GuildRole.MEMBER, nowMs)))
    }

    /** Returns null when the last member leaves and the guild dissolves. */
    fun leave(g: Guild, userId: String): Outcome<Guild?> {
        val m = g.member(userId) ?: return err(GuildError.NotMember)
        val rest = g.members.filter { it.userId != userId }
        if (rest.isEmpty()) return ok(null)
        if (m.role != GuildRole.LEADER) return ok(g.copy(members = rest))
        // Leadership passes to the longest-serving officer, else the longest-serving member.
        val heir = rest.sortedWith(compareBy({ it.role.ordinal }, { it.joinedAtMs })).first()
        return ok(g.copy(members = rest.map { if (it.userId == heir.userId) it.copy(role = GuildRole.LEADER) else it }))
    }

    fun kick(g: Guild, byUserId: String, targetId: String): Outcome<Guild> {
        val by = g.member(byUserId) ?: return err(GuildError.NotMember)
        val target = g.member(targetId) ?: return err(GuildError.NotMember)
        if (by.userId == target.userId) return err(GuildError.CannotTargetSelf)
        if (target.role == GuildRole.LEADER) return err(GuildError.CannotTargetLeader)
        val allowed = by.role == GuildRole.LEADER || (by.role == GuildRole.OFFICER && target.role == GuildRole.MEMBER)
        if (!allowed) return err(GuildError.NoPermission)
        return ok(g.copy(members = g.members.filter { it.userId != targetId }))
    }

    fun promote(g: Guild, byUserId: String, targetId: String): Outcome<Guild> = setRole(g, byUserId, targetId, GuildRole.OFFICER)

    fun demote(g: Guild, byUserId: String, targetId: String): Outcome<Guild> = setRole(g, byUserId, targetId, GuildRole.MEMBER)

    private fun setRole(g: Guild, byUserId: String, targetId: String, role: GuildRole): Outcome<Guild> {
        val by = g.member(byUserId) ?: return err(GuildError.NotMember)
        val target = g.member(targetId) ?: return err(GuildError.NotMember)
        if (by.role != GuildRole.LEADER) return err(GuildError.NoPermission)
        if (target.role == GuildRole.LEADER) return err(GuildError.CannotTargetLeader)
        return ok(g.copy(members = g.members.map { if (it.userId == targetId) it.copy(role = role) else it }))
    }

    fun transferLeadership(g: Guild, byUserId: String, toUserId: String): Outcome<Guild> {
        val by = g.member(byUserId) ?: return err(GuildError.NotMember)
        g.member(toUserId) ?: return err(GuildError.NotMember)
        if (by.role != GuildRole.LEADER) return err(GuildError.NoPermission)
        if (byUserId == toUserId) return err(GuildError.CannotTargetSelf)
        return ok(
            g.copy(
                members = g.members.map {
                    when (it.userId) {
                        toUserId -> it.copy(role = GuildRole.LEADER)
                        byUserId -> it.copy(role = GuildRole.OFFICER)
                        else -> it
                    }
                },
            ),
        )
    }

    fun addXp(g: Guild, amount: Long): Guild = g.copy(xp = g.xp + amount.coerceAtLeast(0))

    fun deposit(g: Guild, userId: String, amount: Long): Outcome<Guild> {
        if (!g.has(userId)) return err(GuildError.NotMember)
        if (amount <= 0) return err(GuildError.InvalidAmount)
        return ok(g.copy(treasury = g.treasury + amount))
    }

    /** Only officers and the leader may spend the treasury (for war stakes, perks). */
    fun withdraw(g: Guild, userId: String, amount: Long): Outcome<Guild> {
        val m = g.member(userId) ?: return err(GuildError.NotMember)
        if (m.role == GuildRole.MEMBER) return err(GuildError.NoPermission)
        if (amount <= 0) return err(GuildError.InvalidAmount)
        if (amount > g.treasury) return err(GuildError.InsufficientFunds)
        return ok(g.copy(treasury = g.treasury - amount))
    }
}
