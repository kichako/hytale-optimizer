package hytaleopttest;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.joml.Vector3d;

/**
 * Test-only mod: reproduces "temple cannot be re-entered" by leaving a stale instance folder behind, then spawns,
 * removes (from the common pool, like RemovalSystem) and re-spawns the Forgotten Temple instance.
 */
public class InstanceRespawnTest extends JavaPlugin {
   private static final String INSTANCE = "Forgotten_Temple_Goblins";
   private static final String KEY = "instance-forgotten-temple-goblins";

   public InstanceRespawnTest(@Nonnull JavaPluginInit init) {
      super(init);
   }

   @Override
   protected void start() {
      Universe.get().getUniverseReady().thenRun(() -> HytaleServer.SCHEDULED_EXECUTOR.schedule(this::run, 3L, TimeUnit.SECONDS));
   }

   private void run() {
      CompletableFuture.runAsync(() -> {
         try {
            Path stale = Universe.get().validateWorldPath(KEY).resolve("resources");
            Files.createDirectories(stale);
            Files.writeString(stale.resolve("WorldEventStorage.json"), "{}");
            log("created stale folder " + stale);
            this.spawn("spawn #1 (stale folder present)");
            long start = System.nanoTime();
            CompletableFuture.runAsync(() -> Universe.get().removeWorld(KEY)).join();
            log("removeWorld took " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
            this.spawn("spawn #2 (after removal)");
            log("DONE");
         } catch (Throwable t) {
            log("FAILED: " + t);
         }
      });
   }

   private void spawn(String label) {
      World defaultWorld = Universe.get().getDefaultWorld();
      long start = System.nanoTime();
      try {
         World world = InstancesPlugin.get().spawnInstance(INSTANCE, defaultWorld, new Transform(new Vector3d(0, 120, 0))).get(60L, TimeUnit.SECONDS);
         log(label + ": OK world=" + world.getName() + " in " + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) + " ms");
      } catch (Throwable t) {
         log(label + ": FAILED " + t);
      }
   }

   private void log(String message) {
      System.out.println("[InstanceRespawnTest] " + message);
   }
}
