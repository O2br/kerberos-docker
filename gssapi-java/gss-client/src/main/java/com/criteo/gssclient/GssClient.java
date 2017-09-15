package com.criteo.gssclient;

import org.ietf.jgss.*;
import java.net.Socket;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.security.*;

import com.criteo.gssutils.*;

/**
 * A sample client application that uses JGSS to do mutual authentication
 * with a server using Kerberos as the underlying mechanism. It then
 * exchanges data securely with the server.
 * <p>
 * Every message sent to the server includes a 4-byte application-level
 * header that contains the big-endian integer value for the number
 * of bytes that will follow as part of the JGSS token.
 * <p>
 * The protocol is:
 * 1.  Context establishment loop:
 * a. client sends init sec context token to server
 * b. server sends accept sec context token to client
 * ....
 * 2. client sends a wrapped token to the server.
 * 3. server sends a wrapped token back to the client for the application
 * <p>
 * Start GSS Server first before starting GSS Client.
 * <p>
 * Usage:  java <options> GssClient <service> <serverName>
 */

public class GssClient {
    private static final int PORT = 4567;
    private static final boolean verbose = false;
    
    public static void usage() {
        System.err.println("Usage: java <options> GssClient <action> <service> <serverName>");
        System.err.println("action: tgt, tgs, login");
        System.exit(1);
    }
    
    public static void main(String[] args) throws Exception {

        // Obtain the command-line arguments and parse the server's principal

        String actiontype = args[0];
        
        GssClient gssClient = new GssClient(); 
        String userPrincipalName;
        String servicePrincipalName;
        GSSCredential userCredential;
        switch (actiontype) {
	        case "tgt":
	            if (args.length != 2) {
	            	usage();
	            }
	            userPrincipalName = args[1];
	            userCredential = gssClient.getTGT(userPrincipalName);
	        	break;
	        case "tgs":
	            if (args.length != 3) {
	            	usage();
	            }
	            userPrincipalName = args[1];
	        	servicePrincipalName = args[2];
	        	userCredential = gssClient.getTGT(userPrincipalName);
	        	gssClient.getTGS(userCredential, servicePrincipalName);
	        	break;
	        case "login":
	            if (args.length != 3) {
	            	usage();
	            }
	            String service = args[1];
	            String serverName = args[2];
	            String serverPrinc = String.format("%s@%s", service, serverName);
	            GssClientAction action = new GssClientAction(serverPrinc, serverName, PORT);
	            Jaas.loginAndAction("client", action);
	        	break;
	        default:
	        	usage();
        }
    }

    public GssClient() {
        this.manager = GSSManager.getInstance();
    }

    public GSSCredential getTGT(String userPrincipalName) throws GSSException {

        GSSName clientName = manager.createName(userPrincipalName, GSSName.NT_USER_NAME);
        Oid mech = null;
        return manager.createCredential(
                clientName,
                GSSCredential.INDEFINITE_LIFETIME,
                mech,
                GSSCredential.INITIATE_ONLY
        );

    }

    public void getTGS(final GSSCredential clientCredential, final String servicePrincipalName) throws GSSException {

        GSSName serverName = this.manager.createName(servicePrincipalName, GSSName.NT_HOSTBASED_SERVICE);

        GSSContext context = manager.createContext(serverName,
        		Utils.createKerberosOid(),
                clientCredential,
                GSSContext.DEFAULT_LIFETIME);

        // Set the desired optional features on the context. The client
        // chooses these options.
        
        context.requestMutualAuth(true);
        context.requestConf(true);
        context.requestInteg(true);

    }
  
    private GSSManager manager;
    
    private static class GssClientAction implements PrivilegedExceptionAction<Object> {

        private String serverPrinc;
        private String hostName;
        private int port;

        GssClientAction(String serverPrinc, String hostName, int port) {
            this.serverPrinc = serverPrinc;
            this.hostName = hostName;
            this.port = port;
        }

        public Object run() throws Exception {
            Socket socket = new Socket(hostName, port);
            DataInputStream inStream = new DataInputStream(socket.getInputStream());
            DataOutputStream outStream = new DataOutputStream(socket.getOutputStream());

            System.out.println("Connected to address " + socket.getInetAddress());

        	// This Oid is used to represent the Kerberos version 5 GSS-API
        	// mechanism. It is defined in RFC 1964. We will use this Oid
        	// whenever we need to indicate to the GSS-API that it must
        	// use Kerberos for some purpose.
            Oid krb5Oid = new Oid("1.2.840.113554.1.2.2");

            GSSManager manager = GSSManager.getInstance();

            // Create a GSSName out of the server's name.
            GSSName serverName = manager.createName(serverPrinc, GSSName.NT_HOSTBASED_SERVICE);

            // Create a GSSContext for mutual authentication with the
            //server.
            //   - serverName is the GSSName that represents the server.
            //   - krb5Oid is the Oid that represents the mechanism to
            //     use. The client chooses the mechanism to use.
            //   - null is passed in for client credentials
            //   - DEFAULT_LIFETIME lets the mechanism decide how long the
            //      context can remain valid.
            // Note: Passing in null for the credentials asks GSS-API to
            // use the default credentials. This means that the mechanism
            // will look among the credentials stored in the current Subject
            // to find the right kind of credentials that it needs.
            GSSContext context = manager.createContext(serverName,
                    krb5Oid,
                    null,
                    GSSContext.DEFAULT_LIFETIME);

            // Set the desired optional features on the context. The client
            // chooses these options.

            context.requestMutualAuth(true);  // Mutual authentication
            context.requestConf(true);  // Will use confidentiality later
            context.requestInteg(true); // Will use integrity later

            // Do the context eastablishment loop

            byte[] token = new byte[0];

            while (!context.isEstablished()) {

                // token is ignored on the first call
                token = context.initSecContext(token, 0, token.length);

                // Send a token to the server if one was generated by
                // initSecContext
                if (token != null) {
                    if (verbose) {
                        System.out.println("Will send token of size " + token.length + " from initSecContext.");
                        System.out.println("writing token = " + Utils.getHexBytes(token));
                    }

                    outStream.writeInt(token.length);
                    outStream.write(token);
                    outStream.flush();
                }

                // If the client is done with context establishment
                // then there will be no more tokens to read in this loop
                if (!context.isEstablished()) {
                    token = new byte[inStream.readInt()];
                    if (verbose) {
                        System.out.println("reading token = " + Utils.getHexBytes(token));
                        System.out.println("Will read input token of size " + token.length + " for processing by initSecContext");
                    }
                    inStream.readFully(token);
                }
            }

            System.out.println("Context Established! ");
            System.out.println("Client principal is " + context.getSrcName());
            System.out.println("Server principal is " + context.getTargName());

            // If mutual authentication did not take place, then only the
            // client was authenticated to the server. Otherwise, both
            // client and server were authenticated to each other.
            if (context.getMutualAuthState())
                System.out.println("Mutual authentication took place!");

            byte[] messageBytes = "Hello There!".getBytes("UTF-8");

            // The first MessageProp argument is 0 to request
            // the default Quality-of-Protection.
            // The second argument is true to request
            // privacy (encryption of the message).
            MessageProp prop = new MessageProp(0, true);


            // Encrypt the data and send it across. Integrity protection
            // is always applied, irrespective of confidentiality
            // (i.e., encryption).
            // You can use the same token (byte array) as that used when
            // establishing the context. 
            System.out.println("Sending message: " + new String(messageBytes, "UTF-8"));
            token = context.wrap(messageBytes, 0, messageBytes.length, prop);
            outStream.writeInt(token.length);
            outStream.write(token);
            outStream.flush();

            // Now we will allow the server to decrypt the message,
            // append a time/date on it, and send then it back.
            token = new byte[inStream.readInt()];
            System.out.println("Will read token of size " + token.length);
            inStream.readFully(token);
            byte[] replyBytes = context.unwrap(token, 0, token.length, prop);

            System.out.println("Received message: " + new String(replyBytes, "UTF-8"));

            System.out.println("Done.");
            context.dispose();
            socket.close();

            return null;
        }

    }

}
