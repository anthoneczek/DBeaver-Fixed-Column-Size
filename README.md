# Fixed column widths for DBeaver

DBeaver recalculates result-grid column widths from cell contents every time a
result set is opened, refreshed or reconnected, and never stores them. This is a
small patch that makes it read the widths from a config file instead.

Related feature requests, all declined or unimplemented:
[#41318](https://github.com/dbeaver/dbeaver/issues/41318),
[#11485](https://github.com/dbeaver/dbeaver/issues/11485)

> **This is an unofficial patch of an installed application.** It modifies one
> class inside one plugin jar of your DBeaver installation. It is not affiliated
> with or endorsed by DBeaver Corp. A backup is taken automatically and a single
> command undoes everything, but if you are not comfortable with that, stop here.

---

## What it does

You write a file like this:

```properties
*=110
id=55
example_value=140
example\ value\ with\ space\ inside=190
Coulum\ A=200
Column\ B=180
```

and every result grid uses those widths, on every open, every refresh, every
restart, until you change the file.

**What it is not:** it does not remember widths you drag with the mouse, and it
does not scope widths per table. Matching is by column header text, globally.
`id=55` applies to a column called `id` in every table of every connection.
Dragging a column still works and holds until the next auto-fit.

---

## Requirements

- DBeaver Community or Enterprise, installed (not the Eclipse plugin build)
- **A JDK 21 or newer.** DBeaver ships a cut-down JRE with no compiler, so you
  need a real JDK — [Temurin](https://adoptium.net) is fine. It is only needed to
  apply the patch, not to run DBeaver afterwards.
- Write access to the installation directory. A per-user install (Windows
  `%LOCALAPPDATA%\DBeaver`) needs nothing special; a system-wide install needs
  Administrator on Windows or `sudo` on Linux.

## Install

1. Close DBeaver.

2. Run the patcher (single-file Java, no build step):

   ```
   java DBeaverFixedWidths.java
   ```

   It finds your installation, reads its version, downloads the matching
   `GridColumn.java` from the official DBeaver repository, applies two edits,
   compiles it against your own installation's jars, backs the plugin jar up to
   `<jar>.bak` and writes the new classes in.

   If auto-detection fails, point it at the installation:

   ```
   java DBeaverFixedWidths.java --install "/opt/dbeaver"
   ```

   On Linux with a system-wide install: `sudo java DBeaverFixedWidths.java`.

3. Create `dbeaver-widths.properties` in your home directory
   (`~/dbeaver-widths.properties`, on Windows `C:\Users\<you>\dbeaver-widths.properties`).
   Copy `dbeaver-widths.properties.example` as a starting point.

4. In DBeaver, set **Preferences → Editors → Data Editor → Appearance → Grid →
   "Max auto size column %"** to **100**. See *Gotchas* below — this one bites.

5. Start DBeaver **once** with `-clean`:

   ```
   dbeaver -clean
   ```

   OSGi caches loaded classes; without `-clean` it keeps serving the old one and
   the patch looks like it did nothing. Only needed after patching, not every day.

## Uninstall

```
java DBeaverFixedWidths.java --restore
```

then start DBeaver once with `-clean`. Or, without touching the jar at all: delete
or rename `dbeaver-widths.properties`. With no config file the patch returns
DBeaver's own calculated width, i.e. stock behaviour.

---

## Config file format

Plain `java.util.Properties`, read as **UTF-8**.

| | |
|---|---|
| key | the column header text as shown in the grid, case-insensitive |
| value | width in pixels, a bare integer — **no `px`** |
| `*` | fallback for columns with no entry of their own |
| no entry, no `*` | DBeaver computes the width as usual |
| `#` at line start | comment |

Edit the file, press **F5** on the grid. Changes apply immediately — the patch
reloads the file whenever its timestamp changes, so no restart and no `-clean`.

### Gotchas

**Spaces in a column name must be escaped with a backslash.** The `.properties`
format ends the key at the first unescaped space:

```properties
Example Value =140     # WRONG: key "Company", value "Name=140" - silently ignored
Example\ Value=140    # correct
```

Parentheses, dots and digits need no escaping. Only spaces.

**"Max auto size column %" must be 100.** DBeaver applies that setting *after*
the width is computed, clamping anything wider than the given share of the
viewport. At the 10–15% some people use, a `Description=400` column gets cut down
to a fraction of that and the patch looks broken.

**Errors in the config file are silent by design.** A typo, `150px` instead of
`150`, a locked file — the patch swallows the exception and returns DBeaver's
computed width. Nothing crashes, but nothing tells you either. If one column
refuses to obey, check the escaping, the `px`, and whether the header text really
is what you think (a SQL alias changes it).

### Custom config location

Set the `dbeaver.widths.file` system property to an absolute path, by adding a
line to `dbeaver.ini` among the VM arguments (anywhere after the `-vmargs` line):

```
-Ddbeaver.widths.file=/home/me/configs/grid-widths.properties
```

Unset, it defaults to `<user.home>/dbeaver-widths.properties`.

---

## How it works

`GridColumn.pack(GC, boolean)` is what DBeaver calls whenever it sizes a column:
it measures the header, measures the cells, folds in child columns and leaves the
result in a local `newWidth`, which it then applies:

```java
if (reflect) {
    setWidth(newWidth, false);
} else {
    this.width = newWidth;
}
```

The patch inserts one line immediately before that block:

```java
newWidth = applyFixedWidth(newWidth);
```

Every path that sizes a column — first load, refresh, reconnect, "fit column
widths" — goes through this one bottleneck, so overriding here covers all of them
without caring how the number was produced.

`applyFixedWidth` is the second edit, an added method that:

1. looks for the config file, and returns the computed width unchanged if it is
   not there (so deleting the file disables the patch);
2. loads it only when its `lastModified()` differs from the cached timestamp,
   which is what makes edits apply on F5 without re-reading the file for every
   column of every grid;
3. lowercases keys with `Locale.ROOT` and matches against
   `grid.getLabelProvider().getText(this)` — the header text, not the column
   index, so reordering columns or changing the query changes nothing;
4. falls back to `*`, then to DBeaver's number;
5. wraps everything in a `try/catch` that returns the computed width, so a broken
   config file can never break the grid.

The full injected code is in `DBeaverFixedWidths.java`, in the `INJECTED_METHOD`
constant — about 40 lines, all fully qualified so it cannot collide with the
imports of any DBeaver version.

### Why the patcher downloads the source

So that the compiled class always matches *your* DBeaver version, and so this
repository does not redistribute DBeaver's code. `GridColumn.java` is fetched from
`raw.githubusercontent.com/dbeaver/dbeaver/<version>/…` at the tag matching your
installation, patched in a temp directory, and compiled against the jars already
in your `plugins` folder.

---

## After every DBeaver update

**An update replaces the plugin jar and silently undoes the patch.** The symptom
is column widths going back to being computed. Fix:

1. close DBeaver
2. `java DBeaverFixedWidths.java`
3. start DBeaver once with `-clean`

The tool is safe to run repeatedly: if it finds an already-patched jar it restores
the pristine copy from `.bak` first, so you never stack patches. If anything fails
— bad version tag, compile error, missing JDK — it exits before touching the jar.

An orphaned `.bak` from the previous version is left behind in `plugins` after an
update. Harmless; delete it if it bothers you.

### When the anchors stop matching

If DBeaver rewrites `GridColumn.java`, the tool stops with
`anchor 1/2 occurs N time(s) … expected exactly 1` and changes nothing. The two
edits then have to be made by hand against the new source:

- insert `newWidth = applyFixedWidth(newWidth);` immediately before the final
  `if (reflect) { setWidth(...) } else { this.width = newWidth; }` block of
  `pack(GC, boolean)`
- paste the `applyFixedWidth` method (the `INJECTED_METHOD` constant) anywhere in
  the class

then compile against the installation's `plugins/*.jar` and put the resulting
`GridColumn*.class` back into the jar.

---

## Command reference

```
java DBeaverFixedWidths.java [options]

  --install <dir>        DBeaver installation directory (auto-detected if omitted)
  --version <ver>        version / git tag to fetch the source from
                         (read from the installation's readme.txt if omitted)
  --jar <file>           patch this jar instead of the installation's one
  --dry-run              download, patch and compile, but touch no jar
  --restore              restore the plugin jar from its .bak and exit
  --skip-process-check   do not refuse to run while DBeaver seems to be running
  --help
```

`--dry-run` is the safe way to check whether a new DBeaver version still works
with the patch: it exercises the download, both edits and the full compile, and
stops before writing anything.

## Tested on

- Windows 11, DBeaver CE **26.1.5** and **26.2.0**, Temurin JDK 25 — including a
  real in-place DBeaver update, after which re-running the tool restored the patch.
- The tool itself is plain OS-independent Java (it compiles through
  `javax.tools`, not by shelling out to `javac`, and edits the jar through the zip
  filesystem, not `jar uf`), and the Linux/macOS install paths are in
  `autoDetectInstall()`. Those paths have not been exercised on real machines —
  if auto-detection misses your install, pass `--install` and please report the
  path so it can be added.

## Limitations

- Matching is global by header text, not per table. Per-table widths would need
  the patch to reach `element` → the attribute binding → the entity name.
- Gone after every DBeaver update, by design of how the patch is delivered.
- Config errors fail silently.
- Enterprise/other editions ship the same plugin and should work, but only
  Community was tested.

## License

Apache License 2.0, matching DBeaver's own license — the injected method becomes
part of a derivative work of DBeaver's `GridColumn.java`. This repository contains
no DBeaver source code; the patcher downloads it from the upstream repository at
run time.
