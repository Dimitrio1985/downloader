package com.download.app

import android.content.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date

val Bg = Color(0xFF080B12)
val Card = Color(0xFF121722)
val Blue = Color(0xFF3B82F6)
val Gray = Color(0xFF8A94A6)

// 0 завантажується, 1 пауза, 2 готово, 3 помилка
data class Item(
    val id: Long, val name: String, val url: String,
    val total: Long = 0, val done: Long = 0, val status: Int = 0,
    val date: Long = System.currentTimeMillis(), val speed: Long = 0
) {
    fun pct() = if (total > 0) (done * 100 / total).toInt() else 0
    fun frac() = if (total > 0) done.toFloat() / total else 0f
}

val kinds = listOf(
    Triple("Изображения", Icons.Filled.Image, Color(0xFF3B82F6)),
    Triple("Видео", Icons.Filled.PlayArrow, Color(0xFF7C5CFC)),
    Triple("Архивы", Icons.Filled.FolderZip, Color(0xFFF5B223)),
    Triple("Музыка", Icons.Filled.MusicNote, Color(0xFFEF4468)),
    Triple("Документы", Icons.Filled.Description, Color(0xFF22B58A))
)

fun kind(n: String) = when (n.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg", "png", "gif", "webp", "bmp" -> 0
    "mp4", "mkv", "avi", "mov", "webm" -> 1
    "zip", "rar", "7z", "tar", "gz" -> 2
    "mp3", "wav", "flac", "ogg", "m4a" -> 3
    else -> 4
}

fun sz(b: Long) = when {
    b >= 1L shl 30 -> "%.1f ГБ".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.1f МБ".format(b / 1048576.0)
    b >= 1L shl 10 -> "%.0f КБ".format(b / 1024.0)
    else -> "$b Б"
}

fun fmtDate(t: Long): String = SimpleDateFormat("dd.MM.yyyy, HH:mm").format(Date(t))
fun statusText(x: Item) = listOf("Загружается", "Пауза", "Завершено", "Ошибка")[x.status]
fun eta(x: Item) =
    if (x.speed > 0 && x.total > 0) "${(x.total - x.done) / x.speed / 60 + 1} мин осталось" else statusText(x)

fun guessName(u: String): String =
    runCatching { Uri.parse(u.trim()).lastPathSegment }.getOrNull()?.takeIf { it.isNotBlank() } ?: "file"

object Store {
    val items = mutableStateListOf<Item>()
    private val jobs = HashMap<Long, Job>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    lateinit var dir: File
    lateinit var prefs: SharedPreferences

    fun init(c: Context) {
        dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)!!
        prefs = c.getSharedPreferences("d", 0)
        val a = JSONArray(prefs.getString("items", "[]"))
        for (i in 0 until a.length()) {
            val o = a.getJSONObject(i)
            val st = if (o.getInt("st") == 2) 2 else 1
            items.add(Item(o.getLong("id"), o.getString("name"), o.getString("url"),
                o.getLong("total"), o.getLong("done"), st, o.getLong("date")))
        }
    }

    fun save() {
        val a = JSONArray()
        items.toList().forEach {
            a.put(JSONObject().put("id", it.id).put("name", it.name).put("url", it.url)
                .put("total", it.total).put("done", it.done).put("st", it.status).put("date", it.date))
        }
        prefs.edit().putString("items", a.toString()).apply()
    }

    private fun upd(id: Long, f: (Item) -> Item) {
        val i = items.indexOfFirst { it.id == id }
        if (i >= 0) items[i] = f(items[i])
    }

    private fun uniq(n: String): String {
        var r = n; var k = 1
        val base = n.substringBeforeLast('.', n); val ext = n.substringAfterLast('.', "")
        while (File(dir, r).exists() || items.any { it.name == r }) {
            r = if (ext.isEmpty()) "$base ($k)" else "$base ($k).$ext"; k++
        }
        return r
    }

    fun add(url: String, name: String): Long {
        val id = System.currentTimeMillis()
        items.add(0, Item(id, uniq(name), url))
        save(); start(id)
        return id
    }

    fun pause(id: Long) { jobs[id]?.cancel(); upd(id) { it.copy(status = 1, speed = 0) }; save() }

    fun delete(id: Long) {
        jobs[id]?.cancel()
        items.firstOrNull { it.id == id }?.let { File(dir, it.name).delete() }
        items.removeAll { it.id == id }
        save()
    }

    fun start(id: Long) {
        val x = items.first { it.id == id }
        upd(id) { it.copy(status = 0) }
        jobs[id] = scope.launch {
            try {
                val f = File(dir, x.name)
                val have = if (f.exists()) f.length() else 0L
                val c = URL(x.url).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 15000
                if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
                c.connect()
                if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
                val resumed = c.responseCode == 206
                var done = if (resumed) have else 0L
                val total = if (c.contentLengthLong > 0) done + c.contentLengthLong else 0L
                upd(id) { it.copy(total = total, done = done) }
                var t0 = System.currentTimeMillis(); var b0 = done
                FileOutputStream(f, resumed).use { out ->
                    c.inputStream.use { ins ->
                        val buf = ByteArray(32768)
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break
                            ensureActive(); out.write(buf, 0, n); done += n
                            val now = System.currentTimeMillis()
                            if (now - t0 >= 500) {
                                val sp = (done - b0) * 1000 / (now - t0); t0 = now; b0 = done
                                upd(id) { it.copy(done = done, speed = sp) }
                            }
                        }
                    }
                }
                upd(id) { it.copy(done = done, total = if (it.total == 0L) done else it.total, status = 2, speed = 0) }
            } catch (e: CancellationException) {
                upd(id) { it.copy(status = 1, speed = 0) }; save(); throw e
            } catch (e: Exception) {
                upd(id) { it.copy(status = 3, speed = 0) }
            }
            save()
        }
    }
}

fun mime(n: String) = MimeTypeMap.getSingleton()
    .getMimeTypeFromExtension(n.substringAfterLast('.', "").lowercase()) ?: "*/*"

fun fileUri(c: Context, n: String): Uri = FileProvider.getUriForFile(c, "${c.packageName}.fp", File(Store.dir, n))

fun openFile(c: Context, n: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(fileUri(c, n), mime(n))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { c.startActivity(i) }.onFailure { Toast.makeText(c, "Нет приложения для этого файла", Toast.LENGTH_SHORT).show() }
}

fun shareFile(c: Context, n: String) {
    val i = Intent(Intent.ACTION_SEND).setType(mime(n)).putExtra(Intent.EXTRA_STREAM, fileUri(c, n))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { c.startActivity(Intent.createChooser(i, null)) }
}

sealed interface S {
    object Home : S
    object Downloads : S
    data class Add(val url: String) : S
    data class Prog(val id: Long) : S
    data class Info(val id: Long) : S
    object Settings : S
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bars = SystemBarStyle.dark(Bg.toArgb())
        enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        Store.init(applicationContext)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = Card,
                onBackground = Color.White, onSurface = Color.White)) {
                Surface(Modifier.fillMaxSize(), color = Bg) {
                    Box(Modifier.systemBarsPadding()) { App() }
                }
            }
        }
    }
}

@Composable
fun App() {
    val stack = remember { mutableStateListOf<S>(if (Store.items.isEmpty()) S.Home else S.Downloads) }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    BackHandler(stack.size > 1) { back() }
    when (val s = stack.last()) {
        S.Home -> Home { stack.add(S.Add(it)) }
        S.Downloads -> Downloads(
            open = { stack.add(if (it.status == 2) S.Info(it.id) else S.Prog(it.id)) },
            add = { stack.add(S.Add("")) },
            settings = { stack.add(S.Settings) })
        is S.Add -> Add(s.url, ::back) { url, name ->
            val id = Store.add(url, name)
            stack.clear(); stack.add(S.Downloads); stack.add(S.Prog(id))
        }
        is S.Prog -> Prog(s.id, ::back) { stack.add(S.Info(s.id)) }
        is S.Info -> Info(s.id, ::back)
        S.Settings -> Settings(::back)
    }
}

@Composable
fun BigBtn(text: String, enabled: Boolean = true, onClick: () -> Unit) =
    Button(onClick, Modifier.fillMaxWidth().height(54.dp), enabled = enabled, shape = RoundedCornerShape(27.dp)) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
    }

@Composable
fun UrlField(v: String, clear: Boolean = false, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
        placeholder = { Text("Вставьте ссылку на файл") },
        leadingIcon = { Icon(Icons.Filled.Link, null) },
        trailingIcon = { if (clear) IconButton({ onChange("") }) { Icon(Icons.Filled.Close, null) } })

@Composable
fun FileIcon(n: String, s: Dp = 44.dp) {
    val (_, ic, c) = kinds[kind(n)]
    Box(Modifier.size(s).clip(RoundedCornerShape(s / 4)).background(c), Alignment.Center) {
        Icon(ic, null, Modifier.size(s / 2), tint = Color.White)
    }
}

@Composable
fun Bar(title: String, back: () -> Unit) =
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, null) }
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }

@Composable
fun Opt(ic: ImageVector, t: String, v: String, chev: Boolean = false, click: (() -> Unit)? = null) =
    Row(Modifier.fillMaxWidth().clickable(enabled = click != null) { click?.invoke() }
        .padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, null, tint = Gray)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) { Text(t, fontSize = 15.sp); Text(v, fontSize = 12.sp, color = Gray) }
        if (chev) Icon(Icons.Filled.KeyboardArrowRight, null, tint = Gray)
    }

@Composable
fun Home(go: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(120.dp).border(4.dp, Blue, CircleShape), Alignment.Center) {
            Icon(Icons.Filled.Download, null, Modifier.size(56.dp))
        }
        Spacer(Modifier.height(24.dp))
        Text("Загрузчик\nфайлов", fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text("Скачивайте файлы\nбыстро и удобно", color = Gray, fontSize = 16.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.weight(1f))
        UrlField(url) { url = it }
        Spacer(Modifier.height(12.dp))
        BigBtn("Скачать", url.isNotBlank()) { go(url.trim()) }
    }
}

@Composable
fun Downloads(open: (Item) -> Unit, add: () -> Unit, settings: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }
    val list = Store.items.filter { tab == 0 || (tab == 1) == (it.status == 2) }
    Box(Modifier.fillMaxSize()) {
        Column {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Загрузки", fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                IconButton(settings) { Icon(Icons.Filled.MoreVert, null) }
            }
            TabRow(tab, containerColor = Color.Transparent) {
                listOf("Все", "Завершённые", "В процессе").forEachIndexed { i, t ->
                    Tab(tab == i, { tab = i }, text = { Text(t, fontSize = 14.sp) })
                }
            }
            if (list.isEmpty()) Text("Нет загрузок. Нажмите +, чтобы добавить ссылку", color = Gray, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.weight(1f)) {
                items(list, key = { it.id }) { x ->
                    Row(Modifier.fillMaxWidth().clickable { open(x) }.padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FileIcon(x.name)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(x.name, fontSize = 15.sp, maxLines = 1)
                            Text("${sz(if (x.total > 0) x.total else x.done)} • ${statusText(x)}", fontSize = 12.sp, color = Gray)
                            if (x.status == 2) Text(fmtDate(x.date), fontSize = 12.sp, color = Gray)
                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                LinearProgressIndicator({ x.frac() }, Modifier.weight(1f).height(4.dp).clip(CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text("${x.pct()}%", fontSize = 12.sp, color = Gray)
                            }
                        }
                    }
                }
            }
        }
        FloatingActionButton(add, Modifier.align(Alignment.BottomEnd).padding(20.dp),
            containerColor = Blue, contentColor = Color.White, shape = CircleShape) { Icon(Icons.Filled.Add, null) }
    }
}

@Composable
fun Add(init: String, back: () -> Unit, go: (String, String) -> Unit) {
    var url by remember { mutableStateOf(init) }
    var name by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf(false) }
    val shown = name ?: guessName(url)
    Column(Modifier.fillMaxSize()) {
        Bar("Добавить ссылку", back)
        Box(Modifier.padding(horizontal = 16.dp)) { UrlField(url, true) { url = it } }
        Text("Параметры загрузки", fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 4.dp))
        Opt(Icons.Filled.Folder, "Сохранить в", "Загрузки")
        Opt(Icons.Filled.Description, "Имя файла", shown, true) { edit = true }
        Opt(Icons.Filled.Category, "Категория", kinds[kind(shown)].first)
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(16.dp)) { BigBtn("Скачать", url.isNotBlank()) { go(url.trim(), shown) } }
    }
    if (edit) {
        var t by remember { mutableStateOf(shown) }
        AlertDialog(onDismissRequest = { edit = false }, title = { Text("Имя файла") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton({ name = t.trim().ifEmpty { null }; edit = false }) { Text("OK") } })
    }
}

@Composable
fun Stat(ic: ImageVector, v: String, l: String, m: Modifier) =
    Row(m, verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, null, tint = Gray)
        Spacer(Modifier.width(10.dp))
        Column { Text(v, fontSize = 15.sp); Text(l, fontSize = 12.sp, color = Gray) }
    }

@Composable
fun Prog(id: Long, back: () -> Unit, info: () -> Unit) {
    val x = Store.items.firstOrNull { it.id == id }
    if (x == null) { LaunchedEffect(Unit) { back() }; return }
    Column(Modifier.fillMaxSize()) {
        Bar("Загрузка", back)
        Column(Modifier.weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(40.dp))
            FileIcon(x.name, 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(x.name, fontSize = 17.sp)
            Text("${sz(x.total)} • ${eta(x)}", fontSize = 13.sp, color = Gray)
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator({ x.frac() }, Modifier.weight(1f).height(6.dp).clip(CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("${x.pct()}%", fontSize = 14.sp)
            }
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth()) {
                Stat(Icons.Filled.Speed, "${sz(x.speed)}/с", "Скорость", Modifier.weight(1f))
                Stat(Icons.Filled.Download, sz(x.done), "Загружено", Modifier.weight(1f))
            }
            Spacer(Modifier.weight(1f))
            when (x.status) {
                0 -> OutlinedButton({ Store.pause(id) }, Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(27.dp)) {
                    Icon(Icons.Filled.Pause, null); Spacer(Modifier.width(8.dp)); Text("Пауза")
                }
                2 -> BigBtn("О файле", onClick = info)
                else -> BigBtn(if (x.status == 3) "Повторить" else "Продолжить") { Store.start(id) }
            }
        }
    }
}

@Composable
fun InfoRow(ic: ImageVector, l: String, v: String) =
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(ic, null, tint = Gray); Spacer(Modifier.width(16.dp))
        Text(l, Modifier.weight(1f), fontSize = 15.sp); Text(v, fontSize = 14.sp, color = Gray)
    }

@Composable
fun Info(id: Long, back: () -> Unit) {
    val ctx = LocalContext.current
    val x = Store.items.firstOrNull { it.id == id }
    if (x == null) { LaunchedEffect(Unit) { back() }; return }
    Column(Modifier.fillMaxSize()) {
        Bar("О файле", back)
        Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            FileIcon(x.name, 72.dp)
            Spacer(Modifier.height(12.dp))
            Text(x.name, fontSize = 17.sp); Text(sz(x.total), fontSize = 13.sp, color = Gray)
            Spacer(Modifier.height(24.dp))
            InfoRow(Icons.Filled.Description, "Тип", "${kinds[kind(x.name)].first} (${x.name.substringAfterLast('.', "?").uppercase()})")
            InfoRow(Icons.Filled.Info, "Размер", sz(x.total))
            InfoRow(Icons.Filled.Folder, "Расположение", "Загрузки")
            InfoRow(Icons.Filled.CalendarMonth, "Дата загрузки", fmtDate(x.date))
        }
        Spacer(Modifier.height(24.dp))
        Opt(Icons.Filled.PlayArrow, "Открыть", "", false) { openFile(ctx, x.name) }
        Opt(Icons.Filled.Share, "Поделиться", "", false) { shareFile(ctx, x.name) }
        Opt(Icons.Filled.Delete, "Удалить", "", false) { Store.delete(id) }
    }
}

@Composable
fun Settings(back: () -> Unit) {
    var notif by remember { mutableStateOf(Store.prefs.getBoolean("n", true)) }
    var about by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Bar("Настройки", back)
        Opt(Icons.Filled.Folder, "Папка загрузок", Store.dir.path)
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Notifications, null, tint = Gray); Spacer(Modifier.width(16.dp))
            Text("Уведомления", Modifier.weight(1f), fontSize = 15.sp)
            Switch(notif, { notif = it; Store.prefs.edit().putBoolean("n", it).apply() })
        }
        Opt(Icons.Filled.Palette, "Тема", "Тёмная")
        HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Card)
        Opt(Icons.Filled.Info, "О приложении", "", true) { about = true }
    }
    if (about) AlertDialog(onDismissRequest = { about = false }, title = { Text("download") },
        text = { Text("Загрузчик файлов по ссылке. Версия 1.0") },
        confirmButton = { TextButton({ about = false }) { Text("OK") } })
}
