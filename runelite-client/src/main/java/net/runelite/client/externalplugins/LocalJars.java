package net.runelite.client.externalplugins;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Opens a local jar of ordinary JVM bytecode (your own plugins, or script jars a plugin
 * loads at runtime) on both desktop and Android.
 *
 * <p>Desktop: a plain {@link URLClassLoader}. Android: the jar is copied into private
 * storage, converted to dex by the same {@link PluginHubHooks#dexer} the Plugin Hub uses
 * (cached per content hash, so an unchanged jar is only converted once), and opened with a
 * {@code DexClassLoader}.
 *
 * <p>Public and in {@code net.runelite.client} so plugins can call it by reflection
 * ({@code Class.forName("net.runelite.client.externalplugins.LocalJars")}) and R8 keeps it.
 */
public final class LocalJars
{
	public static final boolean IS_ANDROID = "Dalvik".equals(System.getProperty("java.vm.name"));

	private LocalJars()
	{
	}

	private static File storeDir()
	{
		File dir = new File(new File(System.getProperty("user.home"), ".runelite"), "local-jars");
		dir.mkdirs();
		return dir;
	}

	/**
	 * @param jar    a jar of JVM bytecode (or an already-dexed jar)
	 * @param parent parent class loader; null means the client's own loader
	 */
	public static ClassLoader open(File jar, ClassLoader parent) throws IOException
	{
		if (parent == null)
		{
			parent = LocalJars.class.getClassLoader();
		}
		if (!IS_ANDROID)
		{
			return new URLClassLoader(new URL[]{jar.toURI().toURL()}, parent);
		}

		String hash = sha256(jar);
		String name = "local-" + jar.getName().replaceAll("(?i)\\.jar$", "").replaceAll("[^A-Za-z0-9._-]", "_");

		// Private copy: external storage can be rewritten under us, and ART refuses writable dex.
		File dir = storeDir();
		File copy = new File(dir, name + "_" + hash + ".jar");
		if (!copy.isFile())
		{
			File tmp = new File(dir, copy.getName() + ".part");
			Files.copy(jar.toPath(), tmp.toPath(), StandardCopyOption.REPLACE_EXISTING);
			if (!tmp.renameTo(copy))
			{
				tmp.delete();
				throw new IOException("Could not store " + jar.getName());
			}
			File[] old = dir.listFiles((d, n) -> n.startsWith(name + "_") && !n.equals(copy.getName()));
			if (old != null)
			{
				for (File f : old)
				{
					f.delete();
				}
			}
		}

		PluginHubHooks.Dexer dexer = PluginHubHooks.dexer;
		File dexed = dexer == null ? copy : dexer.dex(name, jar.getName(), hash, copy);
		if (dexed.canWrite() && !dexed.setReadOnly())
		{
			throw new IOException("Could not make " + dexed + " read-only");
		}
		try
		{
			Class<?> dcl = Class.forName("dalvik.system.DexClassLoader");
			return (ClassLoader) dcl
				.getConstructor(String.class, String.class, String.class, ClassLoader.class)
				.newInstance(dexed.getAbsolutePath(), dexed.getParentFile().getAbsolutePath(), null, parent);
		}
		catch (ReflectiveOperationException e)
		{
			throw new IOException("Failed to create DexClassLoader for " + jar.getName(), e);
		}
	}

	/** Top-level and nested class names in the jar, e.g. {@code com.example.MyPlugin}. */
	public static List<String> classNames(File jar) throws IOException
	{
		List<String> out = new ArrayList<>();
		try (JarFile jf = new JarFile(jar))
		{
			Enumeration<JarEntry> en = jf.entries();
			while (en.hasMoreElements())
			{
				String n = en.nextElement().getName();
				if (n.endsWith(".class") && !n.startsWith("META-INF") && !n.endsWith("module-info.class"))
				{
					out.add(n.substring(0, n.length() - 6).replace('/', '.'));
				}
			}
		}
		return out;
	}

	private static String sha256(File f) throws IOException
	{
		try (InputStream in = Files.newInputStream(f.toPath()))
		{
			MessageDigest md = MessageDigest.getInstance("SHA-256");
			byte[] buf = new byte[65536];
			int r;
			while ((r = in.read(buf)) > 0)
			{
				md.update(buf, 0, r);
			}
			StringBuilder sb = new StringBuilder();
			for (byte b : md.digest())
			{
				sb.append(String.format("%02x", b));
			}
			return sb.substring(0, 16);
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IOException(e);
		}
	}
}
