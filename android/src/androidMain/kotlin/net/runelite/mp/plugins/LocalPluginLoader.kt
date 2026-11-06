package net.runelite.mp.plugins

import android.content.Context
import android.util.Log
import net.runelite.client.externalplugins.LocalJars
import net.runelite.client.plugins.Plugin
import net.runelite.client.plugins.PluginManager
import net.runelite.mp.ui.bridge.RuneLiteAccess
import java.io.File
import java.lang.reflect.Modifier
import javax.swing.SwingUtilities

/**
 * Loads your own plugin jars (built like any desktop RuneLite plugin) from a folder
 * you can reach with adb or a file manager:
 *
 *     /sdcard/Android/data/net.runelite.mp/files/plugins/  (any *.jar)
 *
 * Each jar is converted to dex on the device with the Plugin Hub's own converter (once per
 * jar version), then every @PluginDescriptor class in it is loaded, configured and, if
 * enabled, started — the same steps the hub uses for an install. Restart the app after
 * adding or replacing a jar.
 */
internal object LocalPluginLoader
{
    private const val TAG = "LocalPlugins"

    /** System property other plugins can read to find user-reachable storage. */
    const val EXTERNAL_DIR_PROPERTY = "runelite.mp.externalFilesDir"

    fun externalDir(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun pluginsDir(context: Context): File =
        File(externalDir(context), "plugins").apply { mkdirs() }

    /** Call before RuneLite.main so plugins see the property from their first line. */
    fun prepare(context: Context)
    {
        val ext = externalDir(context)
        System.setProperty(EXTERNAL_DIR_PROPERTY, ext.absolutePath)
        pluginsDir(context)
        Log.i(TAG, "local plugins folder: ${pluginsDir(context)}")
    }

    /** Call after PluginBootstrap has loaded and started the built-in plugins. */
    fun load(context: Context, log: (String) -> Unit)
    {
        val jars = pluginsDir(context).listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
            ?.sortedBy { it.name }.orEmpty()
        if (jars.isEmpty())
        {
            log("local plugins: none in ${pluginsDir(context)}")
            return
        }
        val pluginManager = RuneLiteAccess.instance(PluginManager::class.java) ?: run {
            log("local plugins: PluginManager not available, skipping")
            return
        }
        for (jar in jars)
        {
            try
            {
                val started = System.currentTimeMillis()
                val loader = LocalJars.open(jar, LocalPluginLoader::class.java.classLoader)
                val classes = LocalJars.classNames(jar).mapNotNull { name ->
                    try
                    {
                        Class.forName(name, false, loader)
                    }
                    catch (t: Throwable)
                    {
                        Log.d(TAG, "${jar.name}: skipping $name ($t)")
                        null
                    }
                }.filter { !Modifier.isAbstract(it.modifiers) && !it.isInterface }

                val plugins: List<Plugin> = pluginManager.loadPlugins(classes, null)
                if (plugins.isEmpty())
                {
                    log("local plugins: ${jar.name} has no @PluginDescriptor plugin")
                    continue
                }
                pluginManager.loadDefaultPluginConfiguration(plugins)
                SwingUtilities.invokeAndWait {
                    for (p in plugins)
                    {
                        try
                        {
                            if (pluginManager.isPluginEnabled(p)) pluginManager.startPlugin(p)
                        }
                        catch (t: Throwable)
                        {
                            Log.e(TAG, "${jar.name}: could not start ${p.name}", t)
                        }
                    }
                }
                log("local plugins: ${jar.name} → ${plugins.joinToString { it.name }} (${System.currentTimeMillis() - started} ms)")
            }
            catch (t: Throwable)
            {
                Log.e(TAG, "could not load ${jar.name}", t)
                log("local plugins: FAILED ${jar.name}: ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }
}
