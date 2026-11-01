package com.pdfmanager.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import com.pdfmanager.app.MainActivity
import com.pdfmanager.app.R

object Share {
    /** Opens the system share sheet for one or more PDFs. */
    fun pdfs(context: Context, uris: List<String>) = files(context, uris, "application/pdf", context.getString(R.string.result_share_pdf))

    /** Opens the system share sheet for one or more files of the same [mime] type. */
    fun files(context: Context, uris: List<String>, mime: String, chooserTitle: String) {
        if (uris.isEmpty()) return
        val list = uris.map { Uri.parse(it) }
        runCatching {
            val send = if (list.size == 1) {
                Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, list[0]) }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(list))
                }
            }
            send.type = mime
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(send, chooserTitle).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.share_unavailable), Toast.LENGTH_SHORT).show()
        }
    }

    /** Opens a file in another app (for example a text file in a text viewer). */
    fun view(context: Context, uri: String, mime: String) {
        runCatching {
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(open)
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.share_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    /** Lets the person choose another app to open a PDF; this app is left out of the list. */
    fun openWith(context: Context, uri: String) {
        runCatching {
            val open = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(uri), "application/pdf")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(open, context.getString(R.string.viewer_open_with)).apply {
                putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, MainActivity::class.java)))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(chooser)
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.share_no_app), Toast.LENGTH_SHORT).show()
        }
    }

    /** Puts [text] on the clipboard. Android 13+ shows its own "Copied" message. */
    fun copyText(context: Context, text: String) {
        runCatching {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, context.getString(R.string.common_copied), Toast.LENGTH_SHORT).show()
        }.onFailure {
            Toast.makeText(context, context.getString(R.string.share_copy_failed), Toast.LENGTH_SHORT).show()
        }
    }
}
