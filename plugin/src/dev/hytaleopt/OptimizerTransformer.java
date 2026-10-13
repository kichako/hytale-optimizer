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
import java.util.concurrent.ForkJoinPool;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Replaces a handful of server classes with fixed builds of the same classes.
 *
 * <p>Every replacement is pinned to the SHA-256 of the vanilla class it was built from. A patch group (a class plus
 * its nested classes) is only enabled if every member still matches the HytaleServer.jar on the classpath, so a game
 * update silently turns the affected patches off instead of mixing old and new code.
 */
public final class OptimizerTransformer implements ClassTransformer {
   private static final String PREFIX = "[HytaleOptimizer] ";
   private static final String INDEX = "hytaleopt/patches.properties";
   private static final String CLASSES = "hytaleopt/classes/";
   private final Map<String, byte[]> replacements = new HashMap<>();

   public OptimizerTransformer() {
      ensureCommonPoolParallelism();
      Properties index = new Properties();
      try (InputStream in = OptimizerTransformer.class.getClassLoader().getResourceAsStream(INDEX)) {
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
      Map<String, Map<String, String>> groups = new HashMap<>();

      for (String internalName : index.stringPropertyNames()) {
         String[] value = index.getProperty(internalName).split(";", 2);
         groups.computeIfAbsent(value[1], g -> new HashMap<>()).put(internalName, value[0]);
      }

      ClassLoader serverLoader = ClassLoader.getSystemClassLoader();
      groups.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(group -> {
         String groupName = group.getKey();
         if (disabled.contains(groupName)) {
            System.out.println(PREFIX + groupName + ": disabled by -Dhytaleopt.disable");
            return;
         }

         Map<String, byte[]> groupClasses = new HashMap<>();

         for (Map.Entry<String, String> member : group.getValue().entrySet()) {
            String internalName = member.getKey();
            byte[] original = readResource(serverLoader, internalName + ".class");
            if (original == null || !sha256(original).equals(member.getValue())) {
               System.out.println(PREFIX + groupName + ": skipped, " + internalName + " differs from the version this patch was built for");
               return;
            }

            byte[] replacement = readResource(OptimizerTransformer.class.getClassLoader(), CLASSES + internalName + ".class");
            if (replacement == null) {
               System.err.println(PREFIX + groupName + ": skipped, replacement for " + internalName + " is missing");
               return;
            }

            groupClasses.put(internalName, replacement);
         }

         this.replacements.putAll(groupClasses);
         System.out.println(PREFIX + groupName + ": enabled");
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

   @Nullable
   @Override
   public byte[] transform(@Nonnull String name, @Nonnull String internalName, @Nonnull byte[] classBytes) {
      byte[] replacement = this.replacements.get(internalName);
      return replacement == null ? null : replacement.clone();
   }

   @Nullable
   private static byte[] readResource(@Nonnull ClassLoader loader, @Nonnull String path) {
      try (InputStream in = loader.getResourceAsStream(path)) {
         return in == null ? null : in.readAllBytes();
      } catch (IOException e) {
         return null;
      }
   }

   @Nonnull
   private static String sha256(@Nonnull byte[] bytes) {
      try {
         return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
      } catch (NoSuchAlgorithmException e) {
         throw new IllegalStateException(e);
      }
   }
}
