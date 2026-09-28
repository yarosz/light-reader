package com.yarosz.reader

import java.net.URI

/** A Catalogue on the list, and whether the Tool ships with it. */
data class ListedCatalogue(val catalogue: Catalogue, val shipped: Boolean)

/**
 * Whether the Tool ships with the Catalogue at [url]. Decided by the URL alone: a reader who types
 * Gutenberg's address has the shipped Catalogue, with its shipped name and copy.
 */
fun isShipped(url: HttpsUrl): Boolean = SHIPPED_CATALOGUES.any { it.url == url }

/** The host, such as "books.example.org": the second line of a Catalogue the reader added. */
val HttpsUrl.host: String get() = URI(value).host.orEmpty()

/**
 * The list of Catalogues: those the Tool ships with that the reader hasn't removed, in shipped
 * order, then the ones the reader added, oldest first. A Catalogue added without a name shows its
 * host.
 */
fun ReadingData.catalogueList(): List<ListedCatalogue> {
    val shipped = SHIPPED_CATALOGUES.filter { catalogues[it.url.value]?.removed != true }.map { ListedCatalogue(it, shipped = true) }
    val added = catalogues.entries
        .filter { (url, record) -> !record.removed && SHIPPED_CATALOGUES.none { it.url.value == url } }
        .sortedBy { it.value.updatedAt }
        .mapNotNull { (url, record) ->
            HttpsUrl.parse(url)?.let { ListedCatalogue(Catalogue(record.name?.takeIf { it.isNotBlank() } ?: it.host, it), shipped = false) }
        }
    return shipped + added
}

/** The Catalogues the Tool ships with that the reader removed, which "Add a Catalogue" offers to add back. */
fun ReadingData.removedShipped(): List<Catalogue> = SHIPPED_CATALOGUES.filter { catalogues[it.url.value]?.removed == true }

/** Puts [catalogue] on the list, or back on it. A shipped Catalogue keeps its shipped name. */
fun ReadingData.withCatalogue(catalogue: Catalogue, now: Long): ReadingData =
    record(catalogue.url, CatalogueRecord(catalogue.name.takeUnless { isShipped(catalogue.url) }, removed = false, updatedAt = now))

/** Takes the Catalogue at [url] off the list. Books added from it stay on the Shelf. */
fun ReadingData.withoutCatalogue(url: HttpsUrl, now: Long): ReadingData =
    record(url, CatalogueRecord(catalogues[url.value]?.name, removed = true, updatedAt = now))

/** Stores [record], later than the one it replaces (as [withPlace] does), so a clock stepped back can't lose a [merge]. */
private fun ReadingData.record(url: HttpsUrl, record: CatalogueRecord): ReadingData {
    val old = catalogues[url.value]
    val updatedAt = old?.let { maxOf(record.updatedAt, it.updatedAt + 1) } ?: record.updatedAt
    return copy(catalogues = catalogues + (url.value to record.copy(updatedAt = updatedAt, extras = old?.extras.orEmpty())))
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
        when {
            catalogueList().any { it.catalogue.url == url } -> AddPlan.Duplicate
            isShipped(url) -> AddPlan.AddBack(SHIPPED_CATALOGUES.first { it.url == url })
            else -> AddPlan.Fetch(url)
        }
    }
}

/** The Catalogue a fetched [page] at [url] makes: named by the feed's title, else its host. */
fun catalogueFrom(page: CataloguePage, url: HttpsUrl): Catalogue = Catalogue(page.title.trim().ifEmpty { url.host }, url)

/** Whether the Catalogue list is browsing or editing, and in Edit, which Catalogue is asking to confirm its removal. */
sealed interface CatalogueListMode {
    data object Browsing : CatalogueListMode

    data class Editing(val confirming: HttpsUrl? = null) : CatalogueListMode
}
