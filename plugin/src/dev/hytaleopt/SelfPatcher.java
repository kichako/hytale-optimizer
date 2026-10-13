package dev.hytaleopt;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Builds the patched classes on the user's machine, from the user's own HytaleServer.jar.
 *
 * <p>Same steps as build.ps1 / build.sh: decompile the target classes with the bundled Vineflower, apply
 * hytaleopt/patches/*.patch, compile with the bundled ECJ (the Java runtime shipped with Hytale has no javac).
 * The result is cached next to the plugin jar, so this only runs again when the server or the patches change.
 */
final class SelfPatcher {
   private static final String TOOLS = "hytaleopt/tools/";
   private static final String VINEFLOWER = "vineflower-1.11.1.jar";
   private static final String VINEFLOWER_SHA256 = "a615d07ddbbcd489369674f40e42df639c32be95410890b38f173d5c1e2ea39c";
   private static final String ECJ = "ecj-3.46.100.jar";
   private static final String ECJ_SHA256 = "74c93172475fee0cd4e6eefb44233516dd40525642da35d009146d97ea598229";
   private static final String COMPLETE = "complete";

   private SelfPatcher() {
   }

   /**
    * @param originals vanilla class bytes per patch group (group = top-level class simple name), already hash-checked
    * @return replacement class bytes by internal name
    */
   static Map<String, byte[]> patch(Map<String, Map<String, byte[]>> originals, String version) throws Exception {
      ClassLoader own = SelfPatcher.class.getClassLoader();
      Map<String, String> patches = new TreeMap<>();

      for (String group : originals.keySet()) {
         patches.put(group, new String(OptimizerTransformer.readResource(own, "hytaleopt/patches/" + group + ".patch"), StandardCharsets.UTF_8));
      }

      Path home = pluginDirectory().resolve(".hytaleopt-cache");
      Path cache = home.resolve(cacheKey(originals, patches, version));
      if (Files.exists(cache.resolve(COMPLETE))) {
         Map<String, byte[]> cached = readClasses(cache, originals);
         if (cached != null) {
            System.out.println(OptimizerTransformer.PREFIX + "Using cached patched classes");
            return cached;
         }
      }

      long start = System.nanoTime();
      System.out.println(OptimizerTransformer.PREFIX + "Building patched classes for this server (only on first start or after updates)...");
      Path serverJar = serverJar();
      Path vineflower = extractTool(home, VINEFLOWER, VINEFLOWER_SHA256);
      Path ecj = extractTool(home, ECJ, ECJ_SHA256);
      Path work = Files.createTempDirectory("hytaleopt");

      try {
         Path classes = work.resolve("classes");
         Path src = work.resolve("src");
         Path out = work.resolve("out");

         for (Map<String, byte[]> group : originals.values()) {
            for (Map.Entry<String, byte[]> entry : group.entrySet()) {
               Path file = classes.resolve(entry.getKey() + ".class");
               Files.createDirectories(file.getParent());
               Files.write(file, entry.getValue());
            }
         }

         decompile(vineflower, serverJar, classes, src);

         for (Map.Entry<String, String> entry : patches.entrySet()) {
            UnifiedDiff.apply(src, entry.getValue(), entry.getKey());
         }

         Files.createDirectories(out);
         compile(ecj, serverJar, src, out);
         Map<String, byte[]> built = readClasses(out, originals);
         if (built == null) {
            throw new IllegalStateException("compiled classes do not match the expected set");
         }

         storeInCache(out, cache);
         System.out.printf("%sPatched classes built in %d ms%n", OptimizerTransformer.PREFIX, (System.nanoTime() - start) / 1000000L);
         return built;
      } finally {
         deleteRecursively(work);
      }
   }

   private static void decompile(Path vineflower, Path serverJar, Path classes, Path src) throws Exception {
      try (URLClassLoader loader = new URLClassLoader(new URL[]{vineflower.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
         Method main = loader.loadClass("org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler").getMethod("main", String[].class);
         main.invoke(null, (Object)new String[]{"-dgs=1", "-log=WARN", "-e=" + serverJar, classes.toString(), src.toString()});
      }
   }

   private static void compile(Path ecj, Path serverJar, Path src, Path out) throws Exception {
      List<String> args = new ArrayList<>(List.of("-25", "-proc:none", "-nowarn", "-encoding", "UTF-8", "-cp", serverJar.toString(), "-d", out.toString()));
      try (Stream<Path> files = Files.walk(src)) {
         files.filter(p -> p.toString().endsWith(".java")).forEach(p -> args.add(p.toString()));
      }

      StringWriter log = new StringWriter();
      boolean ok;
      try (URLClassLoader loader = new URLClassLoader(new URL[]{ecj.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
         Class<?> batch = loader.loadClass("org.eclipse.jdt.core.compiler.batch.BatchCompiler");
         Class<?> progress = loader.loadClass("org.eclipse.jdt.core.compiler.CompilationProgress");
         Method compile = batch.getMethod("compile", String[].class, PrintWriter.class, PrintWriter.class, progress);
         PrintWriter writer = new PrintWriter(log);
         ok = (Boolean)compile.invoke(null, args.toArray(String[]::new), writer, writer, null);
         writer.flush();
      }

      if (!ok) {
         throw new IllegalStateException("compiling the patched classes failed:\n" + log);
      }
   }

   private static Map<String, byte[]> readClasses(Path dir, Map<String, Map<String, byte[]>> originals) throws IOException {
      Map<String, byte[]> result = new HashMap<>();

      for (Map<String, byte[]> group : originals.values()) {
         for (String internalName : group.keySet()) {
            Path file = dir.resolve(internalName + ".class");
            if (!Files.isRegularFile(file)) {
               return null;
            }

            result.put(internalName, Files.readAllBytes(file));
         }
      }

      return result;
   }

   private static void storeInCache(Path out, Path cache) throws IOException {
      Path staging = Files.createDirectories(cache.getParent()).resolve(cache.getFileName() + ".tmp-" + ProcessHandle.current().pid());
      deleteRecursively(staging);
      try (Stream<Path> files = Files.walk(out)) {
         for (Path file : (Iterable<Path>)files::iterator) {
            Path target = staging.resolve(out.relativize(file).toString());
            if (Files.isDirectory(file)) {
               Files.createDirectories(target);
            } else {
               Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
            }
         }
      }

      Files.writeString(staging.resolve(COMPLETE), "");
      try {
         Files.move(staging, cache, StandardCopyOption.ATOMIC_MOVE);
      } catch (IOException e) {
         // Another server process filled the cache first, or the filesystem cannot rename atomically. The freshly
         // built classes are used either way; the cache only saves work on the next start.
         deleteRecursively(staging);
      }
   }

   private static Path extractTool(Path home, String name, String sha256) throws IOException {
      Path tool = home.resolve("tools").resolve(name);
      if (Files.isRegularFile(tool) && OptimizerTransformer.sha256(Files.readAllBytes(tool)).equals(sha256)) {
         return tool;
      }

      byte[] bytes = OptimizerTransformer.readResource(SelfPatcher.class.getClassLoader(), TOOLS + name);
      if (bytes == null || !OptimizerTransformer.sha256(bytes).equals(sha256)) {
         throw new IllegalStateException("bundled " + name + " is missing or corrupted");
      }

      Files.createDirectories(tool.getParent());
      Path tmp = tool.resolveSibling(name + ".tmp-" + ProcessHandle.current().pid());
      Files.write(tmp, bytes);
      Files.move(tmp, tool, StandardCopyOption.REPLACE_EXISTING);
      return tool;
   }

   private static String cacheKey(Map<String, Map<String, byte[]>> originals, Map<String, String> patches, String version) {
      StringBuilder key = new StringBuilder(version).append('\n').append(Runtime.version().feature()).append('\n');
      new TreeMap<>(originals).forEach((group, members) -> {
         key.append(group).append('\n').append(patches.get(group)).append('\n');
         new TreeMap<>(members).forEach((name, bytes) -> key.append(name).append('=').append(OptimizerTransformer.sha256(bytes)).append('\n'));
      });
      return OptimizerTransformer.sha256(key.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 16);
   }

   private static Path pluginDirectory() throws Exception {
      return Path.of(SelfPatcher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
   }

   private static Path serverJar() throws Exception {
      URL main = ClassLoader.getSystemClassLoader().getResource("com/hypixel/hytale/Main.class");
      if (main == null || !"jar".equals(main.getProtocol())) {
         throw new IllegalStateException("could not locate HytaleServer.jar on the classpath");
      }

      return Path.of(((JarURLConnection)main.openConnection()).getJarFileURL().toURI());
   }

   private static void deleteRecursively(Path dir) {
      if (!Files.exists(dir)) {
         return;
      }

      try (Stream<Path> files = Files.walk(dir)) {
         files.sorted(Comparator.reverseOrder()).forEach(p -> {
            try {
               Files.deleteIfExists(p);
            } catch (IOException ignored) {
            }
         });
      } catch (IOException ignored) {
      }
   }
}
