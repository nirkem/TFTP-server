package bgu.spl.net.impl.tftp;

import java.util.Arrays;

import bgu.spl.net.api.MessageEncoderDecoder;

public class TftpClientEncoderDecoder implements MessageEncoderDecoder<byte[]> {

    private byte[] bytes = new byte[1 << 10]; // start with 1k
    private int len = 0;
    // packetSize to handle DATA opertaion
    private int packetSize = 512;

    @Override
    public byte[] decodeNextByte(byte nextByte) {

        pushByte(nextByte);
        if (len >= 2) {
            if (bytes[1] == 3) {
                if (len == 4) {
                    byte thirdByte = bytes[2];
                    byte fourthByte = bytes[3];

                    // Convert bytes to an unsigned short (16-bit) integer
                    packetSize = ((thirdByte & 0xFF) << 8) | (fourthByte & 0xFF);
                } else if (len == 6 + packetSize) {
                    byte[] tempBytes = Arrays.copyOfRange(bytes, 0, len);
                    len = 0;
                    packetSize = 512;
                    return tempBytes;
                }
            } else if (bytes[1] == 4) {
                if (len == 4) {
                    byte[] tempBytes = Arrays.copyOfRange(bytes, 0, len);
                    len = 0;
                    return tempBytes;
                }
            } else if (bytes[1] == 5) {
                if (len > 4 && nextByte == 0) {
                    byte[] tempBytes = Arrays.copyOfRange(bytes, 0, len);
                    len = 0;
                    return tempBytes;
                }
            } else if (bytes[1] == 9) {
                if (len > 3 && nextByte == 0) {
                    byte[] tempBytes = Arrays.copyOfRange(bytes, 0, len);
                    len = 0;
                    return tempBytes;
                }
            } else {
                byte[] tempBytes = Arrays.copyOfRange(bytes, 0, len);
                len = 0;
                return tempBytes;
            }

        }
        return null; // no message yet
    }
    

    private void pushByte(byte nextByte) {
        bytes[len++] = nextByte;
    }

    @Override
    public byte[] encode(byte[] message) {
        return message;
    }
}
