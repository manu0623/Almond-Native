package io.github.almondnative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.provider.OpenableColumns;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Building a game on the phone: the recompiler turns the code the game runs
 * into C, the compiler it carries turns that into the game's own library, and
 * the library goes where the engine loads games' code from,
 * code/<title id>.so in the app's own storage, read only.
 *
 * A build takes minutes: the game's code is thousands of files. It runs here,
 * in a foreground service with a notification, so that leaving the app does
 * not end it, and the launcher shows the same progress while it is open.
 */
public final class BuildService extends Service {
    static final String TAG = "AlmondBuild";
    static final String ACTION_BUILD = "io.github.almondnative.BUILD";
    /** the shaders a session used, learned and listed, without rebuilding */
    static final String ACTION_SHADERS = "io.github.almondnative.SHADERS";
    static final String EXTRA_GAME = "game";

    /** what the launcher shows: idle, or the step a build is on */
    static volatile String stage = "idle";
    static volatile int percent = 0;
    /** how the last build ended, null until one has */
    static volatile String result;
    /** the title id the last build made, for the launcher to open its page */
    static volatile String builtGame;
    /** whether a game's shader lists are being built, which a start waits for */
    static volatile boolean preparing;
    /** when that wait started, so a run that died cannot hold every start */
    static volatile long preparingSince;

    /**
     * whether shaders are being built now. The flag is dropped by the thread
     * that built them and by the service going; one left behind by a process
     * that was killed with it is no wait, and a quarter of an hour is longer
     * than any list this app builds.
     */
    static boolean isPreparing() {
        return preparing && System.currentTimeMillis() - preparingSince < 15 * 60 * 1000L;
    }
    /** where the shader service says how it went, read by the launcher's dialog */
    static final String SHADERS_RESULT = "shaders-result.txt";

    private static final int NOTIFICATION = 0x6a6d;
    /** "recompiling, 36 of 72" */
    private static final Pattern FILES = Pattern.compile("(\\d+)\\s+of\\s+(\\d+)");
    /** what the notification says already, so it is only posted when that changes */
    private int posted = -1;
    private String postedStage;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }
        String game = intent.getStringExtra(EXTRA_GAME);
        if (game == null || !(ACTION_SHADERS.equals(intent.getAction()) || ACTION_BUILD.equals(intent.getAction()))) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_SHADERS.equals(intent.getAction())) {
            preparing = true;
            preparingSince = System.currentTimeMillis();
            startForeground(NOTIFICATION, shown("Preparing the shaders", 0));
            new Thread(() -> {
                boolean built;
                try {
                    built = shaderLists(new File(game));
                } catch (Throwable error) {
                    Log.e(TAG, "the shaders were not prepared", error);
                    built = false;
                }
                // where the launcher's dialog reads it, before a start is let through
                try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), SHADERS_RESULT))) {
                    out.write((built ? "ready" : "failed").getBytes(StandardCharsets.UTF_8));
                } catch (IOException error) {
                    Log.w(TAG, "could not write " + SHADERS_RESULT + ", the launcher will not say how it went", error);
                }
                preparing = false;
                // a card that did its work silently looks like one that did nothing
                stopForeground(false);
                getSystemService(NotificationManager.class).notify(NOTIFICATION,
                        shown(built ? "Shader list is ready. Building the shaders now." : "The shader list was not rebuilt. Play the game once, then try again.", built ? 100 : 0));
                stopSelf();
            }, "shaders").start();
            return START_NOT_STICKY;
        }
        Uri uri = Uri.parse(game);
        builtGame = null;
        result = null;
        startForeground(NOTIFICATION, shown("Building a game", 0));
        new Thread(() -> {
            try {
                result = build(uri);
            } catch (Throwable error) {
                Log.e(TAG, "the build failed", error);
                result = "The game did not compile: " + error;
            }
            stage = "done";
            // where the launcher shows it: a notification is easy to miss, and
            // Android shows none at all until the permission is given
            try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), "build-result.txt"))) {
                out.write(result.getBytes(StandardCharsets.UTF_8));
            } catch (IOException error) {
                Log.w(TAG, "could not write build-result.txt, the launcher will not say how it went", error);
            }
            getSystemService(NotificationManager.class).notify(NOTIFICATION, shown(result, 100));
            stopForeground(false);
            stopSelf();
        }, "build").start();
        return START_NOT_STICKY;
    }

    // ---- the build

    /** the game's library, built and installed, its name for the person */
    private String build(Uri uri) throws Exception {
        // an APK made without the toolchain's jni directory looks like any
        // other, and the recompiler is only missing once a build is tried
        if (!Toolchain.recompiler(this).isFile()) {
            throw new IOException("this build of the app carries no compiler: " + Toolchain.recompiler(this) + " is not there");
        }
        say(1, "unpacking the compiler");
        Toolchain.unpack(this);
        Toolchain.links(this);

        // beside the games, where adb can read it: a build that failed is
        // looked at with "adb shell cat .../files/build-log.txt"
        File log = new File(getExternalFilesDir(null), "build-log.txt");
        File games = new File(gamesVolume(), "games");
        games.mkdirs();
        // a game is up to 4 GB, and a copy that runs out of room halfway
        // leaves a dump that reads as cut short
        long size = size(uri);
        if (size > 0 && games.getUsableSpace() < size + FREE_MARGIN) {
            throw new IOException("there is not enough free space for this game: it needs "
                    + megabytes(size + FREE_MARGIN) + " MB and the storage has " + megabytes(games.getUsableSpace()) + " MB");
        }
        // copied under a name of its own first: which game it is, and whether
        // it can be read at all, is only known once the recompiler has looked
        File incoming = new File(games, ".incoming-" + System.currentTimeMillis() + ".rom");
        String id, name;
        File folder, rom;
        try {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("could not open that file");
                }
                say(10, "copying the game");
                try (OutputStream out = new FileOutputStream(incoming)) {
                    copy(in, out, 10, 26);
                }
            }
            // a .3ds, a .cia or a .cxi, decrypted: the recompiler tells them
            // apart and says what is wrong with one it cannot read
            id = identify(incoming);
            name = name(id);
            folder = new File(games, id);
            folder.mkdirs();
            rom = new File(folder, "game.3ds");
            rom.delete();
            if (!incoming.renameTo(rom)) {
                throw new IOException("could not move the game into " + folder);
            }
        } finally {
            incoming.delete();
        }

        // the font the kit puts beside every game it builds, so that a title
        // has the characters it asks the system for
        say(27, "adding the system font");
        File sysdata = new File(folder, "sysdata");
        sysdata.mkdirs();
        try (InputStream in = getAssets().open("shared_font.bin");
             OutputStream out = new FileOutputStream(new File(sysdata, "shared_font.bin"))) {
            copy(in, out, 27, 28);
        }

        File out = new File(new File(getFilesDir(), "recomp"), id);
        Toolchain.deleteAll(out);
        out.mkdirs();
        // the shaders the kit lists, before the code: they come from the
        // game's files alone, and vertex.txt is what the renderer builds its
        // programs from the first time it needs them
        say(28, "collecting the shaders");
        // a session's shaders learned first, so this build's precache list has
        // what the game was seen to draw
        File recorded = new File(new File(folder, "data"), "shaders.txt");
        if (recorded.isFile()) {
            run("learn", Arrays.asList(Toolchain.recompiler(this).getAbsolutePath(), "learn", rom.getAbsolutePath(), recorded.getAbsolutePath(), new File(folder, LEARNED).getAbsolutePath()));
        }
        shaders(rom, folder);
        say(28, "recompiling " + name);
        recompile(rom, out, log);

        File built = new File(out, id + ".so");
        if (!built.isFile()) {
            throw new IOException("the recompiler made no " + id + ".so, the log is " + log);
        }
        say(94, "installing " + name);
        File code = new File(new File(getFilesDir(), "code"), id + ".so");
        code.getParentFile().mkdirs();
        code.setWritable(true);
        code.delete();
        try (InputStream in = new FileInputStream(built); OutputStream to = new FileOutputStream(code)) {
            copy(in, to, 94, 99);
        }
        // where Android loads code from, and nowhere a game may write
        code.setReadOnly();
        Toolchain.deleteAll(out);
        builtGame = id;
        say(100, name + " is ready to play");
        return name + " compiled successfully. Press Play to start it.";
    }

    /**
     * 3dsrecomp's look through the game's files for the shaders they hold,
     * into vertex/ beside the game as the kit leaves them: vertex.txt is what
     * the renderer makes its programs from, precache.txt the ones it hands
     * out at the start. A game it cannot read still plays, its shaders built
     * as it needs them, so this never fails a build.
     */
    private void shaders(File rom, File folder) {
        File vertex = new File(folder, "vertex");
        vertex.mkdirs();
        // the title's learned list, when a session has left one: with it the
        // recompiler also writes precache.txt, the programs the engine builds
        // on the start's screen rather than in game
        File learned = new File(folder, LEARNED);
        List<String> command = new ArrayList<>();
        command.add(Toolchain.recompiler(this).getAbsolutePath());
        command.add("shaders");
        command.add(rom.getAbsolutePath());
        command.add(vertex.getAbsolutePath());
        // precache is asked for whatever the title learned: with no list the
        // recompiler writes the one the game's own files hold
        command.add("precache");
        if (learned.isFile()) {
            command.add(learned.getAbsolutePath());
        }
        run("shaders", command);
    }

    /** the file a run's shaders are learned into, one of these a game */
    private static final String LEARNED = "title-shaders.txt";

    /**
     * the service going takes its wait with it, whether the thread finished
     * or the system took it: nothing is left saying shaders are being built.
     */
    @Override
    public void onDestroy() {
        preparing = false;
        super.onDestroy();
    }

    /**
     * a session's shaders, learned into the title's list, and the precache
     * list written from it: the vertex programs the game's files hold are all
     * known before a start, but the programs the game's drawing state makes
     * are only known once it has drawn, which is what a run's shaders.txt
     * records. A start then builds them all before the first frame.
     */
    private boolean shaderLists(File folder) throws IOException {
        File rom = new File(folder, "game.3ds");
        File recorded = new File(new File(folder, "data"), "shaders.txt");
        File learned = new File(folder, LEARNED);
        File precache = new File(new File(folder, "vertex"), "precache.txt");
        if (!rom.isFile()) {
            return false;
        }
        Toolchain.unpack(this);
        // a title that learned nothing has no run's list to add to its own
        if (recorded.isFile() && (!learned.isFile() || learned.lastModified() < recorded.lastModified())) {
            run("learn", Arrays.asList(Toolchain.recompiler(this).getAbsolutePath(), "learn", rom.getAbsolutePath(), recorded.getAbsolutePath(), learned.getAbsolutePath()));
        }
        if (!precache.isFile() || (recorded.isFile() && precache.lastModified() < recorded.lastModified())) {
            shaders(rom, folder);
        }
        return precache.isFile();
    }

    /** 3dsrecomp, its output to the log, a step that fails leaving the rest be */
    private void run(String what, List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Toolchain.applyTo(builder, this);
        try {
            Process process = builder.start();
            try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = lines.readLine()) != null) {
                    Log.i(TAG, what + ": " + line);
                }
            }
            int status = process.waitFor();
            if (status != 0) {
                Log.w(TAG, what + " stopped with " + status + ", the shaders get built as the game needs them");
            }
        } catch (IOException error) {
            Log.w(TAG, what + " did not finish, the shaders get built as the game needs them", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
    }

    /** 3dsrecomp, its output read for how far along it is */
    private void recompile(File rom, File out, File log) throws Exception {
        List<String> command = new ArrayList<>();
        command.add(Toolchain.recompiler(this).getAbsolutePath());
        command.add("build");
        command.add(rom.getAbsolutePath());
        command.add(out.getAbsolutePath());
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(rom.getParentFile());
        builder.redirectErrorStream(true);
        Toolchain.applyTo(builder, this);
        Process process = builder.start();
        try (Writer into = new OutputStreamWriter(new FileOutputStream(log), StandardCharsets.UTF_8);
             BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                into.write(line);
                into.write('\n');
                Matcher files = FILES.matcher(line);
                if (files.find()) {
                    int done = Integer.parseInt(files.group(1));
                    int all = Integer.parseInt(files.group(2));
                    if (all > 0) {
                        say(28 + Math.min(64, 64 * done / all), "recompiling, " + done + " of " + all + " files");
                    }
                } else if (line.startsWith("==")) {
                    say(percent, line.substring(2).trim());
                }
            }
        }
        int status = process.waitFor();
        if (status != 0) {
            throw new IOException("the recompiler stopped with " + status + ", the log is " + log);
        }
    }

    // ---- the game's folder, as the launcher reads it

    /** the volume with the most room, which is where the launcher looks too */
    private File gamesVolume() throws IOException {
        File best = null;
        for (File dir : getExternalFilesDirs(null)) {
            if (dir != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(dir))
                    && (best == null || dir.getUsableSpace() > best.getUsableSpace())) {
                best = dir;
            }
        }
        if (best == null) {
            throw new IOException("no storage to put the game on");
        }
        return best;
    }

    /** the game's name: its profile's, or its title id */
    private String name(String id) {
        File profile = new File(new File(new File(getFilesDir(), "titles"), id), "profile.toml");
        try (InputStream in = new FileInputStream(profile)) {
            for (String line : new String(read(in, (int) profile.length()), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("name = \"") && line.trim().endsWith("\"")) {
                    return line.trim().substring(8, line.trim().length() - 1);
                }
            }
        } catch (IOException ignored) {
            // a game with no profile of its own
        }
        return id;
    }

    /** room left over beside a game, for its saves, shaders and log */
    private static final long FREE_MARGIN = 256L << 20;

    private static long megabytes(long bytes) {
        return bytes >> 20;
    }

    /** the file's size, as its provider tells it, or -1 when it does not */
    private long size(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[] {OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getLong(0);
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "could not ask how big " + uri + " is", error);
        }
        return -1;
    }

    /**
     * the program id a dump's library is named after, from 3dsrecomp id: it
     * reads a cartridge image, a .cia and a bare .cxi, and refuses an
     * encrypted or a cut short one with a message that says so
     */
    private String identify(File rom) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(Toolchain.recompiler(this).getAbsolutePath(), "id", rom.getAbsolutePath());
        builder.redirectErrorStream(true);
        Toolchain.applyTo(builder, this);
        Process process = builder.start();
        StringBuilder said = new StringBuilder();
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = lines.readLine()) != null) {
                said.append(line.trim()).append('\n');
            }
        }
        String output = said.toString().trim();
        if (process.waitFor() != 0) {
            throw new IOException("that file is not a game this app can build: " + output);
        }
        if (!output.matches("[0-9A-F]{16}")) {
            throw new IOException("the recompiler did not name the game: " + output);
        }
        return output;
    }

    // ---- reading, writing and saying how it goes

    private static byte[] read(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int at = 0;
        while (at < count) {
            int read = in.read(bytes, at, count - at);
            if (read < 0) {
                throw new IOException("that file ended before it should have");
            }
            at += read;
        }
        return bytes;
    }

    /** everything left, a percent of it for every 4 MB */
    private void copy(InputStream in, OutputStream out, int from, int to) throws IOException {
        byte[] buffer = new byte[1 << 20];
        long done = 0;
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
            done += read;
            say(Math.min(to, from + (int) (done >> 22)), null);
        }
    }

    private void say(int at, String what) {
        percent = Math.max(0, Math.min(100, at));
        if (what != null) {
            stage = what;
        }
        // the recompiler says more than the notification has room to
        if (percent == posted && stage.equals(postedStage)) {
            return;
        }
        posted = percent;
        postedStage = stage;
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION, shown(stage, percent));
        }
    }

    private Notification shown(String what, int at) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel("build") == null) {
            manager.createNotificationChannel(new NotificationChannel("build", "Building games", NotificationManager.IMPORTANCE_LOW));
        }
        return new Notification.Builder(this, "build")
                .setContentTitle("Almond Native")
                .setContentText(what)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setProgress(100, at, at <= 0)
                .setOngoing(at > 0 && at < 100)
                .build();
    }
}
