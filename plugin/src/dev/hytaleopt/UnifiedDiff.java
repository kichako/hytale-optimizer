package dev.hytaleopt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Applies a single-file unified diff (as produced by {@code diff -u}) to a source tree. Context has to match exactly:
 * the decompiler output is deterministic, so any mismatch means the input is not what the patch was made for.
 */
final class UnifiedDiff {
   private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@");

   private UnifiedDiff() {
   }

   static void apply(Path root, String patch, String name) throws IOException {
      String[] lines = patch.split("\n", -1);
      String target = null;
      int i = 0;

      for (; i < lines.length; i++) {
         if (lines[i].startsWith("+++ ")) {
            target = lines[i].substring(4).trim();
            if (target.startsWith("b/")) {
               target = target.substring(2);
            }

            i++;
            break;
         }
      }

      if (target == null) {
         throw new IllegalStateException(name + ".patch has no target file");
      }

      Path file = root.resolve(target);
      List<String> source = new ArrayList<>(List.of(Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n", -1)));
      List<String> result = new ArrayList<>();
      int cursor = 0;

      while (i < lines.length) {
         Matcher hunk = HUNK.matcher(lines[i]);
         if (!hunk.find()) {
            i++;
            continue;
         }

         int oldStart = Integer.parseInt(hunk.group(1));
         int oldCount = hunk.group(2) == null ? 1 : Integer.parseInt(hunk.group(2));
         int at = oldCount == 0 ? oldStart : oldStart - 1;
         while (cursor < at) {
            result.add(source.get(cursor++));
         }

         i++;
         for (; i < lines.length && !lines[i].startsWith("@@"); i++) {
            String line = lines[i];
            if (line.startsWith("\\")) {
               continue;
            }

            if (line.isEmpty() && i == lines.length - 1) {
               break;
            }

            char kind = line.isEmpty() ? ' ' : line.charAt(0);
            String text = line.isEmpty() ? "" : line.substring(1);
            switch (kind) {
               case ' ', '-' -> {
                  if (cursor >= source.size() || !source.get(cursor).equals(text)) {
                     throw new IllegalStateException(name + ".patch does not match the decompiled source near line " + (cursor + 1));
                  }

                  if (kind == ' ') {
                     result.add(text);
                  }

                  cursor++;
               }
               case '+' -> result.add(text);
               default -> throw new IllegalStateException(name + ".patch has an unexpected line: " + line);
            }
         }
      }

      while (cursor < source.size()) {
         result.add(source.get(cursor++));
      }

      Files.writeString(file, String.join("\n", result), StandardCharsets.UTF_8);
   }
}
