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

import bgu.spl.net.api.BidiMessagingProtocol;
import bgu.spl.net.srv.Connections;

// class holder {
//     talya changed
//     static ConcurrentHashMap<Integer, String> login_ids = new
//     ConcurrentHashMap<>();
// }

public class TftpProtocol implements BidiMessagingProtocol<byte[]> {

    private boolean shouldTerminate = false;
    private int connectionId;
    private int packetSize;
    private TftpConnections<byte[]> connections;
    private Path currentFilePath;
    private ByteArrayOutputStream byteArrayOutputStream;
    private List<byte[]> createdPackets;

    @Override
    public void start(int connectionId, Connections<byte[]> connections) {

        this.shouldTerminate = false;
        this.connectionId = connectionId;
        this.connections = (TftpConnections<byte[]>) connections;
        this.currentFilePath = null;
        this.byteArrayOutputStream = new ByteArrayOutputStream();
        this.packetSize = 512;
        this.createdPackets = null;
        // talya
        // holder.login_ids.put(connectionId, true);
    }

    @Override
    public void process(byte[] message) throws UnsupportedEncodingException {

        // LOGIN Case: DONE
        if (message[1] == 7) {
            try {
                processLOG(message);
            } catch (Exception e) {
            }
            ;
        }
        else if (checkLogin()) {
            // RRQ Case: DONE
            if (message[1] == 1) {
                try {
                    processRRQ(message);
                } catch (Exception e) {
                }
                ;
            }
            // DELRQ Case: DONE
            else if (message[1] == 8) {
                try {
                    processDEL(message);
                } catch (Exception e) {
                }
                ;
            }
            // WRQ Case: DONE
            else if (message[1] == 2) {
                try {
                    processWRQ(message);
                } catch (Exception e) {
                }
                ;
            }
            // DIRQ Case: DONE
            else if (message[1] == 6) {
                try {
                    processDIRQ();
                } catch (Exception e) {
                }
                ;
            }
            // DISC Case: DONE
            else if (message[1] == 10) {
                try {
                    processDISC(message);
                } catch (Exception e) {
                }
                ;
            }
            // DATA Case: DONE
            else if (message[1] == 3) {
                try {
                    processDATA(message);
                } catch (Exception e) {
                }
                ;
            }
            // DISC Case: DONE
            else if (message[1] == 'a') {
                try {
                    processDISC(message);
                } catch (Exception e) {
                }
                ;
            }
            // ACK Case: DONE
            else if (message[1] == 4) {
                try {
                    ByteBuffer buffer = ByteBuffer.wrap(message, 2, 2).order(ByteOrder.BIG_ENDIAN);
                    int blockNumber = buffer.getShort() & 0xFFFF;
                    sendData(blockNumber);
                } catch (Exception e) {
                }
                ;
            }
            else {
                sendError(4, "Illegal TFTP operation - Unknown Opcode.");
            }
        } 
    }

    @Override
    public boolean shouldTerminate() {
        return shouldTerminate;
    }

    private void processLOG(byte[] message) throws UnsupportedEncodingException {
        // Talya:
        int startIndex = 2;
        int endIndex = message.length - 1;
        byte[] relevantBytes = Arrays.copyOfRange(message, startIndex, endIndex);
        String name = new String(relevantBytes, StandardCharsets.UTF_8);
        boolean canLog1 = false;
        boolean canLog2 = false;

        if (connections.isExistName(name)) {
            sendError(0, "name already exists!");
            canLog1 = false;
        } else
            canLog1 = true;
        if (connections.isAlradyLog(name, connectionId)) {
            sendError(7, "User already logged in – Login username already connected.");
            canLog2 = false;
        } else
            canLog2 = true;
        if (canLog1 && canLog2) {
            connections.setName(name, connectionId);
            acknowledge(new byte[] { 0, 4, 0, 0 });
        }
        return;
    }

    private void processDEL(byte[] message) throws UnsupportedEncodingException {
        System.out.println("processing DELRQ");
        // First, extract string file's name:
        // Skip the first two bytes and the last byte
        int startIndex = 2;
        int endIndex = message.length - 1;
        byte[] relevantBytes = Arrays.copyOfRange(message, startIndex, endIndex);

        // Convert the relevant bytes to a string using UTF-8 encoding
        String resultString = new String(relevantBytes, StandardCharsets.UTF_8);

        // Second, Check if File exist's:
        // Get the current working directory
        String currentDirectory = System.getProperty("user.dir");
        String relativePath = "Files";
        Path filesDirectoryPath = Paths.get(currentDirectory, relativePath);

        // Convert the Path to a string representation
        String filesDirectoryAbsolutePath = filesDirectoryPath.toAbsolutePath().toString();
        String folderPath = filesDirectoryAbsolutePath;
        Path filePath = Paths.get(folderPath).resolve(resultString);

        // Check if the file exists
        boolean fileExists = Files.exists(filePath);

        if (fileExists) {
            System.out.println("File exists, deleting");
            try {
                Files.delete(filePath);
                System.out.println("File deleted successfully: " + filePath);

                byte[] opCodeBytes = new byte[] { 0, 9, 0 };
                byte[] fileName = resultString.getBytes("UTF-8");
                byte lastByte = 0;

                int length = opCodeBytes.length + fileName.length + 1;

                byte[] toBroadcast = new byte[length];

                // build broadCast
                System.arraycopy(opCodeBytes, 0, toBroadcast, 0, opCodeBytes.length);
                System.arraycopy(fileName, 0, toBroadcast, opCodeBytes.length, fileName.length);
                toBroadcast[length - 1] = lastByte;

                broadCast(toBroadcast);

            } catch (IOException e) {
                System.out.println("access violation.");
                sendError(2, "Access violation – File cannot be written, read or deleted.");
            }
        } else {
            System.out.println("File does not exist.");
            // ERROR:
            sendError(1, "File not found – RRQ DELRQ of non-existing file.");
        }
        return;
    }

    private void processRRQ(byte[] message) throws IOException {
        System.out.println("processing RRQ");
        // First, extract string file's name:
        // Skip the first two bytes and the last byte
        int startIndex = 2;
        int endIndex = message.length - 1;
        byte[] relevantBytes = Arrays.copyOfRange(message, startIndex, endIndex);

        // Convert the relevant bytes to a string using UTF-8 encoding
        String resultString = new String(relevantBytes, StandardCharsets.UTF_8);

        // Second, Check if File exist's:
        // Get the current working directory
        String currentDirectory = System.getProperty("user.dir");
        System.out.println(currentDirectory);
        String relativePath = "Files";
        Path filesDirectoryPath = Paths.get(currentDirectory, relativePath);

        // Convert the Path to a string representation
        String filesDirectoryAbsolutePath = filesDirectoryPath.toAbsolutePath().toString();
        String folderPath = filesDirectoryAbsolutePath;
        Path filePath = Paths.get(folderPath).resolve(resultString);

        // Check if the file exists
        boolean fileExists = Files.exists(filePath);

        // Checking if the file exists
        if (fileExists) {
            System.out.println("File exists, preparing transfer to client");
            currentFilePath = filePath;
            createPackets("RRQ", null);
            // send first packet
            sendData(0);

        } else {
            System.out.println("File does not exist.");
            // ERROR:
            sendError(1, "File not found - RRQ DELRQ of non-existing file.");
        }
        return;
    }

    private void processWRQ(byte[] message) throws UnsupportedEncodingException {
        System.out.println("processing WRQ");
        // First, extract string file's name:
        // Skip the first two bytes and the last byte
        int startIndex = 2;
        int endIndex = message.length - 1;

        // Extract the relevant portion of the byte array
        byte[] relevantBytes = Arrays.copyOfRange(message, startIndex, endIndex);

        // Convert the relevant bytes to a string using UTF-8 encoding
        String resultString = new String(relevantBytes, StandardCharsets.UTF_8);

        // Second, Check if File exist's:
        // Get the current working directory
        String currentDirectory = System.getProperty("user.dir");

        // Specify the relative path to the "Files" directory
        String relativePath = "Files";

        // Create a Path object by combining the current directory and the relative path
        Path filesDirectoryPath = Paths.get(currentDirectory, relativePath);

        // Convert the Path to a string representation
        String filesDirectoryAbsolutePath = filesDirectoryPath.toAbsolutePath().toString();

        String folderPath = filesDirectoryAbsolutePath;

        Path filePath = Paths.get(folderPath).resolve(resultString);

        // Check if the file exists
        boolean fileExists = Files.exists(filePath);

        // Checking if the file exists
        if (fileExists) {
            System.out.println("File already exists!");
            // ERROR:
            sendError(5, "File already exists - File name exists on server.");
        } else {
            System.out.println("File does not exist, creating new empty file.");
            // Constructing the absolute path to the file
            String newFilePath = folderPath + File.separator + resultString;

            System.out.println(resultString);
            // Creating a File object
            File file = new File(newFilePath);

            try {
                // Create an empty file
                boolean fileCreated = file.createNewFile();

                if (fileCreated) {
                    System.out.println("Empty file created successfully at: " + filePath);
                    // send ACK:
                    currentFilePath = Paths.get(newFilePath);
                    acknowledge(new byte[] { 0, 4, 0, 0 });
                } else {
                    System.out.println("File already exists at: " + filePath);
                    sendError(2, "Access violation - File cannot be written, read or deleted.");
                }
            } catch (IOException e) {
                System.err.println("Error creating the file: " + e.getMessage());
                sendError(2, "Access violation - File cannot be written, read or deleted.");
            }
        }
    }

    private void processDATA(byte[] message) throws UnsupportedEncodingException {
        // figure Packet size:
        byte thirdByte = message[2];
        byte fourthByte = message[3];
        int packet_size = ((thirdByte & 0xFF) << 8) | (fourthByte & 0xFF);
        System.out.println("Packet size: " + packet_size);

        // figure Block number:
        byte fifthByte = message[4];
        byte sixthByte = message[5];
        int block_number = ((fifthByte & 0xFF) << 8) | (sixthByte & 0xFF);
        System.out.println("Block number: " + block_number);

        if (packet_size == 512) {
            byteArrayOutputStream.write(message, 6, message.length - 6);
            acknowledge(new byte[] { 0, 4, fifthByte, sixthByte });
        } else {
            byteArrayOutputStream.write(message, 6, message.length - 6);
            // write data on curent file:
            writeByteArrayOutputStreamToFile();
            acknowledge(new byte[] { 0, 4, fifthByte, sixthByte });

            String fileName = currentFilePath.getFileName().toString();
            byte[] opCodeBytes = new byte[] { 0, 9, 1 };
            byte[] fileNameBytes = fileName.getBytes("UTF-8");
            byte lastByte = 0;

            int length = opCodeBytes.length + fileNameBytes.length + 1;

            byte[] toBroadcast = new byte[length];

            // build broadCast
            System.arraycopy(opCodeBytes, 0, toBroadcast, 0, opCodeBytes.length);
            System.arraycopy(fileNameBytes, 0, toBroadcast, opCodeBytes.length, fileNameBytes.length);
            toBroadcast[length - 1] = lastByte;

            broadCast(toBroadcast);
            currentFilePath = null;
            byteArrayOutputStream.reset();

        }
        return;
    }

    private void acknowledge(byte[] message) {
        this.connections.send(connectionId, message);
        return;
    }

    private void sendError(int errorCode, String errorMsg) throws UnsupportedEncodingException {

        byte firstByte = (byte) ((errorCode >> 8) & 0xFF);
        byte secondByte = (byte) (errorCode & 0xFF);
        byte[] opCodeBytes = new byte[] { 0, 5, firstByte, secondByte };
        byte[] errorBytes = errorMsg.getBytes("UTF-8");
        byte lastByte = 0;

        int length = opCodeBytes.length + errorBytes.length + 1;
        byte[] resultBytes = new byte[length];

        // build error
        System.arraycopy(opCodeBytes, 0, resultBytes, 0, opCodeBytes.length);
        System.arraycopy(errorBytes, 0, resultBytes, opCodeBytes.length, errorBytes.length);
        resultBytes[length - 1] = lastByte;

        this.connections.send(connectionId, resultBytes);
        return;
    }

    private void processDIRQ() {
        System.out.println("processing DIRQ");
        // First, get the Files folder:
        // Get the current working directory
        String currentDirectory = System.getProperty("user.dir");
        String relativePath = "Files";
        Path filesDirectoryPath = Paths.get(currentDirectory, relativePath);

        // Convert the Path to a string representation
        String filesDirectoryAbsolutePath = filesDirectoryPath.toAbsolutePath().toString();
        String folderPath = filesDirectoryAbsolutePath;

        try {
            byte[] concatenatedNames = concatenateFileNames(folderPath);
            createPackets("DIRQ", concatenatedNames);
            sendData(0);

            System.out.println(new String(concatenatedNames)); // Convert to String for printing
        } catch (IOException e) {
            e.printStackTrace();
        }

        return;
    }

    private void broadCast(byte[] message) {
        for (Integer id : connections.getIds()) {
            if (!connections.isAlradyLog("", id))
                connections.send(id, message);
        }
        return;
    }

    private void processDISC(byte[] message) {
        // remove from list's:
        // holder.login_ids.remove(this.connectionId);

        // send ACK:
        byte[] byteArray = new byte[4];
        for (int i = 0; i < byteArray.length; i++) {
            byteArray[i] = 0;
        }
        byteArray[1] = 4;
        acknowledge(byteArray);
        connections.setName("", connectionId);
        // Terminate:
        connections.disconnect(connectionId);
        shouldTerminate = true;

    }

    private void sendData(int block_number) {
        if (block_number >= 0 && block_number < createdPackets.size()) {

            byte[] packetToSend = createdPackets.get(block_number);
            int packetLength = packetToSend.length;

            int messageLength = 6 + packetLength;
            byte[] message = new byte[messageLength];

            // Set the opcode (first two bytes) to {0, 3}
            message[0] = 0;
            message[1] = 3;

            // Set the length of the packet (next two bytes) in big-endian order
            ByteBuffer.wrap(message, 2, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) packetLength);

            // Set the block number (next two bytes) in big-endian order
            ByteBuffer.wrap(message, 4, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) (block_number + 1));

            // Copy the packetToSend bytes to the message
            System.arraycopy(packetToSend, 0, message, 6, packetLength);

            this.connections.send(connectionId, message);
        } else {
            currentFilePath = null;
        }
        return;
    }

    private void writeByteArrayOutputStreamToFile() {
        try {
            // Ensure that the currentFilePath is initialized
            if (currentFilePath != null) {
                // Get the byte array from the ByteArrayOutputStream
                byte[] byteArray = byteArrayOutputStream.toByteArray();

                // Write the bytes to the file
                Files.write(currentFilePath, byteArray, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

                System.out.println("Bytes written to file: " + currentFilePath);
            } else {
                System.err.println("currentFilePath is not initialized.");
            }
        } catch (IOException e) {
            System.err.println("Error writing bytes to file: " + e.getMessage());
        }
    }

    private void createPackets(String operation, byte[] names) throws IOException {
        byte[] fileBytes;
        if (operation == "RRQ")
            fileBytes = Files.readAllBytes(currentFilePath);
        else
            fileBytes = names;
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
        // Nir
        if (remainingBytes == 0) {
            byte[] lastPacket = new byte[]{};
            packets.add(lastPacket);
        }
        this.createdPackets = packets;
    }
    
    private static byte[] concatenateFileNames(String folderPath) throws IOException {
        
        Path folder = Paths.get(folderPath);
        List<byte[]> fileNamesBytesList = Files.list(folder)
                .filter(Files::isRegularFile)
                .map(Path::getFileName)
                .map(Path::toString)
                .map(String::getBytes)
                .collect(Collectors.toList());

        // Calculate the total length, including '0' bytes as separators
        int totalLength = fileNamesBytesList.stream().mapToInt(bytes -> bytes.length + 1).sum() - 1;

        // Create the final byte array
        byte[] concatenatedNames = new byte[totalLength];

        int offset = 0;
        for (byte[] fileNameBytes : fileNamesBytesList) {
            System.arraycopy(fileNameBytes, 0, concatenatedNames, offset, fileNameBytes.length);
            offset += fileNameBytes.length;

            // Add '0' byte as separator, except for the last file name
            if (offset < totalLength) {
                concatenatedNames[offset++] = (byte) '0';
            }
        }
        return concatenatedNames;
    }
    

    private boolean checkLogin() throws UnsupportedEncodingException {
        boolean loggedIn = (!connections.isAlradyLog("", connectionId));
        if (loggedIn)
            return true;
        else
            sendError(6, "User not logged in.");

        return false;
    }

}
