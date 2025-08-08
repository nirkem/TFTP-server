package bgu.spl.net.impl.echo;

//import bgu.spl.net.srv.Server;

public class EchoServer {

    public static void main(String[] args) {

        // We changed the server interface, thus Echo won't work anymore

        // // you can use any server... 
        // Server.threadPerClient(
        //         7777, //port
        //         () -> new EchoProtocol(), //protocol factory
        //         LineMessageEncoderDecoder::new //message encoder decoder factory
        // ).serve();

    }
}
