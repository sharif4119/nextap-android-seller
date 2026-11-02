package com.example.nfcwritter.ui

import android.annotation.TargetApi
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.nfcwritter.R
import com.example.nfcwritter.data.ApiResult
import com.example.nfcwritter.data.NfcCard
import com.example.nfcwritter.data.PrintDesignDocument
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PrintDesignActions(
    private val fragment: Fragment,
    private val fetch: suspend (NfcCard) -> ApiResult<PrintDesignDocument>,
) {
    private enum class Action { PREVIEW, DOWNLOAD, SHARE }

    private var currentCard: NfcCard? = null
    private var job: Job? = null
    private var pendingSafDocument: PrintDesignDocument? = null
    private var section: View? = null
    private var status: TextView? = null
    private var progress: ProgressBar? = null
    private var preview: MaterialButton? = null
    private var download: MaterialButton? = null
    private var share: MaterialButton? = null

    private val createDocument = fragment.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf"),
    ) { uri ->
        val document = pendingSafDocument
        pendingSafDocument = null
        if (uri == null || document == null) return@registerForActivityResult
        fragment.lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    fragment.requireContext().contentResolver.openOutputStream(uri, "w")?.use {
                        it.write(document.bytes)
                    } ?: error("Could not open the selected file.")
                }
            }.onSuccess {
                showMessage("PDF saved successfully.", false)
            }.onFailure {
                showMessage("Could not save the PDF. ${it.message.orEmpty()}".trim(), true)
            }
        }
    }

    fun bind(root: View) {
        section = root.findViewById(R.id.design_section)
        status = root.findViewById(R.id.design_status)
        progress = root.findViewById(R.id.design_progress)
        preview = root.findViewById<MaterialButton>(R.id.preview_design_button).also {
            it.setOnClickListener { perform(Action.PREVIEW) }
        }
        download = root.findViewById<MaterialButton>(R.id.download_design_button).also {
            it.setOnClickListener { perform(Action.DOWNLOAD) }
        }
        share = root.findViewById<MaterialButton>(R.id.share_design_button).also {
            it.setOnClickListener { perform(Action.SHARE) }
        }
    }

    fun show(card: NfcCard?) {
        currentCard = card
        section?.visibility = if (card == null) View.GONE else View.VISIBLE
        if (card == null) return
        val available = !card.printDesignUrl.isNullOrBlank()
        setButtonsEnabled(available)
        showMessage(
            if (available) "Print-ready front and back PDF supplied by NexTap."
            else "The print-ready design is not available yet. Refresh after card preparation.",
            !available,
        )
    }

    fun clear() {
        job?.cancel()
        job = null
        currentCard = null
        section = null
        status = null
        progress = null
        preview = null
        download = null
        share = null
    }

    private fun perform(action: Action) {
        val card = currentCard ?: return
        if (job?.isActive == true) return
        job = fragment.viewLifecycleOwner.lifecycleScope.launch {
            setBusy(true)
            showMessage("Loading print-ready PDF…", false)
            when (val result = fetch(card)) {
                is ApiResult.Error -> showMessage(result.message, true)
                is ApiResult.Success -> runCatching {
                    when (action) {
                        Action.PREVIEW -> preview(result.data)
                        Action.DOWNLOAD -> download(result.data)
                        Action.SHARE -> share(result.data)
                    }
                }.onFailure {
                    showMessage(it.message ?: "Could not open the PDF.", true)
                }
            }
            setBusy(false)
        }
    }

    private suspend fun preview(document: PrintDesignDocument) {
        val uri = withContext(Dispatchers.IO) { cacheUri(document) }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            fragment.startActivity(intent)
            showMessage("Opened PDF preview.", false)
        } catch (_: ActivityNotFoundException) {
            error("No PDF viewer is installed on this device.")
        }
    }

    private suspend fun download(document: PrintDesignDocument) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            pendingSafDocument = document
            createDocument.launch(document.fileName)
            showMessage("Choose where to save the PDF.", false)
            return
        }
        val uri = withContext(Dispatchers.IO) { saveToDownloads(fragment.requireContext(), document) }
        showMessage("Saved ${document.fileName} to Downloads/NexTap.", false)
        // Retain the URI only through the system collection; no broad storage permission is needed.
        check(uri != Uri.EMPTY)
    }

    private suspend fun share(document: PrintDesignDocument) {
        val uri = withContext(Dispatchers.IO) { cacheUri(document) }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "NexTap card print design")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        fragment.startActivity(Intent.createChooser(intent, "Share print-ready PDF"))
        showMessage("Choose an app to share the PDF.", false)
    }

    private fun cacheUri(document: PrintDesignDocument): Uri {
        val context = fragment.requireContext()
        val directory = File(context.cacheDir, "print-designs").apply { mkdirs() }
        val file = File(directory, document.fileName).apply { writeBytes(document.bytes) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    @TargetApi(Build.VERSION_CODES.Q)
    private fun saveToDownloads(context: Context, document: PrintDesignDocument): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, document.fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/NexTap")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create the PDF in Downloads.")
        return try {
            resolver.openOutputStream(uri, "w")?.use { it.write(document.bytes) }
                ?: error("Could not write the PDF to Downloads.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun setBusy(busy: Boolean) {
        progress?.visibility = if (busy) View.VISIBLE else View.GONE
        setButtonsEnabled(!busy && !currentCard?.printDesignUrl.isNullOrBlank())
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        preview?.isEnabled = enabled
        download?.isEnabled = enabled
        share?.isEnabled = enabled
    }

    private fun showMessage(message: String, error: Boolean) {
        status?.apply {
            text = message
            setTextColor(context.getColor(if (error) R.color.status_error else R.color.text_secondary))
        }
    }
}
