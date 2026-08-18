package com.pbzin.googlephotosmod

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.app.AndroidAppHelper
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.MediaCodec
import android.os.Build
import android.net.TrafficStats
import android.view.View
import android.provider.MediaStore
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Shows the real filename of video items in the Google Photos grid.
 *
 * The important part is that this does not inspect Cursor strings or walk an
 * arbitrary object graph.  In the current Photos build the grid item is:
 *
 * RecyclerView.ViewHolder -> f65819T (apcu) -> f38145a (btcc media)
 * btcc.mo3361b(aeiy.class) -> aeiy.f11113a (the filename feature).
 */
class MainHook : IXposedHookLoadPackage {
    private var textPaint: Paint? = null
    private var backgroundPaint: Paint? = null
    private var loggedFilenameLoad = false
    private var loggedCodecHookCall = false
    private val filenameCache = ConcurrentHashMap<Long, String>()
    private val filenameLoads = ConcurrentHashMap.newKeySet<Long>()
    private val mediaStoreFilenameCache = ConcurrentHashMap<Long, String>()
    private val filenameExecutor = Executors.newSingleThreadExecutor()
    private val backupJobExecutor = Executors.newSingleThreadScheduledExecutor()
    private val backupJobs = ConcurrentHashMap<Int, Long>()
    private val delayedBackupFinishes = ConcurrentHashMap.newKeySet<String>()
    private val backupJobIds = setOf(1026, 1043, 1046)
    private val modulePreferences = XSharedPreferences(
        ModuleSettings.PACKAGE_NAME,
        ModuleSettings.PREFS_NAME
    )
    @Volatile
    private var broadcastSettingKnown = false
    @Volatile
    private var broadcastSettingEnabled = false
    @Volatile
    private var holdBackupJobEnabled = false
    @Volatile
    private var settingsReceiverRegistered = false
    private val settingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ModuleSettings.GET_SETTINGS_ACTION) return
            broadcastSettingEnabled = intent.getBooleanExtra(
                ModuleSettings.ENABLED_RESULT_KEY,
                false
            )
            holdBackupJobEnabled = intent.getBooleanExtra(
                ModuleSettings.HOLD_BACKUP_JOB_RESULT_KEY,
                false
            )
            broadcastSettingKnown = true
            XposedBridge.log(
                "GooglePhotosMod: settings received hevc=$broadcastSettingEnabled " +
                    "holdBackupJob=$holdBackupJobEnabled"
            )
        }
    }
    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != "com.google.android.apps.photos") return

        try {
            installSoftwareHevcOverride()
            registerSettingsReceiver()
            installBackupJobHooks(lpparam.classLoader)

            val photoCellView = XposedHelpers.findClass(
                "com.google.android.apps.photos.photoadapteritem.PhotoCellView",
                lpparam.classLoader
            )

            // draw() is used instead of onDraw(): it runs after PhotoCellView's
            // own overlay and after its children, so our label stays visible.
            XposedHelpers.findAndHookMethod(
                photoCellView,
                "draw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as? View ?: return
                        val canvas = param.args.firstOrNull() as? Canvas ?: return

                        val media = findBoundMedia(view) ?: run {
                            clearCache(view)
                            return
                        }

                        val mediaIdentity = System.identityHashCode(media)
                        val cachedIdentity = XposedHelpers.getAdditionalInstanceField(
                            view,
                            CACHE_MEDIA_ID
                        ) as? Int

                        val cachedTitle = XposedHelpers.getAdditionalInstanceField(view, CACHE_TITLE) as? String
                        val title = if (cachedIdentity == mediaIdentity && !cachedTitle.isNullOrBlank()) {
                            cachedTitle
                        } else if (isVideoMedia(media)) {
                            // A missing feature may be loaded asynchronously. Retry on
                            // later draws until that load completes.
                            val resolved = findFilenameInBoundItem(view)
                                ?: resolveVideoTitle(media, view, lpparam.classLoader)
                            XposedHelpers.setAdditionalInstanceField(view, CACHE_MEDIA_ID, mediaIdentity)
                            XposedHelpers.setAdditionalInstanceField(view, CACHE_TITLE, resolved)
                            resolved
                        } else {
                            XposedHelpers.setAdditionalInstanceField(view, CACHE_MEDIA_ID, mediaIdentity)
                            XposedHelpers.setAdditionalInstanceField(view, CACHE_TITLE, null)
                            null
                        }

                        if (!title.isNullOrBlank()) {
                            drawTitleLabel(canvas, title, view.width, view.height, view.resources.displayMetrics.density)
                        }
                    }
                }
            )

            XposedBridge.log("GooglePhotosMod: hooked PhotoCellView.draw; using btcc -> aeiy.f11113a")
        } catch (t: Throwable) {
            XposedBridge.log("GooglePhotosMod: hook failed: ${t.javaClass.name}: ${t.message}")
        }
    }

    /**
     * Keeps a known Photos backup JobService associated with the process for a
     * short, bounded period when Photos finishes a job while its UID is still
     * transferring data. This is opt-in and never affects unrelated packages.
     */
    private fun installBackupJobHooks(classLoader: ClassLoader) {
        val jobServices = listOf(
            "com.google.android.apps.photos.jobqueue.PhotosJobQueueJobsService",
            "com.google.android.apps.photos.jobqueue.PhotosOfflineJobQueueJobsService",
            "com.google.android.libraries.social.async.BackgroundTaskJobService"
        )
        for (name in jobServices) {
            try {
                val clazz = XposedHelpers.findClass(name, classLoader)
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onStartJob",
                    JobParameters::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val params = param.args.firstOrNull() as? JobParameters ?: return
                            val id = params.jobId
                            if (id in backupJobIds) {
                                backupJobs[id] = currentTraffic()
                                XposedBridge.log(
                                    "GooglePhotosMod: backup job started class=$name id=$id " +
                                        "result=${param.result}"
                                )
                            }
                        }
                    }
                )
                XposedHelpers.findAndHookMethod(
                    clazz,
                    "onStopJob",
                    JobParameters::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val params = param.args.firstOrNull() as? JobParameters ?: return
                            if (params.jobId in backupJobIds) {
                                XposedBridge.log(
                                    "GooglePhotosMod: backup job stopped class=$name id=${params.jobId} " +
                                        "result=${param.result}"
                                )
                            }
                        }
                    }
                )
            } catch (t: Throwable) {
                XposedBridge.log("GooglePhotosMod: backup hook unavailable $name: ${t.message}")
            }
        }

        XposedHelpers.findAndHookMethod(
            JobService::class.java,
            "jobFinished",
            JobParameters::class.java,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val params = param.args.firstOrNull() as? JobParameters ?: return
                    val id = params.jobId
                    if (!holdBackupJobEnabled || id !in backupJobIds) return

                    val baseline = backupJobs[id] ?: return
                    val traffic = currentTraffic()
                    val delta = (traffic - baseline).coerceAtLeast(0L)
                    val key = "$id:${System.identityHashCode(params)}"
                    if (delta < MIN_UPLOAD_TRAFFIC_BYTES || !delayedBackupFinishes.add(key)) {
                        backupJobs.remove(id)
                        return
                    }

                    param.result = null
                    val originalMethod = param.method
                    val receiver = param.thisObject
                    val args = param.args.copyOf()
                    XposedBridge.log(
                        "GooglePhotosMod: delaying backup jobFinished id=$id " +
                            "trafficDelta=$delta bytes for ${BACKUP_FINISH_DELAY_MS}ms"
                    )
                    backupJobExecutor.schedule({
                        delayedBackupFinishes.remove(key)
                        backupJobs.remove(id)
                        try {
                            XposedBridge.invokeOriginalMethod(originalMethod, receiver, args)
                        } catch (t: Throwable) {
                            XposedBridge.log(
                                "GooglePhotosMod: delayed jobFinished failed id=$id " +
                                    "${t.javaClass.name}: ${t.message}"
                            )
                        }
                    }, BACKUP_FINISH_DELAY_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            }
        )
        XposedBridge.log("GooglePhotosMod: backup JobService hooks installed")
    }

    private fun currentTraffic(): Long {
        val tx = TrafficStats.getUidTxBytes(android.os.Process.myUid())
        val rx = TrafficStats.getUidRxBytes(android.os.Process.myUid())
        if (tx == TrafficStats.UNSUPPORTED.toLong() || rx == TrafficStats.UNSUPPORTED.toLong()) {
            return 0L
        }
        return tx + rx
    }

    /**
     * Photos/Media3 normally selects the Qualcomm HEVC decoder first. On this
     * device that decoder rejects 3840x1632 files, while Android also exposes
     * a software HEVC decoder. Replace only HEVC codec creation when the user
     * enables the option in the module UI.
     */
    private fun installSoftwareHevcOverride() {
        XposedHelpers.findAndHookMethod(
            MediaCodec::class.java,
            "createByCodecName",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val codecName = param.args.firstOrNull() as? String ?: return
                    val enabled = isSoftwareHevcEnabled()
                    if (!loggedCodecHookCall) {
                        XposedBridge.log(
                            "GooglePhotosMod: MediaCodec.createByCodecName name=$codecName enabled=$enabled"
                        )
                        loggedCodecHookCall = true
                    }
                    if (!enabled) return
                    if (codecName.contains("hevc", ignoreCase = true) &&
                        !codecName.equals(SOFTWARE_HEVC_DECODER, ignoreCase = true)
                    ) {
                        XposedBridge.log(
                            "GooglePhotosMod: replacing HEVC decoder $codecName with $SOFTWARE_HEVC_DECODER"
                        )
                        param.args[0] = SOFTWARE_HEVC_DECODER
                    }
                }
            }
        )

        XposedHelpers.findAndHookMethod(
            MediaCodec::class.java,
            "createDecoderByType",
            String::class.java,
            object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val mime = param.args.firstOrNull() as? String ?: return
                    val enabled = isSoftwareHevcEnabled()
                    if (!loggedCodecHookCall) {
                        XposedBridge.log(
                            "GooglePhotosMod: MediaCodec.createDecoderByType mime=$mime enabled=$enabled"
                        )
                        loggedCodecHookCall = true
                    }
                    if (!enabled || !mime.equals("video/hevc", ignoreCase = true)) {
                        return
                    }
                    try {
                        param.result = MediaCodec.createByCodecName(SOFTWARE_HEVC_DECODER)
                        XposedBridge.log(
                            "GooglePhotosMod: forcing $SOFTWARE_HEVC_DECODER for $mime"
                        )
                    } catch (t: Throwable) {
                        XposedBridge.log(
                            "GooglePhotosMod: software HEVC decoder unavailable: ${t.javaClass.name}: ${t.message}"
                        )
                    }
                }
            }
        )
        XposedBridge.log(
            "GooglePhotosMod: HEVC decoder hooks installed; enabled=${isSoftwareHevcEnabled()}"
        )
    }

    private fun isSoftwareHevcEnabled(): Boolean {
        if (broadcastSettingKnown) return broadcastSettingEnabled

        return try {
            modulePreferences.reload()
            modulePreferences.getBoolean(ModuleSettings.FORCE_SOFTWARE_HEVC, false)
        } catch (_: Throwable) {
            false
        }
    }

    private fun registerSettingsReceiver() {
        if (settingsReceiverRegistered) return
        val application = AndroidAppHelper.currentApplication() ?: return
        try {
            val filter = IntentFilter(ModuleSettings.GET_SETTINGS_ACTION)
            if (Build.VERSION.SDK_INT >= 33) {
                application.registerReceiver(
                    settingsReceiver,
                    filter,
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                application.registerReceiver(settingsReceiver, filter)
            }
            settingsReceiverRegistered = true
            XposedBridge.log("GooglePhotosMod: settings broadcast receiver registered")
            requestSettingsBroadcast()
        } catch (t: Throwable) {
            XposedBridge.log("GooglePhotosMod: settings receiver registration failed: ${t.message}")
        }
    }

    private fun requestSettingsBroadcast() {
        try {
            AndroidAppHelper.currentApplication()?.sendBroadcast(
                Intent(ModuleSettings.REQUEST_SETTINGS_ACTION)
                    .setPackage(ModuleSettings.PACKAGE_NAME)
            )
        } catch (_: Throwable) {
        }
    }

    private fun findBoundMedia(view: View): Any? {
        var parent: Any? = view.parent
        var attempts = 0

        while (parent != null && attempts++ < 8) {
            if (parent.javaClass.name.contains("RecyclerView")) {
                try {
                    // m13825h resolves the holder even when Photos has wrapped
                    // the cell in an intermediate child. The direct-child
                    // method m13829l is the fallback used by this APK's own
                    // adapters.
                    val holder = findHolderFromLayoutParams(view) ?: try {
                        XposedHelpers.callMethod(parent, "m13825h", view)
                    } catch (_: Throwable) {
                        try {
                            XposedHelpers.callMethod(parent, "m13829l", view)
                        } catch (_: Throwable) {
                            XposedHelpers.callMethod(parent, "getChildViewHolder", view)
                        }
                    }
                    val adapterItem = findAdapterItem(holder)
                    val media = findMediaInAdapterItem(adapterItem)
                    if (media != null) {
                        return media
                    }
                } catch (_: Throwable) {
                    // A cell can be drawn while RecyclerView is recycling it.
                }
            }
            parent = (parent as? View)?.parent
        }

        return null
    }

    private fun findHolderFromLayoutParams(view: View): Any? {
        val layoutParams = view.layoutParams ?: return null
        var type: Class<*>? = layoutParams.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(layoutParams) ?: continue
                    if (value.javaClass.simpleName == "apfa" || readField(value, "f65819T") != null) {
                        return value
                    }
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun findAdapterItem(holder: Any?): Any? {
        if (holder == null) return null
        readField(holder, "f65819T")?.let { return it }

        var type: Class<*>? = holder.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(holder) ?: continue
                    if (value.javaClass.simpleName == "apcu" || readField(value, "f38145a") != null) {
                        return value
                    }
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun findMediaInAdapterItem(adapterItem: Any?): Any? {
        if (adapterItem == null) return null
        readField(adapterItem, "f38145a")?.let { return it }

        var type: Class<*>? = adapterItem.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(adapterItem) ?: continue
                    findMediaCandidate(value, 0)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun findFilenameInBoundItem(view: View): String? {
        var parent: Any? = view.parent
        var attempts = 0
        while (parent != null && attempts++ < 8) {
            if (parent.javaClass.name.contains("RecyclerView")) {
                val holder = findHolderFromLayoutParams(view) ?: return null
                val item = findAdapterItem(holder) ?: return null
                return findFilenameString(item, 0, Collections.newSetFromMap(IdentityHashMap()))
            }
            parent = (parent as? View)?.parent
        }
        return null
    }

    private fun findFilenameString(
        target: Any?,
        depth: Int,
        visited: MutableSet<Any>
    ): String? {
        if (target == null || depth > 6 || !visited.add(target)) return null
        if (target is String) {
            val value = target.trim()
            if (value.length <= 260 &&
                Regex("(?i).+\\.(mp4|mkv|avi|mov|webm|m4v|3gp|ts)$").matches(value)
            ) return value
            return null
        }
        if (target.javaClass.isPrimitive || target is Number || target is Boolean ||
            target is Enum<*> || target is Class<*>
        ) return null
        if (target is Iterable<*>) {
            for (entry in target) {
                findFilenameString(entry, depth + 1, visited)?.let { return it }
            }
            return null
        }
        if (target.javaClass.name.startsWith("java.") ||
            target.javaClass.name.startsWith("android.")
        ) return null

        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findFilenameString(field.get(target), depth + 1, visited)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun findMediaCandidate(value: Any?, depth: Int): Any? {
        if (value == null) return null
        try {
            // The album grid currently stores a btcg reference in vxz.b.  It
            // has the stable media key even when the optional btcc video
            // predicate is not present on that reference.
            XposedHelpers.callMethod(value, "mo3364e")
            return value
        } catch (_: Throwable) {
        }

        if (value.javaClass.simpleName != "vxz" &&
            implementsInterfaceNamed(value.javaClass, "btcc")
        ) return value

        // In the device's optimized Photos runtime mqv.g() exposes the
        // actual btcc object; JADX's symbolic method names are not retained
        // there (they are a/b/c... instead of mo336x...).
        if (value.javaClass.simpleName == "mqv") {
            try {
                val media = XposedHelpers.callMethod(value, "g")
                if (media != null && implementsInterfaceNamed(media.javaClass, "btcc")) {
                    return media
                }
            } catch (_: Throwable) {
            }
        }

        // vxz.a is a CoreMediaIdentifier (vxg); vxg.a is the actual btcc
        // media object used by the details screen.
        if (value.javaClass.simpleName == "vxg") {
            val inner = readField(value, "f252272a") ?: readField(value, "a")
            findMediaCandidate(inner, depth + 1)?.let { return it }
        }
        if (depth >= 4) return null

        var type: Class<*>? = value.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findMediaCandidate(field.get(value), depth + 1)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun implementsInterfaceNamed(type: Class<*>, simpleName: String): Boolean {
        var current: Class<*>? = type
        while (current != null) {
            if (current.simpleName == simpleName) return true
            if (current.interfaces.any { it.simpleName == simpleName }) return true
            current = current.superclass
        }
        return false
    }

    private fun resolveVideoTitle(media: Any, view: View, classLoader: ClassLoader): String? {
        return try {
            if (isVideoMedia(media)) {
                queryMediaStoreFilename(view, media)?.let { return it }
            }
            // This is the feature used by the media-details screen for the
            // actual local filename (for example, 939393.mp4). vxi is the
            // collection/album display-name feature and is not the filename.
            val filenameFeatureClass = findFilenameFeatureClass(media, classLoader)
            val feature = findFeature(media, filenameFeatureClass)
            val name = readFeatureFilename(feature)
            name?.trim()?.takeIf { it.isNotEmpty() }
                ?: loadCompleteFilename(media, view, classLoader)
        } catch (_: Throwable) {
            null
        }
    }

    private fun isVideoMedia(media: Any): Boolean {
        return try {
            (callMethodAny(media, arrayOf("k", "mo3370k")) as? Boolean) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun queryMediaStoreFilename(view: View, media: Any): String? {
        val key = mediaKey(media) ?: return null
        mediaStoreFilenameCache[key]?.let { return it }
        val timestamp = findMediaTimestamp(media) ?: return null
        return try {
            val projection = arrayOf(
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_TAKEN,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.DURATION
            )
            view.context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                null
                )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                val takenIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATE_TAKEN)
                val modifiedIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
                    while (cursor.moveToNext()) {
                        val taken = if (takenIndex >= 0 && !cursor.isNull(takenIndex)) cursor.getLong(takenIndex) else 0L
                    val modified = if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) cursor.getLong(modifiedIndex) * 1000L else 0L
                    val candidateTime = if (taken > 0L) taken else modified
                    if (candidateTime > 0L && kotlin.math.abs(candidateTime - timestamp) <= 15000L) {
                        val name = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                        if (!name.isNullOrBlank()) {
                            mediaStoreFilenameCache[key] = name
                            return name
                        }
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun findMediaTimestamp(media: Any): Long? {
        val core = try { callMethodAny(media, arrayOf("g", "mo3366g")) } catch (_: Throwable) { null }
        return findLongValue(core, 0, Collections.newSetFromMap(IdentityHashMap()))
    }

    private fun findLongValue(target: Any?, depth: Int, visited: MutableSet<Any>): Long? {
        if (target == null || depth > 5 || !visited.add(target)) return null
        if (target is Number) {
            val value = target.toLong()
            return value.takeIf { it in 1_000_000_000_000L..3_000_000_000_000L }
        }
        if (target.javaClass.name.startsWith("java.") || target.javaClass.name.startsWith("android.")) return null
        var type: Class<*>? = target.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    findLongValue(field.get(target), depth + 1, visited)?.let { return it }
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return null
    }

    /**
     * Grid queries do not always request the filename feature. When that
     * happens, ask Photos' own media provider for the same media and request
     * exactly aeiy, which is the feature used by the Info/details screen.
     * This is deliberately asynchronous because the provider can hit disk.
     */
    private fun loadCompleteFilename(media: Any, view: View, classLoader: ClassLoader): String? {
        val key = mediaKey(media) ?: return null

        filenameCache[key]?.let { return it }
        if (filenameLoads.add(key)) {
            filenameExecutor.execute {
                try {
                    val featureClass = findFilenameFeatureClass(media, classLoader)
                    val vyc = callStaticAny(
                        findPhotosClass("vyf", classLoader),
                        arrayOf("c", "m90595c"),
                        media
                    )
                    val requestBuilder = XposedHelpers.newInstance(
                        findPhotosClass("vvz", classLoader),
                        true
                    )
                    callMethodAny(requestBuilder, arrayOf("c", "m90462c"), featureClass)
                    val request = callMethodAny(requestBuilder, arrayOf("a", "m90460a"))
                    val completeMedia = callStaticAny(
                        findPhotosClass("vxy", classLoader),
                        arrayOf("t", "m90584t"),
                        view.context,
                        vyc,
                        request
                    )
                    val feature = callMethodAny(
                        completeMedia ?: throw IllegalStateException("complete media is null"),
                        arrayOf("b", "mo3361b"),
                        featureClass
                    )
                    val name = readFeatureFilename(feature)
                    name?.trim()?.takeIf { it.isNotEmpty() }?.let { filenameCache[key] = it }
                    view.postInvalidate()
                } catch (t: Throwable) {
                    if (!loggedFilenameLoad) {
                        XposedBridge.log("GooglePhotosMod: filename feature load failed: ${t.javaClass.name}: ${t.message}")
                        loggedFilenameLoad = true
                    }
                } finally {
                    filenameLoads.remove(key)
                }
            }
        }
        return filenameCache[key]
    }

    private fun findFilenameFeatureClass(media: Any, fallback: ClassLoader): Class<*> {
        val loaders = listOfNotNull(
            media.javaClass.classLoader,
            fallback,
            Thread.currentThread().contextClassLoader
        ).distinct()
        for (name in listOf("aeiy", "p000.aeiy")) {
            for (loader in loaders) {
                try {
                    return loader.loadClass(name)
                } catch (_: Throwable) {
                }
            }
        }
        return Class.forName("aeiy")
    }

    private fun findPhotosClass(simpleName: String, fallback: ClassLoader): Class<*> {
        val loaders = listOfNotNull(fallback, Thread.currentThread().contextClassLoader).distinct()
        for (name in listOf(simpleName, "p000.$simpleName")) {
            for (loader in loaders) {
                try {
                    return loader.loadClass(name)
                } catch (_: Throwable) {
                }
            }
        }
        return Class.forName(simpleName)
    }

    private fun callMethodAny(target: Any, names: Array<String>, vararg args: Any?): Any? {
        var failure: Throwable? = null
        for (name in names) {
            try {
                return XposedHelpers.callMethod(target, name, *args)
            } catch (t: Throwable) {
                failure = t
            }
        }
        throw failure ?: NoSuchMethodException(names.firstOrNull())
    }

    private fun callStaticAny(type: Class<*>, names: Array<String>, vararg args: Any?): Any? {
        var failure: Throwable? = null
        for (name in names) {
            try {
                return XposedHelpers.callStaticMethod(type, name, *args)
            } catch (t: Throwable) {
                failure = t
            }
        }
        throw failure ?: NoSuchMethodException(names.firstOrNull())
    }

    private fun mediaKey(media: Any): Long? {
        try {
            (XposedHelpers.callMethod(media, "mo3364e") as? Number)?.toLong()?.let { return it }
        } catch (_: Throwable) {
        }
        val methods = (media.javaClass.methods.asSequence() + media.javaClass.declaredMethods.asSequence())
            .distinctBy { it.name + it.toGenericString() }
        for (method in methods) {
            if (method.parameterTypes.isNotEmpty()) continue
            if (method.returnType != java.lang.Long.TYPE && method.returnType != java.lang.Long::class.java) continue
            try {
                method.isAccessible = true
                (method.invoke(media) as? Number)?.toLong()?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun readFeatureFilename(feature: Any?): String? {
        if (feature == null) return null
        (readField(feature, "f11113a") as? String)?.let { return it }
        var type: Class<*>? = feature.javaClass
        while (type != null && type != Any::class.java) {
            for (field in type.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
                try {
                    field.isAccessible = true
                    val value = field.get(feature)
                    if (value is String && value.isNotBlank()) return value
                } catch (_: Throwable) {
                }
            }
            type = type.superclass
        }
        return null
    }

    private fun findFeature(media: Any, featureClass: Class<*>): Any? {
        // Exact method from the btcc/btca interface in the inspected APK.
        try {
            XposedHelpers.callMethod(media, "mo3361b", featureClass)?.let { return it }
        } catch (_: Throwable) {
        }
        try {
            XposedHelpers.callMethod(media, "mo3362c", featureClass)?.let { return it }
        } catch (_: Throwable) {
        }

        // Fallback for a future build where JADX/R8 changes the bridge name:
        // identify the feature lookup by its signature, not by a guessed text.
        val methods = (media.javaClass.methods.asSequence() + media.javaClass.declaredMethods.asSequence())
            .distinctBy { it.name + it.toGenericString() }
        for (method in methods) {
            if (method.parameterTypes.size != 1 || method.parameterTypes[0] != Class::class.java) continue
            try {
                method.isAccessible = true
                val value = method.invoke(media, featureClass)
                if (value != null && featureClass.isInstance(value)) return value
            } catch (_: Throwable) {
            }
        }
        return null
    }

    private fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        return try {
            XposedHelpers.getObjectField(target, name)
        } catch (_: Throwable) {
            null
        }
    }

    private fun clearCache(view: View) {
        XposedHelpers.removeAdditionalInstanceField(view, CACHE_MEDIA_ID)
        XposedHelpers.removeAdditionalInstanceField(view, CACHE_TITLE)
    }

    private fun drawTitleLabel(canvas: Canvas, title: String, width: Int, height: Int, density: Float) {
        if (width <= 0 || height <= 0) return

        if (textPaint == null) {
            textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                typeface = android.graphics.Typeface.create(
                    "sans-serif-medium",
                    android.graphics.Typeface.NORMAL
                )
            }
            backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(190, 0, 0, 0)
                style = Paint.Style.FILL
            }
        }

        val padding = 8f * density
        val margin = 6f * density
        val textSize = 13f * density
        val paint = textPaint!!
        paint.textSize = textSize

        val maxTextWidth = width - (margin * 2f) - (padding * 2f)
        if (maxTextWidth <= 0f) return

        val ellipsis = "…"
        val shown = if (paint.measureText(title) <= maxTextWidth) {
            title
        } else {
            val room = maxTextWidth - paint.measureText(ellipsis)
            val count = paint.breakText(title, true, room, null)
            if (count <= 0) ellipsis else title.substring(0, count).trimEnd() + ellipsis
        }

        val metrics = paint.fontMetrics
        val textHeight = metrics.bottom - metrics.top
        val rectHeight = textHeight + padding * 2f
        val left = margin
        val top = margin
        val right = left + paint.measureText(shown) + padding * 2f

        canvas.drawRoundRect(RectF(left, top, right, top + rectHeight), 5f * density, 5f * density, backgroundPaint!!)
        canvas.drawText(shown, left + padding, top + padding - metrics.top, paint)
    }

    companion object {
        private const val CACHE_MEDIA_ID = "google_photos_mod_media_identity"
        private const val CACHE_TITLE = "google_photos_mod_video_title"
        private const val SOFTWARE_HEVC_DECODER = "c2.android.hevc.decoder"
        private const val MIN_UPLOAD_TRAFFIC_BYTES = 32L * 1024L
        private const val BACKUP_FINISH_DELAY_MS = 60_000L
    }
}
