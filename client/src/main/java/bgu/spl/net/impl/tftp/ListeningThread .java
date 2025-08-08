package bgu.spl.net.impl.tftp;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.BufferedOutputStream;


class ListeningThread implements Runnable {
    private BufferedOutputStream out;
    private BufferedInputStream in;
    private final TftpClientProtocol protocol;
    private final TftpClientEncoderDecoder encdec;
    // talya
    private boolean shouldTerminate;
    private Object syncOb;

    public ListeningThread(BufferedOutputStream out,BufferedInputStream in, TftpClientProtocol protocol,
            // talya
            TftpClientEncoderDecoder encdec, Object syncOb) {
        this.out = out;
        this.in = in;
        this.protocol = protocol;
        this.encdec = encdec;
        // talya
        this.shouldTerminate = false;
        this.syncOb = syncOb;
    }

    @Override
    public void run() {
        try {
            int read;
            byte[] bytesToSend = null;
            while (!shouldTerminate && (read = in.read()) >= 0) {
                byte[] nextMessage = encdec.decodeNextByte((byte) read);
                if (nextMessage != null) {
                    bytesToSend = protocol.process(nextMessage);
                    if(bytesToSend !=null)
                    {
                        // Nir
                        if(bytesToSend.equals(new byte[]{0}))
                            shouldTerminate = true;
                        else
                            send(bytesToSend);
                    }
                        
                    // to continue
                    if (nextMessage[1] == 3 || nextMessage[1] == 4 || nextMessage[1] == 5 || nextMessage[1] == 9){
                        synchronized(syncOb){
                            syncOb.notifyAll();
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void terminate() {
        this.shouldTerminate = true;
    }

    public void ListeningNotifay() {
        synchronized (this) {
            this.notify();
        }
    }
    public synchronized void send(byte[] msg) {
        try {
            if (msg != null && out != null) {
                out.write(msg);
                out.flush();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }

}