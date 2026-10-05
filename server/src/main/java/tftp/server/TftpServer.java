package tftp.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import tftp.common.TftpEncoderDecoder;
import tftp.server.net.Server;

public class TftpServer {

    /** Usage: TftpServer [port] [files directory]. Defaults to 7777 and ./Files. */
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7777;
        Path files = Paths.get(args.length > 1 ? args[1] : "Files").toAbsolutePath().normalize();
        Files.createDirectories(files);

        Server<byte[]> server = create(port, files);
        System.out.println("Serving " + files + " on port " + port);
        server.serve();
    }

    static Server<byte[]> create(int port, Path files) {
        SharedState state = new SharedState(files);
        return new Server<>(port, () -> new TftpProtocol(state), TftpEncoderDecoder::new);
    }
}
