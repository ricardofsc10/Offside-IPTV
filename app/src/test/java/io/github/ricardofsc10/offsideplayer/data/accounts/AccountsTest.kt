package io.github.ricardofsc10.offsideplayer.data.accounts

import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountsTest {
    private fun account(id: String) = Account(
        id = id,
        name = "Conta $id",
        server = "http://example.test:8080",
        username = "test",
        password = "test",
        databaseName = Accounts.databaseName(id),
    )

    @Test
    fun `default name is the server host`() {
        assertEquals("example.test", Accounts.defaultName("http://example.test:8080"))
        assertEquals("example.test", Accounts.displayName("  ", "http://example.test:8080/sub"))
        assertEquals("Casa", Accounts.displayName(" Casa ", "http://example.test"))
    }

    @Test
    fun `adding an account makes it the active one`() {
        val list = Accounts.upsertAndActivate(AccountList(listOf(account("a")), "a"), account("b"))
        assertEquals(listOf("a", "b"), list.accounts.map { it.id })
        assertEquals("b", list.active?.id)
    }

    @Test
    fun `editing an account keeps the position and the active one`() {
        val list = AccountList(listOf(account("a"), account("b")), "a")
        val updated = Accounts.update(list, account("b").copy(name = "Nova"))
        assertEquals(listOf("a", "b"), updated.accounts.map { it.id })
        assertEquals("Nova", updated.accounts[1].name)
        assertEquals("a", updated.active?.id)
    }

    @Test
    fun `activating an unknown account changes nothing`() {
        val list = AccountList(listOf(account("a")), "a")
        assertEquals(list, Accounts.activate(list, "x"))
        assertEquals("b", Accounts.activate(AccountList(listOf(account("a"), account("b")), "a"), "b").activeId)
    }

    @Test
    fun `removing the active account activates the first one left`() {
        val list = AccountList(listOf(account("a"), account("b"), account("c")), "b")
        val removed = Accounts.remove(list, "b")
        assertEquals(listOf("a", "c"), removed.accounts.map { it.id })
        assertEquals("a", removed.activeId)
    }

    @Test
    fun `removing another account keeps the active one`() {
        val removed = Accounts.remove(AccountList(listOf(account("a"), account("b")), "b"), "a")
        assertEquals("b", removed.activeId)
    }

    @Test
    fun `removing the last account leaves none active`() {
        val removed = Accounts.remove(AccountList(listOf(account("a")), "a"), "a")
        assertTrue(removed.accounts.isEmpty())
        assertNull(removed.active)
    }

    @Test
    fun `legacy account keeps the old database and guide`() {
        val list = Accounts.fromLegacy(XtreamCredentials("http://example.test:8080", "test", "test"), "http://example.test/epg.xml")
        val account = list.active!!
        assertEquals(Accounts.LEGACY_DATABASE, account.databaseName)
        assertEquals("http://example.test/epg.xml", account.epgUrl)
        assertEquals("example.test", account.name)
    }

    @Test
    fun `new accounts never reuse the legacy database`() {
        assertFalse(Accounts.databaseName(Accounts.LEGACY_ID) == Accounts.LEGACY_DATABASE)
    }

    @Test
    fun `toString never shows credentials`() {
        val text = account("a").toString() + AccountList(listOf(account("a")), "a").toString()
        assertFalse("password" in text.lowercase() || "test" in text)
    }
}
