/************************************************
 *
 * Author: Agm Islam
 * Assignment: Program 2
 * Class: CSI5321 - Advance Data Communication
 *
 ************************************************/

package megex.app.client;


import megex.serialization.*;


import java.io.*;
import java.net.Socket;
import java.util.*;

import static megex.app.TLSFactory.getClientSocket;


/**
 * Client Application
 *
 * @version 1.7
 * @author Agm Islam
 * Date: 11 March 2023
 */

public class Client {


    private static Socket socket; // Socket
    private static String host;   // Server to connect

    // Type Codes for different Message
    private static final byte DATACODE = 0x0;
    private static final byte HEADERSCODE = 0x1;
    private static final byte SETTINGSCODE = 0x4;
    private static final byte WINDOWCODE = 0x8;


    private static Map<Integer, Client> streamObjectMap = new LinkedHashMap<>();

    private static final int INITIALSTREAMID = 1; // Starting stream ID

    // Header value properties
    private static final String SCHEME = "https";
    private static final String METHOD = "GET";
    private static final String HEADERSTATUS = ":status";

    private static int numberOfSTreams;                                     // holds the number os streams to be sent
    private static final MessageFactory msgFactory = new MessageFactory();  // Message Factory for encode and decode Message


    private final int streamID;
    private final String path;     // path for the stream
    private final String filepath; // Output file name
    private boolean isComplete;    // Stream Data receiving is completed?
    private boolean isStarted;     // Starts receiving data for the stream

    private static final String prefaceString = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    private File outputFile;
    private FileOutputStream fileOutputStream;

    private static Framer framer;
    private static Deframer deframer;

    /**
     * Creates a Client
     *
     * @param streamID stream ID
     * @param path     path of the resource
     * @throws FileNotFoundException if File can't be created
     */
    public Client(int streamID, String path) throws FileNotFoundException {
        this.streamID = streamID;
        this.path = path;
        this.filepath = path.replace('/', '-');
        setIsComplete(false);
        setIsStarted(false);
        //createFile();

    }

    /**
     * sets iscomplete
     *
     * @param iscomplete boolean value whether the file processing is complete
     */
    public final void setIsComplete(boolean iscomplete) {
        this.isComplete = iscomplete;
    }

    /**
     * Getter for iscomplete
     *
     * @return boolean value
     */
    public boolean getIsComplete() {
        return isComplete;
    }

    /**
     * Setter for Isstsrated
     *
     * @return set isStarted
     */
    public boolean getIsStarted() {
        return isStarted;
    }

    /**
     * Creates a file
     *
     * @throws FileNotFoundException if file can't be created
     */
    private final void createFile() throws FileNotFoundException {

        outputFile = new File(filepath);
        fileOutputStream = new FileOutputStream(outputFile);

    }

    /**
     * Writes to file
     *
     * @param out byte to write in the file
     */
    public void writeToFile(byte[] out) {
        try {
            fileOutputStream.write(out);
        } catch (Exception e) {
            displayMessage("Unable to write to file: " + filepath);
        }
    }

    /**
     * Send the preface
     *
     * @param out Outputstream
     * @throws IOException if IOException found
     */
    public static final void sendPreface(OutputStream out) throws IOException {
        out.write(prefaceString.getBytes());
        out.flush();

    }

    /**
     * Send Settings Frame
     *
     * @throws IOException           if IOException
     * @throws BadAttributeException if BadAttributeException
     */
    public static void sendSettings() throws IOException, BadAttributeException {
        Settings s1 = new Settings();
        byte[] encodedSettings = msgFactory.encode(s1);
        framer.putFrame(encodedSettings);

    }


    /**
     * Setter for isStarted
     *
     * @param started boolean value
     */
    private void setIsStarted(boolean started) {
        isStarted = started;
    }


    /**
     * Display Message
     *
     * @param msg String to display
     */
    private static void displayMessage(String msg) {
        System.out.println(msg);

    }

    /**
     * Display Error Message
     *
     * @param msg String to display
     */
    private static void displayErrorMessage(String msg) {
        System.err.println(msg);
    }

    /**
    * CLoses the file
    *
    * @throws IOException
    */
    private final void closeFile() {
        try {
            fileOutputStream.close();
        } catch (Exception e) {
            displayErrorMessage("Unable to close the file");
        }

    }

    /**
     * Send the headers
     */
    private void sendHeaders() {
        try {
            Headers headerMessage = new Headers(streamID, true);
            headerMessage.addValue(":method", METHOD);
            headerMessage.addValue(":path", path);
            headerMessage.addValue(":authority", host);
            headerMessage.addValue(":scheme", SCHEME);

            byte[] encodedHeader = msgFactory.encode(headerMessage);
            framer.putFrame(encodedHeader);


        } catch (Exception e) {
            displayErrorMessage(e.getMessage());
        }

    }


    /**
     * Sends the window update frame
     *
     * @param streamID  streamID
     * @param increment increment value
     */
    private static void sendWindowUpdate(int streamID, int increment) {
        try {
            Window_Update w1 = new Window_Update(streamID, increment);
            byte[] encodedWindow = msgFactory.encode(w1);
            framer.putFrame(encodedWindow);


        } catch (Exception e) {
            displayErrorMessage(e.getMessage());


        }
    }

    /**
     * Terminate Stream
     * @param obj Client
     * @param isSTarted boolean value indicates start
     * @param isComplete boolean value indicates end
     */
    private static void terminateStream(Client obj, boolean isSTarted, boolean isComplete){
        obj.setIsStarted(isSTarted);

        if(!obj.isComplete){
            obj.setIsComplete(isComplete);
            numberOfSTreams--;
        }



    }

    /**
     * Handles the Headers Message
     *
     * @param headerMsg    Headers Message Object
     * @param objectClient Client Object
     */
    private static void handleHeader(Headers headerMsg, Client objectClient) {

        try {

            if (headerMsg.getValue(HEADERSTATUS) != null) {
                String status = headerMsg.getValue(HEADERSTATUS);

                // if valid status
                if (status.startsWith("2") && status.length() == 3) {
                    objectClient.createFile();
                    objectClient.setIsStarted(true);
                    displayMessage("Received Message: " + headerMsg.toString());

                }

                // Bad Status
                else {
                    displayErrorMessage("Bad Status: " + headerMsg.getValue(HEADERSTATUS));
                    terminateStream(objectClient,true,true);
                }


                if (headerMsg.isEnd()) {

                    // Terminate the stream & no more data is expected
                    terminateStream(objectClient,true,true);
                }


            }

        } catch (Exception e) {

            // Unknown error occured
            terminateStream(objectClient,true,true);

        }


    }

    /**
     * Handles the Data Message
     *
     * @param data         Data Message Object
     * @param objectClient Client Object
     */
    private static void handleData(Data data, Client objectClient) {
        try {

            if (objectClient.getIsStarted() && !objectClient.getIsComplete()) {
                int datalen = data.getData().length;


                // if Payload Length > 0
                if (datalen > 0) {

                    objectClient.writeToFile(data.getData());
                    sendWindowUpdate(0, datalen);          // send a window update to stream ID 0
                    sendWindowUpdate(data.getStreamID(), datalen); // send a window update to this stream ID
                }

                displayMessage("Received Message: " + data.toString());
                // End of data & terminate stream
                if (data.isEnd()) {

                    terminateStream(objectClient,true,true);
                    objectClient.closeFile();

                }

            }

        } catch (Exception e) {
            displayErrorMessage(e.getMessage());
        }
    }


    /**
     * Driver application
     *
     * @param args arguments
     * @throws IOException if IOException
     */
    public static void main(String[] args) throws Exception {

        if (args.length < 3) {
            displayErrorMessage("Number of arguments have to be more than two");
            return;
        }

        host = args[0];
        int port = 0;


        // Check for Port
        try {
            port = Integer.parseInt(args[1]);
        } catch (Exception e) {
            displayErrorMessage("Invalid port number given");
        }


        // Create the Socket

        try {


            socket = getClientSocket(host, port);
            if (!socket.isConnected()) {
                displayErrorMessage("Unknown problem connecting to host");
                return;
            }
        } catch (Exception e) {
            displayErrorMessage("Unable to create socket: " + e.getMessage());
            return;
        }


        OutputStream outputStream = socket.getOutputStream();
        InputStream inputStream = socket.getInputStream();

        sendPreface(outputStream); // Send the preface

        framer = new Framer(outputStream);
        deframer = new Deframer(inputStream);

        sendSettings(); // Send the Settings Frame

        for (int i = 2, sid = INITIALSTREAMID; i < args.length; i++) {
            try {

                Client c1 = new Client(sid, args[i]); // throws Exception if file can't be created
                c1.sendHeaders();                     // Send the headers
                streamObjectMap.put(sid, c1);         // Map stream id to an object
                sid += 2;                             // increase stream id by 2 maintain the odd number

            } catch (Exception e) {
                // Error Occurred
                displayErrorMessage(e.getMessage());
                return;
            }
        }


        numberOfSTreams = streamObjectMap.size(); // number of Streams

        // Continue decoding the data until all are received
        while (numberOfSTreams > 0) {

            try {

                byte[] frameData = deframer.getFrame(); // Read frame by frame
                byte typecode = frameData[0];           // Type code


                if (typecode == HEADERSCODE) {
                    try {
                        Headers headerMsg = (Headers) msgFactory.decode(frameData);
                        int rcvdStreamID = headerMsg.getStreamID();


                        if (streamObjectMap.containsKey(rcvdStreamID)) {
                            handleHeader(headerMsg, streamObjectMap.get(rcvdStreamID));
                        } else {
                            // Stream ID not requested
                            displayErrorMessage("Unexpected stream ID: " + headerMsg.toString());

                        }

                    } catch (Exception e) {

                        numberOfSTreams--;
                        displayErrorMessage( e.getMessage());
                    }

                } else if (typecode == DATACODE) {
                    try {

                        Data data = (Data) msgFactory.decode(frameData);
                        int rcvdStreamID = data.getStreamID();
                        //System.out.println("What: "+ data.toString());

                        if (streamObjectMap.containsKey(rcvdStreamID)) {
                            handleData(data, streamObjectMap.get(rcvdStreamID));

                        }
                        else {
                            // Stream ID not requested
                            displayErrorMessage("Unexpected stream ID: " + data.toString());
                        }
                    } catch (Exception e) {
                        displayErrorMessage( e.getMessage());
                    }
                } else if (typecode == SETTINGSCODE || typecode == WINDOWCODE) {
                    Message msg = msgFactory.decode(frameData);
                    displayMessage("Received Message: "+ msg.toString());
                }
                else{
                    displayErrorMessage("Received unknown type: "+ typecode);
                }

            } catch (Exception e) {
                numberOfSTreams--;
                displayErrorMessage("Invalid Message: "+ e.getMessage());
            }
        }

        inputStream.close();
        outputStream.close();
        socket.close();
    }
}

