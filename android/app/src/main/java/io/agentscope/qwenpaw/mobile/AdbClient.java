package io.agentscope.qwenpaw.mobile;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.security.KeyPair;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.math.BigInteger;

/**
 * Minimal self-contained ADB client for wireless-debugging self-pairing.
 *
 * The app connects to its own adbd over loopback, authenticates with its RSA
 * public key, and runs shell commands — no external adb binary and no separate
 * Shizuku app required. Protocol constants follow the ADB wire spec
 * (24-byte header, little-endian uint32 fields).
 */
public class AdbClient {

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_WRTE = 0x45545257;
    private static final int A_CLSE = 0x45534c43;

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    private static final int ADB_VERSION = 0x01000001;
    private static final int ADB_MAXDATA = 262144;

    /** SHA1 DigestInfo prefix for the adb signature scheme. */
    private static final byte[] DIGESTINFO_SHA1 = {
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    private final KeyPair keyPair;
    private final byte[] publicKeyPem;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private int localId = 1;

    public AdbClient(KeyPair keyPair) {
        this.keyPair = keyPair;
        this.publicKeyPem = encodePublicKeyPem(keyPair.getPublic());
    }

    /** Encode the RSA public key as the "ssh-rsa <base64>\n" PEM adb expects. */
    static byte[] encodePublicKeyPem(java.security.PublicKey pub) {
        try {
            RSAPublicKey rsa = (RSAPublicKey) pub;
            BigInteger e = rsa.getPublicExponent();
            BigInteger n = rsa.getModulus();
            byte[] eBytes = e.toByteArray();
            byte[] nBytes = n.toByteArray();
            ByteArrayOutputStream ssh = new ByteArrayOutputStream();
            ssh.write("ssh-rsa".getBytes("UTF-8"));
            ssh.write(intToBytes(eBytes.length));
            ssh.write(eBytes);
            ssh.write(intToBytes(nBytes.length));
            ssh.write(nBytes);
            String base64 = Base64.getEncoder().encodeToString(ssh.toByteArray());
            return ("ssh-rsa " + base64 + "\n").getBytes("UTF-8");
        } catch (Exception ex) {
            throw new IllegalStateException("encode public key", ex);
        }
    }

    /** Connect to adbd and complete the CNXN/AUTH handshake. */
    public void connect(String host, int port) throws IOException {
        openSocket(host, port);
        sendCnxn(null);
        authHandshake(false);
    }

    /** Pair with the pairing port: send the 6-digit code as the CNXN payload. */
    public void pair(String host, int port, String code) throws IOException {
        openSocket(host, port);
        sendCnxn(code.getBytes("UTF-8"));
        authHandshake(true);
    }

    /**
     * Drive the AUTH challenge loop until adbd accepts us and answers CNXN.
     * adbd first asks for our public key (type 1), then presents a 20-byte
     * token (type 1, arg0=1) that we must sign with our private key (type 2).
     * After that it replies CNXN.
     */
    private void authHandshake(boolean pairing) throws IOException {
        AdbMessage first = readMessage();
        if (first.command != A_AUTH) {
            throw new IOException("expected AUTH, got " + cmdName(first.command));
        }
        sendAuthPublicKey();
        AdbMessage msg = readMessage();
        while (msg.command == A_AUTH) {
            int kind = msg.arg0;
            if (kind == ADB_AUTH_TOKEN) {
                sendAuthSignature(msg.data);
            } else if (kind == ADB_AUTH_SIGNATURE) {
                // adbd should not ask us to provide a signature first; it asks
                // for a token to sign. Treat it as a protocol violation.
                throw new IOException("unexpected AUTH signature challenge from adbd");
            } else {
                throw new IOException("unexpected AUTH kind " + kind + " from adbd");
            }
            msg = readMessage();
        }
        if (msg.command != A_CNXN) {
            throw new IOException((pairing ? "pairing auth" : "auth")
                    + " rejected, got " + cmdName(msg.command));
        }
    }

    /** Run a non-interactive shell command, returning stdout+stderr and exit code. */
    public ShellResult execShell(String command) throws IOException {
        int myId = ++localId;
        byte[] service = ("shell:v2,raw:" + command + "\0").getBytes("UTF-8");
        sendMessage(A_OPEN, myId, 0, service);
        AdbMessage okay = readMessage();
        if (okay.command != A_OKAY) {
            throw new IOException("shell OPEN refused: " + cmdName(okay.command));
        }
        int peerId = okay.arg1;

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int[] exitRef = new int[]{-1};

        // Read WRTE packets, parse v2 frames, ack each with OKAY, and keep
        // collecting until adbd closes the stream with CLSE. The exit frame
        // (id 3) may arrive before the final CLSE, so do not stop on it.
        while (true) {
            AdbMessage msg = readMessage();
            if (msg.command == A_WRTE) {
                parseV2Frames(msg.data, stdout, stderr, exitRef);
                // OKAY: arg0 = our local id, arg1 = peer id.
                sendMessage(A_OKAY, myId, peerId, null);
            } else if (msg.command == A_CLSE) {
                break;
            } else {
                throw new IOException("unexpected " + cmdName(msg.command) + " during shell");
            }
        }
        // CLSE: arg0 = our local id, arg1 = peer id.
        sendMessage(A_CLSE, myId, peerId, null);
        try {
            readMessage(); // peer's CLSE
        } catch (IOException ignore) {
        }
        return new ShellResult(stdout.toByteArray(), stderr.toByteArray(), exitRef[0]);
    }

    private void parseV2Frames(byte[] data, ByteArrayOutputStream stdout,
                               ByteArrayOutputStream stderr, int[] exitRef) {
        int off = 0;
        while (off + 5 <= data.length) {
            int packetId = data[off] & 0xff;
            int len = littleEndianInt(data, off + 1);
            off += 5;
            if (off + len > data.length) break;
            byte[] payload = new byte[len];
            System.arraycopy(data, off, payload, 0, len);
            off += len;
            switch (packetId) {
                case 1: stdout.write(payload, 0, len); break;  // stdout
                case 2: stderr.write(payload, 0, len); break;  // stderr
                case 3: if (len >= 1) exitRef[0] = payload[0] & 0xff; break; // exit
                default: break;
            }
        }
    }

    public void close() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignore) {
        }
        socket = null;
        in = null;
        out = null;
    }

    // ------------------------------------------------------------ plumbing

    private void openSocket(String host, int port) throws IOException {
        socket = new Socket(host, port);
        socket.setSoTimeout(15000);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());
    }

    private void sendCnxn(byte[] banner) throws IOException {
        if (banner == null) banner = "host::\0".getBytes("UTF-8");
        sendMessage(A_CNXN, ADB_VERSION, ADB_MAXDATA, banner);
    }

    /** Send the RSA public key as the AUTH RSAPUBLICKEY response. */
    private void sendAuthPublicKey() throws IOException {
        sendMessage(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, publicKeyPem);
    }

    /**
     * Sign the 20-byte AUTH token. ADB signs the raw DigestInfo block
     * (SHA-1 prefix || 20-byte token verbatim) with RSA PKCS#1 v1.5 —
     * it does NOT hash the token again. We build that block and sign it
     * manually with the private exponent so the result is byte-identical
     * to adb's RSA_private_encrypt(NID_sha1, token, 20).
     */
    private void sendAuthSignature(byte[] token) throws IOException {
        java.security.interfaces.RSAPrivateKey priv =
                (java.security.interfaces.RSAPrivateKey) keyPair.getPrivate();
        byte[] signed = new byte[DIGESTINFO_SHA1.length + token.length];
        System.arraycopy(DIGESTINFO_SHA1, 0, signed, 0, DIGESTINFO_SHA1.length);
        System.arraycopy(token, 0, signed, DIGESTINFO_SHA1.length, token.length);

        BigInteger modulus = priv.getModulus();
        BigInteger exponent = priv.getPrivateExponent();
        int emLen = (modulus.bitLength() + 7) / 8;
        int padLen = emLen - 3 - signed.length;
        if (padLen < 8) {
            throw new IOException("RSA key too small for ADB DigestInfo block");
        }
        byte[] em = new byte[emLen];
        em[0] = 0;
        em[1] = 1;
        java.util.Arrays.fill(em, 2, 2 + padLen, (byte) 0xFF);
        em[2 + padLen] = 0;
        System.arraycopy(signed, 0, em, 3 + padLen, signed.length);

        BigInteger message = new BigInteger(1, em);
        BigInteger signature = message.modPow(exponent, modulus);
        byte[] raw = signature.toByteArray();
        if (raw.length > 1 && raw[0] == 0) {
            raw = java.util.Arrays.copyOfRange(raw, 1, raw.length);
        }
        sendMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, raw);
    }

    private void sendMessage(int command, int arg0, int arg1, byte[] data) throws IOException {
        int len = data == null ? 0 : data.length;
        int checksum = 0;
        for (int i = 0; i < len; i++) {
            checksum += data[i] & 0xff;
        }
        out.writeInt(command);
        out.writeInt(arg0);
        out.writeInt(arg1);
        out.writeInt(len);
        out.writeInt(checksum); // data_check = sum of data bytes
        out.writeInt(command ^ 0xffffffff);
        if (len > 0) out.write(data);
        out.flush();
    }

    private AdbMessage readMessage() throws IOException {
        int command = in.readInt();
        int arg0 = in.readInt();
        int arg1 = in.readInt();
        int len = in.readInt();
        in.readInt(); // data_check
        int magic = in.readInt();
        if (magic != (command ^ 0xffffffff)) {
            throw new IOException("bad magic, framing lost");
        }
        byte[] data = new byte[len];
        if (len > 0) in.readFully(data);
        return new AdbMessage(command, arg0, arg1, data);
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
            (byte) (value >>> 24), (byte) (value >>> 16),
            (byte) (value >>> 8), (byte) value
        };
    }

    private static int littleEndianInt(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8)
                | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    private static String cmdName(int cmd) {
        switch (cmd) {
            case A_CNXN: return "CNXN";
            case A_AUTH: return "AUTH";
            case A_OPEN: return "OPEN";
            case A_OKAY: return "OKAY";
            case A_WRTE: return "WRTE";
            case A_CLSE: return "CLSE";
            default: return "0x" + Integer.toHexString(cmd);
        }
    }

    private static final class AdbMessage {
        final int command, arg0, arg1;
        final byte[] data;
        AdbMessage(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    public static final class ShellResult {
        public final byte[] stdout;
        public final byte[] stderr;
        public final int exitCode;
        ShellResult(byte[] stdout, byte[] stderr, int exitCode) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.exitCode = exitCode;
        }
        public String stdoutText() {
            try { return new String(stdout, "UTF-8"); } catch (Exception e) { return ""; }
        }
        public String stderrText() {
            try { return new String(stderr, "UTF-8"); } catch (Exception e) { return ""; }
        }
    }
}
