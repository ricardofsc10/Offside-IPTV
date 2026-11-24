package io.github.ricardofsc10.offsideplayer.data.accounts

import io.github.ricardofsc10.offsideplayer.data.xtream.XtreamCredentials
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Uma conta do fornecedor, com a sua própria base de dados: canais, favoritos, progresso, categorias
 * escondidas e guia não se misturam entre contas.
 */
@Serializable
data class Account(
    val id: String,
    val name: String,
    val server: String,
    val username: String,
    val password: String,
    /** Endereço XMLTV do guia. Pode ter credenciais, por isso nunca vai para os registos. */
    val epgUrl: String = "",
    /** Nome do ficheiro da base de dados. A conta migrada da versão com uma só conta usa o antigo. */
    val databaseName: String,
) {
    val credentials: XtreamCredentials get() = XtreamCredentials(server, username, password)

    // Evita que as credenciais apareçam em logs ou em mensagens de erro.
    override fun toString(): String = "Account($id)"
}

@Serializable
data class AccountList(
    val accounts: List<Account> = emptyList(),
    val activeId: String? = null,
) {
    val active: Account? get() = accounts.firstOrNull { it.id == activeId } ?: accounts.firstOrNull()

    override fun toString(): String = "AccountList(${accounts.size}, active=$activeId)"
}

/** Regras puras da lista de contas, sem Android, para se poderem testar no computador. */
object Accounts {
    /** O ficheiro que a app usava antes de haver várias contas. */
    const val LEGACY_DATABASE = "library.db"
    const val LEGACY_ID = "legacy"

    fun databaseName(id: String): String = "library-$id.db"

    /** Sem nome escrito, a conta fica com o nome do servidor, sem esquema nem porta. */
    fun defaultName(server: String): String =
        server.toHttpUrlOrNull()?.host ?: server.substringAfter("://").substringBefore('/').substringBefore(':')

    fun displayName(typed: String, server: String): String = typed.trim().ifEmpty { defaultName(server) }

    /** Acrescenta ou substitui a conta com o mesmo ID, e torna-a a ativa. */
    fun upsertAndActivate(list: AccountList, account: Account): AccountList {
        val exists = list.accounts.any { it.id == account.id }
        val accounts = if (exists) list.accounts.map { if (it.id == account.id) account else it } else list.accounts + account
        return AccountList(accounts, account.id)
    }

    /** Só substitui, sem mudar a conta ativa. */
    fun update(list: AccountList, account: Account): AccountList =
        list.copy(accounts = list.accounts.map { if (it.id == account.id) account else it })

    fun activate(list: AccountList, id: String): AccountList =
        if (list.accounts.any { it.id == id }) list.copy(activeId = id) else list

    /** Ao remover a conta ativa, passa a ativa a primeira que sobrar. */
    fun remove(list: AccountList, id: String): AccountList {
        val accounts = list.accounts.filterNot { it.id == id }
        val activeId = if (list.activeId == id || accounts.none { it.id == list.activeId }) {
            accounts.firstOrNull()?.id
        } else {
            list.activeId
        }
        return AccountList(accounts, activeId)
    }

    /** A conta única da versão anterior, com o ficheiro e o guia que já tinha. */
    fun fromLegacy(credentials: XtreamCredentials, epgUrl: String): AccountList {
        val account = Account(
            id = LEGACY_ID,
            name = defaultName(credentials.server),
            server = credentials.server,
            username = credentials.username,
            password = credentials.password,
            epgUrl = epgUrl,
            databaseName = LEGACY_DATABASE,
        )
        return AccountList(listOf(account), account.id)
    }
}
