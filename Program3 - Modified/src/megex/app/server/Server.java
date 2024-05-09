/************************************************
 *
 * Author: Agm Islam
 * Assignment: Program 2
 * Class: CSI5321 - Advance Data Communication
 *
 ************************************************/

package megex.app.server;

import megex.serialization.*;
import java.io.*;

import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PermissionCollection;
import java.util.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.logging.*;


import static megex.app.TLSFactory.getServerConnectedSocket;
import static megex.app.TLSFactory.getServerListeningSocket;

/**
 * Server Application
 *
 * @version 1.4
 * @author Agm Islam
 * Date: 01 May 2023
 */

public class Server {


    private static int port;                       // port of the server
    private static int numThreads;                 // number of the threads
    private static String documentRoot;            // root directory of the file
    private static Logger logger;                  // Logger
    private static ThreadPoolExecutor threadPool;  // Thread Pool

    /**
     * maximum payload size
     */
    public static final int MAXDATASIZE = 1000;

    /**
     * minimum Data interval
     */
    public static final int MINDATAINTERVAL = 1000; // minimum data interval in milisecoends


    private static final String KPWD = "qwerasdfzxcv"; // Path for KeyStore
    private static final String KPATH = "keystore";    // Password of the key

    /**
     * Server Constructor
     */
    public Server() {
        this.threadPool = (ThreadPoolExecutor) Executors.newFixedThreadPool(numThreads);
    }

    /**
     * Connection handler
     *
     * @version 1.4
     * @author Agm Islam
     * Date: 01 May 2023
     */
    public class ConnectionHandler implements Runnable {

        private static Socket clientSocket;          // Client Socket
        private static Framer framer;                // Framer
        private static Deframer deframer;            // Deframer
        //private final OutputStream outputStream;    // Socket Output Stream
        //private final InputStream inputStream;      // Socket Input Stream
        private static final MessageFactory msgFactory = new MessageFactory();    // Message Factory for encoding and decoding

        private static final String prefaceString = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"; // Prefface string
        private static final int PREFACELENGTH = 24;    // preface string length

        private static final int BLOCKINGTIME = 40000; // Socket Blocking Time

        // Type Codes for different Message
        private static final byte DATACODE = 0x0;
        private static final byte HEADERSCODE = 0x1;
        private static final byte SETTINGSCODE = 0x4;
        private static final byte WINDOWCODE = 0x8;

        private static final String PATHHEADER = ":path";
        private static final String STATUSHEADER = ":status";

        private static final String HEADERRESPONSEOK = "200";        // Header ok Response
        private static final String HEADERRESPONSENOTFOUND = "404";  // Header not found Response
        private static final String HEADERRESPONSEFORBIDDEN = "403"; // Header forbiddent Response
        private static final String HEADERRESPONSEBAD = "400";       // Header bad request Response



        /**
         * Stream handler
         *
         * @version 1.4
         * @author Agm Islam
         * Date: 01 May 2023
         */
        public class StreamHandler implements Runnable{

            private Socket cSocket;  // Client Socket
            private int sid;         // stream Id
            private String filepath; // file path for the requested resource

            /**
             * Thread status
             */
            public boolean isAlive;

            /**
             * Stream Handler Constructor
             * @param cSocket Client Socket
             * @param sid stream id
             * @param filepath file path
             */
            public StreamHandler(Socket cSocket, int sid, String filepath){
                this.cSocket = cSocket;
                this.filepath = filepath;
                this.sid = sid;
                this.isAlive = true;

            }

            /**
             * Run the Stream Handler to send data
             */
            public  void run(){
                FileInputStream inputStream = null;
                try {
                    inputStream = new FileInputStream(filepath);
                    byte[] buffer = new byte[MAXDATASIZE];
                    int bytesRead;
                    boolean isEnd = false;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {

                        // Detecting the last chunk of the file
                        if(bytesRead<MAXDATASIZE){
                            isEnd = true;
                        }

                        byte[] outData = new byte[bytesRead];
                        System.arraycopy(buffer,0, outData,0,bytesRead);

                        Data d1 = new Data(sid, isEnd, outData);
                        byte[] sendData = msgFactory.encode(d1);

                        if(!(clientSocket.isClosed())){
                            framer.putFrame(sendData);
                            logger.info("Sent1: " + d1.toString());

                            Thread.sleep(MINDATAINTERVAL);
                        }

                    }
                    if(!isEnd){
                        // Send is End if not sent earlier
                        byte[] emptyData = new byte[0];
                        Data d1 = new Data(sid, true, emptyData);
                        byte[] sendData = msgFactory.encode(d1);
                        if(!(clientSocket.isClosed())){
                            framer.putFrame(sendData);
                            logger.info("Sent2: " + d1.toString());
                            Thread.sleep(MINDATAINTERVAL);
                        }
                    }
                }
                catch (Exception e){
                    logger.warning("Unable to read file: "+ e.getMessage());
                }
                finally {
                    isAlive = false;
                }
                // End of thread
                isAlive = false;

            }
        }

        /**
         * Connection Handler: receives frame
         * @param clientSocket Client Socket
         * @throws IOException if IOException
         */
        public ConnectionHandler(Socket clientSocket) throws IOException{
            this.clientSocket = clientSocket;
            //outputStream = clientSocket.getOutputStream();
            //inputStream = clientSocket.getInputStream();
            //framer = new Framer(outputStream);
            //deframer = new Deframer(inputStream);
            //msgFactory = new MessageFactory();
        }


        /**
         * Close Socket
         * @param s Socket
         */
        private void closeSocket(Socket s){
            try {
                s.close();
                logger.severe("Closing Client Socket: ");
            }
            catch (Exception e){
                logger.severe("Unable to close Socket Connection: "+ e.getMessage());
            }
        }

        /**
         * Send Header to the Client
         * @param sid stream Id
         * @param isEnd isEnd
         * @param response Response Status (Header)
         * @return Header
         */
        private Headers sendHeaders(int sid, boolean isEnd, String response){

            try {
                Headers h = new Headers(sid, isEnd);
                h.addValue(STATUSHEADER, response);
                byte[] sendData = msgFactory.encode(h);
                framer.putFrame(sendData);
                return  h;

            }
            catch (Exception e){
                logger.severe("Unable to send Header Response for stream ID: " + sid);
            }
            return null;

        }

        /**
         * Validate stream ID
         * @param sid stream Id
         * @param streamids List of Curret stream id
         * @param maxStreamId maximum stream id of a connection
         * @param h Header
         * @return boolean
         */
        private boolean checkIllegalstreamID(int sid, List<Integer> streamids, int maxStreamId, Headers h){

            // streamID is not incremental
            if(sid<=maxStreamId){
                return false;
            }

            // stream ID already requested
            if(streamids.size()>0){
                if(streamids.contains(sid)){
                    return false;
                }
            }

            // stream ID is even
            if((sid%2)==0){
                return false;
            }

            return true;
        }

        /**
         * Run Connection Handler
         */
        public void run() {

            List<Integer> aliveStatus = new ArrayList<>();


            List<StreamHandler> activeStreamHandlers = new ArrayList<>(); // List of Stream Handlers for this connection
            byte[] prefaceStringByte = new byte[PREFACELENGTH];
            try {

                OutputStream outputStream = clientSocket.getOutputStream();
                InputStream inputStream = clientSocket.getInputStream();
                framer = new Framer(outputStream);
                deframer = new Deframer(inputStream);

                inputStream.read(prefaceStringByte);
                String str = new String(prefaceStringByte, "UTF-8");

                // Check for correct Preface String
                if (!Arrays.equals(prefaceStringByte, prefaceString.getBytes())) {
                    logger.severe("Bad Preface: " + str);
                    closeSocket(clientSocket);
                }

                Settings s = new Settings();
                framer.putFrame(msgFactory.encode(s));
            }
            catch (Exception e) {
                closeSocket(clientSocket);
            }

            int maxStreamID = 0; // holds the maximum Stream ID
            List<Integer> streamList = new ArrayList<>();



            // Handle different frames
            boolean isContinue = true;
            while (isContinue && clientSocket.isConnected()) {
                try {
                    clientSocket.setSoTimeout(BLOCKINGTIME);
                    byte[] frameData = deframer.getFrame(); // Read each frame
                    byte typecode = frameData[0];           // Type code

                    if (typecode == HEADERSCODE) {
                        Headers h = (Headers) msgFactory.decode(frameData);
                        int rcvdStreamId = h.getStreamID();

                        String path = h.getValue(PATHHEADER);

                        // No Path specified: Terminate Stream
                        if(path==null || path.equals("") || path.isBlank() || path.isEmpty()){
                            sendHeaders(rcvdStreamId, true, HEADERRESPONSEBAD);
                            logger.severe("No path");
                            continue;

                        }

                        String docPath = documentRoot + path; // Reference to the requested file
                        File dirFile = new File(docPath);

                        // Request file is a directory: : Terminate Stream
                        if(dirFile.isDirectory()){
                            sendHeaders(rcvdStreamId, true, HEADERRESPONSEFORBIDDEN);
                            logger.severe("Can not request directory");
                            continue;
                        }

                        // Request file is non-existent or don't have read permission: Terminate Stream

                        FilePermission filePermission = new FilePermission(docPath, "read");
                        PermissionCollection permissionCollection = filePermission.newPermissionCollection();
                        permissionCollection.add(filePermission);

                        Path fp = Paths.get(docPath);
                        boolean isReadable = Files.isReadable(fp); // Is the file readable

                        if(!(dirFile.exists() && isReadable)){
                            sendHeaders(rcvdStreamId, true, HEADERRESPONSENOTFOUND);
                            logger.severe("File not found");
                            continue;
                        }

                        boolean isValid = checkIllegalstreamID(rcvdStreamId,streamList,maxStreamID,h);

                        if(!isValid){
                            logger.warning("Illegal stream ID: "+ h.toString());
                        }
                        else{
                            int activeThreadCount = 0;//Thread.activeCount();

                            if(1==2){
                            //if(Thread.activeCount()>=numThreads){
                                logger.warning("Number of threads exceeded");
                            }
                            else{
                                // Send 200 status
                                logger.warning("Received message: "+ h.toString());
                                Headers newHeaders = sendHeaders(rcvdStreamId, false, HEADERRESPONSEOK);
                                logger.info("Sent:"+ newHeaders.toString());
                                streamList.add(rcvdStreamId); // Add to the list
                                maxStreamID=rcvdStreamId;     // Holds the maximum stream ID

                                System.out.println("Header Sent");

                                // Create a stream Handler and send Data
                                //StreamHandler streamHandler = new StreamHandler(clientSocket, rcvdStreamId, docPath);
                                //threadPool.execute(streamHandler);
                                //activeStreamHandlers.add(streamHandler);



                                // Send Data
                                FileInputStream fileInputStream = new FileInputStream(docPath);
                                byte[] buffer = new byte[MAXDATASIZE];
                                int bytesRead;
                                boolean isEnd = false;
                                aliveStatus.add(rcvdStreamId);


                                while ((bytesRead = fileInputStream.read(buffer)) != -1) {

                                    // Detecting the last chunk of the file
                                    if(bytesRead<MAXDATASIZE){
                                        isEnd = true;
                                    }

                                    byte[] outData = new byte[bytesRead];
                                    System.arraycopy(buffer,0, outData,0,bytesRead);

                                    Data d1 = new Data(rcvdStreamId, isEnd, outData);
                                    byte[] sendData = msgFactory.encode(d1);

                                    if(!(clientSocket.isClosed())){
                                        framer.putFrame(sendData);
                                        logger.info("Sent: " + d1.toString());

                                        Thread.sleep(MINDATAINTERVAL);
                                    }
                                    if(!isEnd){
                                        // Send is End if not sent earlier
                                        byte[] emptyData = new byte[0];
                                        Data dataEnd = new Data(rcvdStreamId, true, emptyData);
                                        byte[] endData = msgFactory.encode(dataEnd);
                                        if(!(clientSocket.isClosed())){
                                            framer.putFrame(endData);
                                            logger.info("Sent: " + d1.toString());
                                            Thread.sleep(MINDATAINTERVAL);
                                        }
                                    }

                                }

                                aliveStatus.remove(rcvdStreamId);
                            }

                        }

                    } else if (typecode == DATACODE) {
                        Data d = (Data) msgFactory.decode(frameData);
                        logger.info("Unexpected Message: " + d.toString());
                    } else if (typecode == SETTINGSCODE || typecode == WINDOWCODE) {
                        Message m = msgFactory.decode(frameData);
                        logger.info("Received Message: " + m.toString());
                    }

                } catch (SocketTimeoutException so) {
                    // Socket Timeout expired
                    isContinue = false;
                    if(aliveStatus.size()>0){
                        isContinue = true;
                        break;
                    }
                    /*
                    for (StreamHandler sHandler : activeStreamHandlers) {
                        if (sHandler.isAlive) {
                            //System.out.println("Socket timeout appeared");
                            isContinue = true;
                            break;
                        }
                    }

                     */


                } catch (Exception e) {

                }

            }
            closeSocket(clientSocket);
        }

    }


    /**
     * Start the Server
     */
    public void start() {
        try {
            ServerSocket l = getServerListeningSocket(port, KPATH, KPWD);
            logger.info("Server started on port: " + port);


            // Run infinitely
            while (true) {

                int activeThreadCount = Thread.activeCount();
                Socket clientSocket = getServerConnectedSocket(l);



                //if(activeThreadCount>=numThreads){

                if(1==2){
                    logger.warning("Number of threads exceeded");
                    clientSocket.close();
                }
                else{
                    logger.info("Client connected: " + clientSocket.getInetAddress().getHostAddress());
                    // Create new Connection  Handler for new connection
                    threadPool.execute(new ConnectionHandler(clientSocket));
                }
            }
        }
        catch (Exception e) {
            logger.severe("Error starting server: " + e.getMessage());
            System.exit(1);
        }
    }


    /**
     * Initialize the logger
     * @throws IOException if IOException
     */

    private static void setLogger() throws IOException {
        logger = Logger.getLogger("MyLogger");
        logger.setUseParentHandlers(false);

        // Disable log from console
        Handler[] handlers = logger.getHandlers();
        for (Handler handler : handlers) {
            if (handler instanceof ConsoleHandler) {
                logger.removeHandler(handler);
            }
        }

        // create a file handler
        Handler fileHandler = new FileHandler("server.log");

        // set formatter for file handler
        fileHandler.setFormatter(new SimpleFormatter());

        // add file handler to logger
        logger.addHandler(fileHandler);

        // set logging level
        logger.setLevel(Level.ALL);

    }

    /**
     * Validate command line arguments
     * @param args arguments
     */
    private static void validateArgs(String[] args){
        // Check for the number of arguments
        if (args.length != 3) {
            logger.severe("Invalid number of arguments");
            System.exit(1);
        }

        // check for the port number
        try {
            port = Integer.parseInt(args[0]);
        } catch (Exception e) {
            logger.severe("Invalid Port Number: " + args[0]);
            System.exit(1);
        }

        // Port number of threads should be greater than 0
        if (port<0){
            logger.severe("Invalid Port Number: " + args[0]);
            System.exit(1);
        }


        // check for number of threads

        try {
            numThreads = Integer.parseInt(args[1]);
        } catch (Exception e) {
            logger.severe("Invalid number of threads: " + args[1]);
            System.exit(1);
        }

        // Number of threads should be more than 0
        if (numThreads<=0){
            logger.severe("Invalid number of threads: " + args[1]);
            System.exit(1);
        }


        // check for the doucment root existence
        documentRoot = args[2];
        File directory = new File(documentRoot);

        if (!(directory.exists() && directory.isDirectory())) {
            logger.severe("Invalid Document root: " + args[2]);
            System.exit(1);
        }
    }

    /**
     * Driver Application
     * @param args argument
     * @throws IOException if IOException
     */
    public static void main(String[] args) throws IOException {

        // create logger instance
        setLogger();

        // validate arguments
        validateArgs(args);

        // create the Server instance
        Server server = new Server();
        server.start();

    }
}


