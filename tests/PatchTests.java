import com.hypixel.hytale.component.task.ParallelRangeTask;
import com.hypixel.hytale.component.task.ParallelTask;
import com.hypixel.hytale.server.core.util.BsonUtil;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import org.bson.BsonDocument;
import org.bson.BsonInt32;
import org.bson.BsonString;

/** Run against vanilla (HytaleServer.jar only) and patched (build/patches first) to compare behaviour. */
public class PatchTests {
   public static void main(String[] args) throws Exception {
      parallelTaskRecoversAfterException();
      parallelRangeTaskGrain();
      bsonConcurrentWritesSameFile();
   }

   static void parallelTaskRecoversAfterException() {
      ParallelTask<IntConsumer> task = new ParallelTask<>(() -> i -> {
         throw new IllegalStateException("system failure");
      });
      task.init();
      task.appendTask().init(0, 4);

      try {
         task.doInvoke();
      } catch (RuntimeException expected) {
      }

      try {
         task.init();
         task.appendTask().init(0, 4);
         System.out.println("ParallelTask after a failing tick: usable again");
      } catch (IllegalStateException e) {
         System.out.println("ParallelTask after a failing tick: BROKEN -> " + e.getMessage());
      }
   }

   static void parallelRangeTaskGrain() {
      ParallelRangeTask<IntConsumer> task = new ParallelRangeTask<>(() -> i -> {});
      int[] sizes = {5, 20, 100, 1000};
      StringBuilder sb = new StringBuilder("ParallelRangeTask sub-tasks (TASK_COUNT=" + ParallelRangeTask.TASK_COUNT + "):");

      for (int size : sizes) {
         sb.append(' ').append(size).append(" entities -> ").append(task.init(0, size).size()).append(';');
      }

      System.out.println(sb);
   }

   static void bsonConcurrentWritesSameFile() throws Exception {
      Path dir = Files.createTempDirectory("hytaleopt-bson");
      Path file = dir.resolve("player.json");
      String filler = "x".repeat(20000);
      int writes = 300;
      List<CompletableFuture<Void>> futures = new ArrayList<>();

      for (int i = 0; i < writes; i++) {
         BsonDocument doc = new BsonDocument("n", new BsonInt32(i)).append("filler", new BsonString(filler));
         futures.add(BsonUtil.writeDocument(file, doc, false));
      }

      AtomicInteger failed = new AtomicInteger();

      for (CompletableFuture<Void> f : futures) {
         try {
            f.join();
         } catch (Exception e) {
            failed.incrementAndGet();
         }
      }

      String result;
      try {
         int n = BsonDocument.parse(Files.readString(file)).getInt32("n").getValue();
         result = n == writes - 1 ? "last write wins (n=" + n + ")" : "STALE DATA: file has n=" + n + ", expected " + (writes - 1);
      } catch (Exception e) {
         result = "CORRUPT FILE: " + e;
      }

      System.out.println("BsonUtil " + writes + " concurrent saves of one file: failed writes=" + failed.get() + ", " + result);
   }
}
