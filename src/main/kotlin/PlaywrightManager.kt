import com.microsoft.playwright.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 1GB VPS RAM + 2GB SWAP ke liye optimized Playwright Manager
 */
class PlaywrightManager(
    private val sessionPath: Path = Paths.get("session.json")
) : AutoCloseable {

    private val playwright: Playwright = Playwright.create()
    private val browser: Browser

    init {
        // 1GB RAM ke liye memory-saving Chromium arguments
        val lowMemoryArgs = listOf(
            "--no-sandbox",
            "--disable-dev-shm-usage", // Crash prevent karta hai (/dev/shm shared memory issue)
            "--disable-gpu",
            "--disable-extensions",
            "--disable-background-networking",
            "--disable-default-apps",
            "--disable-sync",
            "--disable-translate",
            "--no-zygote",
            "--mute-audio",
            "--js-flags=--max-old-space-size=256" // V8 heap memory capped at 256MB
        )

        println(">>> Launching Chromium with low-memory profile...")
        browser = playwright.chromium().launch(
            BrowserType.LaunchOptions()
                .setHeadless(true) // VPS ke liye zaroori hai
                .setArgs(lowMemoryArgs)
        )
    }

    /**
     * Browser context create karta hai.
     * Agar session.json already maujood hai to purana session load karega.
     */
    fun createSessionContext(): BrowserContext {
        val options = Browser.NewContextOptions()

        if (Files.exists(sessionPath)) {
            println(">>> [Session] Existing session.json found. Restoring cookies/storage...")
            options.setStorageStatePath(sessionPath)
        } else {
            println(">>> [Session] No previous session found. Starting fresh session...")
        }

        val context = browser.newContext(options)
        return context
    }

    /**
     * Current cookies aur localStorage ko session.json me save karta hai.
     */
    fun saveSession(context: BrowserContext) {
        println(">>> [Session] Saving current state to ${sessionPath.toAbsolutePath()}...")
        context.storageState(
            BrowserContext.StorageStateOptions().setPath(sessionPath)
        )
        println(">>> [Session] Successfully saved!")
    }

    /**
     * Images, media aur fonts block karne ka helper (50% RAM bachata hai)
     */
    fun optimizePageForLowMemory(page: Page) {
        page.route("**/*.{png,jpg,jpeg,webp,svg,gif,woff,woff2,ttf,ico}") { route ->
            route.abort()
        }
    }

    override fun close() {
        println(">>> Closing Browser & Playwright...")
        browser.close()
        playwright.close()
        println(">>> Memory released.")
    }
}
