# Ready-to-paste comment for issue #41318

Everything below the line is the comment body. **Before posting, replace
`<LINK>` with wherever you publish the tool** (a gist or a small repo with
`DBeaverFixedWidths.java`, `README.md` and `dbeaver-widths.properties.example`).

If you would rather not host anything, delete the `<LINK>` sentence and paste the
`applyFixedWidth` method inline instead — the `<details>` block already contains
everything needed to reproduce the patch by hand.

---

Since this keeps coming up and keeps not getting implemented, here is a working
workaround for anyone who just wants their columns to stop resizing themselves.

It is not width *persistence* — it does not remember what you drag. It is fixed
widths from a config file, keyed by column header name:

```properties
*=110
id=55
Company\ Name=140
Description=190
```

Save the file, press F5 on the grid, the widths apply. They survive refresh,
reconnect and restart, because they are no longer computed at all.

**How it works.** `GridColumn.pack(GC, boolean)` computes a width and leaves it in
a local `newWidth` just before applying it:

```java
if (reflect) {
    setWidth(newWidth, false);
} else {
    this.width = newWidth;
}
```

One line inserted immediately above that block overrides it:

```java
newWidth = applyFixedWidth(newWidth);
```

Every sizing path — initial load, refresh, reconnect, "fit column widths" — passes
through there, so that single hook covers all of them. The added method reads a
properties file, caches it until its `lastModified()` changes (hence F5 being
enough), and matches on `grid.getLabelProvider().getText(this)`, i.e. the header
text rather than the column index. Any failure falls through to the width DBeaver
computed, so a broken config file cannot break the grid.

<details>
<summary>The added method (the whole patch, besides that one line)</summary>

```java
private static java.util.Properties fixedWidths = null;
private static long fixedWidthsStamp = -1;

private int applyFixedWidth(int calculated) {
    try {
        String custom = System.getProperty("dbeaver.widths.file");
        java.io.File f = (custom == null || custom.isEmpty())
            ? new java.io.File(System.getProperty("user.home"), "dbeaver-widths.properties")
            : new java.io.File(custom);
        if (!f.exists()) {
            return calculated;
        }
        if (f.lastModified() != fixedWidthsStamp) {
            java.util.Properties raw = new java.util.Properties();
            try (java.io.Reader r = new java.io.InputStreamReader(
                    new java.io.FileInputStream(f), java.nio.charset.StandardCharsets.UTF_8)) {
                raw.load(r);
            }
            java.util.Properties norm = new java.util.Properties();
            for (String k : raw.stringPropertyNames()) {
                norm.setProperty(k.trim().toLowerCase(java.util.Locale.ROOT), raw.getProperty(k));
            }
            fixedWidths = norm;
            fixedWidthsStamp = f.lastModified();
        }
        String name = grid.getLabelProvider().getText(this);
        String v = name == null
            ? null
            : fixedWidths.getProperty(name.trim().toLowerCase(java.util.Locale.ROOT));
        if (v == null) {
            v = fixedWidths.getProperty("*");
        }
        if (v != null) {
            return Integer.parseInt(v.trim());
        }
    } catch (Exception e) {
        // deliberately silent - fall back to the width DBeaver computed
    }
    return calculated;
}
```

</details>

**Applying it.** Since this means recompiling one class and swapping it into
`plugins/org.jkiss.dbeaver.ui.editors.data_*.jar`, I wrote a single-file Java tool
that does the whole thing: <LINK>

```
java DBeaverFixedWidths.java
```

It finds the installation, reads its version, downloads the matching
`GridColumn.java` from this repo at that tag, applies the two edits, compiles
against your own `plugins/*.jar`, backs the jar up to `<jar>.bak` and writes the
classes in. `--dry-run` does everything except touching the jar, `--restore` undoes
it. Needs a JDK 21+ — the JRE bundled with DBeaver has no compiler. Start DBeaver
once with `-clean` afterwards so OSGi drops its cached copy of the old class.

**Caveats, honestly:**

- It patches an installed application. Unofficial, unsupported, obviously not
  something to run on anything you cannot reinstall. A backup is automatic and
  `--restore` puts it back.
- **Every DBeaver update replaces the jar and silently undoes it.** Re-run the tool
  and restart with `-clean`. I have been through one real update this way; the
  tool re-applied it unchanged.
- Matching is global by header text, not per table: `id=55` hits every `id` column
  everywhere.
- Set **Preferences → Editors → Data Editor → Appearance → Grid → "Max auto size
  column %"** to 100, otherwise it clamps wide columns afterwards and this looks
  broken.
- Spaces in a column name must be backslash-escaped in the properties file
  (`Company\ Name=140`) — otherwise the key silently ends at the first space.

Tested on Windows with CE 26.1.5 and 26.2.0.

Obviously I would rather delete all of this and use a checkbox in Preferences.
The hook is one line in one method, if a maintainer ever wants it.
