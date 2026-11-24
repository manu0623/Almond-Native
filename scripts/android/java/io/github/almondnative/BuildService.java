package io.github.almondnative;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
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
    static final String EXTRA_GAME = "game";

    /** what the launcher shows: idle, or the step a build is on */
    static volatile String stage = "idle";
    static volatile int percent = 0;
    /** how the last build ended, null until one has */
    static volatile String result;

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
        if (intent == null || !ACTION_BUILD.equals(intent.getAction())) {
            return START_NOT_STICKY;
        }
        String game = intent.getStringExtra(EXTRA_GAME);
        if (game == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        Uri uri = Uri.parse(game);
        startForeground(NOTIFICATION, shown("Building a game", 0));
        new Thread(() -> {
            try {
                result = build(uri);
            } catch (Throwable error) {
                Log.e(TAG, "the build failed", error);
                result = "That did not work: " + error;
            }
            stage = "done";
            getSystemService(NotificationManager.class).notify(NOTIFICATION, shown(result, 100));
            stopForeground(false);
            stopSelf();
        }, "build").start();
        return START_NOT_STICKY;
    }

    // ---- the build

    /** the game's library, built and installed, its name for the person */
    private String build(Uri uri) throws Exception {
        say(1, "unpacking the compiler");
        Toolchain.unpack(this);

        // beside the games, where adb can read it: a build that failed is
        // looked at with "adb shell cat .../files/build-log.txt"
        File log = new File(getExternalFilesDir(null), "build-log.txt");
        String id, name;
        File folder, rom;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("could not open that file");
            }
            byte[] header = read(in, 0x110);
            id = titleId(header);
            name = name(id);
            // beside the games the kit's folders hold, where the launcher looks
            folder = new File(new File(gamesVolume(), "games"), id);
            folder.mkdirs();
            rom = new File(folder, "game.3ds");
            say(10, "copying " + name);
            try (OutputStream out = new FileOutputStream(rom)) {
                copy(in, out, 10, 26);
            }
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
        say(28, "recompiling " + name);
        recompile(rom, out, log);

        File built = new File(out, id + ".so");
        if (!built.isFile()) {
            throw new IOException("the recompiler made no " + id + ".so, its log is " + log);
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
        say(100, "built " + name);
        return "Built " + name + ": play it from the list";
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
            throw new IOException("the recompiler stopped with " + status + ", its log is " + log);
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

    /** the title id in a decrypted .3ds's header, as 16 hex digits */
    private static String titleId(byte[] header) throws IOException {
        if (header[0x100] != 'N' || header[0x101] != 'C' || header[0x102] != 'S' || header[0x103] != 'D') {
            throw new IOException("that file is not a decrypted .3ds: its header is not NCSD");
        }
        long id = 0;
        for (int i = 7; i >= 0; i--) {
            id = (id << 8) | (header[0x108 + i] & 0xFFL);
        }
        return String.format("%016X", id);
    }

    // ---- reading, writing and saying how it goes

    private static byte[] read(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int at = 0;
        while (at < count) {
            int read = in.read(bytes, at, count - at);
            if (read < 0) {
                throw new IOException("that file is shorter than a game's header");
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
