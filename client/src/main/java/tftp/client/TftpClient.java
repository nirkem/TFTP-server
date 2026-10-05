package tftp.client;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

import tftp.common.TftpEncoderDecoder;

/**
 * Usage: TftpClient [host] [port]. Defaults to localhost and 7777.
 * Reads commands from stdin; files are read from and written to the current directory.
 *
 * Two threads: this one reads the keyboard, and a listening thread reads the socket. The
 * server can send at any moment (a broadcast about another client's upload), so the socket
 * cannot wait for the next command to be read.
 */
public class TftpClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 7777;
        Path dir = Paths.get("").toAbsolutePath();

        try (Socket sock = new Socket(host, port)) {
            BufferedOutputStream socketOut = new BufferedOutputStream(sock.getOutputStream());
            // Both threads write: the keyboard thread sends requests, the listening thread
            // sends ACKs and upload blocks. One lock keeps their packets from interleaving.
            ClientSession session = new ClientSession(dir, packet -> {
                synchronized (socketOut) {
                    socketOut.write(packet);
                    socketOut.flush();
                }
            }, System.out);

            Thread listener = new Thread(() -> listen(sock, session), "listener");
            listener.start();

            BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            while (true) {
                String line = keyboard.readLine();
                if (line == null) {
                    // End of input: say goodbye so the server frees the user name right away.
                    session.command("DISC");
                    break;
                }
                if (!line.isBlank() && !session.command(line))
                    break;
            }
        }
    }

    private static void listen(Socket sock, ClientSession session) {
        TftpEncoderDecoder encdec = new TftpEncoderDecoder();
        try {
            BufferedInputStream in = new BufferedInputStream(sock.getInputStream());
            int read;
            while ((read = in.read()) >= 0) {
                byte[] packet = encdec.decodeNextByte((byte) read);
                if (packet != null)
                    session.receive(packet);
            }
        } catch (IOException ignored) {
            // Socket closed, by the server or by us.
        }
        if (!session.connectionClosed()) {
            // The keyboard thread may be blocked reading stdin, so exit from here.
            System.out.println("Connection closed by the server");
            System.exit(1);
        }
    }
}
