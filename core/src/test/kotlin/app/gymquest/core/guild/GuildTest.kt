package app.gymquest.core.guild

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun <T> Outcome<T>.get(): T = (this as Outcome.Ok).value
private fun Outcome<*>.error(): GuildError = (this as Outcome.Err).error

class GuildRulesTest {
    private fun base(): Guild = GuildRules.create("g1", "lead", "Líder", 10, "Los Gym Bros", "GYM", 0).get()

    private fun withMembers(): Guild {
        var g = base()
        g = GuildRules.join(g, "o1", "Oficial", 100).get()
        g = GuildRules.join(g, "m1", "Miembro 1", 200).get()
        g = GuildRules.join(g, "m2", "Miembro 2", 300).get()
        return GuildRules.promote(g, "lead", "o1").get()
    }

    @Test
    fun createsAGuildWithItsLeader() {
        val g = base()
        assertEquals(GuildRole.LEADER, g.leader.role)
        assertEquals(1, g.members.size)
        assertEquals(1, g.level)
        assertEquals("Los Gym Bros", g.name)
    }

    @Test
    fun validatesNamesTagsAndLeaderLevel() {
        assertTrue(GuildRules.create("g", "u", "U", 10, "ab", "GYM", 0).error() is GuildError.InvalidName)
        assertTrue(GuildRules.create("g", "u", "U", 10, "Nombre con símbolo!", "GYM", 0).error() is GuildError.InvalidName)
        assertTrue(GuildRules.create("g", "u", "U", 10, "Admin", "GYM", 0).error() is GuildError.InvalidName)
        assertTrue(GuildRules.create("g", "u", "U", 10, "x".repeat(30), "GYM", 0).error() is GuildError.InvalidName)
        assertTrue(GuildRules.create("g", "u", "U", 10, "Buen Nombre", "gym", 0).error() is GuildError.InvalidTag)
        assertTrue(GuildRules.create("g", "u", "U", 10, "Buen Nombre", "TOOLONG", 0).error() is GuildError.InvalidTag)
        assertEquals(GuildError.LevelTooLow, GuildRules.create("g", "u", "U", 2, "Buen Nombre", "GYM", 0).error())
    }

    @Test
    fun joiningRespectsDuplicatesAndCapacity() {
        val g = base()
        assertEquals(GuildError.AlreadyMember, GuildRules.join(g, "lead", "x", 1).error())
        var full = g
        var i = 0
        while (full.members.size < GuildRules.maxMembers(full.level)) {
            full = GuildRules.join(full, "u${i++}", "U", 1).get()
        }
        assertEquals(GuildError.Full, GuildRules.join(full, "extra", "X", 1).error())
        // A higher-level guild has more room.
        val bigger = full.copy(xp = GuildRules.xpToReach(10))
        assertTrue(GuildRules.join(bigger, "extra", "X", 1) is Outcome.Ok)
    }

    @Test
    fun levelsGrowWithXpAndGiveABoundedBonus() {
        assertEquals(1, GuildRules.levelFor(0))
        assertEquals(2, GuildRules.levelFor(1_000))
        assertTrue(GuildRules.levelFor(10_000_000) <= 30)
        assertEquals(1.0, GuildRules.xpBonus(1))
        assertEquals(1.15, GuildRules.xpBonus(99), 1e-9)
        assertEquals(40, GuildRules.maxMembers(10))
        assertEquals(50, GuildRules.maxMembers(30))
        assertEquals(3_000L, GuildRules.addXp(base(), 3_000).xp)
        assertEquals(0L, GuildRules.addXp(base(), -5).xp)
    }

    @Test
    fun officersKickMembersButNotEachOtherOrTheLeader() {
        val g = withMembers()
        assertEquals(3, GuildRules.kick(g, "o1", "m1").get().members.size)
        assertEquals(GuildError.CannotTargetLeader, GuildRules.kick(g, "o1", "lead").error())
        assertEquals(GuildError.NoPermission, GuildRules.kick(g, "m1", "m2").error())
        assertEquals(GuildError.CannotTargetSelf, GuildRules.kick(g, "lead", "lead").error())
        assertEquals(GuildError.NotMember, GuildRules.kick(g, "lead", "ghost").error())
        val withSecondOfficer = GuildRules.promote(g, "lead", "m1").get()
        assertEquals(GuildError.NoPermission, GuildRules.kick(withSecondOfficer, "o1", "m1").error())
        assertTrue(GuildRules.kick(withSecondOfficer, "lead", "m1") is Outcome.Ok)
    }

    @Test
    fun onlyTheLeaderChangesRoles() {
        val g = withMembers()
        assertEquals(GuildError.NoPermission, GuildRules.promote(g, "o1", "m1").error())
        assertEquals(GuildRole.OFFICER, GuildRules.promote(g, "lead", "m1").get().member("m1")!!.role)
        assertEquals(GuildRole.MEMBER, GuildRules.demote(g, "lead", "o1").get().member("o1")!!.role)
        assertEquals(GuildError.CannotTargetLeader, GuildRules.demote(g, "lead", "lead").error())
    }

    @Test
    fun transferringLeadershipMakesTheOldLeaderAnOfficer() {
        val g = GuildRules.transferLeadership(withMembers(), "lead", "m1").get()
        assertEquals("m1", g.leader.userId)
        assertEquals(GuildRole.OFFICER, g.member("lead")!!.role)
        assertEquals(GuildError.NoPermission, GuildRules.transferLeadership(g, "lead", "m2").error())
        assertEquals(1, g.members.count { it.role == GuildRole.LEADER })
    }

    @Test
    fun whenTheLeaderLeavesTheOfficerInherits() {
        val g = GuildRules.leave(withMembers(), "lead").get()!!
        assertEquals("o1", g.leader.userId)
        assertEquals(3, g.members.size)
        val g2 = GuildRules.leave(g, "m1").get()!!
        assertEquals(2, g2.members.size)
        assertEquals(GuildError.NotMember, GuildRules.leave(g2, "m1").error())
    }

    @Test
    fun theLastMemberLeavingDissolvesTheGuild() {
        assertNull(GuildRules.leave(base(), "lead").get())
    }

    @Test
    fun treasuryPermissions() {
        var g = withMembers()
        g = GuildRules.deposit(g, "m1", 500).get()
        assertEquals(500, g.treasury)
        assertEquals(GuildError.NoPermission, GuildRules.withdraw(g, "m1", 100).error())
        assertEquals(GuildError.InsufficientFunds, GuildRules.withdraw(g, "o1", 900).error())
        assertEquals(GuildError.InvalidAmount, GuildRules.withdraw(g, "o1", 0).error())
        assertEquals(GuildError.InvalidAmount, GuildRules.deposit(g, "m1", -5).error())
        assertEquals(GuildError.NotMember, GuildRules.deposit(g, "ghost", 5).error())
        assertEquals(200, GuildRules.withdraw(g, "o1", 300).get().treasury)
    }
}
