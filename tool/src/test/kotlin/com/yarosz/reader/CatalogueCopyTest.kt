package com.yarosz.reader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every Catalogue failure's copy, verbatim from D14 and D15, and whether it offers Retry. */
class CatalogueCopyTest {

    @Test
    fun `each Catalogue failure has its copy, with Retry only where retrying can help`() {
        val expected = listOf(
            Unreachable to FailureCopy("Can't reach this Catalogue. Check your connection and try again.", retry = true),
            NoHttps to FailureCopy("This Catalogue needs an https:// address.", retry = false),
            HttpError(500) to FailureCopy("This Catalogue isn't responding properly. Try again later.", retry = true),
            HttpError(404) to FailureCopy("This Catalogue isn't responding properly. Try again later.", retry = true),
            HttpError(401) to FailureCopy("This Catalogue needs a username and password. Sign-in isn't supported yet.", retry = false),
            Unreadable to FailureCopy("This address isn't a Catalogue the Reader can open.", retry = false),
            UntrustedCertificate to FailureCopy("This connection isn't trusted. If you're on public Wi-Fi, sign in to it, then try again.", retry = true),
        )
        expected.forEach { (failure, copy) -> assertEquals(copy, feedFailureCopy(failure, shipped = true), failure.toString()) }
    }

    @Test
    fun `an untrusted certificate on a Catalogue the reader added also asks for a public certificate`() {
        assertEquals(
            FailureCopy(
                "This connection isn't trusted. If you're on public Wi-Fi, sign in to it, then try again. A self-hosted Catalogue needs a public certificate.",
                retry = true,
            ),
            feedFailureCopy(UntrustedCertificate, shipped = false),
        )
        assertEquals(feedFailureCopy(Unreachable, shipped = true), feedFailureCopy(Unreachable, shipped = false))
    }

    @Test
    fun `a host that doesn't resolve reads as a misspelling, with Retry, only for an address typed on a connected phone`() {
        assertEquals(FailureCopy("Couldn't find that address. Check the spelling.", retry = true), feedFailureCopy(NoSuchHost, shipped = false, typedOnline = true))
        assertEquals(feedFailureCopy(Unreachable, shipped = false), feedFailureCopy(NoSuchHost, shipped = false))
        assertEquals(feedFailureCopy(Unreachable, shipped = true), feedFailureCopy(NoSuchHost, shipped = true))
    }

    @Test
    fun `each download failure has its copy, and only the ones a retry can fix offer Retry`() {
        val expected = listOf(
            NotAnEpub to FailureCopy("This file isn't an EPUB the Reader can open.", retry = false),
            CopyProtected to FailureCopy("This Book is copy-protected and can't be opened here.", retry = false),
            DiskError to FailureCopy("There isn't enough space on your phone to add this Book.", retry = true),
            Unreachable to FailureCopy(COPY_UNREACHABLE, retry = true),
            NoHttps to FailureCopy(COPY_NO_HTTPS, retry = false),
            HttpError(503) to FailureCopy(COPY_HTTP_ERROR, retry = true),
            HttpError(401) to FailureCopy(COPY_NEEDS_SIGN_IN, retry = false),
            UntrustedCertificate to FailureCopy(COPY_UNTRUSTED, retry = true),
        )
        expected.forEach { (failure, copy) -> assertEquals(copy, downloadFailureCopy(failure, shipped = true), failure.toString()) }
        expected.forEach { (failure, copy) -> assertEquals(failure.isRetryable, copy.retry, "Retry follows isRetryable: $failure") }
    }

    @Test
    fun `copy capitalises Book, Shelf and Catalogue, and the domain terms are never lowercase in a sentence`() {
        val sentences = listOf(
            COPY_UNREACHABLE, COPY_NO_SUCH_HOST, COPY_NO_HTTPS, COPY_HTTP_ERROR, COPY_NEEDS_SIGN_IN, COPY_UNREADABLE, COPY_UNTRUSTED,
            COPY_UNTRUSTED_SELF_HOSTED, COPY_NOT_AN_EPUB, COPY_COPY_PROTECTED, COPY_DISK_FULL, CATALOGUES_OFFLINE,
            CATALOGUES_CONFIRM_REMOVE, ADD_CATALOGUE_DUPLICATE, CATALOGUES_ADD, DETAIL_ADD, DETAIL_ON_SHELF,
        )
        val lower = Regex("\\b(book|shelf|catalogue)s?\\b")
        sentences.forEach { assertTrue(lower.find(it) == null, it) }
        assertEquals("Add back Project Gutenberg", addBackLabel(GUTENBERG))
    }
}
