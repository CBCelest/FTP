package FTPServer;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * When a client connection arrives starts accepting commands and
 * executes them. Upon receiving the quit command, the server closes
 * the connection and does housekeeping.
 * @author Christine McGee, Andrew Heywood, Matthew Singletary
 *
 */
public class FTPServerWorker implements Runnable {

	// Variable Declaration
	private Socket nSocket = null;

	private DataInputStream nInputFromClient = null;
	private BufferedReader nInputFromClientBuffered = null;
	private PrintStream nOutputToClient = null;

	private static boolean quitCommand = false;

	private String root;
	private String currentDirectory;
	private String sysFileSeparator;

	ExecutorService fileTransferPool;

	//private static String commandIDs = Integer.toString(100);
	private static int commandIDsCounter = 1000;

	private ServerSocket putServerSocket = null;
	private ServerSocket getServerSocket = null;

	private static Map<String, Boolean> statusMap = new HashMap<String, Boolean>();

	/**
	 * Initializes newly created FTPServerWorker object before use.
	 * Determines the directory the server resides in, the operating
	 * system's file separator, and the root directory.
	 * @param socket Socket created in myftpserver
	 */
	public FTPServerWorker(Socket nSocket) {

		this.nSocket = nSocket;

		this.currentDirectory = System.getProperty("user.dir");
		this.root = System.getProperty("user.home");
		this.sysFileSeparator = System.getProperty("file.separator");

		fileTransferPool = Executors.newFixedThreadPool(10);
	}


	/* Overrides the run() method from Runnable class and assigns the input
	 * and output streams to variables. Creates loop to accept Client commands
	 * until quit is received.
	 * (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run() {

		System.out.println("Connected to client");

		// Try assigning input and output streams and starting while loop
		// catch possible errors and inform user. Finally close all streams
		// and socket.
		try {
			nInputFromClient = new DataInputStream(nSocket.getInputStream());
			nInputFromClientBuffered = new BufferedReader(new InputStreamReader(nInputFromClient));

			nOutputToClient = new PrintStream(nSocket.getOutputStream(), true);

			// Get the command and possible arguments from the client
			while (!quitCommand) {
				processCommand();
			}
		}
		catch (IOException e) {
			System.err.println("IOException for command while loop:  " + e + "\n" + e.getMessage());
			e.printStackTrace();
		}
		finally {

			// Close the input and output streams and the socket.
			try {

				if (nInputFromClient != null) {
					nInputFromClient.close();
				}
				if (nOutputToClient != null) {
					nOutputToClient.close();
				}
				if (nInputFromClientBuffered != null) {
					nInputFromClientBuffered.close();
				}
				if (nSocket != null) {
					nSocket.close();
				}

				System.out.println("Disconnected from client");

			} catch (IOException e) {
				System.err.println("IOException while trying to close streams:  " + e + "\n" + e.getMessage());
			}
		}
	}

	/**
	 * Processes command from Client to determine what method to call.
	 * @throws IOException
	 */
	private void processCommand() throws IOException {

		String commands = receiveClientResponse();
		String command = null;
		String arguments = null;
		String backgroundIndicator = null;		
		boolean hasBackgroundIndicator = false;

		try (Scanner separateClientCommand = new Scanner(commands)) {

			if (separateClientCommand.hasNext()) {

				command = separateClientCommand.next().toUpperCase();

				if (separateClientCommand.hasNext()) {
					arguments = separateClientCommand.next();
				}

				if (separateClientCommand.hasNext()) {

					backgroundIndicator = separateClientCommand.next();

					if (!backgroundIndicator.equals("&")) {
						System.out.println("Unrecognized command format! Please try again.");
					}
					else {
						hasBackgroundIndicator = true;
					}
				}
			}
			clientsCommand(command, arguments, hasBackgroundIndicator);
		}
	}

	/**
	 * Sends to different methods depending on command.
	 * @param command String representation of command received from Clients
	 * @param arguments String representation of any arguments sent with command by Client
	 * @throws IOException
	 */
	private void clientsCommand(String command, String arguments, boolean bgIndicator) throws IOException {

		// Switch statement for clients different commands
		switch(command) {

		case "GET":
			if(bgIndicator) {
				getCommandBackground(arguments);
			}
			else {
				getCommand(arguments);
			}
			break;

		case "PUT":
			if(bgIndicator) {
				putCommandBackground(arguments);
			}
			else {
				putCommand(arguments);
			}
			break;

		case "DELETE":
			deleteCommand(arguments);
			break;

		case "LS":
			lsCommand();
			break;

		case "CD":
			cdCommand(arguments);
			break;

		case "MKDIR":
			mkdirCommand(arguments);
			break;

		case "PWD":
			pwdCommand();
			break;

		case "QUIT":
			quitCommand();
			break;

		default:
			messageClient("Unknown command");
			break;
		}

	}

	/**
	 * Command get send a file from the server and copies
	 * it to the client.
	 * @param argument String representation of the file name sent by Client
	 * @throws IOException
	 */
	private synchronized void getCommand(String argument) throws IOException {

		// Setup File object to prepare to send to Client
		File fileClientWants = new File(currentDirectory + sysFileSeparator + argument);

		// If file name sent by Client does not exist inform
		// Client and return from method.
		if (!fileClientWants.exists()) {
			messageClient("NOT FOUND");
			return;
		} else {
			messageClient("EXISTS");
		}

		RandomAccessFile raf = new RandomAccessFile(fileClientWants, "rw");
		FileChannel gChannel = raf.getChannel();
		FileLock lock = gChannel.tryLock();

		// Send length of file to Client
		messageClient(Long.toString(fileClientWants.length()));

		// Try to create a byte buffer the size of the file length.
		// Read from file into file input stream then proceed to read
		// from (buffered) file input stream into byte buffer. Write from
		// the byte buffer to the output stream to the Client.
		// Inform user upon file transfer completion.
		// Catch possible errors.
		try {

			byte[] buffer = new byte[(int) fileClientWants.length()];
			ByteBuffer byteBuffer = ByteBuffer.allocate(buffer.length);

			gChannel.read(byteBuffer);

			buffer = byteBuffer.array();

			if((receiveClientResponse().toUpperCase().equals("READY"))) {
				nOutputToClient.write(buffer,0,buffer.length);
				nOutputToClient.flush();
			}

			// Release the lock - if it is not null!
			if( lock != null ) {
				lock.release();
			}
		}
		catch (FileNotFoundException e) {
			System.err.println("FileNotFoundException: " + e);
		}
		catch (IOException e) {
			System.err.println("IOException: " + e);
		}
		finally {
			gChannel.close();
			raf.close();
		}
		messageClient("SENT");
	}

	/**
	 * Send file to Client
	 * @param argument String representation of filename
	 * @throws IOException
	 */
	private void getCommandBackground(String argument) throws IOException {

		// Setup File object to prepare to send to Client
		File fileClientWants = new File(currentDirectory + sysFileSeparator + argument);

		String filePath;
		String commandID;

		// If file name sent by Client does not exist inform
		// Client and return from method.
		if (!fileClientWants.exists()) {
			messageClient("NOT FOUND");
			return;
		} else {
			messageClient("EXISTS");
			filePath = fileClientWants.getAbsolutePath();
		}

		int portNumber = Integer.parseInt(receiveClientResponse());

		getServerSocket = new ServerSocket(portNumber);

		commandID = Integer.toString(commandIDsCounter);

		commandIDsCounter++;

		messageClient(commandID);

		// Put commandID and run status in hashmap
		statusMap.put(commandID, true);

		fileTransferPool.submit(new FTPGetBackground(getServerSocket.accept(), filePath, commandID));

	}

	/**
	 * Command put sends a file from the Client and copies
	 * it to the Server.
	 * @param argument String representation of file name
	 * @throws IOException
	 */
	private synchronized void putCommand(String argument) throws IOException {

		FileChannel channel = null;
		RandomAccessFile raf = null;

		// Try to create a file output stream and byte buffer the size of the file length.
		// Read input from Client into byte buffer then proceed to use
		// (buffered) file output stream to write from byte buffer to the created file.
		// Inform Client upon file transfer completion.
		// Catch possible errors.
		try {
			String filePath = (currentDirectory + sysFileSeparator + argument);

			// Create new file at specified path name
			File fileToCreate = new File(filePath);
			raf = new RandomAccessFile(fileToCreate, "rw");
			channel = raf.getChannel();

			FileLock lock = channel.tryLock();

			// Receive file length from Client
			String fileLengthFromClient = receiveClientResponse();

			// Parse String of file length to int
			Integer filesLength = Integer.parseInt(fileLengthFromClient);

			byte[] buffer = new byte[filesLength];

			messageClient("READY");

			// Fragment the file to receive
			int times = (filesLength / 1000);

			// IF file under 1000 receive whole thing
			if(filesLength < 1000) {
				nInputFromClient.read(buffer, 0, buffer.length);
				ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
				channel.write(byteBuffer);
			}

			for(int i = 0; i < times; i++) {
				if((1000*i) < (buffer.length - 1000)) {
					nInputFromClient.read(buffer, (1000*i), 1000);
				}
				else {
					nInputFromClient.read(buffer, (1000*i), buffer.length - (1000*i));
				}
			}

			ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
			channel.write(byteBuffer);

			fileToCreate.setReadable(true, false);
			fileToCreate.setWritable(true, false);

			// Release the lock - if it is not null!
			if( lock != null ) {
				lock.release();
			}
		}
		catch (IOException e) {
			System.err.println("IOException: " + e);
		}
		finally {
			channel.close();
			raf.close();
		}

		messageClient("RECEIVED");
	}

	/**
	 * Receive file from Client
	 * @param argument String representation of filename to receive
	 * @throws IOException
	 */
	private void putCommandBackground(String argument) throws IOException {

		String filePath = (currentDirectory + sysFileSeparator + argument);

		String commandID;

		int portNumber = Integer.parseInt(receiveClientResponse());

		putServerSocket = new ServerSocket(portNumber);

		commandID = Integer.toString(commandIDsCounter);

		commandIDsCounter++;
		System.out.println("command id sending to client " + commandID);
		messageClient(commandID);

		// Put commandID and run status in hashmap
		statusMap.put(commandID, true);

		fileTransferPool.execute(new FTPPutBackground(putServerSocket.accept(), filePath, commandID));

	}

	/**
	 * Delete file in directory with name given by client
	 * @param argument String representation of file name sent by Client
	 */
	private synchronized void deleteCommand(String argument) {

		// Try to create file object using file name sent by Client
		// If file exists delete file and inform Client.
		// If file does not exist inform Client.
		// Catch any possible errors.
		try {
			File fileToDelete = new File(currentDirectory, argument);

			if(fileToDelete.exists()) {
				if(fileToDelete.delete()) {
					messageClient("Removed " + fileToDelete.getName());
				}
				else {
					messageClient("Deletion of " + fileToDelete.getName() + " Failed");
				}
			}
			else {
				messageClient("File does not exist.");
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Retrieves list of files from current directory and sends to Client
	 */
	private void lsCommand() {

		File directoryFile = new File(determineCurrentDirectory());

		String directoryFiles[] = directoryFile.list();

		messageClient(Integer.toString(directoryFiles.length));

		for(String file: directoryFiles){

			messageClient(file);

		}
	}

	/**
	 * Takes Client arguments and determines which directory to change to then
	 * changes current directory client is in.
	 * @param argument The argument from the client
	 */
	private void cdCommand(String argument) {

		String directory = determineCurrentDirectory();
		String splitUpArgument[] = argument.split("sysFileSeparator");


		for(int index = 0; index < splitUpArgument.length; index++) {

			if ((splitUpArgument[index].equals("..")) && (directory.length() > root.length())) {

				int indexOfLastSeparator = directory.lastIndexOf(sysFileSeparator);

				if (indexOfLastSeparator > 0) {

					directory = directory.substring(0, indexOfLastSeparator);

				}
			}
			else if ((argument != null) && (!argument.equals("."))) {

				File directoryToCheck = new File(currentDirectory, argument);

				if(directoryToCheck.exists() && directoryToCheck.isDirectory()) {
					directory = directory + sysFileSeparator + argument;
				}
				else {
					messageClient("No such file or directory.");
					return;
				}
			}
		}

		this.currentDirectory = directory;
		messageClient("");
	}

	/**
	 * Makes a directory in current directory as named by Client
	 * @param argument The arguments from the Client
	 */
	private void mkdirCommand(String argument) {
		if (argument != null) {

			String newDirectoryPath = (currentDirectory + sysFileSeparator + argument);

			File directoryToMake = new File(newDirectoryPath);

			if(!directoryToMake.mkdir()) {
				messageClient("New Directory creation failed");
			}
		}
		else {
			messageClient("Directory name must not be blank");
		}

		messageClient("");
	}

	/**
	 * Sends the absolute path of the remote current working directory
	 * to the Client
	 */
	private void pwdCommand() {
		determineCurrentDirectory();
		messageClient("Remote working directory: " + currentDirectory);
	}

	/**
	 * Terminates the while loop to close the thread socket connection
	 */
	private void quitCommand() {
		quitCommand = true;
	}

	/**
	 * Determines the remote current directory from the System
	 * @return currentDirectory String representing the current working directory
	 */
	private String determineCurrentDirectory() {

		return this.currentDirectory;
	}

	/**
	 * Outputs desired message to client via normal output stream
	 * @param message The message for the client
	 */
	private void messageClient(String message) {
		nOutputToClient.println(message);
		nOutputToClient.flush();
	}

	/**
	 * Receives clients response on normal communication socket
	 * and returns its string representation
	 * @return String representation of server's response
	 */
	private String receiveClientResponse() {
		String clientResponse = null;
		try {
			clientResponse = nInputFromClientBuffered.readLine();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return clientResponse;
	}





	// Inner classes for background Get and Put

	/**
	 * Inner class to be run via separate thread to Get file from Server
	 */
	protected class FTPGetBackground extends Thread{

		private Socket gSocket = null;

		private DataInputStream gInputFromClient = null;
		private BufferedReader gInputFromClientBuffered = null;
		private PrintStream gOutputToClient = null;

		private FileChannel gChannel;

		private String fileName = null;
		private Thread currentThread;

		FTPGetBackground (Socket socket, String filePath, String commandID){
			this.gSocket = socket;
			this.fileName = filePath;

			currentThread = Thread.currentThread();
			currentThread.setName(commandID);
		}

		@Override
		public void run(){
			// Try assigning input and output streams and starting while loop
			// catch possible errors and inform user. Finally close all streams
			// and socket.
			try {

				gInputFromClient = new DataInputStream(gSocket.getInputStream());
				gInputFromClientBuffered = new BufferedReader(new InputStreamReader(gInputFromClient));

				gOutputToClient = new PrintStream(gSocket.getOutputStream(), true);

				getFileToClient();

			}
			catch(IOException e) {
				System.err.println("Stream creation failed:  " + e + "\n" + e.getMessage());
			}
			finally {

				// Close the input and output streams and the socket.
				try {

					Thread.sleep(100);

					if (gInputFromClient != null) {
						gInputFromClient.close();
					}

					if (gInputFromClientBuffered != null) {
						gInputFromClientBuffered.close();
					}

					if (gOutputToClient != null) {
						gOutputToClient.close();
					}

					if (gSocket != null) {
						gSocket.close();
					}

				}
				catch (IOException e) {
					System.err.println("IOException while trying to close streams:  " + e + "\n" + e.getMessage());
				} catch (InterruptedException e) {
					System.err.println("Interrupted thread Exception while trying to close thread:  "
							+ e + "\n" + e.getMessage());
				}
			}

			statusMap.remove(currentThread.getName());
		}

		private synchronized void getFileToClient() throws IOException {

			RandomAccessFile raf = null;

			try {

				// Setup File object to prepare to send to server
				File fileToSend = new File(fileName);
				raf = new RandomAccessFile(fileToSend, "rw");
				gChannel = raf.getChannel();

				FileLock lock = gChannel.tryLock();


				// Parse Long of file length to a String to send to server
				String fileLengthString = Long.toString(fileToSend.length());

				// Send file length to server
				messageClientGet(fileLengthString);

				byte[] buffer = new byte[(int) fileToSend.length()];

				ByteBuffer byteBuffer = ByteBuffer.allocate(buffer.length);

				gChannel.read(byteBuffer);

				buffer = byteBuffer.array();

				if((receiveClientResponseGet().toUpperCase().equals("READY"))) {

					// Fragment the file send
					int times = (buffer.length / 1000);

					// IF file under 1000 send whole thing
					if(buffer.length < 1000) {
						gOutputToClient.write(buffer, 0, buffer.length);
						gOutputToClient.flush();
					}

					for(int i = 0; i < times; i++) {

						if((1000*i) < (buffer.length - 1000)) {
							gOutputToClient.write(buffer, (1000*i), 1000);
						}
						else {
							gOutputToClient.write(buffer, (1000*i), buffer.length - (1000*i));
						}

						gOutputToClient.flush();

						if(!statusMap.get(currentThread.getName())) {
							break;
						}
					}

					// Release the lock - if it is not null!
					if( lock != null ) {
						lock.release();
					}
				}
			}
			catch (FileNotFoundException e) {
				System.err.println("FileNotFoundException: " + e + "\n" + e.getMessage());
			}
			catch (IOException e) {
				System.err.println("IOException: " + e + "\n" + e.getMessage());
			}
			finally {
				gChannel.close();
				raf.close();
			}
		}

		/**
		 * Sends messages to the server and flushes the stream.
		 * @param message String representation of message to send to server
		 */
		private void messageClientGet(String message) {
			gOutputToClient.println(message);
		}

		/**
		 * Receives server's response.
		 * @return String representation of server's response
		 */
		private String receiveClientResponseGet() {
			String clientResponse = null;

			// Tries to receive server's response and assign to String
			// and catches possible errors.
			try {
				clientResponse = gInputFromClientBuffered.readLine();
			} catch (IOException e) {
				System.err.println("IOException:  " + e + "\n" + e.getMessage());
			}
			return clientResponse;
		}
	}

	/**
	 * @Inner class to be run via separate thread to Put file to Server
	 */
	protected class FTPPutBackground extends Thread{

		private Socket pSocket;

		private String fileName;

		private DataInputStream pInputFromClient = null;
		private BufferedReader pInputFromClientBuffered = null;
		private PrintStream pOutputToClient = null;
		private FileChannel pChannel;

		private Thread currentThread;

		private boolean pcleanUp;

		FTPPutBackground(Socket socket, String filePath, String commandID) {
			this.pSocket = socket;
			this.fileName = filePath;

			this.currentThread = Thread.currentThread();
			this.currentThread.setName(commandID);

		}

		@Override
		public void run(){

			try {

				pInputFromClient = new DataInputStream(pSocket.getInputStream());
				pInputFromClientBuffered = new BufferedReader(new InputStreamReader(pInputFromClient));

				pOutputToClient = new PrintStream(pSocket.getOutputStream(), true);

				putFile();

			} catch (IOException e) {
				e.printStackTrace();
			}
			finally {

				// Close the input and output streams and the socket.
				try {

					if (pInputFromClient != null) {
						pInputFromClient.close();
					}
					if (pOutputToClient != null) {
						pOutputToClient.close();
					}
					if (pInputFromClientBuffered != null) {
						pInputFromClientBuffered.close();
					}
					if (pSocket != null) {
						pSocket.close();
					}
				} catch (IOException e) {
					System.err.println("IOException while trying to close streams:  " + e + "\n" + e.getMessage());
				}
			}

			statusMap.remove(currentThread.getName());
		}

		private synchronized void putFile() throws IOException {

			File fileToCreate = null;			
			RandomAccessFile raf = null;

			try {

				// Create new file at specified path name
				fileToCreate = new File(fileName);

				raf = new RandomAccessFile(fileToCreate, "rw");

				pChannel = raf.getChannel();

				FileLock lock = pChannel.tryLock();

				// Receive file length from Client
				String fileLengthFromClient = receiveClientResponsePut();

				// Parse String of file length to int
				Integer filesLength = Integer.parseInt(fileLengthFromClient);

				byte[] buffer = new byte[filesLength];

				messageClientPut("READY");

				// Fragment the file to receive
				int times = (filesLength / 1000);

				// IF file under 1000 receive whole thing
				if(filesLength < 1000) {

					pInputFromClient.read(buffer, 0, buffer.length);

					ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);

					pChannel.write(byteBuffer);

				}

				for(int i = 0; i < times; i++) {

					if((1000*i) < (filesLength - 1000)) {

						pInputFromClient.read(buffer, (1000*i), 1000);

					}
					else {
						pInputFromClient.read(buffer, (1000*i), buffer.length - (1000*i));

					}
						
					if(!(statusMap.get(currentThread.getName()))) {

						pcleanUp = true;

						break;
					}

				}

				if(!pcleanUp) {
					ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
					pChannel.write(byteBuffer);
				}


				// Release the lock - if it is not null!
				if( lock != null ) {
					lock.release();
				}

			}
			catch (IOException e) {
				System.err.println("IOException: " + e);
			}
			finally {

				pChannel.close();
				raf.close();

				fileToCreate.setReadable(true, false);
				fileToCreate.setWritable(true, false);

				if(pcleanUp) {
					fileToCreate.delete();
				}
			}
		}


		/**
		 * Outputs desired message to client via normal output stream
		 * @param message The message for the client
		 */
		private void messageClientPut(String message) {
			pOutputToClient.println(message);
			pOutputToClient.flush();
		}

		/**
		 * Receives clients response on  communication socket
		 * and returns its string representation
		 * @return String representation of server's response
		 */
		private String receiveClientResponsePut() {
			String clientResponse = null;
			try {
				clientResponse = pInputFromClientBuffered.readLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return clientResponse;
		}

	}

	protected static class FTPServerTerminate extends Thread {


		private Socket tSocket = null;

		private static DataInputStream tInputFromClient = null;
		private BufferedReader tInputFromClientBuffered = null;
		private PrintStream tOutputToClient = null;

		private static boolean tQuitCommand = false;
		
		FTPServerTerminate(Socket socket) {
			this.tSocket = socket;
		}


		@Override
		public void run() {

			try {

				tInputFromClient = new DataInputStream(tSocket.getInputStream());
				tInputFromClientBuffered = new BufferedReader(new InputStreamReader(tInputFromClient));

				tOutputToClient = new PrintStream(tSocket.getOutputStream(), true);

				while (!tQuitCommand) {
					processTerminate();
				}

			}
			catch(IOException e) {
				System.err.println("IOException for command while loop:  " + e + "\n" + e.getMessage());
			}
			finally {

				try {
					if (tInputFromClient != null) {
						tInputFromClient.close();
					}
					if (tOutputToClient != null) {
						tOutputToClient.close();
					}
					if (tInputFromClientBuffered != null) {
						tInputFromClientBuffered.close();
					}
					if (tSocket != null) {
						tSocket.close();
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
		}

		private void processTerminate() {

			String terminate = receiveClientResponseTerminate();

			String arguments = null;

			try (Scanner separateTerminateCommand = new Scanner(terminate)) {

				if (separateTerminateCommand.hasNext()) {

					arguments = separateTerminateCommand.next();

				}
				
				if(arguments.toUpperCase().equals("QUIT")) {
					tQuitCommand = true;					
				}
				else {
					terminateCommand(arguments);
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		private void terminateCommand(String commandID) throws IOException {

			if(statusMap.containsKey(commandID)) {
				statusMap.replace(commandID, false);
			}
			else {
				messageClientTerminate("Unknown commandID");
			}



		}

		/**
		 * Outputs desired message to client via terminate output stream
		 * @param message The message for the client
		 */
		private void messageClientTerminate(String message) {
			tOutputToClient.println(message);
			tOutputToClient.flush();
		}

		/**
		 * Receives clients response on terminate communication socket
		 * and returns its string representation
		 * @return String representation of server's response
		 */
		private String receiveClientResponseTerminate() {
			String clientResponse = null;
			try {
				clientResponse = tInputFromClientBuffered.readLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return clientResponse;
		}

	}
}
