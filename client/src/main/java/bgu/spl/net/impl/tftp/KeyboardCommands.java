package bgu.spl.net.impl.tftp;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;



class KeyboardCommands implements Runnable {
    private BufferedOutputStream out;
    private BufferedReader consoleReader;
    private final TftpClientProtocol protocol;
    private boolean shouldTerminate;
    private final TftpClientEncoderDecoder encdec;
    private Object syncOb;

    public KeyboardCommands(BufferedOutputStream out, TftpClientEncoderDecoder encdec, TftpClientProtocol protocol, Object syncOb) {
        this.out = out;
        this.consoleReader = new BufferedReader(new InputStreamReader(System.in));
        this.shouldTerminate = false;
        this.encdec = encdec;
        this.protocol = protocol;
        this.syncOb = syncOb;
    }

    @Override
    public void run() {
        try {
            while (!shouldTerminate) {
                byte[] bytesToReturn = null;
                String userInput = getUserInput();
                if (!isValidCommand(userInput))
                    System.out.println("Not a valid command");
                else {
                    bytesToReturn = protocol.processCommands(userInput);
                    if (bytesToReturn != null)
                        send(bytesToReturn);
                    try {
                        synchronized (syncOb) {
                            syncOb.wait();
                        }
                    if (userInput.length() >= 4 && userInput.substring(0, 4).equals("DISC")){
                        this.shouldTerminate = true; 
                    }
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getUserInput() throws IOException {
        // System.out.print("Enter your command: ");
        return consoleReader.readLine();
    }

    public void terminate() {
        this.shouldTerminate = true;
    }

    private boolean isValidCommand(String userInput) {
        // Trim leading and trailing whitespaces
        userInput = userInput.trim();

        // Split the input into words
        String[] words = userInput.split("\\s+");

        // Check if there is at least one word and if the first word is a valid command
        return words.length > 0 && isValidCommandType(words[0]);
    }

    private boolean isValidCommandType(String commandType) {
        String[] validCommandTypes = { "LOGRQ", "DELRQ", "RRQ", "WRQ", "DIRQ", "DISC" };
        for (String validType : validCommandTypes) {
            if (validType.equals(commandType)) {
                return true;
            }
        }
        return false;
    }

    // talya
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