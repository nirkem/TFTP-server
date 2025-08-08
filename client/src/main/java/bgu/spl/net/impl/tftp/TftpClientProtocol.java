package bgu.spl.net.impl.tftp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import bgu.spl.net.api.MessagingProtocol;

public class TftpClientProtocol implements MessagingProtocol<byte[]> {

    private boolean shouldTerminate = false;
    private int packetSize;
    private Path currentFilePath;
    private String currentProcess;
    private String fileName;
    private ByteArrayOutputStream byteArrayOutputStream;
    private List<byte[]> createdPackets;

    public void start() {
        this.shouldTerminate = false;
        this.currentFilePath = null;
        this.byteArrayOutputStream = new ByteArrayOutputStream();
        this.packetSize = 512;
        this.createdPackets = null;
        this.currentProcess = "";
        this.fileName = null;
    }

    @Override
    public byte[] process(byte[] message) {

        // DATA Case:
        if (message[1] == 3) {
            try {
                return processDATA(message);
            } catch (Exception e) {
            }
            ;
        }
        // ACK Case:
        else if (message[1] == 4) {
            try {
                return processACK(message);
            } catch (Exception e) {
            }
            ;
        }
        // ERORR Case:
        else if (message[1] == 5) {
            try {
                processError(message);
            } catch (Exception e) {
            }
            ;
        }
        // BCAST Case: DONE
        else if (message[1] == 9) {
            try {
                broadCast(message);
            } catch (Exception e) {
            }
            ;
        }
        return null;
    }

    public byte[] processCommands(String command) throws IOException {
        command = command.trim(); // Remove leading and trailing whitespaces
        String[] words = command.split("\\s+"); // Split the command into words

        if (words.length < 1) {
            // Handle invalid command (empty command)
            System.out.println("Invalid command");
            return null;
        }

        String commandType = words[0];
        String wordsLeft = command;
        if (words.length >= 2)
            wordsLeft = String.join(" ", Arrays.copyOfRange(words, 1, words.length));

        switch (commandType) {
            case "LOGRQ":
                // Process LOGRQ command
                return processLOG(wordsLeft);
            case "DELRQ":
                // Process DELRQ command
                return processDEL(wordsLeft);
            case "RRQ":
                // Process RRQ command
                return processRRQ(wordsLeft);
            case "WRQ":
                // Process WRQ command
                return processWRQ(wordsLeft);
            case "DIRQ":
                // Process DIRQ command
                return processDIRQ();
            case "DISC":
                // Process DISC command
                return processDISC();
            default:
                System.out.println("Unknown command");
                return null;
        }
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    private byte[] processDEL(String fileName) throws UnsupportedEncodingException {

        byte[] deleteRequest = buildFileRequest(fileName, 8);
        this.fileName = fileName;
        this.currentProcess = "DEL";
        return deleteRequest;
    }

    private byte[] processRRQ(String fileName) throws IOException {

        // Check if File exist's:
        Path filesDirectoryPath = getFilesPath();

        // Convert the Path to a string representation
        String folderPath = filesDirectoryPath.toAbsolutePath().toString();
        Path filePath = Paths.get(folderPath).resolve(fileName);

        // Check if the file exists:
        boolean fileExists = Files.exists(filePath);

        // Checking if the file exists
        if (fileExists) {
            System.out.println("File already exists!");
        } else {
            currentFilePath = filePath;
            File file = new File(filePath.toString());

            try {
                // Create an empty file
                boolean fileCreated = file.createNewFile();
                if (fileCreated) {
                    // send RRQ:
                    byte[] RRQrequest = buildFileRequest(fileName, 1);
                    this.currentProcess = "RRQ";
                    return RRQrequest;
                }
            } catch (IOException e) {
                System.err.println("Error creating the file: " + e.getMessage());
            }
        }
        return null;
    }

    private byte[] processWRQ(String fileName) throws IOException {

        // Check if File exist's:
        Path filesDirectoryPath = getFilesPath();

        // Convert the Path to a string representation
        String folderPath = filesDirectoryPath.toAbsolutePath().toString();
        Path filePath = Paths.get(folderPath).resolve(fileName);

        // Check if the file exists
        boolean fileExists = Files.exists(filePath);

        // Checking if the file exists
        if (fileExists) {
            System.out.println("File exists!, preparing to upload");
            this.currentProcess = "WRQ";
            this.currentFilePath = filePath;
            createPackets();

            // send WRQ:
            byte[] WRQrequest = buildFileRequest(fileName, 2);
            return WRQrequest;

        } else {
            System.out.println("File does not exist.");
        }
        return null;
    }

    private byte[] processLOG(String loginName) throws UnsupportedEncodingException {

        byte[] opCode = new byte[]{0, 7};
        byte[] loginNameBytes = loginName.getBytes();
        byte lastByte = 0;

        int requestLength = opCode.length + loginNameBytes.length + 1;
        byte[] loginRequest = new byte[requestLength];

        // Copy opCode to RRQrequest
        System.arraycopy(opCode, 0, loginRequest, 0, opCode.length);

        // Copy fileNameBytes to RRQrequest after opCode
        System.arraycopy(loginNameBytes, 0, loginRequest, opCode.length, loginNameBytes.length);

        // Set lastByte at the end of RRQrequest
        loginRequest[requestLength - 1] = lastByte;

        // If Big-endian is required, swap bytes accordingly
        ByteBuffer.wrap(loginRequest, 0, opCode.length).order(java.nio.ByteOrder.BIG_ENDIAN).get(opCode);
        ByteBuffer.wrap(
                loginRequest, opCode.length, 
                loginNameBytes.length).order(java.nio.ByteOrder.BIG_ENDIAN)
                .get(loginNameBytes);
        ByteBuffer.wrap(loginRequest, requestLength - 1, 1).order(java.nio.ByteOrder.BIG_ENDIAN).put(lastByte);

        return loginRequest;
    }

    private byte[] processDIRQ() throws IOException {

        String fileName = "dirq";

        Path filesDirectoryPath = getFilesPath();

        // Convert the Path to a string representation
        String filesDirectoryAbsolutePath = filesDirectoryPath.toAbsolutePath().toString();
        String folderPath = filesDirectoryAbsolutePath;
        Path filePath = Paths.get(folderPath).resolve(fileName);

        // Checking if the file exists
        boolean fileExists = Files.exists(filePath);
        try {
            // Check if the file exists
            if (fileExists) {
                // If the file exists, delete its content
                Files.write(filePath, new byte[0]);
            } else {
                // If the file doesn't exist, create a new empty file
                Files.createFile(filePath);
            }
        } catch (IOException e) {
            System.err.println("An error occurred: " + e.getMessage());
        }

        this.currentFilePath = filePath;
        this.currentProcess = "DIRQ";
        this.fileName = fileName;
        byte[] toReturn = new byte[] { 0, 6 };

        return toReturn;
    }

    private byte[] processDISC() {
        byte[] bytes = new byte[2];
        bytes[0] = 0;
        bytes[1] = 10;

        this.currentProcess = "DISC";
        return bytes;
    }

    private byte[] processDATA(byte[] message) throws UnsupportedEncodingException {
        if (currentProcess == "RRQ")
            return processDataRRQ(message);
        else
            return processDataDIRQ(message);
    }

    private void processError(byte[] errorBytes) throws UnsupportedEncodingException {
        StringBuilder errorMessage = new StringBuilder();
        // Skip the first four bytes
        int index = 4;
        // Read bytes until encountering '0'
        while (index < errorBytes.length && errorBytes[index] != 0) {
            errorMessage.append((char) errorBytes[index]);
            index++;
        }
        if (errorBytes[3] == 0)
            System.out.println(errorMessage);
        else if (errorBytes[3] == 1) {
            System.out.println("File not found.");
            cleanPaths();
        }
        else if (errorBytes[3] == 2) {
            System.out.println("ERROR - Access violation.");
            cleanPaths();
        }
        else if (errorBytes[3] == 3) {
            System.out.println("ERROR - Disk full or allocation exceeded.");
            cleanPaths();
        } 
        else if (errorBytes[3] == 4) {
            System.out.println("ERROR - Illegal TFTP operation.");
        }
        else if (errorBytes[3] == 5) {
            System.out.println("ERROR - File already exists.");
            cleanPaths();
        } 
        else if (errorBytes[3] == 6)
            System.out.println("ERROR - User not logged in.");
        else if (errorBytes[3] == 7)
            System.out.println("ERROR - User already logged in.");
    }

    private void broadCast(byte[] message) {

        byte[] relevantBytes = Arrays.copyOfRange(message, 3, message.length - 1);
        String fileName = new String(relevantBytes, StandardCharsets.UTF_8);
        String result = "BCAST ";
        if (message[2] == 0)
            result = result + "del " + fileName;
        else
            result = result + "add " + fileName;
        System.out.println(result);

    }

    private byte[] sendData(int block_number) {
        byte[] message = null;
        if (block_number >= 0 && block_number < createdPackets.size()) {

            byte[] packetToSend = createdPackets.get(block_number);
            int packetLength = packetToSend.length;

            int messageLength = 6 + packetLength;
            message = new byte[messageLength];

            // Set the opcode (first two bytes) to {0, 3}
            message[0] = 0;
            message[1] = 3;

            // Set the length of the packet (next two bytes) in big-endian order
            ByteBuffer.wrap(message, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) packetLength);

            // Set the block number (next two bytes) in big-endian order
            ByteBuffer.wrap(message, 4, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) (block_number + 1));

            // Copy the packetToSend bytes to the message
            System.arraycopy(packetToSend, 0, message, 6, packetLength);

        }
        return message;
    }

    private void writeByteArrayOutputStreamToFile() {
        try {
            // Ensure that the currentFilePath is initialized
            if (currentFilePath != null) {
                // Get the byte array from the ByteArrayOutputStream
                byte[] byteArray = byteArrayOutputStream.toByteArray();

                // Write the bytes to the file
                Files.write(currentFilePath, byteArray, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

            } else {
                System.err.println("Error, currentFilePath is not initialized.");
            }
        } catch (IOException e) {
            System.err.println("Error writing bytes to file: " + e.getMessage());
        }
    }

    private void createPackets() throws IOException {

        byte[] fileBytes;
        fileBytes = Files.readAllBytes(currentFilePath);
        int fileSize = fileBytes.length;

        List<byte[]> packets = new ArrayList<>();

        // Calculate the number of full packets
        int fullPackets = fileSize / packetSize;

        // Create full-size packets
        for (int i = 0; i < fullPackets; i++) {
            int start = i * packetSize;
            byte[] packet = new byte[packetSize];
            System.arraycopy(fileBytes, start, packet, 0, packetSize);
            packets.add(packet);
        }

        // Create the last packet with the remaining bytes
        int remainingBytes = fileSize % packetSize;
        if (remainingBytes > 0) {
            int start = fullPackets * packetSize;
            byte[] lastPacket = new byte[remainingBytes];
            System.arraycopy(fileBytes, start, lastPacket, 0, remainingBytes);
            packets.add(lastPacket);
        }
        this.createdPackets = packets;
    }

    private byte[] buildFileRequest(String fileName, int typeOfRequest) {
        byte[] opCode;

        // RRQ request
        if (typeOfRequest == 1)
            opCode = new byte[] { 0, 1 };
        // WRQ request
        else if (typeOfRequest == 2)
            opCode = new byte[] { 0, 2 };
        // DEL request
        else
            opCode = new byte[] { 0, 8 };

        byte[] fileNameBytes = fileName.getBytes();
        byte lastByte = 0;

        int requestLength = opCode.length + fileNameBytes.length + 1;
        byte[] request = new byte[requestLength];

        // Copy opCode to RRQrequest
        System.arraycopy(opCode, 0, request, 0, opCode.length);

        // Copy fileNameBytes to RRQrequest after opCode
        System.arraycopy(fileNameBytes, 0, request, opCode.length, fileNameBytes.length);

        // Set lastByte at the end of RRQrequest
        request[requestLength - 1] = lastByte;

        // If Big-endian is required, swap bytes accordingly
        ByteBuffer.wrap(request, 0, opCode.length).order(java.nio.ByteOrder.BIG_ENDIAN).get(opCode);
        ByteBuffer.wrap(request, opCode.length, fileNameBytes.length).order(java.nio.ByteOrder.BIG_ENDIAN)
                .get(fileNameBytes);
        ByteBuffer.wrap(request, requestLength - 1, 1).order(java.nio.ByteOrder.BIG_ENDIAN).put(lastByte);

        return request;
    }

    private byte[] processDataRRQ(byte[] message) throws UnsupportedEncodingException {
        // figure Packet size:
        byte thirdByte = message[2];
        byte fourthByte = message[3];
        int packet_size = ((thirdByte & 0xFF) << 8) | (fourthByte & 0xFF);

        // figure Block number:
        byte fifthByte = message[4];
        byte sixthByte = message[5];

        if (packet_size == 512) {
            byteArrayOutputStream.write(message, 6, message.length - 6);
            return (new byte[] { 0, 4, fifthByte, sixthByte });
        } else {
            byteArrayOutputStream.write(message, 6, message.length - 6);
            // write data on curent file:
            writeByteArrayOutputStreamToFile();

            String fileName = currentFilePath.getFileName().toString();
            System.out.println("RRQ " + fileName + " complete");

            currentFilePath = null;
            currentProcess = null;
            byteArrayOutputStream.reset();
            return (new byte[] { 0, 4, fifthByte, sixthByte });
        }
    }

    private byte[] processDataDIRQ(byte[] message) throws UnsupportedEncodingException {
        // figure Packet size:
        byte thirdByte = message[2];
        byte fourthByte = message[3];
        int packet_size = ((thirdByte & 0xFF) << 8) | (fourthByte & 0xFF);

        // figure Block number:
        byte fifthByte = message[4];
        byte sixthByte = message[5];

        if (packet_size == 512) {
            byteArrayOutputStream.write(message, 6, message.length - 6);
            return (new byte[] { 0, 4, fifthByte, sixthByte });
        } else {
            byteArrayOutputStream.write(message, 6, message.length - 6);
            // write data on curent file:
            writeByteArrayOutputStreamToFile();

            System.out.println("DIRQ complete");
            printDIRQ(currentFilePath);

            cleanPaths();
            byteArrayOutputStream.reset();
            return (new byte[] { 0, 4, fifthByte, sixthByte });
        }
    }

    private byte[] processACK(byte[] message) {

        if (currentProcess == "WRQ") {
            ByteBuffer buffer = ByteBuffer.wrap(message, 2, 2).order(ByteOrder.BIG_ENDIAN);
            int blockNumber = buffer.getShort() & 0xFFFF;
            byte[] toReturn = sendData(blockNumber);
            System.out.println("ACK " + blockNumber);
            return toReturn;
        } else if (currentProcess == "DEL") {
            System.out.println(this.fileName + " deleted from the server");
            cleanPaths();
            System.out.println("ACK 0");
            return null;
        } else if (currentProcess == "DISC") {
            System.out.println("Disconnecting");
            System.out.println("ACK 0");
            return (new byte[] { 0 });
        }
        System.out.println("ACK 0");
        return null;
    }

    private void printDIRQ(Path currentFilePath) {
        try {
            byte[] bytes = Files.readAllBytes(currentFilePath);
            StringBuilder currentString = new StringBuilder();
            for (byte b : bytes) {
                char c = (char) b;
                if (c == '0') { // Using '0' character as delimiter
                    System.out.println(currentString.toString());
                    currentString.setLength(0);
                } else {
                    currentString.append(c);
                }
            }
            // Print the last string if not followed by 0
            if (currentString.length() > 0) {
                System.out.println(currentString.toString());
            }
        } catch (IOException e) {
            System.err.println("An error occurred: " + e.getMessage());
        }

    }

    private void cleanPaths() {
        this.currentFilePath = null;
        this.currentProcess = null;
        this.fileName = null;
    }

    private Path getFilesPath(){

        String currentDirectory = System.getProperty("user.dir");
        Path filesDirectoryPath = Paths.get(currentDirectory);

        return filesDirectoryPath;

    }

}
