# Offline NeoForge 1.21.1 compile feasibility — recon findings

**Date:** 2025 (session `session-6c3c0ce34285` line of work)
**Machine:** Windows, shell = **Windows PowerShell 5.1** (`powershell`-class host, not pwsh 7), ANSI codepage **GB2312 (CP936)**
**Verdicts**

| Question | Answer |
|---|---|
| Can we compile a NeoForge 1.21.1 mod **completely offline** with plain `javac`? | **YES** — proven, exit 0, zero diagnostics (`_build_probe\out\probe\Probe.class`) |
| Can we compile against **Create / Sable / Aeronautics / Flywheel / Ponder / Registrate / Catnip** offline? | **YES** — but only after extracting jar-in-jar (`META-INF/jarjar`) jars into a flat dir |
| Is a **Gradle** build (moddevgradle) possible offline? | **NO** — proven by a bounded `gradle --offline` run failing at `settings.gradle:9` |
| Can we run a **dev client** (`runClient`)? | **NO** offline (and not attempted) |
| Can we still **package** a mod jar offline? | **YES** — `jar --create` proven, 2 544-byte probe jar |

Working artifacts live in `D:\DS_WORKSPACE\raildispatch\_build_probe\`.

---

## 1. Environment (verified)

| Item | Value |
|---|---|
| JDK 21 (used) | `C:\Program Files\Java\jdk-21\bin\javac.exe` → `javac 21.0.8`; `java` → `21.0.8+12-LTS-250` |
| Second JDK 21 | `C:\Users\MECHREVO\.gradle\jdks\eclipse_adoptium-21-amd64-windows\jdk-21.0.8+9\bin\javac.exe` → `javac 21.0.8` |
| `C:\Program Files\Java\latest` | **NOT a JDK** — contains only `jre-1.8`. Do not use. |
| JDK 19 | `C:\Program Files\Java\jdk-19\bin\javac.exe` → `javac 19.0.2` (unusable for 1.21.1: needs 21) |
| Launcher root | `D:\CITY\.minecraft` (`libraries\`, `versions\`, `assets\`) |
| Target instance | `D:\CITY\.minecraft\versions\1.21.1-NeoForge_21.1.233CRA6月20号物理火车包` |
| Other instances | `…21.1.228航空学超级整合包` (78 mods), `…21.1.220CRA服务器61更新包` (115 mods) |
| Scratch/probe dir | `D:\DS_WORKSPACE\raildispatch\_build_probe` (only writable location) |

Shell gotchas that cost real time here (documented so scripts behave):

* `.ps1` files are blocked by execution policy → run with `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force` first (or `powershell -ExecutionPolicy Bypass -File …`).
* PowerShell 5.1 reads **BOM-less** `.ps1` as ANSI/CP936 → any non-ASCII literal in a script is mangled. All probe scripts here are **pure ASCII** and resolve the Chinese-named instance dir with `-like '*21.1.233*'`.
* `Get-Content -Raw` was rejected in this host → use `[System.IO.File]::ReadAllText()`.

---

## 2. Task 1 — jar inventory (exact paths)

### NeoForge (`D:\CITY\.minecraft\libraries\net\neoforged\neoforge\…`)

| Version | Path | Variant | Size |
|---|---|---|---|
| 21.1.233 | `…\net\neoforged\neoforge\21.1.233\neoforge-21.1.233-universal.jar` | **universal (the compile/API jar)** | 3.4 MB |
| 21.1.233 | `…\net\neoforged\neoforge\21.1.233\neoforge-21.1.233-client.jar` | client-only distribution jar | 5.4 MB |
| 21.1.228 | `…\21.1.228\neoforge-21.1.228-{universal,client}.jar` | same pair | 3.4 / 5.4 MB |
| 21.1.220 | `…\21.1.220\neoforge-21.1.220-{universal,client}.jar` | same pair | 3.4 / 5.4 MB |

* **Exact NeoForge version used by the target instance: 21.1.233.**
* Variants present: **`universal` + `client` only**.
  * **No `installer`, no `userdev`, no `dev`, no `-sources`, no `-javadoc`.** This is a hard blocker for a Gradle/moddevgradle build (see §7).
* Content check: `neoforge-21.1.233-universal.jar` holds 2 518 entries, of which **1 606 are `net/neoforged/neoforge/**` classes** (e.g. `net/neoforged/neoforge/registries/DeferredRegister.class` → present). `neoforge-21.1.233-client.jar` holds **0** `net.neoforged.neoforge` classes and is **not needed to compile** (negative control N4 proves it: still compiles with it removed).

### FML — note: there is **no** `net\neoforged\fml\*` directory

```
D:\CITY\.minecraft\libraries\net\neoforged\fancymodloader\loader\4.0.42\loader-4.0.42.jar
D:\CITY\.minecraft\libraries\net\neoforged\fancymodloader\earlydisplay\4.0.42\earlydisplay-4.0.42.jar
D:\CITY\.minecraft\libraries\net\neoforged\bus\8.0.5\bus-8.0.5.jar
D:\CITY\.minecraft\libraries\net\neoforged\coremods\7.0.3\coremods-7.0.3.jar
D:\CITY\.minecraft\libraries\net\neoforged\accesstransformers\10.0.1\accesstransformers-10.0.1.jar
D:\CITY\.minecraft\libraries\net\neoforged\accesstransformers\at-modlauncher\10.0.1\at-modlauncher-10.0.1.jar
D:\CITY\.minecraft\libraries\net\neoforged\mergetool\2.0.0\mergetool-2.0.0-api.jar
D:\CITY\.minecraft\libraries\net\neoforged\JarJar{Selector,Metadata,FileSystems}\0.4.1\*.jar
D:\CITY\.minecraft\libraries\net\neoforged\srgutils\1.0.0\srgutils-1.0.0.jar
D:\CITY\.minecraft\libraries\net\neoforged\installertools\{installertools,cli-utils,binarypatcher,jarsplitter}\2.1.2\*.jar
D:\CITY\.minecraft\libraries\net\neoforged\AutoRenamingTool\2.0.3\AutoRenamingTool-2.0.3-all.jar
```

### Minecraft 1.21.1 (`D:\CITY\.minecraft\libraries\net\minecraft\client\1.21.1-20240808.144430\`)

| File | Role | Size |
|---|---|---|
| `client-1.21.1-20240808.144430-srg.jar` | **the compile jar — actually Mojang-mapped** (8 624 entries) | 17.9 MB |
| `client-1.21.1-20240808.144430-slim.jar` | **obfuscated** vanilla client (`fgi$a.class`, `guu.class`, …) — runtime only | 13.8 MB |
| `client-1.21.1-20240808.144430-extra.jar` | assets (8 448) + data (6 796) only — useless for compiling | 11.8 MB |
| `client-1.21.1-20240808.144430-mappings.txt` | Mojang official ProGuard mappings | — |

* **There is no `net\minecraft\server\…` jar anywhere in `libraries\`** (only `net\minecraft\client\`). The NeoForm pipeline needs the server jar → another offline blocker.
* The version profile jar `D:\CITY\.minecraft\versions\1.21.1-NeoForge_21.1.233CRA6月20号物理火车包\1.21.1-NeoForge_21.1.233CRA6月20号物理火车包.jar` (25.6 MB) is the launcher's game jar (`-DignoreList=client-extra,<version>.jar`); the launcher profile does **not** list the NeoForge jar at all — PCL injects it (profile `clientVersion=1.21.1`, `mainClass=cpw.mods.bootstraplauncher.BootstrapLauncher`, module path = bootstraplauncher + securejarhandler + ASM 9.8 + JarJarFileSystems).

---

## 3. Task 2 — mapping situation: **plain Mojang official names, no remapping**

NeoForge 1.20.2+ runs production with Mojang official mappings; mod jars are **not** remapped at runtime. Verified empirically with `javap`:

```
> javap -cp <…>-srg.jar net.minecraft.world.level.Level
public abstract class net.minecraft.world.level.Level implements net.minecraft.world.level.LevelAccessor,java.lang.AutoCloseable
  public static final com.mojang.serialization.Codec<…ResourceKey<…Level>> RESOURCE_KEY_CODEC;

> javap -cp <…>-srg.jar net.minecraft.core.BlockPos
public class net.minecraft.core.BlockPos extends net.minecraft.core.Vec3i

> javap -cp <…-srg.jar> net.minecraft.class_1937          # SRG-style name
错误: 找不到类: net.minecraft.class_1937                    (class not found)

> javap -cp "<mods>\[机械动力] create-1.21.1-6.0.10.jar" com.simibubi.create.content.trains.schedule.Schedule
public class com.simibubi.create.content.trains.schedule.Schedule {
  public static final net.minecraft.network.codec.StreamCodec<…RegistryFriendlyByteBuf, …Schedule> STREAM_CODEC;
  public static java.util.List<net.createmod.catnip.data.Pair<net.minecraft.resources.ResourceLocation, …>> INSTRUCTION_TYPES;
```

**Verdict:** the `-srg` classifier jar contains genuine Mojang-mapped `net/minecraft/**` names (`Level`, `BlockPos`, `LevelAccessor`, `DensityFunctions$…` are all present; SRG `class_…`/`m_…` names are absent). Create's own class file references plain Mojang types. So **addon code can be written directly against these local jars using ordinary Mojang names** — no mapping/remapping step, no `-srg`→`mojmap` conversion needed.

The only nuance: the word "srg" in the filename is a **legacy classifier name**; the bytes inside are Mojang-mapped. And the **slim** jar must never be used for compiling (negative control N1: 24 errors).

---

## 4. Task 3 — the offline compile classpath

`_build_probe\classpath.txt` — 340 absolute paths, one per line (37 818 bytes).
`_build_probe\classpath-joined.txt` — same list, `;`-joined single line (37 477 bytes, 34 007 characters).

Composition:

| Source | Count | Notes |
|---|---|---|
| Library list of the instance's version JSON | 117 | resolved via `downloads.artifact.path`, Windows rules honoured (27 mac/linux-only natives skipped, 0 missing) |
| `client-1.21.1-20240808.144430-srg.jar` | 1 | all Minecraft classes |
| `neoforge-21.1.233-universal.jar` | 1 | all NeoForge classes |
| `neoforge-21.1.233-client.jar` | 1 | not required (kept for parity with the launcher) |
| `…\mods\*.jar` of the 233 instance | 178 | Create 6.0.10, Sable 2.0.3, Aeronautics bundled 1.3.0, ldlib2, kotlinforforge, curios, registrate consumers, etc. |
| `_build_probe\libs\*.jar` — extracted `META-INF/jarjar` jars | 53 (67.7 MB) | **mandatory**, see below |
| **Total** | **340** | 0 missing |

### Why the nested-jar extraction is mandatory

javac cannot read classes inside a jar nested in another jar. Many of this pack's libraries are shipped **only** as jar-in-jar:

* `create-1.21.1-6.0.10.jar` → `flywheel-neoforge-1.21.1-1.0.6.jar`, `ponder-neoforge-1.0.82+mc1.21.1.jar`, `Registrate-MC1.21-1.3.0+67.jar`
* `create-aeronautics-bundled-1.21.1-1.3.0.jar` is a **10-entry wrapper** whose real code is 3 nested jars: `dev.eriksonn.aeronautics.aeronautics-neoforge-1.21.1-1.3.0.jar` (25 MB), `dev.ryanhcode.offroad.offroad-…`, `dev.simulated_team.simulated.simulated-…`
* `sable-neoforge-1.21.1-2.0.3.jar` → `dev.ryanhcode.sable.sable-sable_rapier-…`, `sable-companion-common-…`, `veil-neoforge-1.21.1-4.1.4.jar`
* `ldlib2-neoforge-1.21.1-2.2.24-all.jar` → `taffy`, `yoga`, **`kotlin-stdlib-2.4.0.jar`**

**Catnip is the killer example:** `net/createmod/catnip/data/Pair.class` — part of Create's own public API signature — exists in exactly **one** jar on the whole machine: the *nested* `ponder-neoforge-1.0.82+mc1.21.1.jar`. Without extraction, `Schedule.INSTRUCTION_TYPES` cannot compile (control N8 fails with `程序包 net.createmod.catnip.data 不存在`).

Extraction script: `_build_probe\extract-nested.ps1` (uses `System.IO.Compression.ZipFile`, writes to `_build_probe\libs\`, 53 unique names; 19 duplicates skipped — same-named nested jars from two mod versions, e.g. kotlin-stdlib 2.3.0 vs 2.4.0 both kept, sable-companion 1.5.0/1.6.0 both kept).

### Classpath construction scripts

* `_build_probe\build-classpath.ps1` — reads the version JSON, resolves libraries, adds MC/NeoForge/mod/nested jars, writes both classpath files, reports missing entries.
* `_build_probe\extract-nested.ps1` — extracts nested jars.
* `_build_probe\make-javac-args.ps1` — writes the working `javac-args.txt` / `javac-args-deep.txt`.
* `_build_probe\compile.ps1` — one-shot: classpath → extract → argfile → `javac`. Verified from a deleted `out\`: `COMPILE OK`.

Caveat: the classpath is deliberately **over-inclusive** (178 mod jars include several duplicate mod versions, e.g. JEI ×2, create_connected ×2, immersive_paintings ×2, and a 199 MB car pack). Prune `classpath.txt` for a leaner real build.

---

## 5. Task 4 — the probe and **every error hit, with its fix**

### Probe sources (compile cleanly, exit 0, 0 diagnostics)

`_build_probe\src\probe\Probe.java`
```java
package probe;
import com.simibubi.create.content.trains.schedule.Schedule;
import dev.engine_room.flywheel.api.Flywheel;      // nested jar only
import dev.eriksonn.aeronautics.Aeronautics;       // nested jar only
import dev.ryanhcode.sable.Sable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class Probe {
    public static final class ProbeBlock extends Block {           // vanilla type
        public ProbeBlock() { super(BlockBehaviour.Properties.of()); }
    }
    public static final DeferredRegister<Block> BLOCKS =           // NeoForge API + vanilla registry
            DeferredRegister.create(Registries.BLOCK, Sable.MOD_ID);
    public static Schedule newSchedule() { return new Schedule(); } // Create type
    public static String nestedJarClassNames() { return Aeronautics.MOD_ID + ":" + Flywheel.ID; }
    public static void main(String[] args) { /* BlockPos, Level, ResourceLocation.fromNamespaceAndPath, prints */ }
}
```
`_build_probe\src\probe\ProbeDeep.java` — forces resolution of Create's generic signature:
`List<net.createmod.catnip.data.Pair<ResourceLocation, Supplier<? extends ScheduleInstruction>>> = Schedule.INSTRUCTION_TYPES;`

Output (`_build_probe\out\probe\`): `Probe.class` (2 688 B), `Probe$ProbeBlock.class` (613 B), `ProbeDeep.class` (1 028 B).

### Errors encountered → fixes

| # | Symptom (verbatim) | Root cause | Fix |
|---|---|---|---|
| E1 | `程序"javac.exe"无法运行: The filename or extension is too long` | inline `-cp` makes a ~**34 267-char** command line; Windows limit is 32 767 | use `javac @argfile` |
| E2 | `错误: 无效的标记: jei-1.21.1-neoforge-19.27.0.340.jar;D:\CITY\…\[JEI物品管理器]` (invalid flag) | in an argfile, whitespace separates arguments; **78 classpath entries contain spaces** (Chinese `[…]` mod names, `New_Cars4.4(1.16-1.21-26.1)cut.jar`, `…-0.3 (1).jar`) → the classpath was split | put `-cp "<joined>"` as **one quoted line** |
| E3 | 103 `错误: 程序包 com.simibubi.create…/dev/ryanhcode.sable/net.minecraft.core 不存在` with a *quoted* one-line `-cp` whose paths used **backslashes** | javac's `@argfile` parser honours the quotes but **treats `\` inside a quoted token as an escape**, so `D:\a\b.jar` becomes `D:a b.jar` and nothing resolves | write the argfile classpath with **forward slashes** — `M5`/`M6` controls: same classpath with `/` → 0 errors |
| E4 | `错误: 无效的标记: ?-encoding` | argfile written with a **UTF-8 BOM** (`[Encoding]::GetEncoding('utf-8')` emits a preamble) | write with `New-Object System.Text.UTF8Encoding($false)` (UTF-8, no BOM) |
| E5 | `javap` — same `The filename or extension is too long` | javap has no 32 K exemption either and **no @argfile support** | use a narrow `-cp` with just the jar under inspection |
| E6 | `无法加载文件 …build-classpath.ps1，因为在此系统上禁止运行脚本` | execution policy | `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force` |
| E7 | `[System.IO.File]::ReadAllText: 路径中具有非法字符` | PS 5.1 read the BOM-less script as CP936 → Chinese literal mangled | keep scripts ASCII-only; locate the instance via `Get-ChildItem … -like '*21.1.233*'` |
| E8 | `Get-Content : 找不到与参数名称"Raw"匹配的参数` | `-Raw` unavailable in this host | `[System.IO.File]::ReadAllText($path)` |
| E9 | argfile got `-cp "` and the path and `"` as **three** separate lines | PS 5.1 `@('a' + $b + 'c', …)` inside an array literal splits the concatenation | wrap the concatenation: `@(('a' + $b + 'c'))` |
| E10 | `错误: 无法初始化主类 probe.Probe` / `原因: java.lang.NoClassDefFoundError: com/simibubi/create/content/trains/schedule/Schedule` when running the compiled probe with `java @argfile` | **`java`'s argfile parsing does not treat the quoted classpath the same as javac** (the space-containing `[机械动力] create-…jar` entry was not resolved, while the space-free Sable entry was). This only affects *running*, not compiling. | for running, use the launcher's own command line / a manifest `Class-Path`; not needed for compilation |

### Negative controls (each ingredient proven necessary)

| Case | Result |
|---|---|
| N0 baseline (full 340-jar classpath) | **exit 0, 0 messages** |
| N1 swap `-srg` → `-slim` (obfuscated) | exit 1, **24 errors** (`找不到符号`) |
| N2 drop the 53 extracted nested jars | exit 1, **17 errors**, first: `程序包 dev.engine_room.flywheel.api 不存在` |
| N3 drop `neoforge-21.1.233-universal.jar` | exit 1, **14 errors**, first: `程序包 net.neoforged.neoforge.registries 不存在` |
| N4 drop `neoforge-21.1.233-client.jar` | **exit 0** → client jar unnecessary for compiling |
| N5 drop all 178 mod jars | exit 1, **48 errors**, first: `程序包 com.simibubi.create.content.trains.schedule 不存在` |
| N6 drop `client-…-extra.jar` | **exit 0** → assets jar irrelevant |
| N7 `ProbeDeep` baseline (Catnip in nested Ponder) | **exit 0** |
| N8 `ProbeDeep` without nested jars | exit 1, first: `程序包 net.createmod.catnip.data 不存在` |

---

## 6. Task 5 — `@argfile` support: **works** (with three rules)

`javac @args.txt` works, and is mandatory because the inline command line is 34 267 chars.
`_build_probe\javac-args.txt` (UTF-8 **no BOM**, LF/CRLF irrelevant):

```
-encoding
UTF-8
-source
21
-target
21
-proc:none
-nowarn
-d
D:\DS_WORKSPACE\raildispatch\_build_probe\out
-cp "D:/CITY/.minecraft/libraries/com/github/oshi/…jar;…;D:/DS_WORKSPACE/raildispatch/_build_probe/libs/yoga-1.0.0.jar"
D:\DS_WORKSPACE\raildispatch\_build_probe\src\probe\Probe.java
```
(the `-cp` line is 34 013 characters — the whole reason an argfile is needed)

**Rules for a working argfile:** (1) the classpath value must be **quoted** and on the same line as `-cp`; (2) it must use **forward slashes** (backslashes are eaten as escapes); (3) the file must be **UTF-8 without BOM**.

Companion files: `javac-args-deep.txt` (ProbeDeep), `javac-args-auto.txt` (written by `compile.ps1`), plus failing variants kept as evidence: `javac-args-unquoted.txt`, `javac-args-gbk.txt`, `diag-M1…M6*.txt`, `neg-*.txt`.

---

## 7. Task 6 — Gradle offline: **NOT possible** (empirically proven)

What *is* present:

* **Pre-downloaded Gradle distributions** (usable without the wrapper jar):
  * `C:\Users\MECHREVO\.gradle\wrapper\dists\gradle-8.14-bin\38aieal9i53h9rfe7vjup95b9\gradle-8.14\bin\gradle.bat`
  * `C:\Users\MECHREVO\.gradle\wrapper\dists\gradle-8.8-bin\dl7vupf4psengwqhwktix4v1\gradle-8.8\bin\gradle.bat`
* Toolchain JDKs: `C:\Users\MECHREVO\.gradle\jdks\eclipse_adoptium-{17,21}-amd64-windows\…` (+ the two zips)
* Daemon logs from earlier (2025) Gradle 8.14 runs → Gradle has been run on this box before.
* **NeoForm config for 1.21.1 exists**: `D:\CITY\.minecraft\libraries\net\neoforged\neoform\1.21.1-20240808.144430\neoform-1.21.1-20240808.144430.zip` (461 entries: `config.json`, `config/joined.tsrg`, `config/inject/…`, `patches/…`) plus `…-mappings.txt`, `…-mappings-merged.txt`, and `net\neoforged\neoform\…\` mappings; `client-1.21.1-…-mappings.txt` also present. Tooling jars `installertools 2.1.2`, `AutoRenamingTool 2.0.3`, `binarypatcher 2.1.2`, `jarsplitter 2.1.2`, `mergetool 2.0.0`, `srgutils` are all in `libraries\`.

What is missing / blocking:

1. **`~\.gradle\caches` does not exist** → no plugin/dependency cache at all. `~\.m2\repository` also does not exist.
2. **No `net.neoforged.moddev` / moddevgradle / foojay artifact anywhere** under `~\.gradle` (recursive search for `*moddev*`, `*foojay*`, `*neoforge*` → 0 hits).
3. The existing `D:\DS_WORKSPACE\raildispatch\settings.gradle` resolves plugins from `gradlePluginPortal()` and `maven.neoforged.net` → network.
4. Even with the NeoForm zip present, its `config.json` `joined` pipeline begins with **`downloadManifest`, `downloadJson`, `downloadClient`, `downloadServer`, `downloadClientMappings`** → network, and it needs a **server jar which does not exist locally**.
5. The pipeline's `decompile` step needs a decompiler (vineflower/fernflower/forgeflower/CFR) — **no such jar anywhere in `libraries\`**.
6. No `neoforge-…-userdev.jar` / `-installer.jar` in `libraries\`, i.e. no pre-built dev bundle to side-step the pipeline.

Empirical proof (bounded, `--offline`, `--no-daemon`, isolated `GRADLE_USER_HOME` inside the workspace):

```
> gradle.bat --offline --no-daemon -g <ws>\_build_probe\gradle-home -p D:\DS_WORKSPACE\raildispatch projects
Welcome to Gradle 8.14!
FAILURE: Build failed with an exception.
* Where: Settings file 'D:\DS_WORKSPACE\raildispatch\settings.gradle' line: 9
* What went wrong:
Plugin [id: 'org.gradle.toolchains.foojay-resolver-convention', version: '0.8.0'] was not found in any of the following sources:
 - Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
 - Included Builds (No included builds contain this plugin)
 - Plugin Repositories (could not resolve plugin artifact '…foojay-resolver-convention.gradle.plugin:0.8.0')
```
(Side noise, harmless: `net.rubygrapefruit.platform.NativeException: Couldn't open current thread, error = 5` from the file-watcher under this sandbox; the build then fails for the real reason above.)

**Conclusion: an offline Gradle build is impossible** — no plugin metadata, no module cache, no userdev/installer artifact, and the NeoForm pipeline is download-driven with a missing server jar and missing decompiler. Gradle would need at least the plugin portal, `maven.neoforged.net`, Mojang's manifest/version JSON + client/server jars, and a decompiler.

---

## 8. Task 7 — running a dev client

**Not possible offline, and not attempted** (`runClient`/`runData` require the moddevgradle plugin + the NeoForm decompile/recompile pipeline → network; see §7). No Minecraft process was launched in this session.

Practical alternatives that *are* offline:

* **Packaging** the compiled classes into a loadable mod jar works offline — proven:
  `jar --create --file probe-mod.jar -C out . -C . META-INF` → `probe-mod.jar` (2 544 B) containing `probe/Probe.class`, `probe/Probe$ProbeBlock.class`, `META-INF/neoforge.mods.toml`. A production mod jar is thus producible with `javac` + `jar` alone.
* **Launching the real client** with that jar dropped into `…\mods\` uses the existing PCL profile (the runtime classpath already exists locally: `srg` jar + NeoForge universal + 117 libraries + assets). That is a normal game launch, **not** a dev client, and it was out of scope here.

---

## 9. Exact working commands (copy/paste)

```powershell
# 0) one-time per shell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force

# 1) build classpath + extract nested jars + compile (one shot)
powershell -ExecutionPolicy Bypass -File D:\DS_WORKSPACE\raildispatch\_build_probe\compile.ps1
#    -> compiles ...\src\probe\Probe.java with the 340-jar classpath, output in ...\_build_probe\out

# 2) the raw javac invocation (equivalent)
& 'C:\Program Files\Java\jdk-21\bin\javac.exe' '@D:\DS_WORKSPACE\raildispatch\_build_probe\javac-args.txt'

# 3) compile any other source file
powershell -ExecutionPolicy Bypass -File D:\DS_WORKSPACE\raildispatch\_build_probe\compile.ps1 -Source D:\path\to\YourMod.java

# 4) package (optional)
& 'C:\Program Files\Java\jdk-21\bin\jar.exe' --create --file probe-mod.jar -C out . -C . META-INF
```

`javac-args.txt` semantics: `-encoding UTF-8 -source 21 -target 21 -proc:none -nowarn -d <out> -cp "<340 jars, forward slashes, quoted>" <Probe.java>`.

---

## 10. Caveats & recommendations

1. **`-proc:none`** was used (no annotation processing). If the mod uses annotation processors (e.g. Registrate's `@Register`? no — but JSR-305/auto-service style codegen would), they must be on the classpath as jars; none were needed here.
2. The classpath deliberately over-includes 178 mod jars with duplicates → possible ambiguous duplicate classes at *compile* time are tolerated by javac (first match wins), but keep it in mind for method-signature resolution.
3. `libs\` extraction drops same-named nested jars (19 duplicates skipped) — only one version of e.g. `sable-companion-common` wins per name; both `kotlin-stdlib-2.3.0` and `2.4.0` survive because their names differ.
4. Non-ASCII paths are everywhere (Chinese instance dir + mod filenames). All scripts here are ASCII and tolerate GB2312; only the `@argfile` needs UTF-8 + forward slashes.
5. For a **real** additive mod build, prefer a slim, explicit classpath (Create + Catnip(nested Ponder) + Flywheel + Registrate + Sable + Aeronautics + MC + NeoForge) rather than all 340 entries — regenerate `classpath.txt` and the argfile with `make-javac-args.ps1`.
6. Repointing to another instance (e.g. 21.1.228) only requires changing the `-like '*21.1.233*'` glob in `build-classpath.ps1` / `extract-nested.ps1` to the other version dir and re-running; the NeoForge jar path must be switched to `21.1.228`.
