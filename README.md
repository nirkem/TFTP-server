# TFTP: a multi-client file server and client in Java

[![test](https://github.com/nirkem/TFTP-server/actions/workflows/test.yml/badge.svg)](https://github.com/nirkem/TFTP-server/actions/workflows/test.yml)

A file server and a console client that talk a binary protocol over TCP, an extended version of TFTP. Clients log in with a user name, upload, download, delete and list files, and every logged-in client is told when a file is added or removed. The server gives each client its own thread, and the client uses two: one for the keyboard and one for the socket. Written for the Systems Programming course (SPL) at Ben-Gurion University in 2024.

```
alice$ java -jar tftp-client.jar           bob$ java -jar tftp-client.jar
LOGRQ alice                                LOGRQ bob
ACK 0                                      ACK 0
DIRQ
A.txt
Tennet.txt
abc 512.txt
this is a file with spaces.txt
RRQ abc 512.txt
RRQ abc 512.txt complete
WRQ report-2024.pdf
ACK 0
ACK 1
ACK 2
ACK 3
WRQ report-2024.pdf complete
BCAST add report-2024.pdf                  BCAST add report-2024.pdf
DELRQ Tennet.txt
ACK 0
BCAST del Tennet.txt                       BCAST del Tennet.txt
DISC
ACK 0
```

## The protocol

Every packet starts with a 2-byte big-endian opcode. Strings are UTF-8 and end with a 0 byte.

| Opcode | Packet | Layout | Answered with |
| --- | --- | --- | --- |
| 7 | LOGRQ | user name, 0 | ACK 0, or ERROR 7 if the name is taken |
| 1 | RRQ | file name, 0 | DATA blocks |
| 2 | WRQ | file name, 0 | ACK 0, then the client sends DATA |
| 3 | DATA | size (2), block (2), up to 512 bytes | ACK with the same block number |
| 4 | ACK | block (2) | |
| 6 | DIRQ | | DATA blocks holding the file names, separated by 0 bytes |
| 8 | DELRQ | file name, 0 | ACK 0 |
| 9 | BCAST | 0 = deleted or 1 = added, file name, 0 | sent by the server to every logged-in client |
| 5 | ERROR | error code (2), message, 0 | |
| 10 | DISC | | ACK 0, then the server closes the connection |

Files move in 512-byte blocks, and each block waits for its ACK before the next one is sent. A block shorter than 512 bytes ends the transfer, so a file whose size is a multiple of 512 (including an empty file) ends with an empty block. Everything except LOGRQ and DISC needs a login first.

Error codes: 0 not defined, 1 file not found, 2 access violation, 3 disk full, 4 illegal operation, 5 file already exists, 6 not logged in, 7 already logged in.

## How it works

**Framing.** TCP delivers a stream of bytes with no message boundaries, so [`TftpEncoderDecoder`](common/src/main/java/tftp/common/TftpEncoderDecoder.java) reads one byte at a time and lets the opcode decide where a packet ends: at a 0 byte for packets that carry a string, at `6 + size` for DATA (whose payload may contain 0 bytes), and at a fixed length for the rest. The server and the client share it.

**Server: a thread per client.** [`Server`](server/src/main/java/tftp/server/net/Server.java) accepts connections and gives each one a thread, its own decoder and its own [`TftpProtocol`](server/src/main/java/tftp/server/TftpProtocol.java). A protocol does not return replies. It sends through `Connections`, a map from connection id to handler, so it can answer its own client and also broadcast to everyone else. A broadcast is written on the sender's thread, so each handler's `send` is synchronized.

**What the threads share.** Logged-in names and in-progress uploads live in [`SharedState`](server/src/main/java/tftp/server/SharedState.java). Claiming a name is one atomic `putIfAbsent`, so if two clients log in as `alice` at the same moment, exactly one gets in. Uploads work the same way: the name is reserved first, the bytes are collected in memory, and the file is created only when the last block arrives. Other clients never see half a file, and two uploads of the same name cannot both start. When a client disconnects, or just disappears, its name and any unfinished upload are released.

**Client: two threads.** The server can send at any moment, because a broadcast about someone else's upload is not a reply to anything. So one thread reads the keyboard and another reads the socket. The keyboard thread sends a request and waits until the listening thread says the operation is over: after the last DATA block, after the final ACK, or on an ERROR. Both threads write to the socket (requests from one, ACKs and upload blocks from the other), so writes share one lock.

**File names stay inside `Files/`.** Names with a `/` or `\`, and anything that resolves outside the directory (`..`), are refused with ERROR 2.

## Bugs fixed from the original submission

This repository started as the course submission. Going back to it, a test suite found these:

- **Uploads of an exact multiple of 512 bytes never finished.** The client split the file into full blocks and never sent the closing empty block, so the server waited forever. A 1024-byte upload left an empty file.
- **The client's two threads did not agree on when an operation was over.** The keyboard thread called `wait()` with no condition, and the listener woke it on every ACK, DATA or broadcast. So it could move on to the next command in the middle of an upload, or wait forever if a reply came before it started waiting or a command was refused locally and sent nothing. It now waits on a condition (`while (pending != NONE)`), and only the end of an operation clears it, under the same lock.
- **The directory listing used the character `'0'` as a separator**, so `report-2024.txt` came back as `report-2` and `24.txt`. It now uses a 0 byte, which cannot appear in a file name.
- **`RRQ ../server.log` downloaded a file from outside `Files/`.**
- **A user name could be claimed twice.** Checking a name and taking it were two separate steps, and a client that dropped without DISC kept its name forever.
- **A file existed, empty, from the moment its upload started**, so another client could list it and download nothing.

## Build, run, test

Java 17+ and Maven:

```bash
mvn package
cd server && java -jar target/tftp-server.jar     # serves ./Files on port 7777
java -jar client/target/tftp-client.jar           # in another terminal
```

Both take optional arguments: `tftp-server.jar [port] [directory]` and `tftp-client.jar [host] [port]`. The client reads and writes files in its current directory.

`mvn verify` runs the unit and integration tests: the framer byte by byte, and 26 server tests over real sockets. These include transfers of 0, 511, 512, 513 and 70,000 bytes, a download past block 32,767 (block numbers are unsigned), path traversal, half-finished uploads, and races where 20 clients log in or upload under the same name at the same instant. [`tests/e2e.sh`](tests/e2e.sh) then drives the real client jar against the real server: 23 checks, including a second client that must hear the first one's broadcast and a server that dies mid-session. CI runs both on every push.

## Files

| Path | Role |
| --- | --- |
| `common/.../Packets.java` | Opcodes, error codes and packet builders |
| `common/.../TftpEncoderDecoder.java` | Cuts the byte stream into packets |
| `server/.../net/` | Thread-per-client server, connection handler, connection map |
| `server/.../TftpProtocol.java` | One client's session: login, transfers, broadcasts |
| `server/.../SharedState.java` | Logged-in users and reserved upload names |
| `client/.../TftpClient.java` | Socket, keyboard loop and listening thread |
| `client/.../ClientSession.java` | The client's state, shared by its two threads |
| `server/Files/` | Sample files the server starts with |
| `tests/e2e.sh` | End-to-end tests |
