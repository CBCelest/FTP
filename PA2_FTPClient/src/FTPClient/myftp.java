package FTPClient;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;


/**
 * A simple FTP Client program that takes two command line parameters.
 * The first is the machine name where the server resides and the 
 * second is the port number the server is listening on. 
 * @author Christine McGee, Andrew Heywood, Matthew Singletary
 *
 */
public class myftp {

	// Variable Declarations
	private static Socket nSocket = null;
	private static Socket tSocket = null;
	private static String hostName = null;
	private static int nPortNumber = 0;
	private static int tPortNumber = 0;
	private static Future clientProgramFuture = null;

	/**
	 * Begins execution of Client program by verifying correct user
	 * command line input and creating the socket connection to the 
	 * server via the hostName and portNumber.
	 * @param args args[0] as host name and args[1] as port number
	 */
	public static void main(String[] args) throws Exception {

		// Create thread pool of one for client to run in
		ExecutorService threadPoolClient = Executors.newFixedThreadPool(1);
		
		// Verify user entered host name and port number
		// if not inform them and exit.
		if (args.length != 3) {
            System.err.println("Pass the machine name where the server resides, "
            		+ "the normal port number, and the terminate port number");
            System.err.println("Usage: myftp HOSTNAME NORMALPORTNUMBER TERMINATEPORTNUMBER");
            System.exit(-1);;            
        }

		// Assign first command line argument to hostName		
		hostName = args[0];

		// Try to parse int from string of second command line
		// argument and assign to portNumber.
		// If error inform user and exit.
		try {

			nPortNumber = Integer.parseInt(args[1]);
			tPortNumber = Integer.parseInt(args[2]);
	    } 		
		catch (NumberFormatException e) {

			System.err.println("Command line argument " + args[1] + " and " + args[2] + " must be a valid port number.");
	        System.exit(-1);
	    }

		// Try creating a stream socket and connect it to the specified port number
		// on the named host. Create new Runnable FTPClientWorker and pass to 
		// thread executor. Upon thread completion (user entered quit) return to here and proceed
		//  with thread and program shutdown. Catch Possible errors and inform user.
		try {

			nSocket = new Socket(hostName, nPortNumber);
			tSocket = new Socket(hostName, tPortNumber);

			Runnable clientProgram = new FTPClientWorker(nSocket, tSocket, tPortNumber);

			clientProgramFuture = threadPoolClient.submit(clientProgram);

			if ((clientProgramFuture.get() == null) || (clientProgramFuture.isDone()) || (clientProgramFuture.isCancelled())) {

				threadPoolClient.shutdown();

				try {
					// Wait a while for existing tasks to terminate
					if (!threadPoolClient.awaitTermination(60, TimeUnit.SECONDS)) {
						threadPoolClient.shutdownNow(); // Cancel currently executing tasks
						// Wait a while for tasks to respond to being cancelled
						if (!threadPoolClient.awaitTermination(60, TimeUnit.SECONDS))
							System.err.println("Pool did not terminate");
					}
				} catch (InterruptedException ie) {
					// Re-Cancel if current thread also interrupted
					threadPoolClient.shutdownNow();
					// Preserve interrupt status
					Thread.currentThread().interrupt();
				}
			}
		}
		catch (IOException e) {
			System.err.println("Input Output Exception: " + e.getMessage());
			System.exit(-1);
		}

		if(threadPoolClient.isShutdown() || threadPoolClient.isTerminated()) {
			System.out.println("Connection closed");
		}
	}
}

