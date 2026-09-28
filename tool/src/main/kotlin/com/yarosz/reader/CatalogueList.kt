package com.yarosz.reader

import java.net.URI

/** A Catalogue on the list, and whether the Tool ships with it. */
data class ListedCatalogue(val catalogue: Catalogue, val shipped: Boolean)

/**
 * What a Catalogue URL is compared and stored by ([ReadingData.catalogues]): the host lowercased, the
 * default port dropped, an empty path read as "/", one trailing slash on a longer path ignored, and
 * the fragment, which never reaches the server, dropped. So "https://Books.example.org/opds/" and
 * "https://books.example.org/opds" are one Catalogue. It only compares: a Catalogue is fetched at its
 * own URL.
 */
@JvmInline
value class CatalogueKey private constructor(val value: String) {
    /** The key as a URL: a Catalogue whose record names no other [CatalogueRecord.url] is fetched here. */
    val url: HttpsUrl get() = checkNotNull(HttpsUrl.parse(value)) { "a key is always a URL" }

    companion object {
        fun of(url: HttpsUrl): CatalogueKey {
            val uri = URI(url.value)
            val path = uri.rawPath.orEmpty().ifEmpty { "/" }.let { if (it.length > 1) it.removeSuffix("/") else it }
            return CatalogueKey(
                buildString {
                    append("https://")
                    uri.rawUserInfo?.let { append(it).append('@') }
                    append(uri.host.lowercase())
                    if (uri.port != -1 && uri.port != 443) append(':').append(uri.port)
                    append(path)
                    uri.rawQuery?.let { append('?').append(it) }
                },
            )
        }
    }
}

val HttpsUrl.catalogueKey: CatalogueKey get() = CatalogueKey.of(this)

/**
 * Whether the Tool ships with the Catalogue at [url]. Decided by the URL's [catalogueKey] alone: a
 * reader who types Gutenberg's address, with or without its trailing slash, has the shipped
 * Catalogue, with its shipped name and copy.
 */
fun isShipped(url: HttpsUrl): Boolean = shippedAt(url.catalogueKey) != null

private fun shippedAt(key: CatalogueKey): Catalogue? = SHIPPED_CATALOGUES.firstOrNull { it.url.catalogueKey == key }

/** The host, such as "books.example.org": the second line of a Catalogue the reader added. */
val HttpsUrl.host: String get() = URI(value).host.orEmpty()

/**
 * The list of Catalogues: those the Tool ships with that the reader hasn't removed, in shipped
 * order, then the ones the reader added, oldest first. A Catalogue added without a name shows its
 * host.
 */
fun ReadingData.catalogueList(): List<ListedCatalogue> {
    val shipped = SHIPPED_CATALOGUES.filter { catalogues[it.url.catalogueKey]?.removed != true }.map { ListedCatalogue(it, shipped = true) }
    val added = catalogues.entries
        .filter { (key, record) -> !record.removed && shippedAt(key) == null }
        .sortedBy { it.value.updatedAt }
        .map { (key, record) ->
            val url = record.url ?: key.url
            ListedCatalogue(Catalogue(record.name?.takeIf { it.isNotBlank() } ?: url.host, url), shipped = false)
        }
    return shipped + added
}

/** The Catalogues the Tool ships with that the reader removed, which "Add a Catalogue" offers to add back. */
fun ReadingData.removedShipped(): List<Catalogue> = SHIPPED_CATALOGUES.filter { catalogues[it.url.catalogueKey]?.removed == true }

/**
 * Puts [catalogue] on the list, or back on it. A shipped Catalogue keeps its shipped name and URL; one
 * the reader added keeps its own URL, as [HttpsUrl.plain], when that isn't its key's (see [CatalogueRecord.url]).
 */
fun ReadingData.withCatalogue(catalogue: Catalogue, now: Long): ReadingData {
    val key = catalogue.url.catalogueKey
    val own = shippedAt(key) == null
    return record(key, CatalogueRecord(catalogue.name.takeIf { own }, catalogue.url.plain.takeIf { own && it != key.url }, removed = false, updatedAt = now))
}

/** Takes the Catalogue at [url] off the list. Books added from it stay on the Shelf. */
fun ReadingData.withoutCatalogue(url: HttpsUrl, now: Long): ReadingData {
    val old = catalogues[url.catalogueKey]
    return record(url.catalogueKey, CatalogueRecord(old?.name, old?.url, removed = true, updatedAt = now))
}

/** Stores [record], later than the one it replaces (as [withPlace] does), so a clock stepped back can't lose a [merge]. */
private fun ReadingData.record(key: CatalogueKey, record: CatalogueRecord): ReadingData {
    val old = catalogues[key]
    val updatedAt = old?.let { maxOf(record.updatedAt, it.updatedAt + 1) } ?: record.updatedAt
    return copy(catalogues = catalogues + (key to record.copy(updatedAt = updatedAt, extras = old?.extras.orEmpty())))
}

/**
 * The URL a typed Catalogue address stands for. With no scheme, https:// is assumed, and http:// is
 * tried as https:// once (see [HttpsUrl.upgraded]). Any other scheme can't be used (NoHttps);
 * anything else that isn't a URL with a host isn't a Catalogue (Unreadable).
 */
fun catalogueAddress(typed: String): Fetched<HttpsUrl> {
    val trimmed = typed.trim()
    val scheme = trimmed.substringBefore("://", missingDelimiterValue = "")
    val url = HttpsUrl.parse(if (scheme.isEmpty()) "https://$trimmed" else trimmed)
    return when {
        url != null -> Fetched.Ok(url)
        scheme.isNotEmpty() && scheme.lowercase() !in setOf("http", "https") -> Fetched.Failed(NoHttps)
        else -> Fetched.Failed(Unreadable)
    }
}

/** What "Add" does with a typed address, before anything is fetched. */
sealed interface AddPlan {
    /** Fetch the feed at [url]; it is added only if it parses as a Catalogue. */
    data class Fetch(val url: HttpsUrl) : AddPlan

    /** The address is a shipped Catalogue the reader removed: add it back, with nothing to fetch. */
    data class AddBack(val catalogue: Catalogue) : AddPlan

    /** The Catalogue is already on the list. */
    data object Duplicate : AddPlan

    data class Invalid(val reason: FeedFailure) : AddPlan
}

fun ReadingData.planAdd(typed: String): AddPlan = when (val address = catalogueAddress(typed)) {
    is Fetched.Failed -> AddPlan.Invalid(address.reason)
    is Fetched.Ok -> {
        val url = address.value
        val key = url.catalogueKey
        val shipped = shippedAt(key)
        when {
            catalogueList().any { it.catalogue.url.catalogueKey == key } -> AddPlan.Duplicate
            shipped != null -> AddPlan.AddBack(shipped)
            else -> AddPlan.Fetch(url)
        }
    }
}

/** The Catalogue a fetched [page] at [url] makes: named by the feed's title, else its host, and at [url] as [HttpsUrl.plain]. */
fun catalogueFrom(page: CataloguePage, url: HttpsUrl): Catalogue = Catalogue(page.title.trim().ifEmpty { url.host }, url.plain)

/** Whether the Catalogue list is browsing or editing, and in Edit, which Catalogue is asking to confirm its removal. */
sealed interface CatalogueListMode {
    data object Browsing : CatalogueListMode

    data class Editing(val confirming: HttpsUrl? = null) : CatalogueListMode
}
