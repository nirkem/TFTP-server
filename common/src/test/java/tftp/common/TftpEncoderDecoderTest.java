package tftp.common;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class TftpEncoderDecoderTest {

    /** Feeds the bytes one at a time, the way they come off a socket. */
    private static List<byte[]> decode(byte[]... packets) {
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        for (byte[] p : packets)
            stream.writeBytes(p);
        TftpEncoderDecoder decoder = new TftpEncoderDecoder();
        List<byte[]> out = new ArrayList<>();
        for (byte b : stream.toByteArray()) {
            byte[] packet = decoder.decodeNextByte(b);
            if (packet != null)
                out.add(packet);
        }
        return out;
    }

    @Test
    void splitsBackToBackPacketsOfEveryKind() {
        byte[][] packets = {
                Packets.request(Packets.LOGRQ, "alice"),
                Packets.bare(Packets.DIRQ),
                Packets.ack(7),
                Packets.data(1, new byte[512], 0, 512),
                Packets.data(2, new byte[] { 1, 2, 3 }, 0, 3),
                Packets.error(Packets.ERR_FILE_NOT_FOUND, "File not found"),
                Packets.bcast(true, "notes.txt"),
                Packets.request(Packets.RRQ, "a file with spaces.txt"),
                Packets.bare(Packets.DISC),
        };
        List<byte[]> decoded = decode(packets);
        assertEquals(packets.length, decoded.size());
        for (int i = 0; i < packets.length; i++)
            assertArrayEquals(packets[i], decoded.get(i), "packet " + i);
    }

    @Test
    void dataMayContainZeroBytes() {
        // A 0 byte ends string packets, but DATA is framed by its size field instead.
        byte[] payload = { 0, 0, 5, 0, 0 };
        byte[] packet = Packets.data(1, payload, 0, payload.length);
        assertArrayEquals(packet, decode(packet).get(0));
    }

    @Test
    void emptyDataPacketIsComplete() {
        byte[] packet = Packets.data(3, new byte[0], 0, 0);
        List<byte[]> decoded = decode(packet, Packets.ack(1));
        assertEquals(2, decoded.size());
        assertArrayEquals(packet, decoded.get(0));
    }

    @Test
    void zeroDigitInANameIsNotATerminator() {
        byte[] packet = Packets.request(Packets.WRQ, "report-2024.txt");
        assertEquals("report-2024.txt", Packets.string(decode(packet).get(0), 2));
    }

    @Test
    void namesLongerThanTheInitialBufferStillDecode() {
        String name = "x".repeat(5000);
        byte[] packet = Packets.request(Packets.RRQ, name);
        assertEquals(name, Packets.string(decode(packet).get(0), 2));
    }

    @Test
    void unknownOpcodeIsPassedOn() {
        byte[] packet = { 0, 42 };
        assertArrayEquals(packet, decode(packet).get(0));
    }

    @Test
    void readsBlockNumbersAboveSignedShortRange() {
        assertEquals(40000, Packets.u16(Packets.ack(40000), 2));
    }
}
