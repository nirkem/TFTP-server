package tftp.server;

import static tftp.common.Packets.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import tftp.server.net.BidiMessagingProtocol;
import tftp.server.net.Connections;

/**
 * One client's session. Runs on that client's thread, so its own fields need no locking;
 * everything shared with other clients goes through {@link SharedState} or the file system.
 */
public class TftpProtocol implements BidiMessagingProtocol<byte[]> {

    private final SharedState state;
    private int connectionId;
    private Connections<byte[]> connections;
    private String user;
    private boolean shouldTerminate;

    // A download (RRQ or DIRQ) in progress: the whole payload and how many blocks are out.
    private byte[] outgoing;
    private int blocksSent;
    private boolean lastBlockSent;

    // An upload (WRQ) in progress. The file is only created once the last block arrives,
    // so other clients never see half a file.
    private String uploadName;
    private ByteArrayOutputStream incoming;
    private int expectedBlock;

    TftpProtocol(SharedState state) {
        this.state = state;
    }

    @Override
    public void start(int connectionId, Connections<byte[]> connections) {
        this.connectionId = connectionId;
        this.connections = connections;
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    @Override
    public void process(byte[] packet) {
        int opcode = opcode(packet);
        if (opcode == LOGRQ) {
            login(string(packet, 2));
            return;
        }
        if (opcode == DISC) {
            send(ack(0));
            shouldTerminate = true;
            return;
        }
        if (user == null) {
            send(error(ERR_NOT_LOGGED_IN, "User not logged in"));
            return;
        }
        switch (opcode) {
            case RRQ -> read(string(packet, 2));
            case WRQ -> write(string(packet, 2));
            case DATA -> receiveBlock(packet);
            case ACK -> acknowledged(u16(packet, 2));
            case DIRQ -> listFiles();
            case DELRQ -> delete(string(packet, 2));
            case ERROR -> cancelTransfers();
            default -> send(error(ERR_ILLEGAL_OPERATION, "Illegal TFTP operation - unknown opcode " + opcode));
        }
    }

    @Override
    public void closed() {
        cancelTransfers();
        if (user != null) {
            state.logout(user);
            log("logged out");
        }
    }

    private void login(String name) {
        if (user != null) {
            send(error(ERR_ALREADY_LOGGED_IN, "User already logged in"));
        } else if (name.isEmpty()) {
            send(error(ERR_NOT_DEFINED, "User name is empty"));
        } else if (!state.login(name, connectionId)) {
            send(error(ERR_ALREADY_LOGGED_IN, "User name already connected"));
        } else {
            user = name;
            log("logged in");
            send(ack(0));
        }
    }

    private void read(String name) {
        Path file = resolve(name);
        if (file == null || busy())
            return;
        try {
            startDownload(Files.readAllBytes(file));
            log("RRQ " + name + " (" + outgoing.length + " bytes)");
        } catch (NoSuchFileException e) {
            send(error(ERR_FILE_NOT_FOUND, "File not found"));
        } catch (IOException e) {
            send(error(ERR_ACCESS_VIOLATION, "Access violation - file cannot be read"));
        }
    }

    private void listFiles() {
        if (busy())
            return;
        // Names are separated by a 0 byte, which can never appear in a file name.
        try (Stream<Path> entries = Files.list(state.files)) {
            List<String> names = entries.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(Collectors.toList());
            startDownload(String.join("\0", names).getBytes(StandardCharsets.UTF_8));
            log("DIRQ (" + names.size() + " files)");
        } catch (IOException e) {
            send(error(ERR_ACCESS_VIOLATION, "Access violation - cannot list files"));
        }
    }

    private void startDownload(byte[] payload) {
        outgoing = payload;
        blocksSent = 0;
        lastBlockSent = false;
        sendNextBlock();
    }

    /**
     * Sends the next 512-byte slice. A transfer ends with a block shorter than 512, so a payload
     * whose size is a multiple of 512 (including an empty one) ends with an empty block.
     */
    private void sendNextBlock() {
        int offset = blocksSent * MAX_DATA;
        int length = Math.min(MAX_DATA, outgoing.length - offset);
        blocksSent++;
        lastBlockSent = length < MAX_DATA;
        send(data(blocksSent & 0xFFFF, outgoing, offset, length));
    }

    private void acknowledged(int block) {
        if (outgoing == null || block != (blocksSent & 0xFFFF))
            return;
        if (lastBlockSent)
            outgoing = null;
        else
            sendNextBlock();
    }

    private void write(String name) {
        Path file = resolve(name);
        if (file == null || busy())
            return;
        if (!state.reserve(name)) {
            send(error(ERR_FILE_EXISTS, "File already exists - another upload is in progress"));
            return;
        }
        if (Files.exists(file)) {
            state.release(name);
            send(error(ERR_FILE_EXISTS, "File already exists"));
            return;
        }
        uploadName = name;
        incoming = new ByteArrayOutputStream();
        expectedBlock = 1;
        log("WRQ " + name);
        send(ack(0));
    }

    private void receiveBlock(byte[] packet) {
        int size = u16(packet, 2);
        int block = u16(packet, 4);
        if (uploadName == null) {
            send(error(ERR_ILLEGAL_OPERATION, "Illegal TFTP operation - no upload in progress"));
            return;
        }
        if (size > MAX_DATA || block != (expectedBlock & 0xFFFF)) {
            send(error(ERR_ILLEGAL_OPERATION, "Illegal TFTP operation - unexpected DATA block " + block));
            cancelTransfers();
            return;
        }
        incoming.write(packet, 6, size);
        expectedBlock++;
        if (size == MAX_DATA) {
            send(ack(block));
            return;
        }

        String name = uploadName;
        Path file = state.files.resolve(name);
        try {
            Files.write(file, incoming.toByteArray(), StandardOpenOption.CREATE_NEW);
            log("WRQ " + name + " complete (" + incoming.size() + " bytes)");
            send(ack(block));
            broadcast(bcast(true, name));
        } catch (FileAlreadyExistsException e) {
            send(error(ERR_FILE_EXISTS, "File already exists"));
        } catch (IOException e) {
            send(error(ERR_DISK_FULL, "Disk full or allocation exceeded"));
        } finally {
            cancelTransfers();
        }
    }

    private void delete(String name) {
        Path file = resolve(name);
        if (file == null)
            return;
        try {
            // deleteIfExists is atomic, so if two clients delete the same file only one succeeds.
            if (!Files.deleteIfExists(file)) {
                send(error(ERR_FILE_NOT_FOUND, "File not found"));
                return;
            }
            log("DELRQ " + name);
            send(ack(0));
            broadcast(bcast(false, name));
        } catch (IOException e) {
            send(error(ERR_ACCESS_VIOLATION, "Access violation - file cannot be deleted"));
        }
    }

    private void broadcast(byte[] packet) {
        for (int id : state.loggedIn())
            connections.send(id, packet);
    }

    /** Ends any transfer this client has open and frees the file name of an unfinished upload. */
    private void cancelTransfers() {
        outgoing = null;
        if (uploadName != null) {
            state.release(uploadName);
            uploadName = null;
            incoming = null;
        }
    }

    /** One transfer at a time per client. */
    private boolean busy() {
        if (outgoing == null && uploadName == null)
            return false;
        send(error(ERR_NOT_DEFINED, "Another transfer is in progress"));
        return true;
    }

    /**
     * Maps a client-supplied name to a path inside the files directory, or sends an error and
     * returns null. Without this, a name like "../secret" would reach outside it.
     */
    private Path resolve(String name) {
        Path file = null;
        if (!name.isEmpty() && !name.contains("/") && !name.contains("\\")) {
            try {
                file = state.files.resolve(name).normalize();
            } catch (InvalidPathException ignored) {
            }
        }
        if (file == null || !state.files.equals(file.getParent())) {
            send(error(ERR_ACCESS_VIOLATION, "Access violation - illegal file name"));
            return null;
        }
        return file;
    }

    private void send(byte[] packet) {
        connections.send(connectionId, packet);
    }

    private void log(String message) {
        System.out.println("[" + connectionId + (user != null ? " " + user : "") + "] " + message);
    }
}
