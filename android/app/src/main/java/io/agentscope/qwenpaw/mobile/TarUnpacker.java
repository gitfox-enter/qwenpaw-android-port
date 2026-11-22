package io.agentscope.qwenpaw.mobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streaming tar reader for the app's own payload.
 *
 * The runtime parts are concatenated before parsing, so bytes arrive in
 * arbitrary chunks and nothing may assume a whole record is buffered. Only the
 * record types this payload contains are handled: directories, regular files,
 * GNU long names, and PAX extended headers (which is what the payload actually
 * uses to carry paths longer than the 100-byte field).
 */
final class TarUnpacker {

    private static final int BLOCK = 512;

    private enum State {
        HEADER,   // waiting for a 512-byte record header
        META,     // waiting for an in-memory header body (long name / PAX)
        BODY,     // streaming file bytes to disk
        DISCARD,  // skipping bytes we do not keep
        PAD,      // skipping the block padding that follows a body
    }

    private final File dest;

    /**
     * When true the parser advances through the stream without touching the
     * filesystem. Used to replay already-parsed parts after an interrupted
     * extraction so the record state machine stays aligned.
     */
    private boolean discarding;

    private byte[] buf = new byte[BLOCK * 8];
    private int len;

    private State state = State.HEADER;
    private OutputStream out;
    private long remaining;      // bytes still owed by the current state
    private long padLeft;        // padding owed after the last body
    private long metaSize;       // declared size of an in-memory body
    private char metaType;       // 'L' or 'x'
    private String pendingName;
    private boolean finished;

    TarUnpacker(File dest) {
        this.dest = dest;
    }

    /** Skip writing to disk while still parsing every record. */
    void setDiscarding(boolean on) {
        this.discarding = on;
    }

    void write(byte[] data, int off, int count) throws IOException {
        if (finished) return;
        int i = 0;
        while (i < count && !finished) {
            int slice = Math.min(count - i, BLOCK * 4);
            append(data, off + i, slice);
            i += slice;
            pump();
        }
    }

    void finish() throws IOException {
        closeOut();
        if (!finished && state != State.HEADER) {
            throw new IOException("tar 数据不完整");
        }
        finished = true;
    }

    // ------------------------------------------------------------ machinery

    private void append(byte[] data, int off, int count) {
        if (len + count > buf.length) {
            byte[] bigger = new byte[Math.max(buf.length * 2, len + count)];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(data, off, buf, len, count);
        len += count;
    }

    private void drop(int n) {
        if (n <= 0) return;
        if (n >= len) {
            len = 0;
            return;
        }
        System.arraycopy(buf, n, buf, 0, len - n);
        len -= n;
    }

    private void pump() throws IOException {
        while (!finished) {
            switch (state) {
                case BODY: {
                    int take = (int) Math.min(remaining, len);
                    if (take > 0) {
                        if (out != null) out.write(buf, 0, take);
                        drop(take);
                        remaining -= take;
                    }
                    if (remaining > 0) return;
                    closeOut();
                    state = State.PAD;
                    break;
                }

                case DISCARD: {
                    int take = (int) Math.min(remaining, len);
                    if (take > 0) {
                        drop(take);
                        remaining -= take;
                    }
                    if (remaining > 0) return;
                    state = State.PAD;
                    break;
                }

                case PAD: {
                    int take = (int) Math.min(padLeft, len);
                    if (take > 0) {
                        drop(take);
                        padLeft -= take;
                    }
                    if (padLeft > 0) return;
                    state = State.HEADER;
                    break;
                }

                case META: {
                    if (len < metaSize) return;   // wait for the rest
                    int n = (int) metaSize;
                    int end = n;
                    while (end > 0 && buf[end - 1] == 0) end--;
                    String text = new String(buf, 0, end, StandardCharsets.UTF_8);
                    drop(n);
                    if (metaType == 'L') {
                        pendingName = text;
                    } else {
                        String path = paxValue(text, "path");
                        if (path != null) pendingName = path;
                    }
                    padLeft = padding(metaSize);
                    state = padLeft > 0 ? State.PAD : State.HEADER;
                    break;
                }

                case HEADER: {
                    if (len < BLOCK) return;
                    if (isZeroBlock()) {
                        finished = true;   // end-of-archive marker
                        return;
                    }
                    if (!checksumOk()) {
                        finished = true;
                        throw new IOException("tar 头部校验失败");
                    }
                    readHeader();
                    break;
                }

                default:
                    return;
            }
        }
    }

    private void readHeader() throws IOException {
        String name = cstr(0, 100);
        String prefix = cstr(345, 155);
        if (!prefix.isEmpty()) name = prefix + "/" + name;
        long size = octal(124, 12);
        char type = (char) (buf[156] & 0xff);

        drop(BLOCK);

        switch (type) {
            case 'L':
            case 'x':
                metaType = type;
                metaSize = size;
                state = State.META;
                return;

            case 'g':
                beginSkip(size);
                return;

            case '5':
                if (!discarding) {
                    new File(dest, name).mkdirs();
                }
                beginSkip(size);
                return;

            case '0':
            case 0:
            case '7': {
                if (pendingName != null) {
                    name = pendingName;
                    pendingName = null;
                }
                if (discarding) {
                    beginSkip(size);
                    return;
                }
                File f = new File(dest, name);
                File parent = f.getParentFile();
                if (parent != null) parent.mkdirs();
                try {
                    out = new FileOutputStream(f);
                } catch (IOException e) {
                    out = null;   // reserved or unwritable name
                }
                remaining = size;
                padLeft = padding(size);
                state = size > 0 ? State.BODY : State.PAD;
                if (size == 0) closeOut();
                return;
            }

            default:
                beginSkip(size);
        }
    }

    private void beginSkip(long size) {
        remaining = size;
        padLeft = padding(size);
        state = size > 0 ? State.DISCARD : State.PAD;
    }

    private static long padding(long size) {
        long rem = size % BLOCK;
        return rem == 0 ? 0 : BLOCK - rem;
    }

    private void closeOut() throws IOException {
        if (out != null) {
            out.close();
            out = null;
        }
    }

    private boolean isZeroBlock() {
        for (int i = 0; i < BLOCK; i++) {
            if (buf[i] != 0) return false;
        }
        return true;
    }

    private boolean checksumOk() {
        long want = octal(148, 8);
        if (want == 0) return false;
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (buf[i] & 0xff);
        }
        return sum == want;
    }

    private String cstr(int off, int max) {
        int end = off;
        while (end < off + max && buf[end] != 0) end++;
        return new String(buf, off, end - off, StandardCharsets.UTF_8);
    }

    private long octal(int off, int max) {
        long v = 0;
        for (int i = off; i < off + max; i++) {
            byte c = buf[i];
            if (c == 0 || c == ' ') continue;
            if (c < '0' || c > '7') break;
            v = v * 8 + (c - '0');
        }
        return v;
    }

    /** Pull one "key=value" line out of a PAX payload. */
    private static String paxValue(String pax, String key) {
        String tag = key + "=";
        int i = 0;
        while (i < pax.length()) {
            int nl = pax.indexOf('\n', i);
            if (nl < 0) nl = pax.length();
            String line = pax.substring(i, nl);
            int sp = line.indexOf(' ');
            if (sp > 0 && line.startsWith(tag, sp + 1)) {
                return line.substring(sp + 1 + tag.length());
            }
            i = nl + 1;
        }
        return null;
    }
}