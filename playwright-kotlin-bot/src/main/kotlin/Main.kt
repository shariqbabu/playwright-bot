import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.microsoft.playwright.*
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

val gson: Gson = GsonBuilder().setPrettyPrinting().create()

// Environment Configurations
val PORT = System.getenv("PORT")?.toIntOrNull() ?: 8080
val API_KEY = System.getenv("API_KEY") ?: ""
val START_URL = System.getenv("START_URL") ?: "https://replit.com/@shrqbabu/Gemini-Hub"
val OMNIROUTE_COMMAND = System.getenv("OMNIROUTE_COMMAND")
    ?: "cd /home/runner/workspace/.omniroute && export PNPM_HOME=\$HOME/.local/share/pnpm && export PATH=\$PNPM_HOME:\$PATH && HOST=0.0.0.0 PORT=20128 ./node_modules/.bin/omniroute"

val PROFILE_DIR = Paths.get("data", "replit-profile")

// State Variables
var playwright: Playwright? = null
var context: BrowserContext? = null
var page: Page? = null
var state = "STOPPED"
var startedAt: Long? = null
var lastActivity: String? = null
var lastError: String? = null

data class WatchdogState(
    var enabled: Boolean = true,
    var running: Boolean = false,
    val intervalMinutes: Long = 40,
    val emptyConfirmMinutes: Long = 2,
    var lastShellCheck: String? = null,
    var lastShellEmpty: Boolean? = null,
    var lastCommandSentAt: String? = null,
    var lastCommand: String? = null
)

val watchdog = WatchdogState()
val scheduler: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

fun cleanTerminalText(s: String?): String {
    if (s.isNullOrBlank()) return ""
    return s.replace(Regex("\u001b\\[[0-?]*[ -/]*[@-~]"), "")
        .replace(Regex("\u001b\\][^\u0007]*(?:\u0007|\u001b\\\\)"), "")
        .replace("\r", "")
        .replace("\u200b", "")
        .replace("\u00a0", " ")
        .trim()
}

fun isOmniRouteRunning(text: String): Boolean {
    val t = cleanTerminalText(text).lowercase()
    return t.contains("omniroute is running") ||
            t.contains("dashboard:") ||
            t.contains("api base:") ||
            t.contains("press ctrl+c to stop")
}

@Synchronized
fun getActivePage(): Page {
    val p = page ?: throw IllegalStateException("Playwright page is not available")
    if (p.isClosed) throw IllegalStateException("Playwright page is closed")
    return p
}

fun readShell(): String {
    val p = getActivePage()
    val selectors = listOf(
        ".xterm-screen",
        ".xterm-rows",
        ".xterm",
        "[class*='xterm-screen']",
        "[class*='terminal']"
    )

    for (selector in selectors) {
        try {
            val loc = p.locator(selector).last()
            if (loc.count() > 0) {
                val text = cleanTerminalText(loc.innerText(Locator.InnerTextOptions().setTimeout(1500.0)))
                if (text.isNotBlank() || selector == ".xterm" || selector.contains("terminal")) {
                    return text
                }
            }
        } catch (_: Exception) {}
    }
    return ""
}

fun sendOmniRouteCommand() {
    val p = getActivePage()

    val candidates = listOf(
        ".xterm-helper-textarea",
        ".xterm textarea",
        ".xterm-helper-textarea-container textarea",
        "textarea[aria-label*='terminal' i]",
        "textarea[aria-label*='shell' i]",
        "textarea[placeholder*='terminal' i]",
        "[contenteditable='true'][aria-label*='terminal' i]",
        "[contenteditable='true'][aria-label*='shell' i]",
        "[role='textbox'][aria-label*='terminal' i]",
        "[role='textbox'][aria-label*='shell' i]"
    )

    var input: Locator? = null
    for (sel in candidates) {
        try {
            val loc = p.locator(sel).last()
            if (loc.count() > 0) {
                input = loc
                break
            }
        } catch (_: Exception) {}
    }

    if (input == null) {
        throw IllegalStateException("Replit Shell terminal input not found on page.")
    }

    try {
        input.focus()
        input.click(Locator.ClickOptions().setForce(true))
    } catch (_: Exception) {}

    input.pressSequentially(OMNIROUTE_COMMAND, Locator.PressSequentiallyOptions().setDelay(1.0))
    input.press("Enter")

    val now = Instant.now().toString()
    watchdog.lastCommandSentAt = now
    watchdog.lastCommand = OMNIROUTE_COMMAND
    lastActivity = now
    println(">>> [OmniRoute] Command sent to terminal at $now")
}

fun watchdogCheck() {
    if (watchdog.running || !watchdog.enabled) return
    watchdog.running = true

    try {
        println(">>> [Watchdog] Checking shell status...")
        val text = readShell()
        watchdog.lastShellCheck = Instant.now().toString()

        if (isOmniRouteRunning(text)) {
            watchdog.lastShellEmpty = false
            println(">>> [Watchdog] OmniRoute is ALREADY running!")
            return
        }

        val empty = cleanTerminalText(text).isEmpty()
        watchdog.lastShellEmpty = empty

        if (!empty) return

        println(">>> [Watchdog] Shell empty. Waiting 2 minutes for confirmation...")
        Thread.sleep(watchdog.emptyConfirmMinutes * 60 * 1000)

        val text2 = readShell()
        watchdog.lastShellCheck = Instant.now().toString()

        if (isOmniRouteRunning(text2)) {
            watchdog.lastShellEmpty = false
            return
        }

        val stillEmpty = cleanTerminalText(text2).isEmpty()
        watchdog.lastShellEmpty = stillEmpty

        if (!stillEmpty) return

        println(">>> [Watchdog] Triggering OmniRoute start command...")
        sendOmniRouteCommand()

        Thread.sleep(5000)
        val after = readShell()
        if (!isOmniRouteRunning(after)) {
            lastError = "Command was typed, but OmniRoute marker not detected after 5 seconds"
        } else {
            lastError = null
            println(">>> [Watchdog] OmniRoute started successfully!")
        }
    } catch (err: Exception) {
        lastError = err.message ?: err.toString()
        System.err.println(">>> [Watchdog Error] $lastError")
    } finally {
        watchdog.running = false
    }
}

@Synchronized
fun cleanupBrowser() {
    try { if (page?.isClosed == false) page?.close() } catch (_: Exception) {}
    try { context?.close() } catch (_: Exception) {}
    try { playwright?.close() } catch (_: Exception) {}
    page = null
    context = null
    playwright = null
    println(">>> Browser cleanup completed.")
}

fun scheduleBrowserRecovery() {
    scheduler.schedule({
        if (watchdog.enabled && (context == null || page == null || page?.isClosed == true)) {
            println(">>> Page crashed or closed. Auto-recovering browser...")
            startBrowser()
        }
    }, 5, TimeUnit.SECONDS)
}

@Synchronized
fun startBrowser() {
    if (state == "STARTING" || state == "RUNNING") return

    state = "STARTING"
    lastError = null
    println(">>> Starting Playwright Chromium for Replit...")

    try {
        File(PROFILE_DIR.toString()).mkdirs()
        playwright = Playwright.create()

        // 1GB RAM + 2GB SWAP CRITICAL OPTIMIZATIONS
        val lowMemArgs = listOf(
            "--no-sandbox",
            "--disable-setuid-sandbox",
            "--disable-dev-shm-usage",
            "--disable-gpu",
            "--no-first-run",
            "--no-zygote",
            "--disable-background-networking",
            "--disable-background-timer-throttling",
            "--disable-renderer-backgrounding",
            "--mute-audio",
            "--disable-software-rasterizer",
            "--window-size=1280,720",
            "--js-flags=--max-old-space-size=256"
        )

        val options = BrowserType.LaunchPersistentContextOptions()
            .setHeadless(false)
            .setArgs(lowMemArgs)
            .setViewportSize(1280, 720)

        context = playwright!!.chromium().launchPersistentContext(PROFILE_DIR, options)

        val pages = context!!.pages()
        page = if (pages.isNotEmpty()) pages[0] else context!!.newPage()

        page!!.onClose { scheduleBrowserRecovery() }
        page!!.onCrash { scheduleBrowserRecovery() }

        page!!.route("**/*.{png,jpg,jpeg,webp,svg,gif,woff,woff2,ttf,mp4,webm}") { route ->
            route.abort()
        }

        println(">>> Navigating to Replit Workspace: $START_URL")
        page!!.navigate(START_URL, Page.NavigateOptions().setTimeout(90000.0))

        Thread.sleep(8000)

        state = "RUNNING"
        if (startedAt == null) startedAt = System.currentTimeMillis()
        lastActivity = Instant.now().toString()
        println(">>> Replit Page Loaded & State is RUNNING!")

        scheduler.schedule({ watchdogCheck() }, 10, TimeUnit.SECONDS)

    } catch (err: Exception) {
        state = "STOPPED"
        lastError = err.message ?: err.toString()
        System.err.println(">>> Failed to start browser: $lastError")
        cleanupBrowser()
    }
}

// ----------------- MOBILE WEB DASHBOARD (HTML/CSS/JS) -----------------

val DASHBOARD_HTML = """
<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="UTF-8">
  <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
  <title>OmniRoute Control</title>
  <link rel="apple-touch-icon" href="https://replit.com/public/icons/favicon-196.png">
  <meta name="theme-color" content="#0d1117">
  <style>
    * { box-sizing: border-box; margin: 0; padding: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
    body { background-color: #0d1117; color: #c9d1d9; padding: 16px; display: flex; justify-content: center; }
    .container { width: 100%; max-width: 480px; display: flex; flex-direction: column; gap: 16px; }
    
    .header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 8px; border-bottom: 1px solid #21262d; }
    .header h1 { font-size: 1.25rem; color: #58a6ff; font-weight: 600; display: flex; align-items: center; gap: 8px; }
    
    .card { background: #161b22; border: 1px solid #30363d; border-radius: 12px; padding: 16px; display: flex; flex-direction: column; gap: 12px; }
    .card-title { font-size: 0.85rem; text-transform: uppercase; color: #8b949e; letter-spacing: 0.5px; }

    .status-badge { display: inline-flex; align-items: center; gap: 8px; font-weight: 700; font-size: 1.1rem; padding: 6px 12px; border-radius: 20px; width: fit-content; }
    .status-RUNNING { background: rgba(46, 160, 67, 0.15); color: #3fb950; border: 1px solid #2ea043; }
    .status-STARTING { background: rgba(210, 153, 34, 0.15); color: #d29922; border: 1px solid #bb8009; }
    .status-STOPPED { background: rgba(248, 81, 73, 0.15); color: #f85149; border: 1px solid #da3633; }
    .dot { width: 10px; height: 10px; border-radius: 50%; display: inline-block; }
    .dot-RUNNING { background: #3fb950; box-shadow: 0 0 10px #3fb950; }
    .dot-STARTING { background: #d29922; }
    .dot-STOPPED { background: #f85149; }

    .info-row { display: flex; justify-content: space-between; font-size: 0.9rem; padding: 4px 0; }
    .info-label { color: #8b949e; }
    .info-val { color: #f0f6fc; font-weight: 500; text-align: right; word-break: break-all; }

    .grid-buttons { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    button {
      background: #21262d; border: 1px solid #363b42; color: #f0f6fc;
      padding: 14px; border-radius: 10px; font-size: 0.95rem; font-weight: 600;
      cursor: pointer; display: flex; align-items: center; justify-content: center; gap: 8px;
      transition: background 0.2s, transform 0.1s;
    }
    button:active { transform: scale(0.98); }
    button.btn-start { background: #238636; border-color: #2ea043; }
    button.btn-start:hover { background: #2ea043; }
    button.btn-stop { background: #da3633; border-color: #f85149; }
    button.btn-stop:hover { background: #b62324; }
    button.btn-full { grid-column: span 2; }

    .screenshot-img { width: 100%; border-radius: 8px; border: 1px solid #30363d; background: #000; min-height: 180px; object-fit: contain; }
    .toast { font-size: 0.85rem; color: #8b949e; text-align: center; }
  </style>
</head>
<body>
  <div class="container">
    <div class="header">
      <h1>⚡ OmniRoute Controller</h1>
      <span style="font-size: 0.75rem; color: #8b949e;">VPS 1GB Edition</span>
    </div>

    <!-- STATUS CARD -->
    <div class="card">
      <div class="card-title">Live Status</div>
      <div id="statusBadge" class="status-badge status-STOPPED">
        <span id="statusDot" class="dot dot-STOPPED"></span>
        <span id="statusText">CONNECTING...</span>
      </div>
      
      <div class="info-row">
        <span class="info-label">Uptime:</span>
        <span class="info-val" id="uptimeVal">--</span>
      </div>
      <div class="info-row">
        <span class="info-label">Watchdog Active:</span>
        <span class="info-val" id="watchdogVal">--</span>
      </div>
      <div class="info-row">
        <span class="info-label">Last Error:</span>
        <span class="info-val" id="errorVal" style="color: #f85149;">None</span>
      </div>
    </div>

    <!-- ACTION CONTROLS -->
    <div class="card">
      <div class="card-title">Actions</div>
      <div class="grid-buttons">
        <button class="btn-start" onclick="callApi('/start')">▶ Start Bot</button>
        <button class="btn-stop" onclick="callApi('/stop')">⏹ Stop Bot</button>
        <button onclick="callApi('/restart')">🔄 Restart</button>
        <button onclick="callApi('/watchdog/check')">⚡ Check Shell</button>
        <button class="btn-full" onclick="refreshScreenshot()">📸 Refresh Live Screen</button>
      </div>
      <div class="toast" id="toastMsg">Ready</div>
    </div>

    <!-- LIVE SCREEN CARD -->
    <div class="card">
      <div class="card-title">Live Browser Screen</div>
      <img id="screenImg" class="screenshot-img" src="/screenshot" alt="Browser Screen" />
      <span style="font-size: 0.75rem; color: #8b949e; text-align: center;">Tap 'Refresh Live Screen' to view terminal visually</span>
    </div>
  </div>

  <script>
    async function updateStatus() {
      try {
        const res = await fetch('/status');
        const d = await res.json();
        
        const badge = document.getElementById('statusBadge');
        const dot = document.getElementById('statusDot');
        const text = document.getElementById('statusText');
        
        const state = d.state || 'STOPPED';
        text.innerText = state;
        badge.className = 'status-badge status-' + state;
        dot.className = 'dot dot-' + state;

        document.getElementById('watchdogVal').innerText = d.watchdog && d.watchdog.enabled ? 'Yes (' + (d.watchdog.lastShellCheck ? new Date(d.watchdog.lastShellCheck).toLocaleTimeString() : 'Waiting') + ')' : 'Disabled';
        document.getElementById('errorVal').innerText = d.lastError || 'None';
      } catch (e) {
        document.getElementById('statusText').innerText = 'OFFLINE';
      }
    }

    async function callApi(endpoint) {
      document.getElementById('toastMsg').innerText = 'Sending request to ' + endpoint + '...';
      try {
        const res = await fetch(endpoint, { method: 'POST' });
        const d = await res.json();
        document.getElementById('toastMsg').innerText = d.message || 'Done: ' + (d.state || 'OK');
        setTimeout(updateStatus, 1500);
      } catch (e) {
        document.getElementById('toastMsg').innerText = 'Error: ' + e.message;
      }
    }

    function refreshScreenshot() {
      document.getElementById('toastMsg').innerText = 'Capturing live screenshot...';
      const img = document.getElementById('screenImg');
      img.src = '/screenshot?t=' + Date.now();
      img.onload = () => document.getElementById('toastMsg').innerText = 'Screen updated!';
      img.onerror = () => document.getElementById('toastMsg').innerText = 'Browser closed / No screen';
    }

    setInterval(updateStatus, 5000);
    updateStatus();
  </script>
</body>
</html>
""".trimIndent()

// ----------------- HTTP SERVER & REST CONTROLLER -----------------

fun sendJson(exchange: HttpExchange, statusCode: Int, data: Any) {
    val json = gson.toJson(data)
    val bytes = json.toByteArray(Charsets.UTF_8)
    exchange.responseHeaders.set("Content-Type", "application/json; charset=UTF-8")
    exchange.sendResponseHeaders(statusCode, bytes.size.toLong())
    exchange.responseBody.use { it.write(bytes) }
}

fun checkAuth(exchange: HttpExchange): Boolean {
    if (API_KEY.isBlank()) return true
    val headerKey = exchange.requestHeaders.getFirst("x-api-key")
    val queryKey = exchange.requestURI.query?.split("&")
        ?.firstOrNull { it.startsWith("api_key=") }
        ?.substringAfter("api_key=")
    return headerKey == API_KEY || queryKey == API_KEY
}

fun main() {
    println("=================================================")
    println("   Replit OmniRoute Web Dashboard & Bot (Kotlin) ")
    println("=================================================")

    val server = HttpServer.create(InetSocketAddress("0.0.0.0", PORT), 0)

    // Mobile Web Dashboard Homepage
    server.createContext("/", HttpHandler { exchange ->
        if (exchange.requestURI.path != "/") {
            exchange.sendResponseHeaders(404, -1)
            return@HttpHandler
        }
        val bytes = DASHBOARD_HTML.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "text/html; charset=UTF-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    })

    // Live Screenshot Endpoint (Phone pe screen dekhne ke liye)
    server.createContext("/screenshot", HttpHandler { exchange ->
        try {
            val p = getActivePage()
            val screenshotBytes = p.screenshot(Page.ScreenshotOptions().setType(com.microsoft.playwright.options.ScreenshotType.JPEG).setQuality(50))
            exchange.responseHeaders.set("Content-Type", "image/jpeg")
            exchange.sendResponseHeaders(200, screenshotBytes.size.toLong())
            exchange.responseBody.use { it.write(screenshotBytes) }
        } catch (_: Exception) {
            exchange.sendResponseHeaders(503, -1)
        }
    })

    server.createContext("/health", HttpHandler { exchange ->
        val uptime = if (startedAt != null) (System.currentTimeMillis() - startedAt!!) / 1000 else 0
        sendJson(exchange, 200, mapOf(
            "status" to "ok",
            "service" to "playwright-backend-kotlin",
            "state" to state,
            "running" to (state == "RUNNING"),
            "browser" to (context != null),
            "page" to (page != null && page?.isClosed == false),
            "uptime" to uptime,
            "lastActivity" to lastActivity,
            "lastError" to lastError
        ))
    })

    server.createContext("/status", HttpHandler { exchange ->
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, mapOf("ok" to false, "error" to "Unauthorized"))
            return@HttpHandler
        }
        val pageUrl = try { if (page?.isClosed == false) page?.url() else null } catch (_: Exception) { null }
        sendJson(exchange, 200, mapOf(
            "ok" to true,
            "state" to state,
            "running" to (state == "RUNNING"),
            "browser" to (context != null),
            "page" to (page != null && page?.isClosed == false),
            "pageUrl" to pageUrl,
            "lastActivity" to lastActivity,
            "lastError" to lastError,
            "watchdog" to watchdog
        ))
    })

    server.createContext("/start", HttpHandler { exchange ->
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, mapOf("ok" to false, "error" to "Unauthorized"))
            return@HttpHandler
        }
        scheduler.execute { startBrowser() }
        sendJson(exchange, 200, mapOf("ok" to true, "state" to "STARTING", "message" to "Playwright startup initiated"))
    })

    server.createContext("/stop", HttpHandler { exchange ->
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, mapOf("ok" to false, "error" to "Unauthorized"))
            return@HttpHandler
        }
        watchdog.enabled = false
        cleanupBrowser()
        state = "STOPPED"
        sendJson(exchange, 200, mapOf("ok" to true, "state" to state))
    })

    server.createContext("/restart", HttpHandler { exchange ->
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, mapOf("ok" to false, "error" to "Unauthorized"))
            return@HttpHandler
        }
        watchdog.enabled = false
        cleanupBrowser()
        state = "STOPPED"
        watchdog.enabled = true
        scheduler.execute { startBrowser() }
        sendJson(exchange, 200, mapOf("ok" to true, "state" to "STARTING"))
    })

    server.createContext("/watchdog/check", HttpHandler { exchange ->
        if (!checkAuth(exchange)) {
            sendJson(exchange, 401, mapOf("ok" to false, "error" to "Unauthorized"))
            return@HttpHandler
        }
        scheduler.execute { watchdogCheck() }
        sendJson(exchange, 200, mapOf("ok" to true, "message" to "Watchdog check started"))
    })

    // Periodic Watchdog (Default: every 40 minutes)
    scheduler.scheduleWithFixedDelay(
        { watchdogCheck() },
        watchdog.intervalMinutes,
        watchdog.intervalMinutes,
        TimeUnit.MINUTES
    )

    // Graceful Shutdown
    Runtime.getRuntime().addShutdownHook(Thread {
        println(">>> Shutting down...")
        watchdog.enabled = false
        cleanupBrowser()
    })

    server.executor = Executors.newFixedThreadPool(4)
    server.start()
    println(">>> Server listening on http://0.0.0.0:$PORT")

    // Auto-start browser on boot
    scheduler.execute { startBrowser() }
}
