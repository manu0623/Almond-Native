package io.github.almondnative;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.AlertDialog;
import android.util.Log;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.hardware.display.DisplayManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextThemeWrapper;
import android.view.ViewOutlineProvider;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.FrameLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Almond Native's launcher: the games added to it, Add game for one the PC kit
 * built (its folder, or a pack file), Device for how games show, and Update
 * for a newer release from GitHub. A game plays in Game, in a process of its
 * own.
 *
 * Each game is a folder named after its title id in games/, in the app's
 * folder on a removable card when there is one, else on shared storage:
 * game.3ds, sysdata/ and data/ (saves, its log), with name.txt and icon.png
 * from the game's own banner. Its recompiled code goes in code/ in the app's
 * own storage, read only, where Android lets an app load code from.
 */
public final class Launcher extends Activity {
    // the app's palette: one dark surface, cards a shade above it, and one
    // accent, the same ones the menu in game paints itself with
    private static final int GREY = 0xFF1B2534;
    private static final int BLUE = 0xFF2F6FE0;
    private static final int SURFACE = 0xFF141B25;
    private static final int TEXT = 0xFFE8EEF8;
    private static final int DIM = 0xFF8D9DB4;
    private static final int ADD_FOLDER = 1;
    private static final int ADD_PACK = 2;
    private static final int ADD_BUILD = 3;
    private static final int BACKUP_SAVES = 4;
    private static final int RESTORE_SAVES = 5;
    /** a shortcut's intent: the game in the "game" extra, played at once. */
    private static final String PLAY_GAME = "io.github.almondnative.PLAY";
    /**
     * the GitHub repository this app updates itself from: set it to your
     * own fork when you publish releases there.
     */
    private static final String RELEASES = "https://api.github.com/repos/CHANGE-ME/almond-native/releases/latest";

    /** every game the list can show: the ones on this device, then the titles only its profiles know. */
    private final List<Entry> every = new ArrayList<>();
    /** the ones the filter lets through, as the list draws them. */
    private final List<Entry> listed = new ArrayList<>();
    /** the games on this device, for the app's shortcuts. */
    private final List<File> installed = new ArrayList<>();
    /** all, ready or notbuilt: which of the chips over the list is lit. */
    private String filter = "all";
    /** the chips over the list, their counts kept as the games change. */
    private final List<Button> chips = new ArrayList<>();
    /** a game's own page, in the list's place while it is open. */
    private ScrollView gamePage;
    /** the game that page is about, null for none. */
    private Entry opened;
    /** the game a backup or a restore was picked from its long press for. */
    private File waiting;
    private GameList list;
    private TextView empty;
    /** the game list, and the Device page in its place while Device is lit. */
    private View gamesPage;
    private ScrollView devicePage;
    private Button deviceButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        unpackProfiles();
        // a build runs in a service of its own, its progress and outcome on a
        // notification: until the permission is given Android shows neither,
        // and a build that fails looks like nothing happening at all
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        // asked for from adb, the game's activity not being exported: the
        // self-test runs in it and the report shows on the way back
        if (getIntent().getBooleanExtra("selftest", false)) {
            selftest();
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        // a soft gradient rather than flat black, the top a shade lighter
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF0E141D, 0xFF070A10}));
        root.setFitsSystemWindows(true);

        // the name and version at the left, the app's actions at the right
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(18), dp(14), dp(10));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText("Almond Native");
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        TextView version = new TextView(this);
        version.setText("v" + version());
        version.setTextColor(DIM);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titles.addView(name);
        titles.addView(version);
        bar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        // not offered when this build names no repository to ask: a button
        // that only ever says it cannot reach GitHub is worse than none
        if (!RELEASES.contains("CHANGE-ME")) {
            bar.addView(pill("Update", v -> update()));
        }
        bar.addView(pill("Add game", v -> addGame()));
        deviceButton = pill("Device", v -> showDevice(devicePage.getVisibility() != View.VISIBLE));
        bar.addView(deviceButton);
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout body = new FrameLayout(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        gamesPage = page;
        body.addView(page);
        // the pages scroll, and the pad walks the cards inside them: the
        // scroll views themselves take no focus, their content does
        devicePage = new ScrollView(this);
        devicePage.setVisibility(View.GONE);
        devicePage.setFocusable(false);
        devicePage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        body.addView(devicePage);
        gamePage = new ScrollView(this);
        gamePage.setVisibility(View.GONE);
        gamePage.setFocusable(false);
        gamePage.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        body.addView(gamePage);
        root.addView(body, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        empty = new TextView(this);
        empty.setText("No games built yet.\n\nAdd one Almond Native's kit built on a PC, or build it on this device: every title the app knows is under Build needed and Not here.");
        empty.setTextColor(DIM);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        empty.setLineSpacing(dp(2), 1f);
        empty.setPadding(dp(20), dp(20), dp(20), dp(20));
        empty.setBackground(rounded(SURFACE, dp(16)));
        LinearLayout.LayoutParams room = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        room.setMargins(dp(12), dp(8), dp(12), dp(8));
        empty.setLayoutParams(room);
        page.addView(empty);

        // the chips over the list: every title the app knows, the ones built
        // here, and the ones still to build, each with its count
        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(dp(12), dp(4), dp(12), dp(2));
        for (String key : new String[] {"all", "ready", "build", "missing"}) {
            Button chip = button("", v -> {
                filter = key;
                refresh();
            });
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            LinearLayout.LayoutParams fit = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(36));
            fit.setMargins(0, 0, dp(8), 0);
            chip.setLayoutParams(fit);
            chip.setPadding(dp(16), 0, dp(16), 0);
            chip.setBackground(pillFace(GREY, dp(18)));
            chips.add(chip);
            filters.addView(chip);
        }
        page.addView(filters);

        // the games, as cards with air around them rather than divided rows
        ListView view = new ListView(this);
        view.setDivider(null);
        view.setDividerHeight(0);
        view.setSelector(new ColorDrawable(Color.TRANSPARENT));
        view.setPadding(0, dp(4), 0, dp(12));
        view.setClipToPadding(false);
        // where the gamepad is, drawn over the card it is on: the list itself
        // takes the focus, so no row shows it on its own
        view.setDrawSelectorOnTop(true);
        view.setSelector(listCursor());
        list = new GameList();
        view.setAdapter(list);
        view.setOnItemClickListener((parent, row, position, id) -> {
            Entry game = listed.get(position);
            // one whose code is not on this device would only fail to start:
            // its page is where it is built again
            if (game.ready) {
                play(game.folder);
            } else {
                showGame(game);
            }
        });
        view.setOnItemLongClickListener((parent, row, position, id) -> {
            showGame(listed.get(position));
            return true;
        });
        page.addView(view, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        refresh();
        playShortcut(getIntent());
        buildShortcut(getIntent());
        viewed(getIntent());
    }

    /**
     * a build asked for from outside the launcher, its file in the "build"
     * extra: how a build is started over adb while it is being brought up,
     * and by the tests.
     */
    private void buildShortcut(Intent intent) {
        String file = intent.getStringExtra("build");
        if (file != null) {
            setIntent(new Intent());
            build(Uri.parse(file));
        }
    }

    /** a game from a shortcut on the home screen, its folder in the extra. */
    private void playShortcut(Intent intent) {
        String game = intent.getStringExtra("game");
        if (PLAY_GAME.equals(intent.getAction()) && game != null) {
            setIntent(new Intent());
            if (new File(game, "game.3ds").isFile() && intent.getBooleanExtra("again", false)) {
                playAgain(new File(game));
            } else if (new File(game, "game.3ds").isFile()) {
                play(new File(game));
            } else {
                Toast.makeText(this, "That game is not here any more", Toast.LENGTH_LONG).show();
            }
        }
    }

    /** a game's shortcut, its icon and name, for the home screen. */
    private ShortcutInfo shortcut(File folder) {
        Bitmap icon = BitmapFactory.decodeFile(new File(folder, "icon.png").getPath());
        Intent intent = new Intent(PLAY_GAME).setClass(this, Launcher.class).putExtra("game", folder.getAbsolutePath());
        ShortcutInfo.Builder builder = new ShortcutInfo.Builder(this, folder.getName()).setShortLabel(name(folder)).setIntent(intent);
        if (icon != null) {
            builder.setIcon(Icon.createWithBitmap(Bitmap.createScaledBitmap(icon, 192, 192, false)));
        }
        return builder.build();
    }

    /** asks the home screen, Cocoon or any other, to put a game on it. */
    private void pin(File folder) {
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
            Toast.makeText(this, "This home screen does not take shortcuts", Toast.LENGTH_LONG).show();
            return;
        }
        shortcuts.requestPinShortcut(shortcut(folder), null);
    }

    /**
     * a game's own page, in the list's place: its cover, how it was played,
     * Play, and a card for each thing the app does with it — the menu it
     * starts with, its saves out and back, its shaders, its shortcut, its
     * files, and taking it away again. A title nothing was built for yet says
     * so and offers to build it here. Null goes back to the list.
     */
    private void showGame(Entry game) {
        opened = game;
        if (game == null) {
            gamePage.setVisibility(View.GONE);
            gamesPage.setVisibility(View.VISIBLE);
            return;
        }
        gamePage.removeAllViews();
        gamePage.addView(gameDetails(game));
        gamesPage.setVisibility(View.GONE);
        devicePage.setVisibility(View.GONE);
        deviceButton.setSelected(false);
        gamePage.setVisibility(View.VISIBLE);
    }

    /** what a game's page holds: the game at the left, what to do with it at the right. */
    private View gameDetails(Entry game) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(14), dp(8), dp(14), dp(24));

        Button back = button("‹  Games", v -> showGame(null));
        LinearLayout.LayoutParams small = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38));
        small.setMargins(dp(4), 0, 0, dp(8));
        back.setLayoutParams(small);
        back.setPadding(dp(16), 0, dp(16), 0);
        back.setBackground(face(GREY, dp(19)));
        page.addView(back);

        LinearLayout columns = new LinearLayout(this);
        columns.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(columns, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // the game: its cover, its name, its title id and state, how it was
        // played, and Play under them
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(4), 0, dp(12), 0);
        columns.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        ImageView cover = new ImageView(this);
        Bitmap bitmap = art(game);
        cover.setImageBitmap(bitmap != null ? bitmap : monogram(game));
        cover.setScaleType(bitmap != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_XY);
        cover.setClipToOutline(true);
        cover.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        left.addView(cover, new LinearLayout.LayoutParams(dp(160), dp(160)));

        TextView name = new TextView(this);
        name.setText(game.name);
        name.setTextColor(game.ready ? TEXT : 0xFF9AA7B8);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        name.setPadding(0, dp(8), 0, dp(4));
        left.addView(name);

        LinearLayout badges = new LinearLayout(this);
        badges.setOrientation(LinearLayout.HORIZONTAL);
        badges.addView(badge(game.id, 0xFF1A2230, DIM));
        Where place = where(game);
        badges.addView(badge(place == Where.READY ? "Ready" : place == Where.BUILD ? "Build needed" : "Not here",
                place == Where.READY ? 0xFF14251C : place == Where.BUILD ? 0xFF2A2113 : 0xFF1B2534,
                place == Where.READY ? 0xFF8BD5A0 : place == Where.BUILD ? 0xFFFFC45A : 0xFFA9B7C9));
        left.addView(badges);

        TextView played = new TextView(this);
        String dump = game.dump == null ? null : dumpName(game.dump);
        played.setText(game.ready ? said(game.folder)
                : dump != null ? "Dump: " + dump
                : game.folder != null ? "Its dump is in its folder" : "Not on this device yet");
        played.setTextColor(DIM);
        played.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        played.setPadding(0, dp(6), 0, dp(8));
        left.addView(played);

        Button go = button(game.ready ? "Play" : "Not built yet", v -> {
            if (game.ready) {
                play(game.folder);
            }
        });
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        go.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        go.setEnabled(game.ready);
        go.setAlpha(game.ready ? 1f : 0.5f);
        go.setBackground(pillFace(game.ready ? BLUE : GREY, dp(14)));
        left.addView(go, new LinearLayout.LayoutParams(dp(160), dp(52)));

        // the cards, two to a row, each with what it does under its name
        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setPadding(dp(2), 0, 0, 0);
        columns.addView(right, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2));

        List<View> cards = new ArrayList<>();
        if (game.ready) {
            cards.add(gcard("Its menu at the start", "Starts with the settings menu showing", TEXT, () -> play(game.folder, true)));
            cards.add(gcard("Back up its saves", "Its saves out as a zip", TEXT, () -> backupItsSaves(game.folder)));
            cards.add(gcard("Restore its saves", "A zip or a folder back in", TEXT, () -> restoreItsSaves(game.folder)));
            cards.add(gcard("Rebuild its shaders", "What it learned, built again first", TEXT, () -> rebuildShaders(game.folder)));
            cards.add(gcard("Home screen", "A shortcut with its cover", TEXT, () -> pin(game.folder)));
            cards.add(gcard("Files", "Title " + game.id + " · " + readable(sizeOf(game.folder)) + " · built", DIM, null));
            cards.add(gcard("Remove it", "Its code goes; its saves can stay", 0xFFFF9A8A, () -> removeGame(game.folder)));
        } else if (game.folder != null) {
            // a build that did not finish leaves a folder and its copy of the
            // dump: the games folder's own copy is taken again rather than a
            // copy that may be short being built over
            cards.add(gcard("Build it on this device",
                    game.dump == null ? "Its own dump, built here again" : "Its dump in your games folder, copied and built here",
                    TEXT, () -> {
                        if (game.dump != null) {
                            build(game.dump);
                        } else {
                            buildFrom(game.folder);
                        }
                    }));
            cards.add(gcard("Remove it", "Its folder and its saves go", 0xFFFF9A8A, () -> removeGame(game.folder)));
        } else {
            cards.add(gcard("Build it on this device",
                    game.dump == null ? "Pick its .3ds dump and the app builds it here"
                            : "Its dump is in your games folder: build it here, nothing to pick",
                    TEXT, () -> {
                        if (game.dump != null) {
                            build(game.dump);
                        } else {
                            pickBuild();
                        }
                    }));
            cards.add(gcard("Build it on a PC", "Then Add game takes its folder or pack file", DIM, null));
        }
        for (int i = 0; i < cards.size(); i += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, 0, 0, dp(10));
            row.addView(cards.get(i), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
            if (i + 1 < cards.size()) {
                LinearLayout.LayoutParams shape = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
                shape.setMargins(dp(10), 0, 0, 0);
                row.addView(cards.get(i + 1), shape);
            }
            right.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return page;
    }

    /** a card on a game's page: what it does and what that is, a note only read when it does nothing. */
    private View gcard(String title, String under, int colour, Runnable action) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(16), dp(12), dp(16), dp(12));
        card.setFocusable(action != null);
        card.setBackground(action == null ? rounded(0xFF121A24, dp(14)) : selectable(0xFF171F2B, 0xFF111823, dp(14)));
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(colour);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        TextView sub = new TextView(this);
        sub.setText(under);
        sub.setTextColor(DIM);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        sub.setPadding(0, dp(2), 0, 0);
        card.addView(name);
        card.addView(sub);
        if (action != null) {
            card.setOnClickListener(v -> action.run());
        }
        return card;
    }

    /** a small round badge on a game's page: its title id, or whether it is built. */
    private TextView badge(String text, int background, int colour) {
        TextView badge = new TextView(this);
        badge.setText(text);
        badge.setTextColor(colour);
        badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        badge.setBackground(rounded(background, dp(9)));
        badge.setPadding(dp(10), dp(4), dp(10), dp(4));
        LinearLayout.LayoutParams shape = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shape.setMargins(0, 0, dp(8), 0);
        badge.setLayoutParams(shape);
        return badge;
    }

    /** a dump picked to build here: BuildService does the work, its progress on the notification. */
    private void pickBuild() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), ADD_BUILD);
    }

    /** a game's code built here again from the dump in its own folder, no picker in the way. */
    private void buildFrom(File folder) {
        File rom = new File(folder, "game.3ds");
        if (!rom.isFile()) {
            Toast.makeText(this, "Its dump is not in its folder any more: add the game again", Toast.LENGTH_LONG).show();
            return;
        }
        build(Uri.fromFile(rom));
    }

    /** a game's saves out: where to, picked by the person. */
    private void backupItsSaves(File folder) {
        waiting = folder;
        Intent save = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip")
                .putExtra(Intent.EXTRA_TITLE, name(folder).replaceAll("[^A-Za-z0-9 ._-]", "") + " saves.zip");
        startActivityForResult(save, BACKUP_SAVES);
    }

    /** a game's saves back in, from a zip picked or a folder of them. */
    private void restoreItsSaves(File folder) {
        waiting = folder;
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        startActivityForResult(pick, RESTORE_SAVES);
    }

    /** a game taken away: its saves kept for adding it again, or all of it. */
    private void removeGame(File folder) {
        asks()
                .setTitle("Remove " + name(folder) + "?")
                .setMessage("Keeping its saves leaves the data/ folder, which adding the game again finds. Its built code goes either way.")
                .setPositiveButton("Keep its saves", (dialog, which) -> inBackground("Removing", progress -> {
                    deleteGame(folder, true);
                    return "Removed " + name(folder) + ", its saves kept";
                }))
                .setNeutralButton("Remove everything", (dialog, which) -> asks()
                        .setMessage("Its saves go too: everything saved in the game so far.")
                        .setPositiveButton("Remove everything", (again, confirmed) -> inBackground("Removing", progress -> {
                            deleteGame(folder, false);
                            return "Removed " + name(folder);
                        }))
                        .setNegativeButton("Cancel", null)
                        .show())
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteGame(File folder, boolean keepSaves) {
        File code = codeFor(folder.getName());
        code.setWritable(true);
        code.delete();
        if (!keepSaves) {
            deleteAll(folder);
            return;
        }
        File[] parts = folder.listFiles();
        if (parts != null) {
            for (File part : parts) {
                // data/ holds the saves, and stays for the game's next add
                if (!"data".equals(part.getName())) {
                    deleteAll(part);
                }
            }
        }
    }

    /**
     * the programs the engine builds before a start, built again from the ones
     * a run learned: for a game learned when those programs were other ones.
     */
    private void rebuildShaders(File folder) {
        File learned = new File(new File(folder, "data"), "shaders.txt");
        if (!learned.isFile()) {
            Toast.makeText(this, "It has not learned any shaders yet: play it once", Toast.LENGTH_LONG).show();
            return;
        }
        new File(new File(folder, "vertex"), "precache.txt").delete();
        try {
            startForegroundService(new Intent(this, BuildService.class)
                    .setAction(BuildService.ACTION_SHADERS)
                    .putExtra(BuildService.EXTRA_GAME, folder.getAbsolutePath()));
            Toast.makeText(this, "Rebuilding its shaders: the next start builds them all first", Toast.LENGTH_LONG).show();
        } catch (RuntimeException error) {
            Toast.makeText(this, "Could not start that: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /** a game's saves, data/ as it is, into the zip the person picked. */
    private int backupSaves(File folder, Uri to, Progress progress) throws IOException {
        List<File> files = new ArrayList<>();
        collect(new File(folder, "data"), files);
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        OutputStream raw = getContentResolver().openOutputStream(to);
        if (raw == null) {
            throw new IOException("could not open it");
        }
        long done = 0;
        try (ZipOutputStream zip = new ZipOutputStream(raw)) {
            byte[] buffer = new byte[1 << 20];
            for (File file : files) {
                // data/log.txt and so on, as the game's folder holds it
                zip.putNextEntry(new ZipEntry(file.getPath().substring(folder.getPath().length() + 1).replace('\\', '/')));
                try (InputStream in = new FileInputStream(file)) {
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        zip.write(buffer, 0, read);
                        done += read;
                    }
                }
                zip.closeEntry();
                if (progress != null) {
                    progress.at("Backing up the saves", done, total);
                }
            }
        }
        return files.size();
    }

    /** every file under a folder. */
    private static void collect(File folder, List<File> found) {
        File[] parts = folder.listFiles();
        if (parts == null) {
            return;
        }
        for (File part : parts) {
            if (part.isDirectory()) {
                collect(part, found);
            } else {
                found.add(part);
            }
        }
    }

    /** the saves in the zip the person picked, into the game's data/. */
    private int restoreSaves(File folder, Uri from, Progress progress) throws IOException {
        InputStream raw = getContentResolver().openInputStream(from);
        if (raw == null) {
            throw new IOException("could not open it");
        }
        int count = 0;
        long done = 0;
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                // only the game's own saves, never a path that climbs out
                if (entry.isDirectory() || !name.startsWith("data/") || name.contains("../")) {
                    continue;
                }
                File to = new File(folder, name);
                to.getParentFile().mkdirs();
                copy(zip, to, "the saves", entry.getSize(), progress);
                done += Math.max(0, entry.getSize());
                count++;
            }
        }
        return count;
    }

    /** a pack file shared to this app from a file manager or a chat app. */
    private void viewed(Intent intent) {
        if (!Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) {
            return;
        }
        Uri uri = intent.getData();
        setIntent(new Intent());
        inBackground("Adding", progress -> withCleanUp(() -> added(importPack(uri, progress))));
    }

    /** when the game process started, while one plays. */
    private File playingSince() {
        return new File(getFilesDir(), "playing-since");
    }

    /** where a game's seconds played and when it was last started are kept. */
    private File playedFile(File folder) {
        File dir = new File(getFilesDir(), "played");
        dir.mkdirs();
        return new File(dir, folder.getName());
    }

    /** a game's seconds played and when it was last started, 0 for never. */
    private long[] played(File folder) {
        try {
            String[] parts = new String(readAll(playedFile(folder)), StandardCharsets.UTF_8).trim().split("\\s+");
            return new long[] {Long.parseLong(parts[0]), parts.length > 1 ? Long.parseLong(parts[1]) : 0};
        } catch (IOException | RuntimeException never) {
            return new long[] {0, 0};
        }
    }

    /**
     * a game that ended while the launcher was away, its time counted now: the
     * seconds since it started, but for a day at most, as one left running in
     * the background overnight was not played for all of it.
     */
    private boolean settlePlaytime() {
        File since = playingSince();
        if (!since.isFile() || gameProcess() != 0) {
            return false;
        }
        long started;
        String game;
        try {
            started = Long.parseLong(new String(readAll(since), StandardCharsets.UTF_8).trim());
            game = new String(readAll(new File(getFilesDir(), "playing")), StandardCharsets.UTF_8).trim();
        } catch (IOException | RuntimeException gone) {
            since.delete();
            return false;
        }
        File folder = new File(game);
        long seconds = Math.min(Math.max(0, (System.currentTimeMillis() - started) / 1000), 24 * 3600);
        if (folder.isDirectory() && seconds > 0) {
            long[] had = played(folder);
            try (FileOutputStream out = new FileOutputStream(playedFile(folder))) {
                out.write((had[0] + seconds + " " + Math.max(started, had[1])).getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
            }
        }
        since.delete();
        return true;
    }

    /** "Played 12h 40m · yesterday", or "Never played". */
    private String said(File folder) {
        long[] played = played(folder);
        if (played[1] == 0) {
            return "Never played";
        }
        String time = played[0] >= 3600 ? (played[0] / 3600) + "h " + (played[0] % 3600 / 60) + "m"
                : played[0] >= 60 ? (played[0] / 60) + "m" : played[0] + "s";
        return "Played " + time + " · " + when(played[1]);
    }

    private static String when(long at) {
        long days = (System.currentTimeMillis() - at) / (24 * 3600 * 1000L);
        return days <= 0 ? "today" : days == 1 ? "yesterday" : days < 30 ? days + " days ago" : (days / 30) + " months ago";
    }

    /** whether the game's code was built onto this device. */
    private boolean built(File folder) {
        return codeFor(folder.getName()).isFile();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }

    /** an equal share of a row, a button's height. */
    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(48), 1);
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    /** a rectangle of one colour, its corners round. */
    private static GradientDrawable rounded(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    /** blue when pressed or picked with a pad, the colour given otherwise. */
    private StateListDrawable face(int normal, float radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_pressed}, rounded(BLUE, radius));
        states.addState(new int[] {android.R.attr.state_focused}, rounded(BLUE, radius));
        states.addState(new int[] {android.R.attr.state_selected}, rounded(BLUE, radius));
        states.addState(new int[] {}, rounded(normal, radius));
        return states;
    }

    /** a card's face: a soft gradient, round, a ripple when it is touched. */
    private Drawable card(int top, int bottom, float radius) {
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        face.setCornerRadius(radius);
        return new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), face, face);
    }

    /** the list's cursor: a ring in the accent around the card it is on, inset past the card's air. */
    private Drawable listCursor() {
        GradientDrawable ring = new GradientDrawable();
        ring.setColor(Color.TRANSPARENT);
        ring.setCornerRadius(dp(16));
        ring.setStroke(dp(2), BLUE);
        return new InsetDrawable(ring, dp(12), dp(5), dp(12), dp(5));
    }

    /**
     * a card's face where the gamepad can land on it: the same soft gradient,
     * a ring in the accent while it has the focus, lighter as it is pressed.
     */
    private Drawable selectable(int top, int bottom, float radius) {
        GradientDrawable face = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
        face.setCornerRadius(radius);
        GradientDrawable lit = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF25364A, 0xFF1B2637});
        lit.setCornerRadius(radius);
        lit.setStroke(dp(2), BLUE);
        GradientDrawable pressed = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {0xFF22303F, 0xFF19232F});
        pressed.setCornerRadius(radius);
        pressed.setStroke(dp(2), 0xFF4C6A8F);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, lit);
        states.addState(new int[] {android.R.attr.state_pressed}, pressed);
        states.addState(new int[] {}, face);
        return states;
    }

    /** a pill's face: its colour plain, blue while picked, and lit with a ring while it has the focus. */
    private Drawable pillFace(int normal, float radius) {
        GradientDrawable blue = rounded(BLUE, radius);
        GradientDrawable lit = rounded(0xFF3B7BEC, radius);
        lit.setStroke(dp(2), 0xFFBFD9FF);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, lit);
        states.addState(new int[] {android.R.attr.state_selected}, blue);
        states.addState(new int[] {android.R.attr.state_pressed}, blue);
        states.addState(new int[] {}, rounded(normal, radius));
        return states;
    }

    private Button button(String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(TEXT);
        // on one line, the text smaller where the button is narrow
        button.setMaxLines(1);
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        button.setAutoSizeTextTypeUniformWithConfiguration(9, 15, 1, TypedValue.COMPLEX_UNIT_SP);
        button.setBackground(face(GREY, dp(10)));
        button.setOnClickListener(action);
        return button;
    }

    /** a button in the header: a pill, sized to its word. */
    private Button pill(String label, View.OnClickListener action) {
        Button button = button(label, action);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        params.setMargins(dp(8), 0, 0, 0);
        button.setLayoutParams(params);
        button.setPadding(dp(18), 0, dp(18), 0);
        button.setBackground(face(GREY, dp(20)));
        return button;
    }

    // ---- the games

    /**
     * the app's folders on each storage volume, the one with the most room
     * first, which new games go on: a card's small boot partition is
     * mounted as a card too.
     */
    private List<File> volumes() {
        List<File> found = new ArrayList<>();
        for (File dir : getExternalFilesDirs(null)) {
            if (dir != null && Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState(dir))) {
                found.add(dir);
            }
        }
        Collections.sort(found, (a, b) -> Long.compare(b.getUsableSpace(), a.getUsableSpace()));
        return found;
    }

    private File codeFor(String id) {
        return new File(new File(getFilesDir(), "code"), id + ".so");
    }

    /**
     * the graphics self-test: the game's activity with no game, which puts a
     * GLES context on its own window, compiles the engine's shaders with the
     * phone's driver and draws a triangle it reads back. It says how it went
     * when it ends, from the report the activity wrote.
     */
    private void selftest() {
        new File(getFilesDir(), "selftest.txt").delete();
        Intent intent = new Intent(this, Game.class);
        intent.putExtra("selftest", true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // what the app's other parts had to say back here, where the person
        // reads it: the self-test's report, why a game did not start, how a
        // build went
        told("Graphics self-test", "selftest.txt");
        told("The game did not start", "last-error.txt");
        told("Building a game", "build-result.txt");
        prepareShaders();
        // a game that ended while the launcher was away, its time counted now
        if (settlePlaytime()) {
            refresh();
        }
    }

    /**
     * a game that was played drew shaders the build could not know: they are
     * learned and listed here, in the background, so that the next start
     * builds them all before its first frame rather than through play.
     */
    private void prepareShaders() {
        File playing = new File(getFilesDir(), "playing");
        if (!playing.isFile()) {
            return;
        }
        String game;
        try {
            game = new String(readAll(playing), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            return;
        }
        File recorded = new File(new File(game, "data"), "shaders.txt");
        File precache = new File(new File(game, "vertex"), "precache.txt");
        if (!recorded.isFile() || (precache.isFile() && precache.lastModified() >= recorded.lastModified())) {
            return;
        }
        Intent service = new Intent(this, BuildService.class)
                .setAction(BuildService.ACTION_SHADERS)
                .putExtra(BuildService.EXTRA_GAME, game);
        startForegroundService(service);
    }

    /** a note one of the app's other parts left for the person, then gone */
    private void told(String title, String name) {
        File said = new File(getFilesDir(), name);
        if (!said.isFile()) {
            return;
        }
        String text;
        try {
            text = new String(readAll(said), StandardCharsets.UTF_8).trim();
        } catch (IOException error) {
            text = "nothing was written down: " + error;
        }
        said.delete();
        Log.i("AlmondNative", name + ": " + text.replace("\n", " | "));
        asks().setTitle(title).setMessage(text).setPositiveButton("OK", null).show();
    }

    private void refresh() {
        every.clear();
        installed.clear();
        List<Entry> here = new ArrayList<>();
        Map<String, Uri> dumps = dumps();
        for (File volume : volumes()) {
            File[] folders = new File(volume, "games").listFiles();
            if (folders == null) {
                continue;
            }
            for (File folder : folders) {
                // not one still coming in, or left half copied
                if (!folder.getName().startsWith(".") && new File(folder, "game.3ds").isFile()) {
                    // the cover read out of the dump where it has none yet: the
                    // banner's icon, as the 3DS's own home menu shows it
                    if (!new File(folder, "icon.png").isFile()) {
                        banner(new File(folder, "game.3ds"), folder);
                    }
                    // its dump in the games folder, where the folder here is
                    // one a build left behind without its code
                    here.add(new Entry(folder, folder.getName(), name(folder), built(folder), dumps.get(folder.getName())));
                    installed.add(folder);
                }
            }
        }
        String order = prefs().getString("order", "name");
        Collections.sort(here, (a, b) -> {
            if ("recent".equals(order)) {
                long was = played(a.folder)[1], is = played(b.folder)[1];
                if (was != is) {
                    return Long.compare(is, was);
                }
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        // then the titles the app's profiles know that nothing was built for
        // here yet, each named so that the list says what is left to build
        Set<String> names = new HashSet<>();
        for (Entry game : here) {
            names.add(game.name.toLowerCase(Locale.ROOT));
        }

        List<Entry> known = new ArrayList<>();
        // one entry a title, the region the games folder has a dump of where
        // there is one: a title has a profile for each of its regions
        Map<String, Entry> byName = new LinkedHashMap<>();
        File[] profiles = new File(getFilesDir(), "titles").listFiles();
        if (profiles != null) {
            for (File profile : profiles) {
                String id = profile.getName();
                if (id.length() != 16 || !new File(profile, "profile.toml").isFile()) {
                    continue;
                }
                String title = profileName(id);
                boolean onDevice = false;
                for (Entry game : here) {
                    onDevice |= game.id.equals(id);
                }
                if (title == null || onDevice || names.contains(title.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String key = title.toLowerCase(Locale.ROOT);
                Entry entry = new Entry(null, id, title, false, dumps.get(id));
                Entry had = byName.get(key);
                if (had == null || (had.dump == null && entry.dump != null)) {
                    byName.put(key, entry);
                }
            }
        }
        known.addAll(byName.values());
        Collections.sort(known, (a, b) -> {
            // the ones with a dump on the device ahead of the rest
            Where was = where(a), is = where(b);
            if (was != is) {
                return was == Where.BUILD ? -1 : 1;
            }
            return a.name.compareToIgnoreCase(b.name);
        });
        every.addAll(here);
        every.addAll(known);
        listed.clear();
        for (Entry game : every) {
            Where place = where(game);
            if (("ready".equals(filter) && place != Where.READY)
                    || ("build".equals(filter) && place != Where.BUILD)
                    || ("missing".equals(filter) && place != Where.MISSING)) {
                continue;
            }
            listed.add(game);
        }
        int readyCount = 0, buildCount = 0;
        for (Entry game : every) {
            Where place = where(game);
            if (place == Where.READY) {
                readyCount++;
            } else if (place == Where.BUILD) {
                buildCount++;
            }
        }
        // what is on the device first, then what its games folder holds a
        // dump of, and the rest last
        String[] labels = {"All  ", "Ready  ", "Build needed  ", "Not here  "};
        int[] counts = {every.size(), readyCount, buildCount, every.size() - readyCount - buildCount};
        String[] keys = {"all", "ready", "build", "missing"};
        for (int i = 0; i < chips.size(); i++) {
            chips.get(i).setText(labels[i] + counts[i]);
            chips.get(i).setSelected(keys[i].equals(filter));
        }
        empty.setVisibility(listed.isEmpty() ? View.VISIBLE : View.GONE);
        list.notifyDataSetChanged();
        // the Device page says which folder the games come from: kept as it is
        if (devicePage.getVisibility() == View.VISIBLE) {
            showDevice(true);
        }
        // the games as the app's shortcuts too, for launchers that list them
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts != null) {
            List<ShortcutInfo> each = new ArrayList<>();
            for (File folder : installed.subList(0, Math.min(installed.size(), shortcuts.getMaxShortcutCountPerActivity()))) {
                each.add(shortcut(folder));
            }
            try {
                shortcuts.setDynamicShortcuts(each);
            } catch (RuntimeException ignored) {
                // over the rate limit, the ones before stay
            }
        }
    }

    /** the game's name: its profile's, or the one its banner gives. */
    private String name(File folder) {
        String known = profileName(folder.getName());
        if (known != null) {
            return known;
        }
        try {
            return new String(readAll(new File(folder, "name.txt")), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            return folder.getName();
        }
    }

    /** a title's name from the profile the app carries, null when it has none. */
    private String profileName(String id) {
        File profile = new File(new File(new File(getFilesDir(), "titles"), id), "profile.toml");
        try {
            for (String line : new String(readAll(profile), StandardCharsets.UTF_8).split("\n")) {
                if (line.startsWith("name = \"") && line.trim().endsWith("\"")) {
                    return line.trim().substring(8, line.trim().length() - 1);
                }
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** a game's cover: cover.png beside its folder's icon, else the icon read out of its dump, null for none. */
    private Bitmap art(Entry game) {
        if (game.folder == null) {
            return null;
        }
        Bitmap cover = BitmapFactory.decodeFile(new File(game.folder, "cover.png").getPath());
        return cover != null ? cover : BitmapFactory.decodeFile(new File(game.folder, "icon.png").getPath());
    }

    /** a game's first letter on a colour of its own, for one with no art at all. */
    private Bitmap monogram(Entry game) {
        int size = dp(96);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.HSVToColor(new float[] {Math.abs(game.id.hashCode() * 31) % 360f, 0.34f, 0.42f}));
        canvas.drawRoundRect(new android.graphics.RectF(0, 0, size, size), dp(14), dp(14), paint);
        paint.setColor(TEXT);
        paint.setTextSize(size * 0.4f);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        paint.setTextAlign(android.graphics.Paint.Align.CENTER);
        android.graphics.Paint.FontMetrics metrics = paint.getFontMetrics();
        String letter = game.name.isEmpty() ? "?" : game.name.substring(0, 1).toUpperCase(Locale.ROOT);
        canvas.drawText(letter, size / 2f, size / 2f - (metrics.ascent + metrics.descent) / 2f, paint);
        return bitmap;
    }

    /** everything under a folder, its length in bytes. */
    private static long sizeOf(File file) {
        if (file == null) {
            return 0;
        }
        if (file.isFile()) {
            return file.length();
        }
        File[] parts = file.listFiles();
        long size = 0;
        if (parts != null) {
            for (File part : parts) {
                size += sizeOf(part);
            }
        }
        return size;
    }

    /** a size as a person reads it: "1.2 GB", "340 MB". */
    private static String readable(long size) {
        if (size >= 1L << 30) {
            return String.format(Locale.ROOT, "%.1f GB", size / (double) (1L << 30));
        }
        if (size >= 1L << 20) {
            return (size + (1L << 19)) / (1L << 20) + " MB";
        }
        return Math.max(1, size / 1024) + " KB";
    }

    private static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int at = 0;
            while (at < bytes.length) {
                int read = in.read(bytes, at, bytes.length - at);
                if (read < 0) {
                    break;
                }
                at += read;
            }
            return bytes;
        }
    }

    /**
     * a game in the list: one on this device, its folder, and whether its code
     * was built here, or a title the app's profiles know that nothing was
     * built for yet, its folder null.
     */
    private static final class Entry {
        final File folder;
        final String id;
        final String name;
        final boolean ready;
        /** the dump in the games folder this title was found in, null when it has none there. */
        final Uri dump;

        Entry(File folder, String id, String name, boolean ready) {
            this(folder, id, name, ready, null);
        }

        Entry(File folder, String id, String name, boolean ready, Uri dump) {
            this.folder = folder;
            this.id = id;
            this.name = name;
            this.ready = ready;
            this.dump = dump;
        }
    }

    /** where a game in the list stands: built and playable, a dump on the
     * device to build, or nothing of it here yet. */
    private enum Where { READY, BUILD, MISSING }

    private static Where where(Entry game) {
        if (game.ready) {
            return Where.READY;
        }
        return game.folder != null || game.dump != null ? Where.BUILD : Where.MISSING;
    }

    /** a dump's own file name, from the document the games folder gave. */
    private static String dumpName(Uri dump) {
        String id = DocumentsContract.getDocumentId(dump);
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }

    private final class GameList extends BaseAdapter {
        @Override
        public int getCount() {
            return listed.size();
        }

        @Override
        public Object getItem(int position) {
            return listed.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View reused, ViewGroup parent) {
            Entry game = listed.get(position);
            // a card of its own, its corners round, a ripple as it is touched
            FrameLayout holder = new FrameLayout(Launcher.this);
            holder.setPadding(dp(12), dp(5), dp(12), dp(5));
            LinearLayout row = new LinearLayout(Launcher.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(14), dp(12), dp(14), dp(12));
            row.setMinimumHeight(dp(84));
            row.setBackground(card(0xFF171F2B, 0xFF111823, dp(16)));
            // the game's cover, its own corners round like the card's: no gap
            // in the card where the game has art, a lettered tile where it has
            // none yet
            Bitmap bitmap = art(game);
            ImageView icon = new ImageView(Launcher.this);
            icon.setImageBitmap(bitmap != null ? bitmap : monogram(game));
            icon.setScaleType(bitmap != null ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_XY);
            icon.setClipToOutline(true);
            icon.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
                }
            });
            row.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
            // the name, and under it how long it was played and when last
            LinearLayout column = new LinearLayout(Launcher.this);
            column.setOrientation(LinearLayout.VERTICAL);
            TextView text = new TextView(Launcher.this);
            text.setText(game.name);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            text.setPadding(dp(16), 0, 0, 0);
            TextView under = new TextView(Launcher.this);
            under.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            under.setPadding(dp(16), dp(3), 0, 0);
            Where place = where(game);
            if (place == Where.READY) {
                text.setTextColor(TEXT);
                under.setText(said(game.folder));
                under.setTextColor(DIM);
            } else if (place == Where.BUILD) {
                // its dump is here: one build away from playing
                text.setTextColor(0xFFC3CDDA);
                under.setText(game.dump != null ? "Its dump is on the device · build it here"
                        : "Its code is gone · build it again here");
                under.setTextColor(0xFFFFC45A);
            } else {
                // a title the profiles know and the device has nothing of yet
                text.setTextColor(0xFF9A9A9A);
                under.setText("Not on this device · add its dump to your games folder");
                under.setTextColor(0xFF7E8CA0);
            }
            column.addView(text);
            column.addView(under);
            row.addView(column, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            // where it stands, at the card's right: playable, one build
            // away, or not on this device at all
            TextView state = new TextView(Launcher.this);
            state.setText(place == Where.READY ? "Ready" : place == Where.BUILD ? "Build needed" : "Not here");
            state.setTextColor(place == Where.READY ? 0xFF8BD5A0 : place == Where.BUILD ? 0xFFFFC45A : 0xFFA9B7C9);
            state.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            state.setBackground(rounded(place == Where.READY ? 0xFF14251C : place == Where.BUILD ? 0xFF2A2113 : 0xFF1B2534, dp(9)));
            state.setPadding(dp(10), dp(4), dp(10), dp(4));
            row.addView(state);
            holder.addView(row, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return holder;
        }
    }

    /**
     * a game: the one held in the background brought back, another started
     * once that one is ended, after asking.
     */
    private void play(File folder) {
        play(folder, false);
    }

    private void play(File folder, boolean menu) {
        int pid = gameProcess();
        String running = null;
        try {
            running = pid == 0 ? null : new String(readAll(new File(getFilesDir(), "playing")), StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) {
            // a game process that never said which game, ended below
        }
        if (pid == 0) {
            start(folder, true, menu);
        } else if (folder.getAbsolutePath().equals(running)) {
            start(folder, false, menu);
        } else {
            String other = running == null ? "Another game" : name(new File(running));
            asks()
                    .setMessage(other + " is still running. End it and play " + name(folder) + "? Anything not saved in it is lost.")
                    .setPositiveButton("End it", (dialog, which) -> {
                        android.os.Process.killProcess(pid);
                        settlePlaytime();
                        playAgain(folder, menu);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }
    }

    /** the game process's id, 0 when none runs. */
    private int gameProcess() {
        String process = getPackageName() + ":game";
        for (ActivityManager.RunningAppProcessInfo info : getSystemService(ActivityManager.class).getRunningAppProcesses()) {
            if (process.equals(info.processName)) {
                return info.pid;
            }
        }
        return 0;
    }

    /**
     * the game in a task of its own on the main display, wherever the
     * launcher was opened, its second screen going on the other one: Cocoon
     * opens apps on the Thor's bottom display. Fresh, any task left from a
     * game before is cleared, else the one held in the background comes back.
     */
    private void start(File folder, boolean fresh) {
        start(folder, fresh, false);
    }

    /**
     * a game started, its menu up first when asked for: the resolution and
     * the rest are reached without walking into the game to press Menu.
     */
    private void start(File folder, boolean fresh, boolean menu) {
        // when this game started, which onResume turns into its time played
        try (FileOutputStream out = new FileOutputStream(playingSince())) {
            out.write(String.valueOf(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
        }
        Intent intent = new Intent(this, Game.class);
        intent.putExtra("game", folder.getAbsolutePath());
        intent.putExtra("orientation", prefs().getString("orientation", "auto"));
        if (menu) {
            intent.putExtra("menu", true);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | (fresh ? Intent.FLAG_ACTIVITY_CLEAR_TASK : 0));
        ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(Display.DEFAULT_DISPLAY);
        startActivity(intent, options.toBundle());
    }

    /**
     * a game started fresh once the game process is gone, for Reset in its
     * menu and for one ended to play another: one started first would go
     * into the old process as it ends.
     */
    private void playAgain(File folder) {
        playAgain(folder, false);
    }

    private void playAgain(File folder, boolean menu) {
        new Thread(() -> {
            for (int tries = 0; tries < 40 && gameProcess() != 0; tries++) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
            runOnUiThread(() -> start(folder, true, menu));
        }).start();
    }

    // ---- adding a game

    private void addGame() {
        asks()
                .setTitle("Add game")
                .setItems(new String[] {"A games folder on this device", "A pack file", "Build it on this device", "Run the graphics self-test"}, (dialog, which) -> {
                    if (which == 3) {
                        selftest();
                        return;
                    }
                    if (which == 0) {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), ADD_FOLDER);
                    } else {
                        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        pick.addCategory(Intent.CATEGORY_OPENABLE);
                        pick.setType("*/*");
                        startActivityForResult(pick, which == 1 ? ADD_PACK : ADD_BUILD);
                    }
                })
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (request == ADD_FOLDER) {
            // the folder is this app's games folder from now on, read again
            // when the person asks for it on the Device page
            try {
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // a picker that hands out no lasting permission still adds what is there now
            }
            prefs().edit().putString("gamesfolder", uri.toString()).apply();
            scanFolder(uri);
        } else if (request == ADD_PACK) {
            inBackground("Adding", progress -> withCleanUp(() -> added(importPack(uri, progress))));
        } else if (request == ADD_BUILD) {
            build(uri);
        } else if (request == BACKUP_SAVES && waiting != null) {
            File folder = waiting;
            waiting = null;
            inBackground("Backing up", progress -> "Backed up " + backupSaves(folder, uri, progress) + " files of saves");
        } else if (request == RESTORE_SAVES && waiting != null) {
            File folder = waiting;
            waiting = null;
            inBackground("Restoring", progress -> "Restored " + restoreSaves(folder, uri, progress) + " files of saves");
        }
    }

    /**
     * a game built here: BuildService does the work in a service of its own,
     * the dialog here showing what it is on until it is done.
     */
    private void build(Uri uri) {
        BuildService.result = null;
        BuildService.stage = "starting";
        BuildService.percent = 0;
        Intent service = new Intent(this, BuildService.class)
                .setAction(BuildService.ACTION_BUILD)
                .putExtra(BuildService.EXTRA_GAME, uri.toString());
        startForegroundService(service);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(8));
        TextView label = new TextView(this);
        label.setText("Starting...");
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        box.addView(label);
        box.addView(bar);
        AlertDialog dialog = asks().setTitle("Building").setView(box).setCancelable(false).show();
        Handler poll = new Handler(Looper.getMainLooper());
        Runnable look = new Runnable() {
            @Override
            public void run() {
                if (dialog.isShowing() && !"done".equals(BuildService.stage)) {
                    label.setText(BuildService.stage);
                    bar.setProgress(BuildService.percent);
                    poll.postDelayed(this, 400);
                    return;
                }
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
                String said = BuildService.result;
                Toast.makeText(Launcher.this, said == null ? "Done" : said, Toast.LENGTH_LONG).show();
                refresh();
            }
        };
        poll.postDelayed(look, 400);
    }

    private interface Progress {
        void at(String what, long done, long total);
    }

    private interface Work {
        /** what to tell the person when it is done. */
        String run(Progress progress) throws IOException;
    }

    private static String added(int count) {
        return count == 0 ? "No game there: a game's folder holds game.3ds and title.so" : count == 1 ? "Added a game" : "Added " + count + " games";
    }

    /** runs work off the main thread with a progress bar, then says how it went. */
    private void inBackground(String title, Work work) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(16), dp(24), dp(16));
        TextView label = new TextView(this);
        label.setText("Copying...");
        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        box.addView(label);
        box.addView(bar);
        AlertDialog dialog = asks().setTitle(title).setView(box).setCancelable(false).show();
        new Thread(() -> {
            String outcome;
            try {
                outcome = work.run((what, done, total) -> runOnUiThread(() -> {
                    label.setText(what);
                    bar.setProgress(total > 0 ? (int) (done * 1000 / total) : 0);
                }));
            } catch (IOException error) {
                outcome = "That did not work: " + error.getMessage();
            }
            String shown = outcome;
            runOnUiThread(() -> {
                dialog.dismiss();
                if (shown != null) {
                Toast.makeText(this, shown, Toast.LENGTH_LONG).show();
            }
                refresh();
            });
        }).start();
    }

    /** a document in a picked tree: its id, name, kind and size. */
    private static final class Document {
        String id;
        String name;
        boolean folder;
        long size;
    }

    private List<Document> children(Uri tree, String parent) {
        List<Document> found = new ArrayList<>();
        Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent);
        String[] columns = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = getContentResolver().query(uri, columns, null, null, null)) {
            while (cursor != null && cursor.moveToNext()) {
                Document document = new Document();
                document.id = cursor.getString(0);
                document.name = cursor.getString(1);
                document.folder = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
                document.size = cursor.isNull(3) ? -1 : cursor.getLong(3);
                found.add(document);
            }
        }
        return found;
    }

    /** every game folder in a picked tree, a few levels down: the kit's whole output works too. */
    private int importFolder(Uri tree, Progress progress) throws IOException {
        List<List<Document>> found = new ArrayList<>();
        findGames(tree, DocumentsContract.getTreeDocumentId(tree), 0, found);
        int added = 0;
        for (List<Document> game : found) {
            Document rom = null, code = null, font = null;
            List<Document> mii = new ArrayList<>();
            for (Document document : game) {
                if ("game.3ds".equals(document.name)) {
                    rom = document;
                } else if ("title.so".equals(document.name)) {
                    code = document;
                } else if ("shared_font.bin".equals(document.name)) {
                    font = document;
                } else if (miiParts(document.name)) {
                    mii.add(document);
                }
            }
            if (rom == null || code == null) {
                continue;
            }
            // a game already here is left as it is, so that a scan of the same
            // folder costs nothing and never copies gigabytes twice
            String known;
            try (InputStream header = open(tree, rom.id)) {
                known = titleId(header);
            }
            if (new File(new File(new File(volumes().get(0), "games"), known), "game.3ds").isFile()) {
                String title = profileName(known);
                progress.at("Already here: " + (title == null ? known : title), 0, 1);
                continue;
            }
            File incoming = incoming();
            copy(open(tree, rom.id), new File(incoming, "game.3ds"), "game.3ds", rom.size, progress);
            copy(open(tree, code.id), new File(incoming, "title.so"), "title.so", code.size, progress);
            if (font != null) {
                copy(open(tree, font.id), new File(new File(incoming, "sysdata"), "shared_font.bin"), "system font", font.size, progress);
            }
            for (Document document : game) {
                if (document.name.startsWith("vertex/")) {
                    copy(open(tree, document.id), new File(incoming, document.name), "the game's shader list", document.size, progress);
                }
            }
            for (Document parts : mii) {
                copy(open(tree, parts.id), new File(new File(incoming, "sysdata"), parts.name), "Mii parts", parts.size, progress);
            }
            settle(incoming);
            added++;
        }
        return added;
    }

    private void findGames(Uri tree, String parent, int depth, List<List<Document>> found) {
        List<Document> here = children(tree, parent);
        boolean rom = false, code = false;
        for (Document document : here) {
            rom |= "game.3ds".equals(document.name);
            code |= "title.so".equals(document.name);
        }
        if (rom && code) {
            // the system font is in sysdata beside them, the shaders the
            // kit found in the game in vertex
            List<Document> files = new ArrayList<>(here);
            for (Document document : here) {
                if (document.folder && "sysdata".equals(document.name)) {
                    files.addAll(children(tree, document.id));
                } else if (document.folder && "vertex".equals(document.name)) {
                    for (Document shader : children(tree, document.id)) {
                        shader.name = "vertex/" + shader.name;
                        files.add(shader);
                    }
                }
            }
            found.add(files);
            return;
        }
        if (depth < 4) {
            for (Document document : here) {
                if (document.folder) {
                    findGames(tree, document.id, depth + 1, found);
                }
            }
        }
    }

    private InputStream open(Uri tree, String id) throws IOException {
        InputStream in = getContentResolver().openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id));
        if (in == null) {
            throw new IOException("could not open it");
        }
        return in;
    }

    /**
     * a pack file: a zip of a game's folder as the kit builds it, game.3ds,
     * title.so, sysdata/shared_font.bin and vertex/, in any folder inside it.
     */
    private int importPack(Uri uri, Progress progress) throws IOException {
        InputStream raw = getContentResolver().openInputStream(uri);
        if (raw == null) {
            throw new IOException("could not open it");
        }
        File incoming = incoming();
        boolean rom = false, code = false;
        try (ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = new File(entry.getName()).getName();
                if ("game.3ds".equals(name)) {
                    copy(zip, new File(incoming, name), name, entry.getSize(), progress);
                    rom = true;
                } else if ("title.so".equals(name)) {
                    copy(zip, new File(incoming, name), name, entry.getSize(), progress);
                    code = true;
                } else if ("shared_font.bin".equals(name)) {
                    copy(zip, new File(new File(incoming, "sysdata"), name), "system font", entry.getSize(), progress);
                } else if (entry.getName().contains("/vertex/") && !entry.isDirectory()) {
                    copy(zip, new File(new File(incoming, "vertex"), name), "the game's shader list", entry.getSize(), progress);
                } else if (miiParts(name)) {
                    copy(zip, new File(new File(incoming, "sysdata"), name), "Mii parts", entry.getSize(), progress);
                }
            }
        }
        if (!rom || !code) {
            deleteAll(incoming);
            return 0;
        }
        settle(incoming);
        return 1;
    }

    /** the Mii parts the kit puts beside the system font, experimental. */
    private static boolean miiParts(String name) {
        return "CFL_Res.dat".equals(name) || "CFL_Res.placeholder.dat".equals(name);
    }

    private interface Adding {
        String run() throws IOException;
    }

    /** adding a game, the folder it was coming into gone if it failed. */
    private String withCleanUp(Adding adding) throws IOException {
        try {
            return adding.run();
        } catch (IOException error) {
            for (File volume : volumes()) {
                deleteAll(new File(new File(volume, "games"), ".incoming"));
            }
            throw error;
        }
    }

    /** an empty folder to copy a game into, on the volume games go on. */
    private File incoming() throws IOException {
        List<File> volumes = volumes();
        if (volumes.isEmpty()) {
            throw new IOException("no storage to put it on");
        }
        File folder = new File(new File(volumes.get(0), "games"), ".incoming");
        deleteAll(folder);
        if (!folder.mkdirs()) {
            throw new IOException("could not make " + folder);
        }
        return folder;
    }

    /** an incoming game in its place: its folder named by its title id, its code read only in the app's own storage. */
    private void settle(File incoming) throws IOException {
        String id = titleId(new File(incoming, "game.3ds"));
        File code = codeFor(id);
        code.getParentFile().mkdirs();
        code.setWritable(true);
        code.delete();
        File copied = new File(incoming, "title.so");
        try (InputStream in = new FileInputStream(copied)) {
            copy(in, code, "title.so", copied.length(), null);
        }
        code.setReadOnly();
        copied.delete();
        banner(new File(incoming, "game.3ds"), incoming);
        File folder = new File(incoming.getParentFile(), id);
        if (folder.isDirectory()) {
            // a game added again keeps its saves and settings
            for (String name : new String[] {"game.3ds", "sysdata", "name.txt", "icon.png"}) {
                File old = new File(folder, name);
                deleteAll(old);
                File fresh = new File(incoming, name);
                if (fresh.exists() && !fresh.renameTo(old)) {
                    throw new IOException("could not move " + name);
                }
            }
            deleteAll(incoming);
        } else if (!incoming.renameTo(folder)) {
            throw new IOException("could not move it into place");
        }
    }

    private static void copy(InputStream in, File to, String what, long total, Progress progress) throws IOException {
        to.getParentFile().mkdirs();
        byte[] buffer = new byte[1 << 20];
        long done = 0, shown = 0;
        try (OutputStream out = new FileOutputStream(to)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                done += read;
                if (progress != null && done - shown > (8 << 20)) {
                    progress.at("Copying " + what, done, total);
                    shown = done;
                }
            }
        }
        if (!(in instanceof ZipInputStream)) {
            in.close();
        }
    }

    private static void deleteAll(File file) {
        File[] inside = file.listFiles();
        if (inside != null) {
            for (File child : inside) {
                deleteAll(child);
            }
        }
        file.delete();
    }

    /** the title id in a decrypted .3ds's header, as 16 hex digits. */
    private static String titleId(File rom) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            byte[] header = new byte[0x110];
            in.readFully(header);
            return titleId(header);
        }
    }

    /** the title id in a decrypted .3ds's first 0x110 bytes, as 16 hex digits. */
    private static String titleId(byte[] header) throws IOException {
        if (header[0x100] != 'N' || header[0x101] != 'C' || header[0x102] != 'S' || header[0x103] != 'D') {
            throw new IOException("game.3ds is not a decrypted .3ds");
        }
        long id = 0;
        for (int i = 7; i >= 0; i--) {
            id = (id << 8) | (header[0x108 + i] & 0xFF);
        }
        return String.format("%016X", id);
    }

    /** the title id of a game in a picked folder, from as much of its header as the folder gives. */
    private static String titleId(InputStream in) throws IOException {
        byte[] header = new byte[0x110];
        int at = 0;
        while (at < header.length) {
            int read = in.read(header, at, header.length - at);
            if (read < 0) {
                throw new IOException("game.3ds is too short to read");
            }
            at += read;
        }
        return titleId(header);
    }

    /** name.txt and icon.png from the game's banner, its SMDH, when it has one. */
    private static void banner(File rom, File folder) {
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            long first = u32(in, 0x120) * 0x200;
            byte[] magic = new byte[4];
            in.seek(first + 0x100);
            in.readFully(magic);
            if (!"NCCH".equals(new String(magic, StandardCharsets.US_ASCII))) {
                return;
            }
            long exefs = first + u32(in, first + 0x1A0) * 0x200;
            for (int entry = 0; entry < 10; entry++) {
                byte[] name = new byte[8];
                in.seek(exefs + entry * 16);
                in.readFully(name);
                if (!new String(name, StandardCharsets.US_ASCII).startsWith("icon")) {
                    continue;
                }
                long offset = u32(in, exefs + entry * 16 + 8);
                byte[] smdh = new byte[0x36C0];
                in.seek(exefs + 0x200 + offset);
                in.readFully(smdh);
                if (smdh[0] != 'S' || smdh[1] != 'M' || smdh[2] != 'D' || smdh[3] != 'H') {
                    return;
                }
                for (int language : new int[] {1, 0}) {
                    int at = 0x8 + language * 0x200;
                    String title = new String(smdh, at, 0x80, StandardCharsets.UTF_16LE).split("\0")[0].trim().replaceAll("\\s+", " ");
                    if (!title.isEmpty()) {
                        try (OutputStream out = new FileOutputStream(new File(folder, "name.txt"))) {
                            out.write(title.getBytes(StandardCharsets.UTF_8));
                        }
                        break;
                    }
                }
                // the large icon: 48x48 RGB565, in 8x8 tiles, each in Morton order
                int[] pixels = new int[48 * 48];
                for (int index = 0; index < 48 * 48; index++) {
                    int tile = index / 64, inside = index % 64;
                    int x = (tile % 6) * 8, y = (tile / 6) * 8;
                    for (int bit = 0; bit < 3; bit++) {
                        x += ((inside >> (2 * bit)) & 1) << bit;
                        y += ((inside >> (2 * bit + 1)) & 1) << bit;
                    }
                    int value = (smdh[0x24C0 + index * 2] & 0xFF) | ((smdh[0x24C0 + index * 2 + 1] & 0xFF) << 8);
                    int r = (value >> 11) & 31, g = (value >> 5) & 63, b = value & 31;
                    pixels[y * 48 + x] = Color.rgb((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2));
                }
                Bitmap icon = Bitmap.createBitmap(pixels, 48, 48, Bitmap.Config.ARGB_8888);
                try (OutputStream out = new FileOutputStream(new File(folder, "icon.png"))) {
                    icon.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                return;
            }
        } catch (IOException ignored) {
        }
    }

    private static long u32(RandomAccessFile in, long at) throws IOException {
        in.seek(at);
        byte[] bytes = new byte[4];
        in.readFully(bytes);
        return (bytes[0] & 0xFFL) | ((bytes[1] & 0xFFL) << 8) | ((bytes[2] & 0xFFL) << 16) | ((bytes[3] & 0xFFL) << 24);
    }

    /** the profiles in the app, unpacked into its own storage once a version. */
    private void unpackProfiles() {
        File titles = new File(getFilesDir(), "titles");
        String version = version();
        if (version.equals(prefs().getString("profiles", "")) && titles.isDirectory()) {
            return;
        }
        deleteAll(titles);
        try {
            String[] ids = getAssets().list("titles");
            if (ids != null) {
                for (String id : ids) {
                    try (InputStream in = getAssets().open("titles/" + id + "/profile.toml")) {
                        copy(in, new File(new File(titles, id), "profile.toml"), id, -1, null);
                    } catch (IOException ignored) {
                        // a title with overrides and no profile
                    }
                }
            }
            prefs().edit().putString("profiles", version).apply();
        } catch (IOException ignored) {
        }
    }

    // ---- the device

    private SharedPreferences prefs() {
        return getSharedPreferences("launcher", Context.MODE_PRIVATE);
    }

    private File oneScreen() {
        return new File(new File(getFilesDir(), "config"), "one-screen");
    }

    /** the Device page in the game list's place, or the list back. */
    private void showDevice(boolean shown) {
        devicePage.setVisibility(shown ? View.VISIBLE : View.GONE);
        gamesPage.setVisibility(shown ? View.GONE : View.VISIBLE);
        if (shown) {
            gamePage.setVisibility(View.GONE);
            opened = null;
        }
        deviceButton.setSelected(shown);
        if (!shown) {
            return;
        }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(8), dp(6), dp(8), dp(16));
        TextView heading = new TextView(this);
        heading.setText("Device");
        heading.setTextColor(TEXT);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heading.setPadding(dp(16), dp(6), dp(16), dp(2));
        page.addView(heading);
        TextView subtitle = new TextView(this);
        subtitle.setText("How games are drawn and played on this handheld.");
        subtitle.setTextColor(DIM);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        subtitle.setPadding(dp(16), 0, dp(16), dp(6));
        page.addView(subtitle);
        String[] orientations = {"auto", "landscape", "portrait"};
        page.addView(choice("Orientation", "How games are held. Auto turns with the device.",
                new String[] {"Auto", "Landscape", "Portrait"},
                indexOf(orientations, prefs().getString("orientation", "auto")), true,
                which -> prefs().edit().putString("orientation", orientations[which]).apply()));
        page.addView(choice("Touch controls", "Buttons and a circle pad on the screen. They hide while a gamepad is used.",
                new String[] {"On", "Off"}, "false".equals(setting("touch_controls", "true")) ? 1 : 0, true,
                which -> putSetting("touch_controls", which == 0 ? "true" : "false")));
        String[] aspects = {"Original", "Integer", "FourThree", "SixteenTen", "SixteenNine", "Stretch"};
        page.addView(choice("Aspect ratio", "The screens' shape: as on the 3DS, scaled by whole pixels, the top one wider or narrower, or both stretched to fill.",
                new String[] {"Original", "Integer scale", "4:3", "16:10", "16:9", "Stretch"},
                indexOf(aspects, setting("aspect", "\"Original\"").replace("\"", "")), true,
                which -> putSetting("aspect", "\"" + aspects[which] + "\"")));
        String[] resolutions = {"Native", "1.25x", "1.5x", "1.75x", "2x", "2.25x", "2.5x", "2.75x", "3x", "3.25x", "3.5x"};
        int resolution = 0;
        try {
            resolution = (int) Math.round((Double.parseDouble(setting("resolution", "1.0")) - 1.0) * 4.0);
        } catch (NumberFormatException ignored) {
        }
        page.addView(choice("Resolution", "How sharp the 3D is drawn, times the 3DS's own. Higher takes more of the GPU.",
                resolutions, Math.max(0, Math.min(resolution, resolutions.length - 1)), true,
                which -> putSetting("resolution", String.valueOf(1.0 + which * 0.25))));
        page.addView(choice("Smooth scaling", "Blends the pixels as the screens are scaled to fit. Off is sharper, with uneven pixels.",
                new String[] {"On", "Off"}, "false".equals(setting("smooth", "true")) ? 1 : 0, true,
                which -> putSetting("smooth", which == 0 ? "true" : "false")));
        boolean second = getSystemService(DisplayManager.class).getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).length > 0;
        page.addView(choice("Screens", second ? "With a second display, the bottom screen can go on it by itself." : "For handhelds with a second display. This one has one.",
                new String[] {"Bottom on the second", "Both on this one"}, oneScreen().exists() ? 1 : 0, second,
                which -> {
                    File flag = oneScreen();
                    if (which == 0) {
                        flag.delete();
                    } else {
                        flag.getParentFile().mkdirs();
                        try {
                            new FileOutputStream(flag).close();
                        } catch (IOException ignored) {
                        }
                    }
                }));
        String[] orders = {"name", "recent"};
        page.addView(choice("Game list", "Recent first puts the game played last on top, with how long each was played under its name.",
                new String[] {"By name", "Recent first"}, indexOf(orders, prefs().getString("order", "name")), true,
                which -> {
                    prefs().edit().putString("order", orders[which]).apply();
                    refresh();
                }));
        page.addView(folderCard());
        TextView note = new TextView(this);
        note.setText("Changes take effect the next time a game starts. More settings are in the menu in game, the Menu button or Back, where Edit touch controls moves and sizes them.\n\nAlmond Native " + version());
        note.setTextColor(DIM);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        note.setLineSpacing(dp(2), 1f);
        note.setBackground(rounded(0xFF0F151E, dp(12)));
        note.setPadding(dp(16), dp(20), dp(16), 0);
        page.addView(note);
        devicePage.removeAllViews();
        devicePage.addView(page);
    }

    /**
     * the games folder on this device: where it is, a scan of it for games not
     * added yet, and letting it go again.
     */
    private View folderCard() {
        Uri folder = gamesFolder();
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(16), dp(16), dp(16), dp(16));
        section.setBackground(rounded(SURFACE, dp(16)));
        LinearLayout.LayoutParams shape = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shape.setMargins(dp(12), dp(5), dp(12), dp(5));
        section.setLayoutParams(shape);
        TextView name = new TextView(this);
        name.setText("Games folder");
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        section.addView(name);
        TextView about = new TextView(this);
        about.setText(folder == null
                ? "Not set. Pick the folder on this device your games are kept in: the .3ds dumps in it are listed to build here, and built games in it are added."
                : gamesFolderName(folder) + "\nIts .3ds dumps are listed as Build needed; Scan now finds the new ones.");
        about.setTextColor(DIM);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        about.setLineSpacing(dp(2), 1f);
        about.setPadding(0, dp(3), 0, 0);
        section.addView(about);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(6), 0, 0);
        buttons.addView(button(folder == null ? "Pick a folder" : "Change it",
                v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), ADD_FOLDER)), weight());
        if (folder != null) {
            buttons.addView(button("Scan now", v -> scanFolder(folder)), weight());
            buttons.addView(button("Forget", v -> {
                prefs().edit().remove("gamesfolder").remove("dumps").apply();
                refresh();
                showDevice(true);
            }), weight());
        }
        section.addView(buttons);
        return section;
    }

    /**
     * the games folder read again: the built games in it added, and the .3ds
     * dumps in it found, so that the list says what is left to build and from
     * which file.
     */
    private void scanFolder(Uri tree) {
        inBackground("Scanning", progress -> withCleanUp(() -> {
            int added = importFolder(tree, progress);
            List<Document> found = new ArrayList<>();
            findDumps(tree, DocumentsContract.getTreeDocumentId(tree), 0, found);
            Set<String> ids = new HashSet<>();
            StringBuilder dumps = new StringBuilder();
            for (Document dump : found) {
                String id;
                try (InputStream header = open(tree, dump.id)) {
                    id = titleId(header);
                } catch (IOException not) {
                    continue;
                }
                if (!ids.add(id)) {
                    continue;
                }
                dumps.append(id).append(' ').append(DocumentsContract.buildDocumentUriUsingTree(tree, dump.id)).append('\n');
            }
            prefs().edit().putString("dumps", dumps.toString()).apply();
            String foundText = ids.isEmpty() ? "no .3ds dumps" : ids.size() == 1 ? "1 game" : ids.size() + " games";
            String addedText = added == 0 ? "" : ", " + (added == 1 ? "1 built game added" : added + " built games added");
            return "Found " + foundText + " in the folder" + addedText;
        }));
    }

    /** every .3ds in a picked tree, a few levels down: a library of dumps, however it is laid out. */
    private void findDumps(Uri tree, String parent, int depth, List<Document> found) {
        List<Document> here = children(tree, parent);
        for (Document document : here) {
            if (!document.folder && document.name.toLowerCase(Locale.ROOT).endsWith(".3ds")) {
                found.add(document);
            }
        }
        if (depth < 4) {
            for (Document document : here) {
                if (document.folder) {
                    findDumps(tree, document.id, depth + 1, found);
                }
            }
        }
    }

    /** the dumps the games folder gave, each title's where it is. */
    private Map<String, Uri> dumps() {
        Map<String, Uri> found = new LinkedHashMap<>();
        for (String line : prefs().getString("dumps", "").split("\n")) {
            int space = line.indexOf(' ');
            if (space > 0) {
                found.put(line.substring(0, space), Uri.parse(line.substring(space + 1)));
            }
        }
        return found;
    }

    /** the folder on the device games are read from, as it was picked, null for none. */
    private Uri gamesFolder() {
        String saved = prefs().getString("gamesfolder", null);
        return saved == null ? null : Uri.parse(saved);
    }

    /** a picked folder's path below its volume, as the picker names it: "Native 3ds". */
    private static String gamesFolderName(Uri tree) {
        String id = DocumentsContract.getTreeDocumentId(tree);
        int colon = id.indexOf(':');
        String path = colon < 0 ? id : id.substring(colon + 1);
        return path.isEmpty() ? "This device" : path;
    }

    private interface Pick {
        void picked(int which);
    }

    /**
     * a setting: its name, what it does, and a button for each choice, three
     * to a row, the one chosen lit.
     */
    private View choice(String title, String help, String[] labels, int chosen, boolean enabled, Pick pick) {
        // a setting on a card of its own: its name, what it does, then the
        // buttons as a segmented control
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setPadding(dp(16), dp(16), dp(16), dp(16));
        section.setBackground(rounded(SURFACE, dp(16)));
        LinearLayout.LayoutParams shape = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        shape.setMargins(dp(12), dp(5), dp(12), dp(5));
        section.setLayoutParams(shape);
        TextView name = new TextView(this);
        name.setText(title);
        name.setTextColor(TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        name.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        section.addView(name);
        TextView about = new TextView(this);
        about.setText(help);
        about.setTextColor(DIM);
        about.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        about.setLineSpacing(dp(2), 1f);
        about.setPadding(0, dp(3), 0, 0);
        section.addView(about);
        List<Button> buttons = new ArrayList<>();
        // as many to a row as fit at 110dp each, the rows even
        int fit = Math.max(2, (getResources().getConfiguration().screenWidthDp - 32) / 110);
        int rows = (labels.length + fit - 1) / fit;
        int across = (labels.length + rows - 1) / rows;
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % across == 0) {
                row = new LinearLayout(this);
                row.setPadding(0, dp(6), 0, 0);
                section.addView(row);
            }
            final int which = i;
            Button button = button(labels[i], v -> {
                for (Button other : buttons) {
                    other.setSelected(other == v);
                }
                pick.picked(which);
            });
            button.setSelected(i == chosen);
            button.setEnabled(enabled);
            button.setAlpha(enabled ? 1f : 0.4f);
            buttons.add(button);
            row.addView(button, weight());
        }
        return section;
    }

    /** a dialog on the app's own dark face, not the device's bright one. */
    private AlertDialog.Builder asks() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this, android.R.style.Theme_Material_Dialog_Alert));
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                return i;
            }
        }
        return 0;
    }

    /** the settings every game shares, which the menu in game sets too. */
    private File settingsFile() {
        return new File(new File(getFilesDir(), "config"), "settings.toml");
    }

    /** a setting's value as settings.toml has it, quotes and all. */
    private String setting(String key, String fallback) {
        try {
            for (String line : new String(readAll(settingsFile()), StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2 && parts[0].trim().equals(key)) {
                    return parts[1].trim();
                }
            }
        } catch (IOException ignored) {
        }
        return fallback;
    }

    /** a setting's value into settings.toml, at the top, where no table holds it. */
    private void putSetting(String key, String value) {
        File file = settingsFile();
        List<String> lines = new ArrayList<>();
        try {
            for (String line : new String(readAll(file), StandardCharsets.UTF_8).split("\n")) {
                String[] parts = line.split("=", 2);
                if (!line.isEmpty() && !(parts.length == 2 && parts[0].trim().equals(key))) {
                    lines.add(line);
                }
            }
        } catch (IOException ignored) {
            // none yet, the games' defaults hold
        }
        lines.add(0, key + " = " + value);
        file.getParentFile().mkdirs();
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write((String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) {
            Toast.makeText(this, "Could not save the setting: " + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration changed) {
        super.onConfigurationChanged(changed);
        // turned: the choices laid out again for the new width
        if (devicePage.getVisibility() == View.VISIBLE) {
            showDevice(true);
        } else if (gamePage.getVisibility() == View.VISIBLE && opened != null) {
            showGame(opened);
        }
    }

    @Override
    public void onBackPressed() {
        if (gamePage.getVisibility() == View.VISIBLE) {
            showGame(null);
        } else if (devicePage.getVisibility() == View.VISIBLE) {
            showDevice(false);
        } else {
            super.onBackPressed();
        }
    }

    // ---- updating

    private String version() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception error) {
            return "0";
        }
    }

    /** whether release version a, as 1.2.3, is newer than b. */
    private static boolean newer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) {
                return p > q;
            }
        }
        return false;
    }

    private static int parse(String number) {
        try {
            return Integer.parseInt(number.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private void update() {
        Toast.makeText(this, "Looking for a new version...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String message = null;
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES).openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                JSONObject release;
                try (InputStream in = connection.getInputStream()) {
                    java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        text.write(buffer, 0, read);
                    }
                    release = new JSONObject(text.toString("UTF-8"));
                }
                String latest = release.getString("tag_name").replaceFirst("^v", "");
                String apk = null;
                JSONArray assets = release.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".apk")) {
                        apk = asset.getString("browser_download_url");
                    }
                }
                if (!newer(latest, version())) {
                    message = "Almond Native " + version() + " is the latest";
                } else if (apk == null) {
                    message = "Almond Native " + latest + " is out, but has no Android app yet";
                } else {
                    String url = apk;
                    runOnUiThread(() -> asks()
                            .setTitle("Almond Native " + latest)
                            .setMessage("Download and install it? Your games and saves stay.")
                            .setPositiveButton("Update", (dialog, which) -> download(url))
                            .setNegativeButton("Not now", null)
                            .show());
                }
            } catch (Exception error) {
                message = "Could not reach GitHub: " + error.getMessage();
            }
            String shown = message;
            if (shown != null) {
                runOnUiThread(() -> Toast.makeText(this, shown, Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void download(String url) {
        inBackground("Updating", progress -> {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            File apk = new File(getCacheDir(), "update.apk");
            try (InputStream in = connection.getInputStream()) {
                copy(in, apk, "the update", connection.getContentLengthLong(), progress);
            }
            PackageInstaller installer = getPackageManager().getPackageInstaller();
            int id = installer.createSession(new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL));
            try (PackageInstaller.Session session = installer.openSession(id)) {
                try (OutputStream out = session.openWrite("almond-native.apk", 0, apk.length()); InputStream in = new FileInputStream(apk)) {
                    byte[] buffer = new byte[1 << 20];
                    int read;
                    while ((read = in.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                    session.fsync(out);
                }
                Intent done = new Intent(this, Launcher.class).setAction("installed");
                PendingIntent pending = PendingIntent.getActivity(this, 0, done, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pending.getIntentSender());
            }
            return null;
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        playShortcut(intent);
        // a build asked for while the launcher is already up
        buildShortcut(intent);
        viewed(intent);
        if (intent.getBooleanExtra("selftest", false)) {
            selftest();
        }
        // the installer asks the person first, through the intent it sends back
        if ("installed".equals(intent.getAction())
                && intent.getIntExtra(PackageInstaller.EXTRA_STATUS, 0) == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                startActivity(confirm);
            }
        }
    }
}
