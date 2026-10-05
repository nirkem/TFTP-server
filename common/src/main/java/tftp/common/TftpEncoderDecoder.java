package tftp.common;

import java.util.Arrays;

/**
 * Cuts the TCP byte stream into packets. TCP has no message boundaries, so the opcode decides
 * where each packet ends: after a 0 byte for string packets, after the declared size for DATA,
 * and after a fixed length for the rest. Both the server and the client use it.
 */
public class TftpEncoderDecoder implements MessageEncoderDecoder<byte[]> {

    private byte[] bytes = new byte[1 << 10];
    private int len = 0;

    @Override
    public byte[] decodeNextByte(byte nextByte) {
        push(nextByte);
        if (len < 2)
            return null;

        switch (Packets.opcode(bytes)) {
            case Packets.RRQ, Packets.WRQ, Packets.LOGRQ, Packets.DELRQ:
                return len > 2 && nextByte == 0 ? pop() : null;
            case Packets.BCAST:
                return len > 3 && nextByte == 0 ? pop() : null;
            case Packets.ERROR:
                return len > 4 && nextByte == 0 ? pop() : null;
            case Packets.ACK:
                return len == 4 ? pop() : null;
            case Packets.DATA:
                return len >= 6 && len == 6 + Packets.u16(bytes, 2) ? pop() : null;
            default:
                // DIRQ and DISC are just an opcode. An unknown opcode is passed on as-is
                // so the protocol can answer it with an error.
                return pop();
        }
    }

    @Override
    public byte[] encode(byte[] message) {
        return message;
    }

    private void push(byte nextByte) {
        if (len == bytes.length)
            bytes = Arrays.copyOf(bytes, len * 2);
        bytes[len++] = nextByte;
    }

    private byte[] pop() {
        byte[] packet = Arrays.copyOf(bytes, len);
        len = 0;
        return packet;
    }
}
