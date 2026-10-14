# hytale-optimizer

An early plugin for the Hytale server that fixes a handful of bugs and bottlenecks in the official server. It doesn't modify `HytaleServer.jar`. At startup it swaps a few classes for fixed versions, using the early plugin system the server already ships with.

The network protocol is untouched, so the official client connects as usual. It works on dedicated servers and in singleplayer.

**Supported versions:** HytaleServer 0.7.0, 0.7.1 and 0.7.2 (the hotfixes did not touch any of the patched classes). On any other version the patches simply stay off and the server runs as normal.

## Why

It started as a read-through of the server code to figure out why my VPS was struggling. It turned up a few real bugs along the way, including one that kicked us off the server when leaving the Forgotten Temple and then wouldn't let anyone back in.

## What it fixes

- **Forgotten Temple / instances.** On 1–2 core machines the server's shared thread pool has a single thread. Shutting down an instance world blocked it for 30 seconds, which caused:
  - "Abandoning TickingThread" in the log;
  - kicks when switching worlds ("Exception when adding player to world");
  - a leftover folder in `universe/worlds/` that threw `FileAlreadyExistsException` every time someone tried to go back in.

  The pool now gets at least 8 threads, and leftover instance folders are cleaned up before the instance is created again.
- **Stutter every 10 seconds.** Each world's thread stopped until the world config and resources were written to disk. That write is now asynchronous.
- **Lost or corrupted saves.** Two saves of the same file at once shared the same `.tmp` file and could fail or overwrite each other. Saves also weren't flushed to disk before the rename, so a power cut could leave empty files.
- **A world that broke for good.** If a parallel task threw an exception, that world could never run parallel tasks again.
- **Wasted CPU.** Every world with players spent about 3–4 ms per tick in a busy-wait loop. It now sleeps and only spins for the last moment. Parallel work is also no longer split into one-entity tasks.
- **World generation fighting the game for CPU.** Terrain generation runs on 75% of the cores at normal priority. Its threads now run at the lowest priority, like the ones in Hypixel's newer generator. In singleplayer they're also capped at half the cores, since the game renders on the same machine. On a 16-core PC the server went from ~11.6 to ~9 busy cores while generating, and terrain generated about 30% slower. Dedicated servers keep the vanilla thread count unless you set `hytaleopt.worldgenThreads`.
- **Mods that filter packets.** If a mod cancelled a packet inside a batch, the native QUIC transport threw a `NullPointerException`.
- **Packet rate limiter.** It lost precision on every refill and could kick clients that were under the limit.

## Download

Grab `HytaleOptimizer-<version>.jar` from [Releases](../../releases) and follow [Installing](#installing).

The release jar doesn't contain any Hypixel code. It ships our patches (`patches/*.patch`) plus two unmodified tools: [Vineflower](https://github.com/Vineflower/vineflower) (decompiler, Apache 2.0) and [ECJ](https://github.com/eclipse-jdt/eclipse.jdt.core) (Eclipse's Java compiler, EPL 2.0). The Java runtime that comes with Hytale has no compiler of its own, hence ECJ.

The first time the server starts with it, the plugin builds the fixed classes from **your** `HytaleServer.jar`:

1. Checks that your jar is the supported version.
2. Decompiles only the affected classes.
3. Applies the patches.
4. Compiles them.

This takes a few seconds. The result is cached in `.hytaleopt-cache/` next to the plugin jar, so later starts are instant. The cache rebuilds itself when the server or the plugin changes.

Releases are built by GitHub Actions straight from this repo (`build-release.sh`). Each one comes with a `.sha256` file.

## Building it yourself

If you'd rather not run the decompiler on your server, you can build a jar that already contains the fixed classes. You'll need JDK 25 or newer (`java`, `javac` and `jar`), `git`, and your `HytaleServer.jar`.

Keep that jar to yourself, since it does contain recompiled Hypixel classes.

Windows:

```powershell
powershell -ExecutionPolicy Bypass -File build.ps1
# or, if your server lives somewhere else:
powershell -ExecutionPolicy Bypass -File build.ps1 -ServerJar "D:\path\HytaleServer.jar"
```

Linux:

```bash
./build.sh /path/to/HytaleServer.jar
```

The output is `build/HytaleOptimizer.jar`. To build the release-style jar instead, run `./build-release.sh` (no `HytaleServer.jar` needed).

## Installing

**Dedicated server:** put the jar in `earlyplugins/`, next to `HytaleServer.jar`, and start the server with `--accept-early-plugins`. Without that flag, on panels without an interactive console the server crashes at startup with a `NullPointerException` in `System.console()` (a bug in the server itself).

**AMP (CubeCoders):** upload the jar to `Server/earlyplugins/` and tick *Configuration → Hytale → Server → Accept Early Plugins*.

**Singleplayer:** put the jar in `%APPDATA%\Hytale\UserData\EarlyPlugins\` (create the folder if it doesn't exist). No extra flags needed.

If everything went well, you'll see something like this at startup:

```
[HytaleOptimizer] ForkJoinPool.commonPool parallelism: 8 (CPU cores: 2)
[HytaleOptimizer] BsonUtil: enabled
[HytaleOptimizer] InstancesPlugin: enabled
...
```

To uninstall, delete the jar.

## Game updates

Every replaced class is pinned to the hash of the original. If an update changes any of them, that patch turns itself off (you'll see `skipped` in the console) and the server uses the original code. Code from different versions never gets mixed.

## Options

You can set these in two ways:

- **As Java options** (`-D...`, before `-jar`). In AMP they go in *Java and Memory → Additional Java Arguments*.
- **In a `hytaleopt.properties` file** next to the plugin jar, one `key=value` per line. This is the only way in singleplayer, where the game starts the server for you. If both are set, the `-D` option wins.

| Option | Default | What it does |
|---|---|---|
| `hytaleopt.disable` | — | Turn off specific patches, comma-separated: `-Dhytaleopt.disable=TickingThread,BsonUtil` |
| `hytaleopt.worldgenThreads` | vanilla (75% of cores); half in singleplayer | Threads used for terrain generation. Minimum 2. |
| `hytaleopt.minCommonParallelism` | `8` | Minimum threads in the shared pool. `0` leaves it alone. |
| `hytaleopt.spinThresholdNanos` | `1500000` | How long to busy-wait before each tick. |
| `hytaleopt.minParallelGrain` | `16` | Minimum entities per parallel task. `1` = original behaviour. |
| `hytaleopt.noFsync` | `false` | Don't force saves to disk. Faster, less safe. |

## If your server uses a lot of resources

By far the biggest factor is view distance. `MaxViewRadius` defaults to 32 chunks, the maximum, and the server loads whatever each client asks for up to that limit. Lowering it to 12–16 in `config.json` cuts CPU and RAM usage a lot.

The other big one is terrain generation while people explore: on a 2-core VPS it keeps both cores busy. If you know the play area, the world config can generate it in advance (`ChunkConfig.PregenerateRegion`), for example overnight.

## Tests

- `tests/PatchTests.java` reproduces several of the bugs against the original jar and checks that they don't happen with the patches.
- `tests/instancemod` is a test mod that spawns, removes and respawns the Forgotten Temple. **Don't install it on a real server.**

## Disclaimer

Unofficial project, not affiliated with Hypixel Studios. Use at your own risk, and back up your world before trying it. This repository contains no Hypixel code apart from the few lines of context in the patches.

## License

MIT, see [LICENSE](LICENSE). It covers the code in this repository, not the Hytale server.
