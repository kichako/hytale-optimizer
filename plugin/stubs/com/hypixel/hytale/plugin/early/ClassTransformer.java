package com.hypixel.hytale.plugin.early;

/**
 * Compile-only stand-in for the server's early plugin interface, so the release jar can be built without
 * HytaleServer.jar. It is not packaged: at runtime the real interface from the server is used.
 */
public interface ClassTransformer {
   default int priority() {
      return 0;
   }

   byte[] transform(String name, String internalName, byte[] classBytes);
}
