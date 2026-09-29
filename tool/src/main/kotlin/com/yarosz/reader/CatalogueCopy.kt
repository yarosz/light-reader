package com.yarosz.reader

/** Catalogue copy, verbatim from DESIGN.md "Catalogues" (advisor rulings D14, D15 and N3c). */
const val CATALOGUES_EDIT = SHELF_EDIT
const val CATALOGUES_DONE = SHELF_DONE
const val CATALOGUES_TITLE = SHELF_ADD_A_BOOK
const val CATALOGUES_OFFLINE = "You're offline. Your Shelf still works."
const val CATALOGUES_ADD = "Add a Catalogue"
const val CATALOGUES_REMOVE = SHELF_REMOVE
const val CATALOGUES_CANCEL = SHELF_CANCEL
const val CATALOGUES_CONFIRM_REMOVE = "Remove this Catalogue? Books you added from it stay on your Shelf."
const val ADD_CATALOGUE_TITLE = "Add a Catalogue"
const val ADD_CATALOGUE_LABEL = "Catalogue address"
const val ADD_CATALOGUE_PLACEHOLDER = "https://…"
const val ADD_CATALOGUE_BUTTON = "Add"
const val ADD_CATALOGUE_DUPLICATE = "This Catalogue is already in your list."
const val ADD_BACK = "Add back"
const val PAGE_MORE = "More"
const val PAGE_LOADING = "loading…"
const val SEARCH = "Search"
const val SEARCH_STANDARD_EBOOKS = "Search all Standard Ebooks"
const val DETAIL_ADD = "Add to Shelf"
const val DETAIL_DOWNLOAD_AGAIN = "Download again"
const val DETAIL_READ = "Read"
const val DETAIL_ON_SHELF = "On your Shelf"
const val DETAIL_DOWNLOADING = ROW_DOWNLOADING
const val RETRY = "Retry"

const val COPY_UNREACHABLE = "Can't reach this Catalogue. Check your connection and try again."
const val COPY_NO_SUCH_HOST = "Couldn't find that address. Check the spelling."
const val COPY_NO_HTTPS = "This Catalogue needs an https:// address."
const val COPY_HTTP_ERROR = "This Catalogue isn't responding properly. Try again later."
const val COPY_NEEDS_SIGN_IN = "This Catalogue needs a username and password. Sign-in isn't supported yet."
const val COPY_UNREADABLE = "This address isn't a Catalogue the Reader can open."
const val COPY_UNTRUSTED = "This connection isn't trusted. If you're on public Wi-Fi, sign in to it, then try again."
const val COPY_UNTRUSTED_SELF_HOSTED = "A self-hosted Catalogue needs a public certificate."
const val COPY_NOT_AN_EPUB = "This file isn't an EPUB the Reader can open."
const val COPY_COPY_PROTECTED = "This Book is copy-protected and can't be opened here."
const val COPY_DISK_FULL = "There isn't enough space on your phone to add this Book."

/** "Add back Project Gutenberg": the row that adds a removed shipped Catalogue back. */
fun addBackLabel(catalogue: Catalogue) = "$ADD_BACK ${catalogue.name}"

/** A failure as the reader sees it: one plain line, and whether a Retry can help. */
data class FailureCopy(val text: String, val retry: Boolean)

/**
 * A Catalogue page, its search or an added address that failed. [shipped] is whether the Catalogue
 * ships with the Tool ([isShipped]): an untrusted certificate there is almost surely public Wi-Fi,
 * while a Catalogue the reader added may also have a private certificate (D15). [typedOnline] is
 * whether the address was just typed on a phone that reports a connection, the one case where a
 * host that doesn't resolve is most likely misspelt; offline, or for a Catalogue on the list, it is
 * Unreachable. It keeps Retry: the phone reporting a connection doesn't mean it has internet (a
 * Wi-Fi with a dead upstream, or DNS failing for a moment, fails a correct address the same way).
 */
fun feedFailureCopy(failure: FeedFailure, shipped: Boolean, typedOnline: Boolean = false): FailureCopy = when (failure) {
    is NetworkFailure -> networkFailureCopy(failure, shipped)
    Unreadable -> FailureCopy(COPY_UNREADABLE, retry = false)
    NoSuchHost -> if (typedOnline) FailureCopy(COPY_NO_SUCH_HOST, retry = true) else networkFailureCopy(Unreachable, shipped)
}

/** A download from a Catalogue that failed, shown on the Book's detail page. */
fun downloadFailureCopy(failure: DownloadFailure, shipped: Boolean): FailureCopy = when (failure) {
    is NetworkFailure -> networkFailureCopy(failure, shipped)
    NotAnEpub -> FailureCopy(COPY_NOT_AN_EPUB, failure.isRetryable)
    CopyProtected -> FailureCopy(COPY_COPY_PROTECTED, failure.isRetryable)
    DiskError -> FailureCopy(COPY_DISK_FULL, failure.isRetryable)
}

/** Retry follows [isRetryable], the rule a Shelf row's "tap to retry" follows too. */
private fun networkFailureCopy(failure: NetworkFailure, shipped: Boolean): FailureCopy {
    val text = when (failure) {
        Unreachable -> COPY_UNREACHABLE
        NoHttps -> COPY_NO_HTTPS
        UntrustedCertificate -> if (shipped) COPY_UNTRUSTED else "$COPY_UNTRUSTED $COPY_UNTRUSTED_SELF_HOSTED"
        is HttpError -> if (failure.status == 401) COPY_NEEDS_SIGN_IN else COPY_HTTP_ERROR
    }
    return FailureCopy(text, failure.isRetryable)
}
