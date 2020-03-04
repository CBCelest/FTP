package FTPServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A simple server program takes a single command line parameter, 
 * which is the port number where the server will execute.
 * @author Christine McGee, Andrew Heywood, Matthew Singletary
 *
 */
public class myftpserver {

	// Variable Declaration
	private static ServerSocket normalServerSocket = null;
	private static ServerSocket terminateServerSocket = null;

	/**
	 * Begins execution of FTP Server program by verifying correct command
	 * line arguments and creating a server socket to accept incoming
	 * connections from clients.
	 * @param args args[0] as port number where server will execute
	 */
	public static void main(String[] args) {

		int nPortNumber = 0;
		int tPortNumber = 0;

		// Verify port number entered on command line
		// Print out error if not and exit. 
		if (args.length != 2) {
            System.err.println("Pass the port for normal commands and the terminate port");
            System.err.println("Usage: myftpserver NORMALPORTNUMBER TERMINATEPORTNUMBER");
            return;            
        }

		// If port number entered parse String to int from args[0]
		if (args.length == 2) {

    		try {

    			nPortNumber = Integer.parseInt(args[0]);
    			tPortNumber = Integer.parseInt(args[1]);
    	    } 
    		catch (NumberFormatException e) {

    			System.err.println("Command line arguments" + args[0] + " and " + args[1] + " must be a port integers."); //FIX
    	        System.exit(-1);
    	    }
    	}
		else {
			System.err.println("Command line arguments" + args[0] + " and " + args[1] + " must be a port integers.");  //FIX
	        System.exit(-1);
		}


		// Try creating server socket for server to accept incoming connections
		// using port number from command line. Create thread pool to enable multiple 
		// clients to connect. Once created start accepting new client connections.
		// Catch errors, print cause, and exit.
		try {

			normalServerSocket = new ServerSocket(nPortNumber);
			terminateServerSocket = new ServerSocket(tPortNumber);

			ExecutorService normalThreadPoolServer = Executors.newFixedThreadPool(20);

			System.out.println("Server Ready");

			while (true) {

				normalThreadPoolServer.execute(new FTPServerWorker(normalServerSocket.accept()));
				normalThreadPoolServer.execute(new FTPServerWorker.FTPServerTerminate(terminateServerSocket.accept()));
			}
		}
		catch (IOException e) {
			System.err.println("IOException while trying to create server socket and thread pool: " 
					+ e.getMessage() + "\n System Terminating."); //FIX
			System.exit(-1);
		}
	}
}
