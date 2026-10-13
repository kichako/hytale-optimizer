package dev.hytaleopt;

import com.hypixel.hytale.plugin.early.ClassTransformer;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ForkJoinPool;

/**
 * Replaces a handful of server classes with fixed builds of the same classes.
 *
 * <p>Every replacement is pinned to the SHA-256 of the vanilla class it was built from. A patch group (a class plus
 * its nested classes) is only enabled if every member still matches the HytaleServer.jar on the classpath, so a game
 * update silently turns the affected patches off instead of mixing old and new code.
 *
 * <p>The replacement classes either ship inside the jar (local build with build.ps1 / build.sh) or are built on
 * first start from the bundled patches (release jar, which contains no Hypixel code), see {@link SelfPatcher}.
 */
public final class OptimizerTransformer implements ClassTransformer {
   static final String PREFIX = "[HytaleOptimizer] ";
   static final String VERSION = "1.0.0";
   private static final String INDEX = "hytaleopt/patches.properties";
   private static final String CLASSES = "hytaleopt/classes/";
   private final Map<String, byte[]> replacements = new HashMap<>();

   public OptimizerTransformer() {
      ensureCommonPoolParallelism();
      ClassLoader own = OptimizerTransformer.class.getClassLoader();
      Properties index = new Properties();
      try (InputStream in = own.getResourceAsStream(INDEX)) {
         if (in == null) {
            System.err.println(PREFIX + "Missing " + INDEX + ", no patches applied.");
            return;
         }

         index.load(new InputStreamReader(in, StandardCharsets.UTF_8));
      } catch (IOException e) {
         System.err.println(PREFIX + "Failed to read " + INDEX + ": " + e);
         return;
      }

      Set<String> disabled = new HashSet<>(Arrays.asList(System.getProperty("hytaleopt.disable", "").split(",")));
      Map<String, Map<String, String>> groups = new TreeMap<>();

      for (String internalName : index.stringPropertyNames()) {
         String[] value = index.getProperty(internalName).split(";", 2);
         groups.computeIfAbsent(value[1].trim(), g -> new HashMap<>()).put(internalName, value[0].trim());
      }

      // Collect the vanilla bytes of every group that matches this server version.
      ClassLoader serverLoader = ClassLoader.getSystemClassLoader();
      Map<String, Map<String, byte[]>> matching = new TreeMap<>();
      groups.forEach((groupName, members) -> {
         if (disabled.contains(groupName)) {
            System.out.println(PREFIX + groupName + ": disabled by -Dhytaleopt.disable");
            return;
         }

         Map<String, byte[]> originals = new HashMap<>();
         for (Map.Entry<String, String> member : members.entrySet()) {
            byte[] original = readResource(serverLoader, member.getKey() + ".class");
            if (original == null || !sha256(original).equals(member.getValue())) {
               System.out.println(PREFIX + groupName + ": skipped, " + member.getKey() + " differs from the version this patch was made for");
               return;
            }

            originals.put(member.getKey(), original);
         }

         matching.put(groupName, originals);
      });

      if (matching.isEmpty()) {
         return;
      }

      Map<String, byte[]> built;
      String anyMember = matching.values().iterator().next().keySet().iterator().next();
      if (own.getResource(CLASSES + anyMember + ".class") != null) {
         built = new HashMap<>();
         for (Map<String, byte[]> originals : matching.values()) {
            for (String internalName : originals.keySet()) {
               byte[] replacement = readResource(own, CLASSES + internalName + ".class");
               if (replacement != null) {
                  built.put(internalName, replacement);
               }
            }
         }
      } else {
         try {
            built = SelfPatcher.patch(matching, VERSION);
         } catch (Throwable t) {
            System.err.println(PREFIX + "Could not build the patched classes, starting without patches: " + t);
            return;
         }
      }

      // Enable a group only if every member has a replacement.
      matching.forEach((groupName, originals) -> {
         if (built.keySet().containsAll(originals.keySet())) {
            originals.keySet().forEach(name -> this.replacements.put(name, built.get(name)));
            System.out.println(PREFIX + groupName + ": enabled");
         } else {
            System.err.println(PREFIX + groupName + ": skipped, replacement classes are missing");
         }
      });
   }

   /**
    * The server runs blocking work on ForkJoinPool.commonPool(): world removal joins the world thread for up to 30s,
    * chunk I/O, and completing the ClientReady future. On 1-2 vCPU machines the pool has a single thread, so removing
    * an instance world starves it: the world thread waits on chunk saves queued behind the blocked removal (abandoned
    * after 30s) and players changing worlds time out waiting for ClientReady. This runs from Main before anything
    * touches the pool, so the property still takes effect. An explicit -D...common.parallelism always wins.
    */
   private static void ensureCommonPoolParallelism() {
      String key = "java.util.concurrent.ForkJoinPool.common.parallelism";
      int minimum = Integer.getInteger("hytaleopt.minCommonParallelism", 8);
      int vanilla = Math.max(Runtime.getRuntime().availableProcessors() - 1, 1);
      if (System.getProperty(key) == null && vanilla < minimum) {
         System.setProperty(key, Integer.toString(minimum));
      }

      System.out.println(
         PREFIX + "ForkJoinPool.commonPool parallelism: " + ForkJoinPool.getCommonPoolParallelism() + " (CPU cores: " + Runtime.getRuntime().availableProcessors() + ")"
      );
   }

   @Override
   public int priority() {
      // Run before other transformers so the bytes we receive are still vanilla.
      return 1000;
   }

   @Override
   public byte[] transform(String name, String internalName, byte[] classBytes) {
      byte[] replacement = this.replacements.get(internalName);
      return replacement == null ? null : replacement.clone();
   }

   static byte[] readResource(ClassLoader loader, String path) {
      try (InputStream in = loader.getResourceAsStream(path)) {
         return in == null ? null : in.readAllBytes();
      } catch (IOException e) {
         return null;
      }
   }

   static String sha256(byte[] bytes) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }
}
