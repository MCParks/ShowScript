# show-check

Compiles every ShowScript 3 (Groovy) show against **two server classpaths**, Paper 1.12.2 and Paper 1.21.4 by default, and reports what would break on the second one. It also reports calls that can never work on any version, such as Kotlin's `minByOrNull` used on a Groovy list. **It only reports.** The exit code is 0 unless you pass `--fail-on`.

**It's a command-line tool that people run by hand.** It doesn't run in the technicians' show pipeline: the owner chose "CLI only" on ryan/1-12-2-to-1-21-4#16, and the proposed CI job (technicians/shows!4) was closed. ShowScript maintainers and agents run it, for example before the Epic Universe launch and as part of show-related work (see [Running it by hand](#running-it-by-hand)).

Background: issue [#30](https://dev.mcparks.us/ryan/1-12-2-to-1-21-4/-/issues/30) of the 1.12.2 → 1.21.4 upgrade tracker. The research prototype it grew out of is [#15](https://dev.mcparks.us/ryan/1-12-2-to-1-21-4/-/issues/15).

## How it works

1. Collects every `*.groovy` under the `--shows` directories and dedupes them by SHA-256.
2. Starts one worker JVM per classpath. The JVM classpath is this tool and Groovy 3.0.25, followed by that version's server and plugin jars. For each show the worker:
   - compiles it the way ShowScript does: script base class `TimecodeDsl`, default imports, dynamic Groovy, up to class generation;
   - compiles it again with `@TypeChecked` and a relaxed type-checking extension (`RelaxedTypeChecking`), so that every call it can resolve points at an exact member. The extension makes untyped (`def`, `Map`, `Closure`, binding) receivers dynamic. It accepts `BigDecimal` literals for `double`/`float`/`int` parameters, `GString` for `String`, and `Object` for anything. So what's left is version-relevant, not the ~60% of shows that strict type checking rejects;
   - recompiles **together with sibling shows** when a file only fails because it uses classes that other shows declare. ShowScript compiles every show through one `GroovyClassLoader`, so `shared/ParticlePath/Functions.groovy` can use the `Vec3` class that `Path.groovy` declares. This is transitive (a sibling that needs another sibling pulls that one in too, up to 16 files) and works for qualified names (`import demo.Packaged`, a class in a `package`). If a sibling the file needs doesn't compile itself, the file is **incomplete**: it's checked up to the compiler phase where the sibling stopped, flagged `internal`, and **not** counted as compiling;
   - records every member each show uses, with line numbers: classes, methods, constructors, static fields and enum constants, properties on typed values, and constants looked up by string (`Material.valueOf("X")`, `Material.matchMaterial("x")`).
3. Each worker checks **every member used on either side** by reflection against its own classpath, member by member. Pass the real 1.21.4 server jar (Mojang-mapped Paper) and the 1.21 plugin builds, and NMS, CraftBukkit, authlib and plugin APIs (WorldGuard 7, LuckPerms 5, TrainCarts…) get checked like the Bukkit API. A check that can't be done because the class can't be linked on that classpath (a class it depends on is missing) is reported as `unresolved`, with the missing class. It's never dropped, and never reported as a removal.
   - Constants looked up by string are matched the way each lookup method normalises its argument on that version: `valueOf`/`getMaterial` exactly; `matchMaterial` upper-cased with spaces turned into `_` and other punctuation dropped, plus a stripped `minecraft:` prefix on 1.13+ and numeric ids on 1.12.2 only (so `matchMaterial("stone")` is fine on both, and `matchMaterial("35")` is reported as missing on 1.21.4); `getByName` case-insensitively.
4. Diffs the two sides and writes a Markdown or JSON report, plus an optional GitLab Code Quality file.

## Rules

| rule | severity | meaning |
|---|---|---|
| `missing` | error | resolves on 1.12.2, doesn't exist on 1.21.4: a class, method, constructor, field/enum constant, property, or constant looked up by string |
| `compile` | error | compiles on 1.12.2, not on 1.21.4 (e.g. `import net.minecraft.server.v1_12_R1.*`) |
| `stc` | error | a type error that only shows up on 1.21.4 (after relaxation) |
| `signature` | error / warning | the method exists on 1.21.4 but no overload takes these argument types (`Material.getMaterial(int)`); a warning when Groovy would coerce the arguments |
| `unknown-method` | error (typed receiver) / warning (untyped) | no class on the classpath, no GDK method and no show declares a method with that name, so the call fails on **every** version (`minByOrNull`, ryan/1-12-2-to-1-21-4#52) |
| `legacy-api` | warning | still present on 1.21.4 but legacy pre-1.13 API with different behaviour: `MaterialData`, damage-value `ItemStack`s, `setDurability`, block data bytes |
| `name-gone` | warning | a method name called on an untyped value exists on 1.12.2's classpath but on no 1.21.4 class |
| `ambiguous-material` | warning | a 1.12 material name that 1.13 reused for something else (`BRICK`, `NETHER_BRICK`, `MELON`, `CARROT`, `POTATO`, `GRASS`). Passed as a constant to a ShowScript helper (`material`, `item`, `blockData`, `particle`'s data), it means the 1.12 thing on every version; used as Bukkit's `Material.MELON` or `Material.valueOf/getMaterial/matchMaterial("melon")`, it means the 1.12 thing on 1.12.2 and the new thing on 1.21. A `minecraft:` id is not flagged. See technicians/shows `docs/version-agnostic-helpers.md` ("Reused 1.12 names", and the plan for switching to modern names) (ryan/1-12-2-to-1-21-4#221, A33) |
| `unknown-constant` | warning | `valueOf("X")`/`getMaterial("X")`/`matchMaterial("x")`/`getByName("X")` names a constant that exists on neither version (after that method's normalisation) |
| `unresolved` | warning | a member couldn't be checked on one side because its class can't be linked there: a class it depends on is missing from that classpath (the message names it). A gap in the inputs, not a confirmed removal. Fix the classpath and rerun. The summary counts these per side |
| `broken-both` / `fails-on-a` | error | doesn't compile on 1.12.2 today |
| `internal` | error / warning | show-check itself couldn't fully check the file: the 1.21.4 worker crashed on it, the type-checking pass crashed or stopped early (later calls checked by name only), the walk that collects the members a show uses crashed ("member analysis incomplete": uses after that point weren't checked; counted per side in the summary), or a sibling it needs failed to compile (the file is "incomplete": not counted as compiling; the message names the sibling and its error). Not counted as a break |
| `deprecated` | info | newly deprecated on 1.21.4. Counted by default; listed only with `--deprecations` |

Suggested replacements come from `src/main/resources/us/mcparks/showcheck/renames.tsv`: renamed `Particle`, `PotionEffectType`, `Enchantment` and `EntityType` constants, flattened `Material` names, and so on. Legacy `Material` constants also say which `LEGACY_*` twin exists. Treat the suggestions as hints: several need a data-type change too (e.g. `REDSTONE` becomes `DUST` plus `DustOptions`, and `BLOCK_DUST` becomes `BLOCK` plus `BlockData`).

**Limits.** Calls on untyped (`def`) values are checked only by name, so behaviour changes there (enum order, legacy material comparisons, entity equipment) can't be ruled out statically. Commands inside `cmd` strings aren't checked here; vanilla command syntax is handled by legacy-command-converter.

## Build

```bash
cd tools/show-check
./gradlew installDist        # needs JDK 17-23 to run Gradle 8.10; the tool itself is Java 8 bytecode
# -> build/install/show-check/bin/show-check  (also build/distributions/show-check-1.0.3.zip)
```

If your `~/.gradle/gradle.properties` pins `org.gradle.java.home` to Java 8, override it with `-Dorg.gradle.java.home=/path/to/jdk21`.

## Run

```bash
show-check --shows shows --classpath-112 cp112 --classpath-121 cp121 --out report.md --json report.json
```

- `--shows [label=]DIR`: a show tree. Repeat it for several servers, e.g. `--shows dl=/mnt/mcparks/minecraft/dl/plugins/ShowsRebuild`.
- `--classpath-112`, `--classpath-121`: directories (searched for `*.jar`), jars, `:`-separated lists, or `@file` with one entry per line.
- Optional: `--focus shared/FunItems/StarGlowWand.groovy` lists these files first (e.g. the files you just changed); `--codequality gl-code-quality-report.json --path-prefix shows/` also writes a GitLab Code Quality file.

Each classpath needs **the server jar** and **ShowsRebuild.jar** (for `TimecodeDsl`). Add the plugin jars (or the server's `plugins/` directory) so shows that use plugin APIs resolve:

| side | what to pass |
|---|---|
| 1.12.2 | the production `cache/patched_1.12.2.jar` plus the server's `plugins/*.jar`. Alternatively the merged `us.mcparks:classpath:1.1` jar from the technicians/shows package registry, which has the server, the plugins and ShowsRebuild in one jar |
| 1.21.4 | `versions/1.21.4/paper-1.21.4.jar` (Mojang-mapped server: NMS + CraftBukkit) and `libraries/` from a Paper 1.21.4 server, plus its `plugins/*.jar` and the same `ShowsRebuild.jar` |

Both workers compile with the tool's **Groovy 3.0.25**, which comes first on the worker classpath, ahead of the Groovy shaded into ShowsRebuild.jar. Production runs ShowsRebuild's Groovy 3.0.12, so the 1.12.2 side doesn't use production's exact compiler. That's deliberate: 3.0.12 can't read Java 21 class files (ryan/1-12-2-to-1-21-4#29), and the language is the same across 3.0.x, so a show that compiles on one compiles on the other.

Run the 1.21.4 side on Java 21. Both sides run on the JVM that runs `show-check` unless you pass `--java-112`/`--java-121`. With the default (both sides on Java 21), the 1.12.2 side checks shows against Java 21's JDK classes, not Java 8's; to also catch shows that use JDK API production's Java 8 doesn't have, pass `--java-112 /usr/lib/jvm/temurin-8-jdk/bin/java` (the tool is Java 8 bytecode, so its worker runs there). The whole production snapshot (about 7,150 unique shows) takes a few minutes on 16 cores; the technicians' repo (about 250 shows) takes about 35 s.

Other options: `--only-focus` (report only the focus files), `--deprecations`, `--xmx 8g` (worker heap), `--threads N`, `--work DIR` (keep intermediate files), `--fail-on error|warning` (non-zero exit, off by default), `--name-a/--name-b` (labels if you compare other versions), `--rerender` (rebuild the report from the worker output already in `--work`).

## Running it by hand

This is the whole workflow (CLI only, ryan/1-12-2-to-1-21-4#16). The inputs the sample report used are in the technicians/shows package registry: `us.mcparks:classpath:1.1` (Maven) for 1.12.2 and the generic package `classpath-121/1.21.4-uso-2026-10-24b` for 1.21.4. Every line below can be pasted as is, from the root of this repository, with `glab` logged in to dev.mcparks.us (any `glab` command run from a clone of a dev.mcparks.us repo uses that host):

```bash
cd tools/show-check
./gradlew -Dorg.gradle.java.home=/usr/lib/jvm/temurin-21-jdk installDist   # any JDK 17-23
mkdir -p /tmp/show-check/cp112 /tmp/show-check/cp121
glab api projects/107/packages/maven/us/mcparks/classpath/1.1/classpath-1.1.jar > /tmp/show-check/cp112/classpath-1.1.jar
glab api projects/107/packages/generic/classpath-121/1.21.4-uso-2026-10-24b/classpath-121.zip > /tmp/show-check/classpath-121.zip
unzip -q -o /tmp/show-check/classpath-121.zip -d /tmp/show-check/cp121
git clone https://dev.mcparks.us/technicians/shows.git /tmp/show-check/shows
JAVA_HOME=/usr/lib/jvm/temurin-21-jdk build/install/show-check/bin/show-check --shows /tmp/show-check/shows/shows --classpath-112 /tmp/show-check/cp121/ShowsRebuild.jar:/tmp/show-check/cp112/classpath-1.1.jar --classpath-121 /tmp/show-check/cp121 --out /tmp/show-check/report.md --json /tmp/show-check/report.json
```

(`107` is technicians/shows' project id.) `ShowsRebuild.jar` comes first on the 1.12.2 side so both sides see the same production ShowScript API. To check a production snapshot instead, replace the `--shows` argument with one `--shows <server>=/mnt/mcparks/minecraft/<server>/plugins/ShowsRebuild` per server (read-only; skip the `*-auto-*-clone` directories).

Read the summary table first: `unresolved` counts above zero mean a classpath is missing something, so add the missing jar and rerun before trusting `missing` findings for that API.

### Checking against Paper 26.3 (Epic Universe target)

The Epic Universe server moves to Paper 26.3 (ryan/1-12-2-to-1-21-4#10, #191). The two sides aren't tied to 1.12.2 and 1.21.4: `--classpath-121` takes any server's jars and `--name-a`/`--name-b` label the report. Two runs are useful: **1.21.4 vs 26.3** (what's new on 26.3 compared with the already-checked 1.21.4) and **1.12.2 vs 26.3**.

- **Run the 26.3 side on Java 25:** Paper 26.x is class file 69, so pass `--java-121 /path/to/java-25/bin/java` (a JRE is enough; the tool is Java 8 bytecode). The tool's Groovy 3.0.25 compiles shows against class-69 jars fine.
- **26.3 classpath:** there's no registry bundle for it yet. Build one from the Paper download: `paper-26.3-<build>.jar` from `fill.papermc.io` (check its sha256), then `java -Dpaperclip.patchonly=true -jar paper-26.3-<build>.jar` (Java 25) writes `versions/26.3/paper-26.3.jar` and `libraries/`. Add the 26.3 plugin set (WorldGuard 7.0.20+, FastAsyncWorldEdit, BKCommonLib/TrainCarts/TCCoasters/TCHangRail 2.0.x, LuckPerms 5, PlaceholderAPI, Vault, CoreProtect, EssentialsX 2.22) and the first-party jars. Use the **same** first-party and TrainCarts jars on both sides so the diff only shows server changes.

```bash
show-check --shows repo=shows/shows --shows uso=/mnt/mcparks/minecraft/uso/plugins/ShowsRebuild \
  --classpath-112 @cp-1.21.4.txt --classpath-121 @cp-26.3.txt \
  --java-112 /usr/lib/jvm/temurin-21-jdk/bin/java --java-121 /usr/lib/jvm/java-25-openjdk/bin/java \
  --name-a 1.21.4 --name-b 26.3 --out report-121-263.md
```

Result on 2026-11-13 (Paper 26.3 build 249, technicians/shows `upgrade/main` 3eaffea3 plus production `uso`'s shows, 381 unique files): 380 compile on both versions, and the only 26.3-only break is `GameProfile#getProperties()` (authlib's `GameProfile` is a record on 26.x: `properties()`) in production `uso`'s **old** `shared/AdvancedCMs/Spawn.groovy`, which technicians/shows' `upgrade/main` version (#31) no longer uses. The 12 phase-1 shows have no new findings on 26.3. Things show-check can't see that changed after 1.21.4: particles that gained required data in 1.21.9 (`FLASH`, `EFFECT`, `INSTANT_EFFECT`, `DRAGON_BREATH`; ShowScript's `particle()` helper supplies vanilla's defaults since #191) and command syntax inside `cmd` strings (legacy-command-converter).

**What may go into a 1.21.4 classpath bundle** (if you rebuild one, e.g. once the Epic Universe plugin set is decided in ryan/1-12-2-to-1-21-4#40): the Paper 1.21.4 server jar and its `libraries/`, `ShowsRebuild.jar`, the first-party plugin jars (MCPX, MCPAttractions, MCPWGFlags, Points, LegacyCommandConverter), and **free** third-party plugins whose API shows can use: TrainCarts, BKCommonLib, TCCoasters, TCHangRail, WorldEdit, WorldGuard, LuckPerms, CoreProtect, PlaceholderAPI, Vault, HolographicDisplays, ImageOnMap and EssentialsX. **Never put paid or premium plugin jars in a bundle that's uploaded anywhere** (FeatherBoard, HeadDatabase, DeluxeChat, UltimateRides and the like), or jars whose license you don't know: anyone who can read technicians/shows can download its registry packages. No production show references any other plugin's classes (checked on the 2026-10-24 snapshot), so leaving them out doesn't change the results.

## Sample report

[`sample-report/production-2026-10-25.md`](sample-report/production-2026-10-25.md) covers the production snapshot in `/mnt/mcparks` as read on 2026-10-25: all 7 distinct servers, generated with show-check 1.0.3 from the registry inputs above (production `ShowsRebuild.jar` + `us.mcparks:classpath:1.1` for 1.12.2, and `classpath-121` `1.21.4-uso-2026-10-24b` for 1.21.4: Paper 1.21.4 build 226 `a838a88` from the dev `uso` server, plus the first-party and free plugins). The header comment of the report has the exact command line. The numbers are compared with the ryan/1-12-2-to-1-21-4#15 prototype on ryan/1-12-2-to-1-21-4#30.
