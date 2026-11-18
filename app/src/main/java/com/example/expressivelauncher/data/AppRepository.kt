package com.example.expressivelauncher.data

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import android.view.View
import android.window.SplashScreen
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Иконка без маски: форму накладывает UI, поэтому её можно менять на лету.
 * [custom] — готовый значок из темы со своей формой; [accent] — средний цвет для анимации запуска (0, если неизвестен).
 */
@Immutable
class AppIcon(
    val image: ImageBitmap,
    val legacy: Boolean,
    val mono: ImageBitmap?,
    val custom: Boolean = false,
    val accent: Int = 0,
    /** Передний слой adaptive-иконки без фона: цветной логотип для прозрачных значков Liquid Glass. */
    val fg: ImageBitmap? = null,
    /**
     * [mono] дало само приложение (системный монохромный слой Android 13+), а не синтезировал лаунчер.
     * Тематическими значками становятся только такие: синтезированный рисунок у части приложений выходил кривым.
     */
    val monoNative: Boolean = false,
)

@Immutable
class ShortcutEntry(val info: ShortcutInfo, val label: String, val icon: ImageBitmap?)

private val HEX_DIGITS = "0123456789abcdef"

/**
 * Папка дискового кэша значков. v15: круглые плашки старых значков тоже растягиваются на всю фигуру
 * (цвет края ищется по самой кромке рисунка, а не по описанной окружности). v14: круглые и квадратные старые значки растягиваются на всю фигуру. v13: синтез монохромного слоя не делает его для иллюстраций и бесформенных пятен
 * и вырезает детали на плашке. v12: на Android 8–12 монохромный слой читается из XML значка. v11: значок, снятый
 * во время обновления приложения, больше не сохраняется.
 */
const val ICON_CACHE_DIR = "icons_v15"

class AppRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    val themes: IconThemeManager,
    initialTheme: String,
) {
    private val launcherApps = context.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    private val userManager = context.getSystemService(Context.USER_SERVICE) as UserManager

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    /** Быстрый поиск по ключу; пересобирается вместе со списком. */
    @Volatile
    var byKey: Map<String, AppInfo> = emptyMap()
        private set

    /** Растёт, когда значки нужно перечитать: сменилась тема или обновилось приложение. */
    var iconEpoch by mutableIntStateOf(0)
        private set

    private val iconPx = (context.resources.displayMetrics.density * 56f * 1.25f).toInt().coerceIn(128, 224)

    // Растры лежат в видеопамяти, в куче остаются только монохромные слои, поэтому ограничиваем число значков
    private val cache = LruCache<String, AppIcon>(MAX_CACHED_ICONS)

    /** Описания активностей из последнего обхода: повторный запрос на каждый значок — это межпроцессный вызов. */
    @Volatile
    private var activityInfos: Map<String, LauncherActivityInfo> = emptyMap()

    /** Время обновления пакета: один запрос на приложение вместо запроса на каждый значок и тему. */
    private val updatedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private val overridesDir = File(context.filesDir, "icon_overrides")
    @Volatile
    private var overrideKeys: Set<String> = emptySet()

    // v11: прежний кэш мог хранить значок-«робота» Android или значок без монохромного слоя, снятый с устаревшего
    // описания активности во время обновления приложения. Ключ меняется вместе с темой значков, обновлением
    // приложения и размером — устаревший файл просто не найдётся, а лишнее вытесняет trimDisk
    private val diskDir = File(context.cacheDir, DISK_DIR_NAME)

    /** Пакеты, у которых пришло событие (обновление, установка), а список активностей ещё не перечитан. */
    private val stale = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Сколько раз значок пакета перечитывали из-за стандартной иконки Android: если она настоящая, цикла не будет. */
    private val brokenRetries = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val diskWrites = java.util.concurrent.atomic.AtomicInteger()
    private val trimming = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Стандартный значок Android (зелёный «робот»): система отдаёт его, если ресурсы приложения не открылись. */
    private val defaultIconState by lazy { runCatching { context.packageManager.defaultActivityIcon.constantState }.getOrNull() }

    @Volatile
    var iconThemeId: String = initialTheme
        private set
    @Volatile
    private var theme: LoadedIconTheme? = null

    private var refreshJob: Job? = null
    private var preloadJob: Job? = null
    private var themeJob: Job? = null
    private val preloadDispatcher = Dispatchers.Default.limitedParallelism(2)

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            purgeIcons(packageName)
            refreshThemes()
            scheduleRefresh()
        }

        override fun onPackageAdded(packageName: String, user: UserHandle) {
            updatedAt.remove(packageName)
            stale += packageName
            eventAt[packageName] = SystemClock.elapsedRealtime()
            refreshThemes()
            scheduleRefresh()
        }

        override fun onPackageChanged(packageName: String, user: UserHandle) {
            purgeIcons(packageName)
            scheduleRefresh()
        }

        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) {
            packageNames.forEach(::purgeIcons)
            scheduleRefresh()
        }

        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = scheduleRefresh()
    }

    /** Темы и свои значки прочитаны: до этого значки не рисуем, иначе они мелькнули бы без темы. */
    private val iconsReady = kotlinx.coroutines.CompletableDeferred<Unit>()

    init {
        launcherApps.registerCallback(callback)
        // Список приложений не ждёт тем значков: на холодном старте стол появляется сразу, а не через секунду
        scheduleRefresh(0)
        scope.launch(Dispatchers.Default) {
            // Кэши прошлых версий значков больше не читаются: раньше чистились только перечисленные папки,
            // и v8 с v9 оставались у всех навсегда
            runCatching { context.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("icons_v") && f.name != DISK_DIR_NAME }?.forEach { it.deleteRecursively() } }
            runCatching { overrideKeys = overridesDir.list()?.map { it.removeSuffix(".png") }?.toSet().orEmpty() }
            try {
                // Ошибка разбора одного набора не должна оставить лаунчер без значков вообще:
                // iconsReady обязательно завершаем, иначе icon() будет ждать его вечно
                runCatching { themes.refresh() }
                runCatching { if (iconThemeId.isNotEmpty()) theme = themes.load(iconThemeId) }
            } finally {
                iconsReady.complete(Unit)
            }
            runCatching { trimDisk() }
        }
    }

    private fun scheduleRefresh(delayMs: Long = 250) {
        refreshJob?.cancel()
        refreshJob = scope.launch(Dispatchers.Default) {
            delay(delayMs)
            // Пакеты, о которых уже знаем: их описания активностей этот обход обновит. Событие, пришедшее во время
            // обхода, останется в stale до следующего
            val fresh = stale.toList()
            val list = queryApps()
            stale.removeAll(fresh.toSet())
            byKey = list.associateBy { it.key }
            _apps.value = list
            _loaded.value = true
            bumpEpoch()
            preloadIcons(list)
        }
    }

    private suspend fun bumpEpoch() = withContext(Dispatchers.Main) { iconEpoch++ }

    private fun queryApps(): List<AppInfo> {
        val result = ArrayList<AppInfo>(256)
        val infos = HashMap<String, LauncherActivityInfo>(256)
        for (user in userManager.userProfiles) {
            val serial = userManager.getSerialNumberForUser(user)
            val isWork = user != android.os.Process.myUserHandle()
            for (info in launcherApps.getActivityList(null, user)) {
                val cn = info.componentName
                val key = "${cn.packageName}/${cn.className}#$serial"
                infos[key] = info
                result += AppInfo(
                    key = key,
                    packageName = cn.packageName,
                    className = cn.className,
                    label = info.label?.toString().orEmpty().ifBlank { cn.packageName },
                    user = user,
                    userSerial = serial,
                    isWork = isWork,
                    installTime = info.firstInstallTime,
                    isSystem = info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                )
            }
        }
        result.sortWith(compareBy({ it.sortKey }, { it.userSerial }))
        activityInfos = infos
        return result
    }

    fun serialOf(user: UserHandle): Long = userManager.getSerialNumberForUser(user)

    /** Пакет реально удалён: отключённые и временно недоступные приложения сюда не попадают. */
    fun isPackageGone(pkg: String, userSerial: Long): Boolean {
        val user = userManager.getUserForSerialNumber(userSerial) ?: return true
        return runCatching { launcherApps.getApplicationInfo(pkg, PackageManager.MATCH_UNINSTALLED_PACKAGES, user) }.isFailure
    }

    // ---------- Иконки ----------

    fun cachedIcon(app: AppInfo): AppIcon? = cache.get(app.key)

    suspend fun icon(app: AppInfo): AppIcon? {
        cache.get(app.key)?.let { return it }
        iconsReady.await()
        return withContext(Dispatchers.Default) { loadIcon(app) }
    }

    private fun loadIcon(app: AppInfo): AppIcon? {
        cache.get(app.key)?.let { return it }
        val themeAtStart = iconThemeId
        return runCatching {
            loadOverride(app)?.let { cache.put(app.key, it); return@runCatching it }
            val dk = diskKey(app)
            val disk = dk?.let(::readDisk)
            if (disk != null) {
                // Если тему успели сменить, не кладём значок прошлой темы в общий кэш
                if (themeAtStart == iconThemeId) cache.put(app.key, disk)
                return@runCatching disk
            }
            val sys = systemDrawable(app)
            // Значок, снятый посреди обновления приложения, ненадёжен: система может отдать стандартный значок
            // Android (зелёный «робот»), старую версию или значок без монохромного слоя. Такой результат
            // показываем, но не кэшируем ни в памяти, ни на диске (иначе он остался бы до следующего обновления
            // приложения) и перечитываем, когда обновление уляжется.
            val pkg = app.packageName
            val broken = sys != null && isDefaultIcon(sys)
            val retries = brokenRetries[pkg] ?: 0
            val sinceEvent = eventAt[pkg]?.let { SystemClock.elapsedRealtime() - it }
            val settling = sinceEvent != null && sinceEvent < SETTLE_MS
            val transient = settling || (broken && retries < MAX_BROKEN_RETRIES)
            if (transient) scheduleReread(pkg, if (settling) SETTLE_MS - (sinceEvent ?: 0L) + 500L else BROKEN_RETRY_DELAY_MS, countRetry = !settling)
            else if (!broken) brokenRetries.remove(pkg)
            val icon = loadThemed(app, sys) ?: render(sys ?: return@runCatching null, app)
            // Тему успели сменить, пока рисовали: этот значок принадлежит прошлой теме, в кэш его нельзя
            if (!transient && themeAtStart == iconThemeId) {
                if (dk != null) writeDisk(dk, icon)
                cache.put(app.key, icon)
            }
            icon
        }.getOrNull()
    }

    private val retryPending = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Когда у пакета было последнее событие (установка, обновление): по нему значок считается «оседающим». */
    private val eventAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Через [delayMs] перечитываем значки пакета: к этому времени обновление обычно закончено. */
    private fun scheduleReread(pkg: String, delayMs: Long, countRetry: Boolean) {
        if (!retryPending.add(pkg)) return
        scope.launch(Dispatchers.Default) {
            delay(delayMs)
            if (countRetry) brokenRetries.merge(pkg, 1, Int::plus)
            retryPending.remove(pkg)
            updatedAt.remove(pkg)
            stale += pkg
            scheduleRefresh(0)
        }
    }

    private fun isDefaultIcon(d: Drawable): Boolean {
        val def = defaultIconState ?: return false
        return d.constantState == def
    }

    // ---------- Дисковый кэш значков: на холодном старте значки читаются готовыми, а не рисуются заново ----------

    /** Ключ меняется вместе с темой значков, обновлением приложения и размером — устаревший файл просто не найдётся. */
    private fun diskKey(app: AppInfo): String? {
        val updated = updatedAt.getOrPut(app.packageName) {
            runCatching { context.packageManager.getPackageInfo(app.packageName, 0).lastUpdateTime }.getOrDefault(-1L)
        }
        if (updated < 0) return null
        return sha("${app.key}|$iconThemeId|$updated|$iconPx")
    }

    private fun readDisk(key: String): AppIcon? = runCatching {
        val meta = File(diskDir, "$key.meta")
        if (!meta.exists()) return null
        val parts = meta.readText().split(',')
        val (legacy, custom, accent) = parts
        val monoNative = parts.getOrNull(3) == "1"
        val image = BitmapFactory.decodeFile(File(diskDir, "$key.png").path) ?: return null
        // Файл есть, но не читается — запись оборвана: значок рисуется заново целиком, а не без монохромного слоя
        val monoFile = File(diskDir, "$key.m.png")
        val mono = if (monoFile.exists()) (BitmapFactory.decodeFile(monoFile.path) ?: return null).let { m -> m.extractAlpha().also { m.recycle() } } else null
        val fgFile = File(diskDir, "$key.f.png")
        val fg = if (fgFile.exists()) (BitmapFactory.decodeFile(fgFile.path) ?: return null).toDisplay() else null
        // Давность использования для вытеснения: обновляем не чаще раза в несколько часов, чтобы не писать на каждое чтение
        val now = System.currentTimeMillis()
        if (now - meta.lastModified() > TOUCH_INTERVAL_MS) meta.setLastModified(now)
        AppIcon(image.toDisplay(), legacy = legacy == "1", mono = mono?.asImageBitmap(), custom = custom == "1", accent = accent.toInt(), fg = fg, monoNative = monoNative)
    }.getOrNull()

    /** WebP без потерь кодируется заметно быстрее PNG и меньше весит: запись кэша не отнимает кадры. */
    private fun iconFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.PNG

    private fun writeDisk(key: String, icon: AppIcon) {
        runCatching {
            diskDir.mkdirs()
            val format = iconFormat()
            // Каждый файл пишется во временный и переименовывается: два потока, рисующие один значок, и чтение
            // посреди записи больше не видят обрезанный файл
            fun put(name: String, bmp: Bitmap) {
                val tmp = File(diskDir, "$name.${System.nanoTime()}.tmp")
                try {
                    tmp.outputStream().buffered(64 * 1024).use { bmp.compress(format, 100, it) }
                    check(tmp.renameTo(File(diskDir, name))) { "rename failed" }
                } finally {
                    tmp.delete()
                }
            }
            val image = icon.image.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
            put("$key.png", image)
            image.recycle()
            // Если у значка нет слоя, файл прежней записи с тем же ключом не должен остаться
            val monoFile = File(diskDir, "$key.m.png")
            val fgFile = File(diskDir, "$key.f.png")
            icon.mono?.let { m ->
                val src = m.asAndroidBitmap()
                val argb = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
                Canvas(argb).drawBitmap(src, 0f, 0f, Paint().apply { color = android.graphics.Color.BLACK })
                put("$key.m.png", argb)
                argb.recycle()
            } ?: monoFile.delete()
            icon.fg?.let { f ->
                val soft = f.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)
                put("$key.f.png", soft)
                soft.recycle()
            } ?: fgFile.delete()
            // meta пишется последним: по нему файл считается готовым
            val metaTmp = File(diskDir, "$key.meta.${System.nanoTime()}.tmp")
            try {
                metaTmp.writeText("${if (icon.legacy) 1 else 0},${if (icon.custom) 1 else 0},${icon.accent},${if (icon.monoNative) 1 else 0}")
                check(metaTmp.renameTo(File(diskDir, "$key.meta"))) { "rename failed" }
            } finally {
                metaTmp.delete()
            }
        }
        if (diskWrites.incrementAndGet() % TRIM_EVERY_WRITES == 0) trimDisk()
    }

    /**
     * Держит кэш в пределах [MAX_DISK_BYTES]: сначала удаляются оборванные записи и временные файлы,
     * затем значки, которыми давно не пользовались (удалённые приложения, прежние версии и темы).
     */
    private fun trimDisk() {
        if (!trimming.compareAndSet(false, true)) return
        try {
            runCatching {
                val files = diskDir.listFiles() ?: return@runCatching
                val now = System.currentTimeMillis()
                val groups = HashMap<String, MutableList<File>>()
                for (f in files) {
                    if (f.name.endsWith(".tmp")) {
                        if (now - f.lastModified() > ORPHAN_AGE_MS) f.delete()
                        continue
                    }
                    groups.getOrPut(f.name.substringBefore('.')) { ArrayList(4) }.add(f)
                }
                class Entry(val files: List<File>, val bytes: Long, val used: Long)
                val entries = ArrayList<Entry>(groups.size)
                for ((_, fs) in groups) {
                    val used = fs.maxOf { it.lastModified() }
                    // Без meta значок считается недописанным; свежую запись, которую пишут прямо сейчас, не трогаем
                    if (fs.none { it.name.endsWith(".meta") } && now - used > ORPHAN_AGE_MS) { fs.forEach { it.delete() }; continue }
                    entries += Entry(fs, fs.sumOf { it.length() }, used)
                }
                var total = entries.sumOf { it.bytes }
                if (total <= MAX_DISK_BYTES) return@runCatching
                for (e in entries.sortedBy { it.used }) {
                    if (total <= MAX_DISK_BYTES) break
                    e.files.forEach { it.delete() }
                    total -= e.bytes
                }
            }
        } finally {
            trimming.set(false)
        }
    }

    /** Сброс кэша значков из настроек: значки перерисуются заново. */
    fun clearIconCache() {
        scope.launch(Dispatchers.Default) {
            diskDir.deleteRecursively()
            cache.evictAll()
            bumpEpoch()
            preloadIcons(_apps.value)
        }
    }

    /** Описание активности. Пока по пакету есть необработанное событие, прежнее из обхода устарело: берём свежее. */
    private fun systemDrawable(app: AppInfo): Drawable? {
        val info = (if (app.packageName in stale) null else activityInfos[app.key])
            ?: launcherApps.getActivityList(app.packageName, app.user).firstOrNull { it.componentName.className == app.className }
        return info?.getIcon(0)
    }

    private fun loadThemed(app: AppInfo, sys: Drawable?): AppIcon? {
        val t = theme ?: return null
        t.iconFor(app.packageName, app.className)?.let { d ->
            val icon = renderPackIcon(d)
            // У значка из набора нет своего монохромного слоя: тематическим он становится по слою самого приложения
            if (icon.mono != null) return icon
            val native = nativeMono(app, sys) ?: return icon
            return AppIcon(icon.image, icon.legacy, native.asImageBitmap(), icon.custom, icon.accent, icon.fg, monoNative = true)
        }
        if (!t.hasShapeTreatment) return null
        return runCatching { composeWithTheme(t, app, sys) }.getOrNull()
    }

    /** Монохромный слой, который дало само приложение. */
    private fun nativeMono(app: AppInfo?, sys: Drawable?): Bitmap? {
        val m = monoLayer(app, sys) ?: return null
        return runCatching { MonoSynth.fixSystem(renderLayers(iconPx, null, m, alphaOnly = true)) }.getOrNull()
    }

    /**
     * Монохромный слой приложения. На Android 13+ его отдаёт система. На Android 8–12 тег `<monochrome>` в XML
     * adaptive-иконки система при разборе пропускает, хотя большинство приложений (Google и не только) его уже
     * кладут: читаем его из XML сами, как Lawnchair и Pixel Launcher на старых версиях. Без этого на Android 11
     * все тематические значки были синтезированными, и у многих рисунок выходил кривым.
     */
    private fun monoLayer(app: AppInfo?, sys: Drawable?): Drawable? {
        if (Build.VERSION.SDK_INT >= 33) return (sys as? AdaptiveIconDrawable)?.monochrome
        if (app == null || sys !is AdaptiveIconDrawable) return null
        return runCatching { xmlMonochrome(app) }.getOrNull()
    }

    private fun xmlMonochrome(app: AppInfo): Drawable? {
        val pm = context.packageManager
        val appInfo = activityInfos[app.key]?.applicationInfo ?: pm.getApplicationInfo(app.packageName, 0)
        val iconRes = runCatching { pm.getActivityInfo(app.component, 0).iconResource }.getOrNull()?.takeIf { it != 0 } ?: appInfo.icon
        if (iconRes == 0) return null
        val res = pm.getResourcesForApplication(appInfo)
        val dpi = context.resources.displayMetrics.densityDpi
        // PNG-значок — не XML: getXml бросит исключение, и слоя просто нет
        val parser = res.getXml(iconRes)
        try {
            var type = parser.next()
            while (type != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (type == org.xmlpull.v1.XmlPullParser.START_TAG) {
                    if (parser.depth == 1 && parser.name != "adaptive-icon") return null
                    if (parser.depth == 2 && parser.name == "monochrome") {
                        val id = parser.getAttributeResourceValue(ANDROID_NS, "drawable", 0)
                        if (id != 0) return res.getDrawableForDensity(id, dpi, null)
                        // Рисунок описан прямо внутри тега
                        val depth = parser.depth
                        while (true) {
                            type = parser.next()
                            if (type == org.xmlpull.v1.XmlPullParser.END_DOCUMENT) return null
                            if (type == org.xmlpull.v1.XmlPullParser.END_TAG && parser.depth <= depth) return null
                            if (type == org.xmlpull.v1.XmlPullParser.START_TAG) {
                                return Drawable.createFromXmlInner(res, parser, android.util.Xml.asAttributeSet(parser), null)
                            }
                        }
                    }
                }
                type = parser.next()
            }
            return null
        } finally {
            parser.close()
        }
    }

    private fun renderPackIcon(d: Drawable): AppIcon {
        if (d is AdaptiveIconDrawable) return render(d)
        val bmp = createBitmap(iconPx, iconPx)
        d.setBounds(0, 0, iconPx, iconPx)
        d.draw(Canvas(bmp))
        return AppIcon(bmp.toDisplay(), legacy = false, mono = null, custom = true, accent = bmp.accent())
    }

    /** Значка в теме нет: применяем её подложку, маску и накладку к обычной иконке (как в наборах ADW/Nova). */
    private fun composeWithTheme(t: LoadedIconTheme, app: AppInfo, sys: Drawable?): AppIcon? {
        val source = sys ?: return null
        val s = iconPx
        val inner = if (source is AdaptiveIconDrawable) renderLayers(s, source.background, source.foreground) else {
            val raw = createBitmap(s, s)
            source.setBounds(0, 0, s, s)
            source.draw(Canvas(raw))
            // Плашку старого значка растягиваем на всю фигуру: с темой она иначе остаётся кружком на подложке темы
            fullBleedArt(raw) { raw.recycle() } ?: raw
        }
        val out = createBitmap(s, s)
        val c = Canvas(out)
        val backs = t.filter.backs
        if (backs.isNotEmpty()) t.drawable(backs[(app.packageName.hashCode() and Int.MAX_VALUE) % backs.size])?.let {
            it.setBounds(0, 0, s, s)
            it.draw(c)
        }
        val inset = ((1f - t.filter.scale) * s / 2f).toInt()
        c.drawBitmap(inner, null, Rect(inset, inset, s - inset, s - inset), Paint(Paint.FILTER_BITMAP_FLAG))
        t.filter.mask?.let(t::drawable)?.let { m ->
            val layer = createBitmap(s, s)
            m.setBounds(0, 0, s, s)
            m.draw(Canvas(layer))
            c.drawBitmap(layer, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        t.filter.upon?.let(t::drawable)?.let {
            it.setBounds(0, 0, s, s)
            it.draw(c)
        }
        return AppIcon(out.toDisplay(), legacy = false, mono = null, custom = true, accent = out.accent())
    }

    private fun render(d: Drawable, app: AppInfo? = null): AppIcon {
        val s = iconPx
        if (d is AdaptiveIconDrawable) {
            val full = renderLayers(s, d.background, d.foreground)
            val fg = renderLayers(s, null, d.foreground)
            val native = monoLayer(app, d)?.let { runCatching { MonoSynth.fixSystem(renderLayers(s, null, it, alphaOnly = true)) }.getOrNull() }
            // Синтезированный слой оставляем для «тонированных» значков Liquid Glass, тематическими он не станет
            val mono = native ?: runCatching { MonoSynth.fromAdaptive(fg, full) }.getOrNull()
            return AppIcon(full.toDisplay(), legacy = false, mono = mono?.asImageBitmap(), accent = full.accent(), fg = fg.toDisplay(), monoNative = native != null)
        }
        // Старый значок на своей круглой или квадратной плашке: растягиваем плашку на всю фигуру, как делает
        // Pixel Launcher, иначе внутри формы лаунчера остаётся маленький кружок на чужом фоне
        fullBleedLegacy(d, s)?.let { full ->
            val mono = runCatching { MonoSynth.fromLegacy(full) }.getOrNull()
            return AppIcon(full.toDisplay(), legacy = false, mono = mono?.asImageBitmap(), accent = full.accent())
        }
        // Обычная иконка: вписываем с полями, фон дорисует UI
        val bmp = createBitmap(s, s)
        val inset = (s * 0.16f).toInt()
        d.setBounds(inset, inset, s - inset, s - inset)
        d.draw(Canvas(bmp))
        val mono = runCatching { MonoSynth.fromLegacy(bmp) }.getOrNull()
        return AppIcon(bmp.toDisplay(), legacy = true, mono = mono?.asImageBitmap(), accent = bmp.accent())
    }

    /**
     * Старый значок, который сам — сплошной круг или скруглённый квадрат почти на всю картинку. Возвращает значок
     * во всю фигуру: картинка увеличена так, что круг касается краёв, а углы залиты цветом края плашки.
     * null — значок другой формы (логотип на прозрачном, пёстрый край): такой рисуется с полями, как раньше.
     */
    private fun fullBleedLegacy(d: Drawable, s: Int): Bitmap? = runCatching {
        val src = createBitmap(s, s)
        d.setBounds(0, 0, s, s)
        d.draw(Canvas(src))
        fullBleedArt(src) { src.recycle() }
    }.getOrNull()

    /**
     * Растягивает плашку рисунка на весь холст, если рисунок — сплошной круг или скруглённый квадрат.
     * Цвет края берётся по внешней кромке самой плашки: от границы рамки внутрь до первого непрозрачного пикселя.
     * Так круг даёт ровное кольцо (выборка по описанной окружности у него попадала в прозрачные углы и цвет
     * не находился — значки оставались кружками на чужой серой плашке).
     * null — рисунок не плашка: логотип на прозрачном, пёстрый или неровный край.
     */
    private fun fullBleedArt(src: Bitmap, recycleSrc: () -> Unit = {}): Bitmap? = runCatching {
        val s = src.width
        if (src.height != s) return@runCatching null
        val px = IntArray(s * s)
        src.getPixels(px, 0, s, 0, 0, s, s)
        var l = s; var t = s; var r = -1; var b = -1; var opaque = 0
        for (y in 0 until s) for (x in 0 until s) {
            if (android.graphics.Color.alpha(px[y * s + x]) <= 200) continue
            opaque++
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > b) b = y
        }
        if (r < 0) return@runCatching null
        val w = r - l + 1
        val h = b - t + 1
        // Плашка почти на весь значок, квадратная; заполнена сплошь (круг — π/4 ≈ 0.785 рамки)
        if (w < s * 0.6f || h < s * 0.6f || w > h * 1.1f || h > w * 1.1f) return@runCatching null
        val fill = opaque.toFloat() / (w * h)
        if (fill < 0.72f) return@runCatching null
        // Цвет края плашки — по её собственной кромке: 72 луча от края рамки к центру, первый непрозрачный пиксель
        val cx = (l + r) / 2f
        val cy = (t + b) / 2f
        var sr = 0L; var sg = 0L; var sb = 0L; var n = 0
        val rays = 72
        val samples = IntArray(rays)
        for (i in 0 until rays) {
            val a = i * Math.PI * 2 / rays
            val dx = kotlin.math.cos(a).toFloat()
            val dy = kotlin.math.sin(a).toFloat()
            var p = 0
            for (step in 0..40) {
                val k = 1.02f - step * 0.02f
                val x = (cx + w / 2f * k * dx).toInt().coerceIn(0, s - 1)
                val y = (cy + h / 2f * k * dy).toInt().coerceIn(0, s - 1)
                val q = px[y * s + x]
                if (android.graphics.Color.alpha(q) > 200) { p = q; break }
            }
            samples[i] = p
            if (p != 0) {
                sr += android.graphics.Color.red(p); sg += android.graphics.Color.green(p); sb += android.graphics.Color.blue(p); n++
            }
        }
        // Логотип на прозрачном: большинство лучей не находит плашку
        if (n < rays - 8) return@runCatching null
        val er = (sr / n).toInt(); val eg = (sg / n).toInt(); val eb = (sb / n).toInt()
        var spread = 0
        for (p in samples) {
            if (p == 0) { spread++; continue }
            val dist = kotlin.math.abs(android.graphics.Color.red(p) - er) + kotlin.math.abs(android.graphics.Color.green(p) - eg) +
                kotlin.math.abs(android.graphics.Color.blue(p) - eb)
            if (dist > 60) spread++
        }
        // Пёстрый край (фото, градиент) не растягиваем
        if (spread > 14) return@runCatching null
        val out = createBitmap(s, s)
        val c = Canvas(out)
        c.drawColor(android.graphics.Color.rgb(er, eg, eb))
        // Круг чуть больше значка: тонкий светлый ободок старой плашки и сглаженный край уходят за фигуру
        val zoom = if (fill > 0.93f) 1.03f else 1.07f
        val scale = s * zoom / maxOf(w, h)
        val m = android.graphics.Matrix()
        m.postTranslate(-cx, -cy)
        m.postScale(scale, scale)
        m.postTranslate(s / 2f, s / 2f)
        c.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        out
    }.getOrNull().also { if (it != null) recycleSrc() }

    /** Аппаратный растр не занимает кучу и рисуется без повторной загрузки текстуры. */
    private fun Bitmap.toDisplay(): ImageBitmap =
        (copy(Bitmap.Config.HARDWARE, false) ?: this).asImageBitmap()

    private fun Bitmap.accent(): Int = runCatching {
        val px = Bitmap.createScaledBitmap(this, 1, 1, true).getPixel(0, 0)
        if (android.graphics.Color.alpha(px) < 24) 0 else px or (0xFF shl 24)
    }.getOrDefault(0)

    /** Слои adaptive-иконки лежат в области 108dp, видимая часть — центральные 72dp. */
    private fun renderLayers(size: Int, bg: Drawable?, fg: Drawable?, alphaOnly: Boolean = false): Bitmap {
        val bmp = if (alphaOnly) Bitmap.createBitmap(size, size, Bitmap.Config.ALPHA_8) else createBitmap(size, size)
        val c = Canvas(bmp)
        val pad = (size * 0.25f).toInt()
        for (layer in arrayOf(bg, fg)) {
            layer ?: continue
            layer.setBounds(-pad, -pad, size + pad, size + pad)
            layer.draw(c)
        }
        return bmp
    }

    private fun purgeIcons(pkg: String) {
        updatedAt.remove(pkg)
        stale += pkg
        eventAt[pkg] = SystemClock.elapsedRealtime()
        for (k in cache.snapshot().keys) if (k.startsWith("$pkg/")) cache.remove(k)
    }

    private fun preloadIcons(list: List<AppInfo>) {
        preloadJob?.cancel()
        preloadJob = scope.launch(preloadDispatcher) {
            iconsReady.await()
            // Первые кадры и анимация входа важнее: чтение сотен значков в этот момент только отнимало бы
            // процессор у интерфейса. Видимые значки стол и шторка загрузят сами, остальное — после паузы.
            delay(PRELOAD_DELAY_MS)
            for (a in list.take(PRELOAD_LIMIT)) {
                ensureActive()
                if (cache.get(a.key) == null) loadIcon(a)
            }
        }
    }

    // ---------- Темы значков ----------

    fun setIconTheme(id: String) {
        if (id == iconThemeId) return
        themeJob?.cancel()
        themeJob = scope.launch(Dispatchers.Default) {
            // Сначала загружаем набор, и только потом меняем id. Если сделать наоборот, значки, нарисованные
            // в это время по старой теме, попадут в дисковый кэш под ключом новой и останутся там навсегда.
            val loaded = runCatching { if (id.isEmpty()) null else themes.load(id) }.getOrNull()
            if (!isActive) return@launch
            theme = loaded
            iconThemeId = id
            cache.evictAll()
            bumpEpoch()
            preloadIcons(_apps.value)
        }
    }

    /** Значки для карточки темы в настройках; в общий кэш не попадают. */
    suspend fun previewIcons(id: String, limit: Int = 5): List<ImageBitmap> = withContext(Dispatchers.Default) {
        val t = (if (id == iconThemeId) theme else themes.load(id)) ?: return@withContext emptyList()
        val out = ArrayList<ImageBitmap>(limit)
        for (a in _apps.value) {
            if (out.size >= limit) break
            ensureActive()
            val d = t.iconFor(a.packageName, a.className) ?: continue
            runCatching { out += renderPackIcon(d).image }
        }
        out
    }

    fun refreshThemes() { scope.launch(Dispatchers.Default) { runCatching { themes.refresh() } } }

    // ---------- Свой значок приложения ----------

    // Без "%02x".format на каждый байт: он форматирует строку через Locale в цикле, а хэш считается для каждого
    // значка при поиске своего значка и при прокрутке списка приложений
    private fun sha(key: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        val hex = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            hex[i * 2] = HEX_DIGITS[v ushr 4]
            hex[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
        }
        return String(hex)
    }

    private fun overrideFile(key: String) = File(overridesDir, sha(key) + ".png")

    fun hasOverride(app: AppInfo): Boolean = sha(app.key) in overrideKeys

    private fun loadOverride(app: AppInfo): AppIcon? {
        if (sha(app.key) !in overrideKeys) return null
        val bmp = BitmapFactory.decodeFile(overrideFile(app.key).path) ?: return null
        return AppIcon(bmp.toDisplay(), legacy = false, mono = null, accent = bmp.accent())
    }

    /** Обрезает картинку по центру в квадрат и сохраняет как значок приложения. */
    suspend fun setOverride(app: AppInfo, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ok = runCatching {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= iconPx) sample *= 2
            val src = resolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: error("not an image")
            val side = minOf(src.width, src.height)
            val out = createBitmap(iconPx, iconPx)
            Canvas(out).drawBitmap(
                src,
                Rect((src.width - side) / 2, (src.height - side) / 2, (src.width + side) / 2, (src.height + side) / 2),
                Rect(0, 0, iconPx, iconPx),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            overridesDir.mkdirs()
            overrideFile(app.key).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            overrideKeys = overrideKeys + sha(app.key)
        }.isSuccess
        if (ok) { cache.remove(app.key); bumpEpoch() }
        ok
    }

    fun clearOverride(app: AppInfo) {
        overrideFile(app.key).delete()
        overrideKeys = overrideKeys - sha(app.key)
        cache.remove(app.key)
        iconEpoch++
    }

    // ---------- Запуск и действия ----------

    /**
     * [bounds] — прямоугольник значка, из которого раскрывается окно. Он же задаёт, куда система свернёт
     * окно при закрытии: с этими границами закрытие идёт в значок приложения, а не «в никуда».
     * Для [portal] системный переход начинается на весь экран, а исходная позиция
     * значка остаётся в sourceBounds для системы.
     */
    fun launch(app: AppInfo, view: View?, bounds: Rect?, portal: Boolean = false): Boolean = runCatching {
        val sourceBounds = bounds?.let { rect ->
            Rect(rect).apply {
                if (view != null) {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    offset(location[0], location[1])
                }
            }
        }
        val opts = when {
            portal && view != null && view.width > 0 && view.height > 0 ->
                ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.width, view.height)
            view != null && bounds != null ->
                ActivityOptions.makeScaleUpAnimation(view, bounds.left, bounds.top, bounds.width(), bounds.height())
            else -> null
        }
        // Системная заставка с крупным значком по центру показала бы второй значок поверх карточки —
        // визуально «сначала иконка, потом приложение». Просим у системы сплошной цвет (Android 13+):
        // стартовое окно совпадает по цвету с карточкой и просто ждёт первый кадр приложения.
        if (opts != null && portal && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            opts.setSplashScreenStyle(SplashScreen.SPLASH_SCREEN_STYLE_SOLID_COLOR)
        }
        launcherApps.startMainActivity(app.component, app.user, sourceBounds, opts?.toBundle())
    }.isSuccess

    // ---------- Цвет стартового окна ----------

    /** Ключ — приложение и ночной режим: у тёмной и светлой темы сплэш разный. Значение 0 — цвет узнать не удалось. */
    private val splashColors = java.util.concurrent.ConcurrentHashMap<String, Int>()

    private fun splashKey(app: AppInfo): String =
        app.key + "|" + (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)

    /** Цвет фона, с которым система покажет окно приложения; null — ещё не посчитан или неизвестен. Без IPC. */
    fun cachedSplashColor(app: AppInfo): Int? = splashColors[splashKey(app)]?.takeIf { it != 0 }

    /**
     * Читает из темы приложения фон сплэша (Android 12+) или окна. Карточка запуска приходит к этому цвету,
     * и окно приложения проявляется поверх без светлой или тёмной вспышки. Вызывать не в главном потоке.
     */
    fun warmSplashColor(app: AppInfo) {
        val key = splashKey(app)
        if (splashColors.containsKey(key)) return
        splashColors[key] = runCatching { resolveSplashColor(app) }.getOrNull() ?: 0
    }

    private fun resolveSplashColor(app: AppInfo): Int? {
        val info = context.packageManager.getActivityInfo(app.component, 0)
        val themeRes = info.themeResource.takeIf { it != 0 } ?: info.applicationInfo.theme
        if (themeRes == 0) return null
        val pkg = context.createPackageContext(app.packageName, 0)
        pkg.setTheme(themeRes)
        val tv = android.util.TypedValue()
        fun rawColor(attr: Int): Int? {
            if (!pkg.theme.resolveAttribute(attr, tv, true)) return null
            if (tv.type in android.util.TypedValue.TYPE_FIRST_COLOR_INT..android.util.TypedValue.TYPE_LAST_COLOR_INT) return tv.data
            if (tv.resourceId == 0) return null
            // Ссылка на селектор цвета или на цветную картинку
            runCatching { pkg.getColor(tv.resourceId) }.getOrNull()?.let { return it }
            return when (val d = runCatching { pkg.getDrawable(tv.resourceId) }.getOrNull()) {
                is android.graphics.drawable.ColorDrawable -> d.color
                else -> if (Build.VERSION.SDK_INT >= 29 && d is android.graphics.drawable.ColorStateListDrawable) d.colorStateList.defaultColor else null
            }
        }
        // Прозрачный или полупрозрачный цвет система не использует как фон сплэша: идём дальше по цепочке
        fun color(attr: Int): Int? = rawColor(attr)?.takeIf { android.graphics.Color.alpha(it) == 0xFF }
        val splash = if (Build.VERSION.SDK_INT >= 31) color(android.R.attr.windowSplashScreenBackground) else null
        return splash ?: color(android.R.attr.windowBackground) ?: color(android.R.attr.colorBackground)
    }

    fun openAppInfo(app: AppInfo, bounds: Rect?) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, bounds, null) }
    }

    fun uninstall(app: AppInfo) {
        val i = Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}"))
            .putExtra(Intent.EXTRA_USER, app.user)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    suspend fun shortcuts(app: AppInfo): List<ShortcutEntry> = withContext(Dispatchers.Default) {
        runCatching {
            if (!launcherApps.hasShortcutHostPermission()) return@runCatching emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.component)
                .setQueryFlags(
                    LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                        LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED
                )
            val dpi = context.resources.displayMetrics.densityDpi
            launcherApps.getShortcuts(q, app.user).orEmpty()
                .filter { it.isEnabled }
                .take(5)
                .map { s ->
                    val icon = runCatching {
                        launcherApps.getShortcutIconDrawable(s, dpi)?.toBitmap(96, 96)?.asImageBitmap()
                    }.getOrNull()
                    ShortcutEntry(s, (s.shortLabel ?: s.id).toString(), icon)
                }
        }.getOrDefault(emptyList())
    }

    fun startShortcut(s: ShortcutInfo, bounds: Rect?) {
        runCatching { launcherApps.startShortcut(s, bounds, null) }
    }

    /** Приложение основного профиля для действия; если обработчик не выбран, берём первого подходящего. */
    fun findDefault(intent: Intent): AppInfo? {
        val pm = context.packageManager
        val candidates = listOfNotNull(pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo) +
            runCatching { pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo } }.getOrDefault(emptyList())
        val mine = byKey.values.filter { !it.isWork }
        for (c in candidates) {
            mine.firstOrNull { it.packageName == c.packageName && it.className == c.name }?.let { return it }
            mine.firstOrNull { it.packageName == c.packageName }?.let { return it }
        }
        return null
    }

    private companion object {
        const val MAX_CACHED_ICONS = 400
        /** Сколько значков читаем заранее: остальные подгружаются при прокрутке, когда они нужны. */
        const val PRELOAD_LIMIT = 160
        const val PRELOAD_DELAY_MS = 900L
        const val DISK_DIR_NAME = ICON_CACHE_DIR
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
        /** Потолок дискового кэша значков. */
        const val MAX_DISK_BYTES = 100L * 1024 * 1024
        const val TRIM_EVERY_WRITES = 150
        const val TOUCH_INTERVAL_MS = 6L * 60 * 60 * 1000
        const val ORPHAN_AGE_MS = 60L * 60 * 1000
        const val MAX_BROKEN_RETRIES = 2
        /** Столько после события пакета значок считается «оседающим» и не сохраняется. */
        const val SETTLE_MS = 6000L
        const val BROKEN_RETRY_DELAY_MS = 4000L
    }
}
