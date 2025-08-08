package bgu.spl.net.impl.echo;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

public class NirsClient {

    public static void main(String[] args) throws IOException {

        if (args.length == 0) {
            args = new String[] { "localhost", "hello" };
        }

        if (args.length < 2) {
            System.out.println("you must supply two arguments: host, message");
            System.exit(1);
        }

        // BufferedReader and BufferedWriter automatically using UTF-8 encoding
        try (Socket sock = new Socket(args[0], 7777);
                BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                BufferedOutputStream out = new BufferedOutputStream((sock.getOutputStream()))) {

            byte[] first_op_code = new byte[] { 0, 2 };
            byte[] file_name = "openheimer.mp4".getBytes();
            byte last_byte = 0;
            out.write(first_op_code);
            out.write(file_name);
            out.write(last_byte);
            System.out.println("writen");
            while (true) {
            }
            // byte[] op_code = new byte[] { 0, 3 };
            // byte[] size = new byte[] { 0, 2 };
            // byte[] block_number = new byte[] { 0, 1 };
            // byte[] data = new byte[] { 9, 9 };
            // out.write(op_code);
            // out.write(size);
            // out.write(block_number);
            // out.write(data);

        }
    }
}
