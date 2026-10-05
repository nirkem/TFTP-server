package tftp.server;

import static org.junit.jupiter.api.Assertions.*;
import static tftp.common.Packets.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tftp.server.net.Server;

@Timeout(20)
class TftpServerTest {

    @TempDir
    Path root;
    Path files;
    Server<byte[]> server;
    int port;

    @BeforeEach
    void start() throws Exception {
        files = Files.createDirectory(root.resolve("Files"));
        server = TftpServer.create(0, files);
        Thread thread = new Thread(() -> {
            try {
                server.serve();
            } catch (IOException ignored) {
            }
        });
        thread.setDaemon(true);
        thread.start();
        port = server.awaitPort();
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
    }

    RawClient connect() throws IOException {
        return new RawClient(port);
    }

    RawClient loggedIn(String name) throws IOException {
        RawClient c = connect();
        c.login(name);
        return c;
    }

    static byte[] randomBytes(int size, long seed) {
        byte[] bytes = new byte[size];
        new Random(seed).nextBytes(bytes);
        return bytes;
    }

    /** Server-side cleanup after a dropped connection runs on another thread, so retry briefly. */
    static void eventually(Callable<Boolean> check) throws Exception {
        long deadline = System.currentTimeMillis() + 3000;
        while (!check.call()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition never became true");
            Thread.sleep(20);
        }
    }

    // ---- login ----

    @Test
    void everythingButLoginAndDiscNeedsALogin() throws IOException {
        try (RawClient c = connect()) {
            c.send(request(RRQ, "a.txt"));
            c.expectError(ERR_NOT_LOGGED_IN);
            c.send(bare(DIRQ));
            c.expectError(ERR_NOT_LOGGED_IN);
        }
    }

    @Test
    void aUserNameCanOnlyBeConnectedOnce() throws IOException {
        try (RawClient alice = loggedIn("alice"); RawClient other = connect()) {
            other.send(request(LOGRQ, "alice"));
            other.expectError(ERR_ALREADY_LOGGED_IN);
            alice.send(request(LOGRQ, "alice2"));
            alice.expectError(ERR_ALREADY_LOGGED_IN);
        }
    }

    @Test
    void discAcknowledgesClosesAndFreesTheName() throws Exception {
        try (RawClient alice = loggedIn("alice")) {
            alice.send(bare(DISC));
            alice.expectAck(0);
            assertTrue(alice.closedByServer());
        }
        try (RawClient again = connect()) {
            eventually(() -> {
                again.send(request(LOGRQ, "alice"));
                return opcode(again.next()) == ACK;
            });
        }
    }

    @Test
    void aClientThatVanishesFreesItsName() throws Exception {
        connect().close(); // warm-up connection, never logged in
        RawClient alice = loggedIn("alice");
        alice.close(); // no DISC
        try (RawClient again = connect()) {
            eventually(() -> {
                again.send(request(LOGRQ, "alice"));
                return opcode(again.next()) == ACK;
            });
        }
    }

    // ---- transfers ----

    @ParameterizedTest
    @ValueSource(ints = { 0, 1, 511, 512, 513, 1024, 1537, 70_000 })
    void uploadsAndDownloadsRoundTrip(int size) throws IOException {
        byte[] content = randomBytes(size, size);
        try (RawClient c = loggedIn("alice")) {
            c.upload("file.bin", content);
            assertArrayEquals(content, Files.readAllBytes(files.resolve("file.bin")));
            assertArrayEquals(content, c.download(request(RRQ, "file.bin")));
        }
    }

    @Test
    @Timeout(60) // 33,000 lockstep round trips
    void transfersPastBlock32767() throws IOException {
        // Block numbers are unsigned 16-bit, so they must not go negative after 32767.
        byte[] content = randomBytes(MAX_DATA * 33_000 + 7, 1);
        Path big = files.resolve("big.bin");
        Files.write(big, content);
        try (RawClient c = loggedIn("alice")) {
            assertArrayEquals(content, c.download(request(RRQ, "big.bin")));
        }
    }

    @Test
    void missingAndExistingFilesAreReported() throws IOException {
        Files.writeString(files.resolve("a.txt"), "hello");
        try (RawClient c = loggedIn("alice")) {
            c.send(request(RRQ, "missing.txt"));
            c.expectError(ERR_FILE_NOT_FOUND);
            c.send(request(DELRQ, "missing.txt"));
            c.expectError(ERR_FILE_NOT_FOUND);
            c.send(request(WRQ, "a.txt"));
            c.expectError(ERR_FILE_EXISTS);
        }
    }

    @Test
    void namesCannotReachOutsideTheFilesDirectory() throws IOException {
        Path secret = Files.writeString(root.resolve("secret.txt"), "do not serve");
        try (RawClient c = loggedIn("alice")) {
            for (String name : List.of("../secret.txt", "..\\secret.txt", "..", ".", "sub/x.txt", ""))
                for (int op : new int[] { RRQ, WRQ, DELRQ }) {
                    c.send(request(op, name));
                    c.expectError(ERR_ACCESS_VIOLATION);
                }
        }
        assertTrue(Files.exists(secret));
        assertFalse(Files.exists(root.resolve("x.txt")));
    }

    @Test
    void directoryListingSeparatesNamesWithAZeroByte() throws IOException {
        // The original listing used the character '0' as a separator and split these names apart.
        for (String name : List.of("report-2024.txt", "a0.txt", "with spaces.txt"))
            Files.writeString(files.resolve(name), name);
        Files.createDirectory(files.resolve("not-a-file"));
        try (RawClient c = loggedIn("alice")) {
            assertArrayEquals(new String[] { "a0.txt", "report-2024.txt", "with spaces.txt" }, c.dir());
        }
    }

    @Test
    void emptyDirectoryListsNothing() throws IOException {
        try (RawClient c = loggedIn("alice")) {
            assertEquals(0, c.dir().length);
        }
    }

    @Test
    void deleteRemovesTheFile() throws IOException {
        Files.writeString(files.resolve("a.txt"), "hello");
        try (RawClient c = loggedIn("alice")) {
            c.send(request(DELRQ, "a.txt"));
            c.expectAck(0);
        }
        assertFalse(Files.exists(files.resolve("a.txt")));
    }

    @Test
    void unknownOpcodeIsAnIllegalOperation() throws IOException {
        try (RawClient c = loggedIn("alice")) {
            c.send(new byte[] { 0, 42 });
            c.expectError(ERR_ILLEGAL_OPERATION);
        }
    }

    // ---- broadcasts ----

    @Test
    void uploadsAndDeletesAreBroadcastToLoggedInClientsOnly() throws IOException {
        try (RawClient alice = loggedIn("alice"); RawClient bob = loggedIn("bob"); RawClient guest = connect()) {
            alice.upload("notes.txt", "hi".getBytes(StandardCharsets.UTF_8));
            assertArrayEquals(bcast(true, "notes.txt"), alice.nextAny());
            assertArrayEquals(bcast(true, "notes.txt"), bob.nextAny());

            bob.send(request(DELRQ, "notes.txt"));
            bob.expectAck(0);
            assertArrayEquals(bcast(false, "notes.txt"), bob.nextAny());
            assertArrayEquals(bcast(false, "notes.txt"), alice.nextAny());

            assertTrue(guest.silentFor(300));
        }
    }

    // ---- uploads in progress ----

    @Test
    void aHalfUploadedFileIsInvisibleAndReserved() throws IOException {
        byte[] content = randomBytes(1500, 2);
        try (RawClient alice = loggedIn("alice"); RawClient bob = loggedIn("bob")) {
            alice.send(request(WRQ, "big.bin"));
            alice.expectAck(0);
            alice.send(data(1, content, 0, MAX_DATA));
            alice.expectAck(1);

            assertEquals(0, bob.dir().length);
            bob.send(request(RRQ, "big.bin"));
            bob.expectError(ERR_FILE_NOT_FOUND);
            bob.send(request(WRQ, "big.bin"));
            bob.expectError(ERR_FILE_EXISTS);

            alice.send(data(2, content, 512, MAX_DATA));
            alice.expectAck(2);
            alice.send(data(3, content, 1024, 1500 - 1024));
            alice.expectAck(3);
            assertArrayEquals(content, bob.download(request(RRQ, "big.bin")));
        }
    }

    @Test
    void anAbandonedUploadFreesItsName() throws Exception {
        RawClient alice = loggedIn("alice");
        alice.send(request(WRQ, "x.bin"));
        alice.expectAck(0);
        alice.send(data(1, new byte[MAX_DATA], 0, MAX_DATA));
        alice.expectAck(1);
        alice.close();

        try (RawClient bob = loggedIn("bob")) {
            eventually(() -> {
                bob.send(request(WRQ, "x.bin"));
                byte[] reply = bob.next();
                return opcode(reply) == ACK;
            });
        }
        assertFalse(Files.exists(files.resolve("x.bin")));
    }

    @Test
    void outOfOrderBlockCancelsTheUpload() throws IOException {
        try (RawClient c = loggedIn("alice")) {
            c.send(request(WRQ, "x.bin"));
            c.expectAck(0);
            c.send(data(2, new byte[3], 0, 3));
            c.expectError(ERR_ILLEGAL_OPERATION);
            c.send(request(WRQ, "x.bin"));
            c.expectAck(0);
        }
    }

    // ---- races ----

    /** Starts every task at the same instant and returns their results. */
    static <T> List<T> race(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++)
            futures.add(pool.submit(() -> {
                go.await();
                return task.call();
            }));
        go.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures)
            results.add(f.get(15, TimeUnit.SECONDS));
        pool.shutdownNow();
        return results;
    }

    @Test
    void racingLoginsForOneNameHaveOneWinner() throws Exception {
        List<RawClient> clients = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            clients.add(connect());
        int[] next = { 0 };
        List<Integer> replies = race(20, () -> {
            RawClient c;
            synchronized (next) {
                c = clients.get(next[0]++);
            }
            c.send(request(LOGRQ, "same"));
            return opcode(c.next());
        });
        assertEquals(1, replies.stream().filter(op -> op == ACK).count());
        for (RawClient c : clients)
            c.close();
    }

    @Test
    void racingUploadsOfOneNameHaveOneWinner() throws Exception {
        List<RawClient> clients = new ArrayList<>();
        for (int i = 0; i < 20; i++)
            clients.add(loggedIn("user" + i));
        int[] next = { 0 };
        List<Integer> replies = race(20, () -> {
            RawClient c;
            synchronized (next) {
                c = clients.get(next[0]++);
            }
            c.send(request(WRQ, "race.txt"));
            return opcode(c.next());
        });
        assertEquals(1, replies.stream().filter(op -> op == ACK).count());
        for (RawClient c : clients)
            c.close();
    }

    @Test
    void manyClientsTransferAtOnce() throws Exception {
        int n = 16;
        int[] next = { 0 };
        List<Boolean> ok = race(n, () -> {
            int i;
            synchronized (next) {
                i = next[0]++;
            }
            byte[] content = randomBytes(10_000 + i, i);
            try (RawClient c = loggedIn("user" + i)) {
                c.upload("f" + i + ".bin", content);
                return Arrays.equals(content, c.download(request(RRQ, "f" + i + ".bin")));
            }
        });
        assertFalse(ok.contains(false));
        try (RawClient c = loggedIn("checker")) {
            assertEquals(n, c.dir().length);
        }
    }
}
