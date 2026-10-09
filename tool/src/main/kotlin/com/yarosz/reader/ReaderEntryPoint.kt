package com.yarosz.reader

import com.thelightphone.sdk.EntryPoint
import com.thelightphone.sdk.LightEntryPoint
import com.thelightphone.toolmanager.ClientLeafNode
import com.thelightphone.toolmanager.ClientToolManifest
import com.thelightphone.toolmanager.UploadSpec

/**
 * Reader's place in the Tool Manager: one "Add Books" page that uploads EPUBs into the inbox
 * ([importInbox]). LightOS reports uploads to [onToolManagerDataUpdate] from a worker in Reader's
 * process, which may have no screen. The SDK gives that worker no filesDir, so it imports through
 * the process's [ShelfOwner] when a screen has made one; otherwise the files wait in the inbox for
 * the Shelf, which imports whenever it shows.
 */
@EntryPoint
object ReaderEntryPoint : LightEntryPoint {
    override fun getToolManagerManifest() = ClientToolManifest(
        title = TOOL_MANAGER_TITLE,
        roots = listOf(ClientLeafNode(UploadSpec(label = ADD_BOOKS_LABEL, path = ADD_BOOKS_PATH, headerText = ADD_BOOKS_HEADER, buttonText = ADD_BOOKS_BUTTON))),
    )

    override suspend fun onToolManagerDataUpdate() {
        ShelfOwner.ofProcess()?.importBooks()?.join()
    }
}
