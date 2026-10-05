package tftp.server;

import static org.junit.jupiter.api.Assertions.*;
import static tftp.common.Packets.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;

import tftp.common.TftpEncoderDecoder;

/** A test client that speaks raw packets, so tests can check exactly what the server sends. */
final class RawClient implements AutoCloseable {

    private final Socket sock;
    private final InputStream in;
    private final OutputStream out;
    private final TftpEncoderDecoder decoder = new TftpEncoderDecoder();
    /** Broadcasts that arrived while waiting for something else. */
    final Deque<byte[]> broadcasts = new ArrayDeque<>();

    RawClient(int port) throws IOException {
        sock = new Socket("localhost", port);
        sock.setSoTimeout(5000);
        in = new BufferedInputStream(sock.getInputStream());
        out = sock.getOutputStream();
    }

    void send(byte[] packet) throws IOException {
        out.write(packet);
        out.flush();
    }

    /** The next packet that is not a broadcast. */
    byte[] next() throws IOException {
        while (true) {
            byte[] packet = nextAny();
            if (opcode(packet) != BCAST)
                return packet;
            broadcasts.add(packet);
        }
    }

    byte[] nextAny() throws IOException {
        int read;
        while ((read = in.read()) >= 0) {
            byte[] packet = decoder.decodeNextByte((byte) read);
            if (packet != null)
                return packet;
        }
        throw new IOException("connection closed");
    }

    /** True if the server sends nothing within the timeout. */
    boolean silentFor(int millis) throws IOException {
        sock.setSoTimeout(millis);
        try {
            in.read();
            return false;
        } catch (SocketTimeoutException e) {
            return true;
        } finally {
            sock.setSoTimeout(5000);
        }
    }

    boolean closedByServer() throws IOException {
        return in.read() == -1;
    }

    void expectAck(int block) throws IOException {
        byte[] packet = next();
        assertEquals(ACK, opcode(packet), () -> "expected ACK, got " + describe(packet));
        assertEquals(block, u16(packet, 2));
    }

    void expectError(int code) throws IOException {
        byte[] packet = next();
        assertEquals(ERROR, opcode(packet), () -> "expected ERROR " + code + ", got " + describe(packet));
        assertEquals(code, u16(packet, 2), () -> string(packet, 4));
    }

    void login(String name) throws IOException {
        send(request(LOGRQ, name));
        expectAck(0);
    }

    /** RRQ or DIRQ: collects DATA blocks, acknowledging each, until a short one ends it. */
    byte[] download(byte[] request) throws IOException {
        send(request);
        ByteArrayOutputStream received = new ByteArrayOutputStream();
        int expected = 1;
        while (true) {
            byte[] packet = next();
            assertEquals(DATA, opcode(packet), () -> "expected DATA, got " + describe(packet));
            assertEquals(expected & 0xFFFF, u16(packet, 4));
            int size = u16(packet, 2);
            received.write(packet, 6, size);
            send(ack(u16(packet, 4)));
            expected++;
            if (size < MAX_DATA)
                return received.toByteArray();
        }
    }

    void upload(String name, byte[] content) throws IOException {
        send(request(WRQ, name));
        expectAck(0);
        int block = 0;
        int offset = 0;
        while (true) {
            int length = Math.min(MAX_DATA, content.length - offset);
            send(data(++block, content, offset, length));
            expectAck(block);
            offset += length;
            if (length < MAX_DATA)
                return;
        }
    }

    String[] dir() throws IOException {
        String names = new String(download(bare(DIRQ)), StandardCharsets.UTF_8);
        return names.isEmpty() ? new String[0] : names.split("\0");
    }

    static String describe(byte[] packet) {
        return opcode(packet) == ERROR
                ? "ERROR " + u16(packet, 2) + " " + string(packet, 4)
                : "opcode " + opcode(packet);
    }

    @Override
    public void close() throws IOException {
        sock.close();
    }
}
