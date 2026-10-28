package org.matrix.chromext.extension

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.widget.ProgressBar
import android.widget.Toast
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import org.matrix.chromext.Chrome
import org.matrix.chromext.utils.Log

/**
 * Runs inside the target browser. The browser owns the SAF picker and opens the chosen URI.
 * Extension files are staged only; they are never activated by this UI.
 */
object ExtensionManager {
  private const val PICK_EXTENSION_ZIP = 0x67CD
  private val hookedMethods = mutableSetOf<Method>()
  private var pendingActivity: WeakReference<Activity>? = null

  private fun findActivity(context: Context): Activity? {
    var current: Context = context
    repeat(12) {
      val activity = current as? Activity
      if (activity != null) return activity
      val wrapper = current as? ContextWrapper ?: return null
      val base = wrapper.baseContext
      if (base === current) return null
      current = base
    }
    return null
  }

  fun show(context: Context) {
    val activity = findActivity(context)
    if (activity == null) {
      Log.e("No active browser Activity for extension manager")
      return
    }
    activity.runOnUiThread { showList(activity) }
  }

  private fun showList(activity: Activity) {
    if (activity.isFinishing || activity.isDestroyed) return
    val items = LocalFiles.listImported()
    val labels =
        items.map { "${it.name} · v${it.version} (inactive)" } +
            listOf("+ Import extension ZIP")
    AlertDialog.Builder(activity)
        .setTitle("ChromeXt extensions (preview)")
        .setMessage("Imported packages are stored locally but cannot run yet.")
        .setItems(labels.toTypedArray()) { _, position ->
          if (position == items.size) {
            AlertDialog.Builder(activity)
                .setTitle("Choose a trusted extension ZIP")
                .setMessage(
                    "Requires manifest.json at the archive root. CRX files are not supported. " +
                        "The imported extension remains inactive.")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton("Choose ZIP") { _, _ -> openPicker(activity) }
                .show()
          } else {
            showDetails(activity, items[position])
          }
        }
        .setNegativeButton(android.R.string.cancel, null)
        .show()
  }

  private fun showDetails(activity: Activity, item: ImportedExtension) {
    val details =
        "Version: ${item.version}\nManifest: V${item.manifestVersion}\n" +
            "Local ID: ${item.id}\nStatus: inactive (runtime not implemented)"
    AlertDialog.Builder(activity)
        .setTitle(item.name)
        .setMessage(details)
        .setNegativeButton(android.R.string.cancel, null)
        .setNeutralButton("Back") { _, _ -> showList(activity) }
        .setPositiveButton("Delete") { _, _ ->
          AlertDialog.Builder(activity)
              .setTitle("Delete ${item.name}?")
              .setMessage("Only this imported package's files will be removed.")
              .setNegativeButton(android.R.string.cancel, null)
              .setPositiveButton("Delete") { _, _ ->
                Chrome.IO.execute {
                  val deleted =
                      runCatching { LocalFiles.removeImported(item.id) }.getOrDefault(false)
                  activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                      toast(activity, if (deleted) "Package deleted" else "Unable to delete package")
                      showList(activity)
                    }
                  }
                }
              }
              .show()
        }
        .show()
  }

  private fun toast(activity: Activity, message: String) {
    Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
  }

  /**
   * Hook the Activity's overrides as well as the base method. Chrome Activity subclasses may
   * consume results without calling super. Android 15+ also has a four-argument overload.
   */
  private fun ensureResultHook(activity: Activity): Boolean {
    var type: Class<*>? = activity.javaClass
    var available = false
    while (type != null && Activity::class.java.isAssignableFrom(type)) {
      for (method in type.declaredMethods) {
        val args = method.parameterTypes
        if (method.name != "onActivityResult" ||
            method.returnType != Void.TYPE ||
            args.size !in 3..4 ||
            args[0] != Int::class.javaPrimitiveType ||
            args[1] != Int::class.javaPrimitiveType ||
            args[2] != Intent::class.java) continue

        synchronized(hookedMethods) {
          if (hookedMethods.contains(method)) {
            available = true
          } else {
            runCatching {
                  XposedBridge.hookMethod(
                      method,
                      object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                          if (param.args[0] != PICK_EXTENSION_ZIP) return
                          val owner = param.thisObject as? Activity ?: return
                          if (pendingActivity?.get() !== owner) return
                          pendingActivity = null
                          param.result = null
                          if (param.args[1] == Activity.RESULT_OK) {
                            val uri = (param.args[2] as? Intent)?.data
                            owner.runOnUiThread { importPicked(owner, uri) }
                          }
                        }
                      })
                }
                .onSuccess {
                  hookedMethods.add(method)
                  available = true
                }
                .onFailure { Log.ex(it) }
          }
        }
      }
      type = type.superclass
    }
    return available
  }

  private fun openPicker(activity: Activity) {
    if (pendingActivity?.get() != null) {
      toast(activity, "Finish the existing picker first")
      return
    }
    if (!ensureResultHook(activity)) {
      toast(activity, "Picker hook is not available for this Chrome version")
      return
    }
    val intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
          addCategory(Intent.CATEGORY_OPENABLE)
          type = "*/*"
          putExtra(
              Intent.EXTRA_MIME_TYPES,
              arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
          addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    pendingActivity = WeakReference(activity)
    runCatching { activity.startActivityForResult(intent, PICK_EXTENSION_ZIP) }
        .onFailure {
          pendingActivity = null
          Log.ex(it)
          toast(activity, "Cannot open the Android file picker")
        }
  }

  private fun importPicked(activity: Activity, uri: Uri?) {
    if (uri == null || uri.scheme != "content") {
      toast(activity, "No readable ZIP selected")
      return
    }
    val spinner = ProgressBar(activity)
    spinner.setPadding(32, 24, 32, 24)
    val progress =
        AlertDialog.Builder(activity)
            .setTitle("Importing ZIP")
            .setView(spinner)
            .setCancelable(false)
            .create()
    progress.show()
    Chrome.IO.execute {
      val result =
          runCatching {
            val source =
                activity.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Selected file is unreadable")
            source.use { LocalFiles.importZip(it) }
          }
      activity.runOnUiThread {
        progress.dismiss()
        if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
        result
            .onSuccess {
              toast(activity, "Imported ${it.name} (inactive)")
              showList(activity)
            }
            .onFailure {
              Log.e("Extension import rejected: ${it.javaClass.simpleName}")
              AlertDialog.Builder(activity)
                  .setTitle("Import failed")
                  .setMessage(it.message ?: "Invalid or unreadable ZIP")
                  .setPositiveButton(android.R.string.ok, null)
                  .show()
            }
      }
    }
  }
}
