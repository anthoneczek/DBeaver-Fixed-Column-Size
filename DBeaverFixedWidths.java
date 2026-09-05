/*
 * DBeaverFixedWidths - give DBeaver fixed, config-driven result-grid column widths.
 *
 * Patches org.jkiss.dbeaver.ui.controls.lightgrid.GridColumn inside an installed
 * DBeaver so that column widths are read from a properties file instead of being
 * recomputed on every refresh / reconnect / restart.
 *
 * This file contains NO DBeaver code. It downloads GridColumn.java from the
 * official DBeaver repository at the tag matching your installation, applies two
 * small edits, compiles it against your installation's own jars, and writes the
 * resulting classes back into the plugin jar (after backing it up).
 *
 * Requires a JDK 21 or newer (a JRE is not enough - we need the compiler).
 *
 *   java DBeaverFixedWidths.java [options]
 *
 * See README.md for details. Licensed under the Apache License 2.0.
 */

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class DBeaverFixedWidths {

    private static final String PLUGIN_JAR_PREFIX = "org.jkiss.dbeaver.ui.editors.data_";
    private static final String CLASS_PACKAGE_PATH = "org/jkiss/dbeaver/ui/controls/lightgrid";
    private static final String SOURCE_URL_TEMPLATE =
        "https://raw.githubusercontent.com/dbeaver/dbeaver/%s/plugins/"
            + "org.jkiss.dbeaver.ui.editors.data/src/org/jkiss/dbeaver/ui/controls/lightgrid/GridColumn.java";

    private static final String ANCHOR_PACK = "if (reflect) {";
    private static final String ANCHOR_METHOD = "private int computeCellWidth(GC gc, IGridRow row) {";
    private static final String INSERTED_CALL = "newWidth = applyFixedWidth(newWidth);";
    private static final String MARKER = "applyFixedWidth";

    /**
     * The code injected into GridColumn. Kept deliberately small and dependency-free:
     * everything is fully qualified so it cannot clash with the imports of any
     * DBeaver version, and every failure falls back to DBeaver's own calculation.
     */
    private static final String INJECTED_METHOD = """
            // --- fixed column widths patch ---------------------------------------
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
            // --- end of fixed column widths patch ---------------------------------

        """;

    // ---------------------------------------------------------------- options

    private static Path installDir;
    private static Path jarOverride;
    private static String version;
    private static boolean dryRun;
    private static boolean restore;
    private static boolean skipProcessCheck;

    public static void main(String[] args) {
        try {
            parseArgs(args);
            run();
        } catch (UserError e) {
            System.err.println();
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        } catch (Exception e) {
            System.err.println();
            System.err.println("UNEXPECTED ERROR: " + e);
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--install" -> installDir = Paths.get(requireValue(args, ++i, "--install"));
                case "--jar" -> jarOverride = Paths.get(requireValue(args, ++i, "--jar"));
                case "--version" -> version = requireValue(args, ++i, "--version");
                case "--dry-run" -> dryRun = true;
                case "--restore" -> restore = true;
                case "--skip-process-check" -> skipProcessCheck = true;
                case "--help", "-h" -> {
                    printUsage();
                    System.exit(0);
                }
                default -> throw new UserError("unknown option: " + args[i] + " (try --help)");
            }
        }
    }

    private static String requireValue(String[] args, int i, String opt) {
        if (i >= args.length) {
            throw new UserError(opt + " needs a value");
        }
        return args[i];
    }

    private static void printUsage() {
        System.out.println("""
            DBeaverFixedWidths - fixed result-grid column widths for DBeaver

              java DBeaverFixedWidths.java [options]

            Options:
              --install <dir>   DBeaver installation directory (auto-detected if omitted)
              --version <ver>   DBeaver version, i.e. the git tag to fetch the source from
                                (read from the installation's readme.txt if omitted)
              --jar <file>      patch this jar instead of the one in the installation
                                (for testing; the installation is still used for the classpath)
              --dry-run         download, patch and compile, but do not touch any jar
              --restore         restore the plugin jar from its .bak and exit
              --skip-process-check   do not refuse to run while DBeaver seems to be running
              --help            this text

            Run it again after every DBeaver update - updates replace the plugin jar
            and silently undo the patch.""");
    }

    // -------------------------------------------------------------- main flow

    private static void run() throws Exception {
        System.out.println("DBeaverFixedWidths");
        System.out.println("==================");

        installDir = (installDir != null) ? installDir : autoDetectInstall();
        require(Files.isDirectory(installDir), "not a directory: " + installDir);
        System.out.println("installation : " + installDir);

        Path pluginsDir = installDir.resolve("plugins");
        require(Files.isDirectory(pluginsDir),
            "no 'plugins' directory under " + installDir + " - is this really a DBeaver installation?");

        Path jar = (jarOverride != null) ? jarOverride : findPluginJar(pluginsDir);
        Path backup = Paths.get(jar + ".bak");
        System.out.println("plugin jar   : " + jar.getFileName());

        if (restore) {
            restoreFromBackup(jar, backup);
            return;
        }

        if (!skipProcessCheck && !dryRun) {
            require(!looksLikeDBeaverIsRunning(),
                "DBeaver appears to be running. Close it first "
                    + "(or pass --skip-process-check if this is a false positive).");
        }

        String ver = (version != null) ? version : readVersion(installDir);
        String release = readRequiredJavaVersion(installDir);
        System.out.println("version      : " + ver);
        System.out.println("bytecode     : Java " + release);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        require(compiler != null,
            "no Java compiler available - you are running a JRE. Install a JDK 21+ "
                + "(https://adoptium.net) and run this tool with its 'java'.");

        // 1. fetch --------------------------------------------------------
        String url = SOURCE_URL_TEMPLATE.formatted(ver);
        System.out.println();
        System.out.println("[1/5] downloading GridColumn.java for tag " + ver);
        String source = download(url);
        System.out.println("      ok, " + source.lines().count() + " lines");

        // 2. patch --------------------------------------------------------
        System.out.println("[2/5] applying the two edits");
        String patched = patchSource(source, ver);
        Path work = Files.createTempDirectory("dbeaver-fixed-widths");
        Path srcDir = work.resolve("src");
        Path outDir = work.resolve("out");
        Files.createDirectories(srcDir);
        Files.createDirectories(outDir);
        Path srcFile = srcDir.resolve("GridColumn.java");
        Files.writeString(srcFile, patched, StandardCharsets.UTF_8);
        System.out.println("      ok, patched source in " + srcFile);

        // 3. compile ------------------------------------------------------
        System.out.println("[3/5] compiling against the installation's jars");
        String classpath = buildClasspath(pluginsDir);
        compile(compiler, srcFile, outDir, classpath, release);
        List<Path> classes = collectClasses(outDir);
        require(!classes.isEmpty(), "compilation produced no class files");
        System.out.println("      ok, " + classes.size() + " class file(s): "
            + classes.stream().map(p -> p.getFileName().toString()).collect(Collectors.joining(", ")));

        if (dryRun) {
            System.out.println("[4/5] dry run - jar left untouched");
            System.out.println("[5/5] dry run - nothing to verify");
            System.out.println();
            System.out.println("Dry run finished successfully. Re-run without --dry-run to apply.");
            return;
        }

        // 4. backup + write ------------------------------------------------
        System.out.println("[4/5] backing up and writing into the jar");
        prepareJar(jar, backup);
        writeClassesIntoJar(jar, outDir, classes);
        System.out.println("      ok, backup at " + backup.getFileName());

        // 5. verify ---------------------------------------------------------
        System.out.println("[5/5] verifying");
        require(jarContainsPatchedClass(jar),
            "verification failed - the patched class is not in the jar. Restore with: --restore");
        System.out.println("      ok, patched GridColumn is inside the jar");

        System.out.println();
        System.out.println("Done. Now:");
        System.out.println("  1. create " + defaultConfigPath() + " (see README)");
        System.out.println("  2. set Preferences > Editors > Data Editor > Appearance > Grid");
        System.out.println("     > 'Max auto size column %' to 100");
        System.out.println("  3. start DBeaver ONCE with -clean so OSGi drops its cached copy of the old class");
        System.out.println();
        System.out.println("To undo: java DBeaverFixedWidths.java --restore");
    }

    // ---------------------------------------------------------------- steps

    private static Path autoDetectInstall() {
        List<Path> candidates = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String home = System.getProperty("user.home");

        if (os.contains("win")) {
            String localAppData = System.getenv("LOCALAPPDATA");
            if (localAppData != null) {
                candidates.add(Paths.get(localAppData, "DBeaver"));
                candidates.add(Paths.get(localAppData, "DBeaverEE"));
            }
            String programFiles = System.getenv("ProgramFiles");
            if (programFiles != null) {
                candidates.add(Paths.get(programFiles, "DBeaver"));
                candidates.add(Paths.get(programFiles, "DBeaverEE"));
            }
        } else if (os.contains("mac")) {
            candidates.add(Paths.get("/Applications/DBeaver.app/Contents/Eclipse"));
            candidates.add(Paths.get("/Applications/DBeaverEE.app/Contents/Eclipse"));
            candidates.add(Paths.get(home, "Applications/DBeaver.app/Contents/Eclipse"));
        } else {
            candidates.add(Paths.get("/usr/share/dbeaver-ce"));
            candidates.add(Paths.get("/usr/share/dbeaver"));
            candidates.add(Paths.get("/usr/share/dbeaver-ee"));
            candidates.add(Paths.get("/opt/dbeaver"));
            candidates.add(Paths.get("/opt/dbeaver-ce"));
            candidates.add(Paths.get("/opt/dbeaverce"));
            candidates.add(Paths.get(home, ".local/share/dbeaver"));
            candidates.add(Paths.get("/snap/dbeaver-ce/current"));
        }

        for (Path c : candidates) {
            if (Files.isDirectory(c.resolve("plugins")) && hasPluginJar(c.resolve("plugins"))) {
                return c;
            }
        }
        throw new UserError("could not find a DBeaver installation. Looked in:\n  "
            + candidates.stream().map(Path::toString).collect(Collectors.joining("\n  "))
            + "\nPass it explicitly: --install <dir>");
    }

    private static boolean hasPluginJar(Path pluginsDir) {
        try (Stream<Path> s = Files.list(pluginsDir)) {
            return s.anyMatch(DBeaverFixedWidths::isPluginJar);
        } catch (IOException e) {
            return false;
        }
    }

    private static boolean isPluginJar(Path p) {
        String n = p.getFileName().toString();
        return n.startsWith(PLUGIN_JAR_PREFIX) && n.endsWith(".jar");
    }

    private static Path findPluginJar(Path pluginsDir) throws IOException {
        try (Stream<Path> s = Files.list(pluginsDir)) {
            List<Path> found = s.filter(DBeaverFixedWidths::isPluginJar)
                .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                .toList();
            require(!found.isEmpty(), "no " + PLUGIN_JAR_PREFIX + "*.jar in " + pluginsDir);
            if (found.size() > 1) {
                System.out.println("      note: several matching jars, using the last one: "
                    + found.get(found.size() - 1).getFileName());
            }
            return found.get(found.size() - 1);
        }
    }

    private static String readVersion(Path install) throws IOException {
        Path readme = install.resolve("readme.txt");
        if (Files.isRegularFile(readme)) {
            Pattern p = Pattern.compile("^\\s*(\\d+\\.\\d+\\.\\d+)\\s*$");
            for (String line : Files.readAllLines(readme, StandardCharsets.UTF_8)) {
                Matcher m = p.matcher(line);
                if (m.matches()) {
                    return m.group(1);
                }
            }
        }
        throw new UserError("could not read the version from " + readme
            + ". Check Help > About and pass it: --version 26.1.5");
    }

    private static String readRequiredJavaVersion(Path install) throws IOException {
        Path ini = install.resolve("dbeaver.ini");
        if (Files.isRegularFile(ini)) {
            Matcher m = Pattern.compile("requiredJavaVersion=(\\d+)")
                .matcher(Files.readString(ini, StandardCharsets.UTF_8));
            if (m.find()) {
                return m.group(1);
            }
        }
        return "21";
    }

    private static String download(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() == 404) {
            throw new UserError("404 from GitHub - that version tag does not exist in the DBeaver repo.\n"
                + "  " + url + "\nCheck Help > About and pass the exact version: --version <ver>");
        }
        require(resp.statusCode() == 200, "HTTP " + resp.statusCode() + " for " + url);
        return resp.body();
    }

    /**
     * Injects the two edits. Both anchors must occur exactly once; anything else
     * means the upstream file changed and a human needs to look at it.
     */
    private static String patchSource(String source, String ver) {
        require(!source.contains(MARKER), "the downloaded source already contains " + MARKER
            + " - that should be impossible, aborting instead of guessing");

        List<String> lines = new ArrayList<>(source.lines().toList());
        int packIdx = singleIndexOf(lines, ANCHOR_PACK, 1, ver);
        int methodIdx = singleIndexOf(lines, ANCHOR_METHOD, 2, ver);

        String indent = leadingWhitespace(lines.get(packIdx));

        List<String> out = new ArrayList<>(lines.size() + 40);
        for (int i = 0; i < lines.size(); i++) {
            if (i == packIdx) {
                out.add(indent + INSERTED_CALL);
            }
            if (i == methodIdx) {
                out.addAll(INJECTED_METHOD.lines().toList());
            }
            out.add(lines.get(i));
        }
        System.out.println("      anchor 1 at line " + (packIdx + 1) + ", anchor 2 at line " + (methodIdx + 1));
        return String.join("\n", out) + "\n";
    }

    private static int singleIndexOf(List<String> lines, String anchor, int which, String ver) {
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(anchor)) {
                hits.add(i);
            }
        }
        if (hits.size() != 1) {
            throw new UserError("anchor " + which + " (\"" + anchor + "\") occurs " + hits.size()
                + " time(s) in GridColumn.java of DBeaver " + ver + ", expected exactly 1.\n"
                + "Upstream changed the file; the two edits have to be made by hand. See README > "
                + "'When the anchors stop matching'.");
        }
        return hits.get(0);
    }

    private static String leadingWhitespace(String line) {
        int i = 0;
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) {
            i++;
        }
        return line.substring(0, i);
    }

    private static String buildClasspath(Path pluginsDir) throws IOException {
        try (Stream<Path> s = Files.list(pluginsDir)) {
            List<String> jars = s.filter(p -> p.getFileName().toString().endsWith(".jar"))
                .map(Path::toString)
                .sorted()
                .toList();
            require(!jars.isEmpty(), "no jars in " + pluginsDir);
            System.out.println("      " + jars.size() + " jars on the classpath");
            return String.join(java.io.File.pathSeparator, jars);
        }
    }

    private static void compile(JavaCompiler compiler, Path srcFile, Path outDir, String classpath, String release) {
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<String> options = List.of(
                "--release", release,
                "-nowarn",
                "-proc:none",
                "-encoding", "UTF-8",
                "-classpath", classpath,
                "-d", outDir.toString());
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(srcFile.toFile());
            boolean ok = compiler.getTask(null, fm, diagnostics, options, null, units).call();
            if (!ok) {
                StringBuilder sb = new StringBuilder("compilation failed:\n");
                for (Diagnostic<? extends JavaFileObject> d : diagnostics.getDiagnostics()) {
                    if (d.getKind() == Diagnostic.Kind.ERROR) {
                        sb.append("  ").append(d.getLineNumber()).append(": ")
                          .append(d.getMessage(Locale.ROOT)).append('\n');
                    }
                }
                sb.append("The jar was NOT touched. This usually means the DBeaver API the patch "
                    + "relies on changed in this version.");
                throw new UserError(sb.toString());
            }
        } catch (IOException e) {
            throw new UserError("compiler I/O error: " + e.getMessage());
        }
    }

    private static List<Path> collectClasses(Path outDir) throws IOException {
        try (Stream<Path> s = Files.walk(outDir)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".class")).sorted().toList();
        }
    }

    /**
     * Makes sure we start from a pristine jar and that a backup exists.
     * Running the tool twice on the same installation is therefore safe.
     */
    private static void prepareJar(Path jar, Path backup) throws IOException {
        boolean alreadyPatched = jarContainsPatchedClass(jar);
        if (alreadyPatched) {
            require(Files.isRegularFile(backup),
                "the jar is already patched but " + backup.getFileName() + " is missing, so there is no "
                    + "pristine copy to work from. Reinstall DBeaver (or restore the jar) and try again.");
            System.out.println("      already patched - restoring the pristine jar from the backup first");
            Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.copy(jar, backup, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeClassesIntoJar(Path jar, Path outDir, List<Path> classes) throws IOException {
        try (FileSystem fs = FileSystems.newFileSystem(jar)) {
            for (Path cls : classes) {
                String rel = outDir.relativize(cls).toString().replace('\\', '/');
                Path target = fs.getPath(rel);
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.copy(cls, target, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("      + " + rel);
            }
        }
    }

    private static boolean jarContainsPatchedClass(Path jar) throws IOException {
        if (!Files.isRegularFile(jar)) {
            return false;
        }
        try (FileSystem fs = FileSystems.newFileSystem(jar)) {
            Path entry = fs.getPath(CLASS_PACKAGE_PATH + "/GridColumn.class");
            if (!Files.exists(entry)) {
                return false;
            }
            byte[] data = Files.readAllBytes(entry);
            return indexOf(data, MARKER.getBytes(StandardCharsets.US_ASCII)) >= 0;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static void restoreFromBackup(Path jar, Path backup) throws IOException {
        require(Files.isRegularFile(backup), "no backup at " + backup);
        Files.copy(backup, jar, StandardCopyOption.REPLACE_EXISTING);
        System.out.println();
        System.out.println("Restored " + jar.getFileName() + " from " + backup.getFileName() + ".");
        System.out.println("Start DBeaver once with -clean.");
    }

    private static boolean looksLikeDBeaverIsRunning() {
        try {
            return ProcessHandle.allProcesses()
                .map(ProcessHandle::info)
                .map(i -> i.command().orElse(""))
                .anyMatch(cmd -> {
                    String name = cmd.substring(Math.max(cmd.lastIndexOf('/'), cmd.lastIndexOf('\\')) + 1);
                    return name.toLowerCase(Locale.ROOT).startsWith("dbeaver");
                });
        } catch (Exception e) {
            return false; // cannot inspect processes - let the file lock decide
        }
    }

    private static String defaultConfigPath() {
        return Paths.get(System.getProperty("user.home"), "dbeaver-widths.properties").toString();
    }

    // --------------------------------------------------------------- helpers

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new UserError(message);
        }
    }

    private static final class UserError extends RuntimeException {
        UserError(String message) {
            super(message);
        }
    }
}
