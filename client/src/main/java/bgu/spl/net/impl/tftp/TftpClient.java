package bgu.spl.net.impl.tftp;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.UnknownHostException;

public class TftpClient {

    public static void main(String[] args) throws UnknownHostException, IOException {
        if (args.length == 0) {
            args = new String[] { "localhost", "hello" };
        }

        if (args.length < 2) {
            System.out.println("you must supply two arguments: host, message");
            System.exit(1);
        }

        try (Socket sock = new Socket(args[0], 7777);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream()))) {

            // Create protocol and encdec:
            TftpClientEncoderDecoder encdec = new TftpClientEncoderDecoder();
            TftpClientProtocol protocol = new TftpClientProtocol();
            protocol.start();
            BufferedOutputStream newOut = new BufferedOutputStream(sock.getOutputStream());
            BufferedInputStream newIn = new BufferedInputStream(sock.getInputStream());
            final Object syncObj = new Object();

            // Create and start the Keyboard Commands thread
            Thread keyboardThread = new Thread(new KeyboardCommands(newOut, encdec, protocol, syncObj));
            keyboardThread.start();

            // Create and start the Listening thread
            // talya
            Thread listeningThread = new Thread(new ListeningThread(newOut, newIn, protocol, encdec, syncObj));
            listeningThread.start();

            // Wait for both threads to finish
            try {
                keyboardThread.join();
                listeningThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            // talya
            sock.close();
        }
    }
}
