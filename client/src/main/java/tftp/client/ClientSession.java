package tftp.client;

import static tftp.common.Packets.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The client's state, shared by two threads: the keyboard thread starts an operation and waits,
 * and the listening thread answers the server and says when the operation is over.
 *
 * Both run under this object's lock, and the keyboard thread waits on a condition
 * ({@code pending != NONE}) rather than on a bare wait(). A reply can arrive before the
 * keyboard thread starts waiting, and a command can fail before anything is sent; with a
 * condition, neither leaves it waiting forever.
 */
final class ClientSession {

    private enum Op { NONE, LOGIN, READ, WRITE, DIR, DELETE, DISC }

    private final Path dir;
    private final Sender sender;
    private final PrintStream out;

    private Op pending = Op.NONE;
    private boolean closed;
    private boolean disconnecting;
    private String fileName;
    private ByteArrayOutputStream incoming;
    private byte[] outgoing;
    private int blocksSent;
    private boolean lastBlockSent;

    interface Sender {
        void send(byte[] packet) throws IOException;
    }

    ClientSession(Path dir, Sender sender, PrintStream out) {
        this.dir = dir;
        this.sender = sender;
        this.out = out;
    }

    /**
     * Runs one typed command and waits until the server has finished it.
     * Returns false once the session is over.
     */
    synchronized boolean command(String line) throws IOException, InterruptedException {
        if (closed)
            return false;
        String[] parts = line.trim().split("\\s+", 2);
        String arg = parts.length > 1 ? parts[1] : "";

        byte[] packet = switch (parts[0]) {
            case "LOGRQ" -> needsArg(arg) ? begin(Op.LOGIN, request(LOGRQ, arg)) : null;
            case "DELRQ" -> needsArg(arg) ? begin(Op.DELETE, request(DELRQ, arg)) : null;
            case "RRQ" -> needsArg(arg) ? startRead(arg) : null;
            case "WRQ" -> needsArg(arg) ? startWrite(arg) : null;
            case "DIRQ" -> {
                incoming = new ByteArrayOutputStream();
                yield begin(Op.DIR, bare(DIRQ));
            }
            case "DISC" -> {
                disconnecting = true;
                yield begin(Op.DISC, bare(DISC));
            }
            default -> {
                out.println("Invalid command");
                yield null;
            }
        };
        if (packet == null)
            return true;

        Op op = pending;
        sender.send(packet);
        while (pending != Op.NONE && !closed)
            wait();
        return op != Op.DISC && !closed;
    }

    /** Called by the listening thread for every packet from the server. */
    synchronized void receive(byte[] packet) throws IOException {
        switch (opcode(packet)) {
            case ACK -> acknowledged(u16(packet, 2));
            case DATA -> receiveBlock(packet);
            case ERROR -> {
                out.println("Error " + u16(packet, 2) + " " + string(packet, 4));
                finish();
            }
            case BCAST -> out.println("BCAST " + (packet[2] == 0 ? "del " : "add ") + string(packet, 3));
            default -> {
            }
        }
    }

    /**
     * Called by the listening thread when the connection ends. Returns false if the server
     * closed it without being asked to.
     */
    synchronized boolean connectionClosed() {
        closed = true;
        notifyAll();
        return disconnecting;
    }

    private boolean needsArg(String arg) {
        if (!arg.isEmpty())
            return true;
        out.println("Invalid command - missing argument");
        return false;
    }

    private byte[] begin(Op op, byte[] packet) {
        pending = op;
        return packet;
    }

    private byte[] startRead(String name) {
        Path file = local(name);
        if (file == null)
            return null;
        if (Files.exists(file)) {
            out.println("File already exists");
            return null;
        }
        fileName = name;
        incoming = new ByteArrayOutputStream();
        return begin(Op.READ, request(RRQ, name));
    }

    private byte[] startWrite(String name) throws IOException {
        Path file = local(name);
        if (file == null)
            return null;
        if (!Files.isRegularFile(file)) {
            out.println("File does not exist");
            return null;
        }
        fileName = name;
        outgoing = Files.readAllBytes(file);
        blocksSent = 0;
        lastBlockSent = false;
        return begin(Op.WRITE, request(WRQ, name));
    }

    private void acknowledged(int block) throws IOException {
        out.println("ACK " + block);
        switch (pending) {
            case WRITE -> {
                if (lastBlockSent) {
                    out.println("WRQ " + fileName + " complete");
                    finish();
                } else {
                    sendNextBlock();
                }
            }
            case LOGIN, DELETE, DISC -> finish();
            default -> {
            }
        }
    }

    /** Same rule as the server: a block shorter than 512 bytes ends the transfer. */
    private void sendNextBlock() throws IOException {
        int offset = blocksSent * MAX_DATA;
        int length = Math.min(MAX_DATA, outgoing.length - offset);
        blocksSent++;
        lastBlockSent = length < MAX_DATA;
        sender.send(data(blocksSent & 0xFFFF, outgoing, offset, length));
    }

    private void receiveBlock(byte[] packet) throws IOException {
        if (pending != Op.READ && pending != Op.DIR)
            return;
        int size = u16(packet, 2);
        incoming.write(packet, 6, size);
        sender.send(ack(u16(packet, 4)));
        if (size == MAX_DATA)
            return;

        if (pending == Op.READ) {
            Files.write(dir.resolve(fileName), incoming.toByteArray(), StandardOpenOption.CREATE_NEW);
            out.println("RRQ " + fileName + " complete");
        } else {
            String names = incoming.toString(StandardCharsets.UTF_8);
            if (!names.isEmpty())
                for (String name : names.split("\0"))
                    out.println(name);
        }
        finish();
    }

    private void finish() {
        pending = Op.NONE;
        fileName = null;
        incoming = null;
        outgoing = null;
        notifyAll();
    }

    private Path local(String name) {
        try {
            return dir.resolve(name);
        } catch (InvalidPathException e) {
            out.println("Invalid file name");
            return null;
        }
    }
}
