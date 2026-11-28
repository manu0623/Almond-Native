package io.github.almondnative;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Log;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * What the launcher reads out of a game dump itself: which title it is, and
 * the name and icon its banner gives. A dump is a cartridge image (.3ds,
 * .cci), a .cia or a bare .cxi, decrypted, and the executable partition, the
 * NCCH, is found in each the way recompiler/src/rom.rs finds it, so the app
 * and the recompiler agree on the program id a game's folder and library are
 * named after.
 */
final class Rom {
    private static final String TAG = "AlmondRom";
    /** offsets and sizes in NCSD and NCCH headers count these */
    private static final long MEDIA_UNIT = 0x200;
    /** a .cia's header size, which is also its first field */
    private static final int CIA_HEADER_SIZE = 0x2020;

    private Rom() {
    }

    /** reads a part of a dump, at an offset from its start */
    private interface Reader {
        byte[] read(long offset, int length) throws IOException;
    }

    /** the program id of the dump in the file, as 16 hex digits */
    static String titleId(File rom) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            return titleId(random(in));
        }
    }

    /**
     * the program id of a dump read from its start, as 16 hex digits: a
     * picked document is a stream, so it is read forward only, the first
     * header kept for what is asked of it again
     */
    static String titleId(InputStream in) throws IOException {
        return titleId(forward(in));
    }

    private static String titleId(Reader reader) throws IOException {
        long ncch = ncchOffset(reader);
        byte[] header = reader.read(ncch, 0x200);
        if (!magic(header, 0x100, "NCCH")) {
            throw new IOException("the game's executable partition has no NCCH header");
        }
        return String.format("%016X", le64(header, 0x118));
    }

    /** where the executable NCCH starts: a cartridge's first partition, a .cia's first content */
    private static long ncchOffset(Reader reader) throws IOException {
        byte[] start = reader.read(0, 0x200);
        if (magic(start, 0x100, "NCSD")) {
            return le32(start, 0x120) * MEDIA_UNIT;
        }
        if (magic(start, 0x100, "NCCH")) {
            return 0;
        }
        if (le32(start, 0) == CIA_HEADER_SIZE) {
            return ciaExecutable(start, reader);
        }
        throw new IOException("not a 3DS cartridge image, .cia or executable");
    }

    /** the header, certificates, ticket, TMD and contents each start on 64 bytes */
    private static long align(long n) {
        return (n + 0x3F) & ~0x3FL;
    }

    private static long ciaExecutable(byte[] header, Reader reader) throws IOException {
        byte[] bitmap = reader.read(0x20, 0x2000);
        long tmdOffset = align(align(align(CIA_HEADER_SIZE) + le32(header, 0x08)) + le32(header, 0x0C));
        int tmdSize = (int) le32(header, 0x10);
        long contents = align(tmdOffset + tmdSize);
        byte[] tmd = reader.read(tmdOffset, tmdSize);
        int signature;
        switch ((int) (be32(tmd, 0) & 0xFFFF)) {
            case 0x0000:
            case 0x0003:
                signature = 0x240;
                break;
            case 0x0001:
            case 0x0004:
                signature = 0x140;
                break;
            case 0x0002:
            case 0x0005:
                signature = 0x80;
                break;
            default:
                throw new IOException("the .cia's TMD has an unknown signature");
        }
        long kind = be64(tmd, signature + 0x4C) >>> 32;
        if (kind == 0x0004000EL) {
            throw new IOException("this .cia is an update, not a game");
        }
        if (kind == 0x0004008CL) {
            throw new IOException("this .cia is downloadable content, not a game");
        }
        long offset = contents;
        int count = be16(tmd, signature + 0x9E);
        for (int i = 0; i < count; i++) {
            int record = signature + 0xC4 + 64 * 0x24 + i * 0x30;
            int index = be16(tmd, record + 4);
            if ((bitmap[index / 8] & (0x80 >> (index % 8))) == 0) {
                continue;
            }
            if (index == 0) {
                if ((be16(tmd, record + 6) & 1) != 0) {
                    throw new IOException("the .cia is encrypted, it has to be decrypted first");
                }
                return offset;
            }
            offset = align(offset + be64(tmd, record + 8));
        }
        throw new IOException("the .cia has no executable");
    }

    /** name.txt and icon.png from the game's banner, its SMDH, when it has one. */
    static void banner(File rom, File folder) {
        try (RandomAccessFile in = new RandomAccessFile(rom, "r")) {
            Reader reader = random(in);
            long first = ncchOffset(reader);
            byte[] ncch = reader.read(first, 0x200);
            if (!magic(ncch, 0x100, "NCCH")) {
                return;
            }
            long exefs = first + le32(ncch, 0x1A0) * MEDIA_UNIT;
            byte[] entries = reader.read(exefs, 10 * 16);
            for (int entry = 0; entry < 10; entry++) {
                if (!new String(entries, entry * 16, 8, StandardCharsets.US_ASCII).startsWith("icon")) {
                    continue;
                }
                long offset = le32(entries, entry * 16 + 8);
                byte[] smdh = reader.read(exefs + 0x200 + offset, 0x36C0);
                if (!magic(smdh, 0, "SMDH")) {
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
        } catch (IOException error) {
            // the game still plays, named after its profile or its title id
            Log.w(TAG, "no name or icon from " + rom + "'s banner", error);
        }
    }

    // ---- readers

    private static Reader random(RandomAccessFile in) {
        return (offset, length) -> {
            byte[] bytes = new byte[length];
            in.seek(offset);
            in.readFully(bytes);
            return bytes;
        };
    }

    /**
     * a stream read forward: the first CIA_HEADER_SIZE bytes are kept, which
     * covers every header that is asked for again, and anything past them is
     * skipped to and read in order
     */
    private static Reader forward(InputStream in) throws IOException {
        byte[] head = readUpTo(in, CIA_HEADER_SIZE);
        long[] position = {head.length};
        return (offset, length) -> {
            if (offset + length <= head.length) {
                return Arrays.copyOfRange(head, (int) offset, (int) offset + length);
            }
            if (offset < position[0]) {
                throw new IOException("the dump's headers are out of order");
            }
            skipFully(in, offset - position[0]);
            byte[] bytes = readUpTo(in, length);
            if (bytes.length < length) {
                throw new EOFException("the dump ends before its headers do");
            }
            position[0] = offset + length;
            return bytes;
        };
    }

    private static byte[] readUpTo(InputStream in, int count) throws IOException {
        byte[] bytes = new byte[count];
        int at = 0;
        while (at < count) {
            int read = in.read(bytes, at, count - at);
            if (read < 0) {
                return Arrays.copyOf(bytes, at);
            }
            at += read;
        }
        return bytes;
    }

    private static void skipFully(InputStream in, long count) throws IOException {
        while (count > 0) {
            long skipped = in.skip(count);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("the dump ends before its headers do");
                }
                skipped = 1;
            }
            count -= skipped;
        }
    }

    // ---- fields

    private static boolean magic(byte[] bytes, int at, String magic) {
        return bytes.length >= at + 4 && new String(bytes, at, 4, StandardCharsets.US_ASCII).equals(magic);
    }

    private static long le32(byte[] b, int at) {
        return (b[at] & 0xFFL) | ((b[at + 1] & 0xFFL) << 8) | ((b[at + 2] & 0xFFL) << 16) | ((b[at + 3] & 0xFFL) << 24);
    }

    private static long le64(byte[] b, int at) {
        return le32(b, at) | (le32(b, at + 4) << 32);
    }

    private static int be16(byte[] b, int at) {
        return ((b[at] & 0xFF) << 8) | (b[at + 1] & 0xFF);
    }

    private static long be32(byte[] b, int at) {
        return ((b[at] & 0xFFL) << 24) | ((b[at + 1] & 0xFFL) << 16) | ((b[at + 2] & 0xFFL) << 8) | (b[at + 3] & 0xFFL);
    }

    private static long be64(byte[] b, int at) {
        return (be32(b, at) << 32) | be32(b, at + 4);
    }

    /** whether a file's name is one of the dumps the app can build */
    static boolean isDump(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return lower.endsWith(".3ds") || lower.endsWith(".cci") || lower.endsWith(".cia") || lower.endsWith(".cxi");
    }
}
