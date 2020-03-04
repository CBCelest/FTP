package FTPClient;

import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


/**
 * Displays a prompt mytftp> to the user and then accepts and executes commands
 * by relaying the commands to the server and displaying the results and 
 * error messages. Runs until user enters the quit command.
 * @author Christine McGee, Andrew Heywood, Matthew Singletary
 *
 */
public class FTPClientWorker implements Runnable{

	// Variable Declarations
	private Socket nClientSocket = null;
	private Socket tClientSocket = null;
	
	private final int tPortNumber;

	private Scanner userInputScanner = null;

	private DataInputStream nInputFromServer = null;
	private BufferedReader nInputFromServerBuffered = null;
	private PrintStream nOutputToServer = null;

	private DataInputStream tInputFromServer = null;
	private BufferedReader tInputFromServerBuffered = null;
	private PrintStream tOutputToServer = null; 

	boolean quitCommand = false;	

	private String currentDirectory;
	private String sysFileSeparator;

	private ExecutorService executorPool;

	private static int threadCount = 1;

	private Socket getSocket;
	private Socket putSocket;

	private static Map<String, Boolean> statusMap = new HashMap<String, Boolean>();

	/**
	 * Initializes newly created FTPClientWorker object before use.
	 * Determines the directory the server resides in and the operating
	 * system's file separator.
	 * @param nSocket Socket created in myftp class
	 */
	public FTPClientWorker(Socket nSocket, Socket tSocket, int tPortNumber){
		this.nClientSocket = nSocket;
		this.tClientSocket = tSocket;
		this.tPortNumber = tPortNumber;
		this.currentDirectory = System.getProperty("user.dir");
		this.sysFileSeparator = System.getProperty("file.separator");
		executorPool = Executors.newFixedThreadPool(10);
	}	

	/**
	 * Overrides the run() method from Runnable class and assigns the input
	 * and output streams to variables. Creates loop to accept user commands
	 * until quit is entered.
	 * (non-Javadoc)
	 * @see java.lang.Runnable#run()
	 */
	@Override
	public void run(){

		// Takes user command
		userInputScanner = new Scanner(System.in);

		// Try assigning input and output streams and starting while loop
		// catch possible errors and inform user. Finally close all streams
		// and socket.
		try {					

			nInputFromServer = new DataInputStream(nClientSocket.getInputStream());
			nInputFromServerBuffered = new BufferedReader(new InputStreamReader(nInputFromServer));

			nOutputToServer = new PrintStream(nClientSocket.getOutputStream(), true);

			tInputFromServer = new DataInputStream(tClientSocket.getInputStream());
			tInputFromServerBuffered = new BufferedReader(new InputStreamReader(tInputFromServer));

			tOutputToServer = new PrintStream(tClientSocket.getOutputStream(), true);

			while (!quitCommand) {
				try {
					commands();
				} catch (IOException e) {
					System.err.println("IOException for command while loop:  " + e + "\n" + e.getMessage());					
				}
			}
		}
		catch(IOException e) {
			System.err.println("Stream creation failed:  " + e + "\n" + e.getMessage());
		}
		finally {

			// Close the input and output streams and the socket.
			try {

				Thread.sleep(1000);

				if (nInputFromServer != null) {
					nInputFromServer.close();
				}

				if (nInputFromServerBuffered != null) {
					nInputFromServerBuffered.close();
				}

				if (nOutputToServer != null) {
					nOutputToServer.close();
				}

				if (nClientSocket != null) {
					nClientSocket.close();
				}
				if (tInputFromServer != null) {
					tInputFromServer.close();
				}

				if (tInputFromServerBuffered != null) {
					tInputFromServerBuffered.close();
				}

				if (tOutputToServer != null) {
					tOutputToServer.close();
				}

				if (tClientSocket != null) {
					tClientSocket.close();
				}
			} 
			catch (IOException e) {
				System.err.println("IOException while trying to close streams:  " + e + "\n" + e.getMessage());
			} catch (InterruptedException e) {
				System.err.println("Interrupted thread Exception while trying to close thread:  " 
						+ e + "\n" + e.getMessage());
			} 
		}
	}	

	/**
	 * Method to display prompt to user and take user input to determine
	 * the command entered. Continue until user enters quit.
	 * @throws IOException
	 */
	private void commands() throws IOException {

		System.out.print("myftp> ");

		while (userInputScanner.hasNextLine()) {

			String commands = userInputScanner.nextLine();
			String command = null;
			String arguments = null;
			String backgroundIndicator = null;
			boolean hasBackgroundIndicator = false;


			try (Scanner separateCommand = new Scanner(commands)) {

				if (separateCommand.hasNext()) {

					command = separateCommand.next();
				}
				else {
					System.out.print("myftp> ");
					continue;              		  
				}

				if (separateCommand.hasNext()) {

					arguments = separateCommand.next();
				}

				if (separateCommand.hasNext()) {
					backgroundIndicator = separateCommand.next();

					if (!backgroundIndicator.equals("&")) {
						System.out.println("Unrecognized command format! Please try again.");
						System.out.print("myftp> ");
						continue;              		  
					}
					else {
						hasBackgroundIndicator = true;
					}
				}
			}

			// If else block to route the command
			if (command.toUpperCase().equals("GET")) {

				if (hasBackgroundIndicator) {
					getCommandBackground(command, arguments);				
				}
				else {
					getCommand(command, arguments);
				}
			}
			else if (command.toUpperCase().equals("PUT")) {	

				if (hasBackgroundIndicator) {
					putCommandBackground(command, arguments);
				}
				else {
					putCommand(command, arguments);
				}
			}
			else if (command.toUpperCase().equals("LS")) {

				lsCommand(commands);
			}
			else if (command.toUpperCase().equals("TERMINATE")) {

				terminateCommand(command, arguments);        		
			}
			else if (command.toUpperCase().equals("QUIT")) {

				quitCommand(commands);
				break;
			}
			else {

				remainingCommands(commands);
			}

			System.out.print("\nmyftp> ");
		}
	}  

	/**
	 * Command get retrieves a file from the server and copy
	 * it to the client.
	 * @param command String representation of command entered by user
	 * @param arguments String representation of file name entered by user
	 * @throws IOException 
	 */
	private synchronized void getCommand(String command, String arguments) throws IOException {

		// Send command and file name to server
		messageServer(command + " " + arguments);

		// If file is not found on server inform user and return from method   		
		if ((receiveServerResponse()).toUpperCase().equals("NOT FOUND")) {
			System.out.println("File not found.");
			return;
		}

		// Get file length from server to setup for transfer
		String filesLengthString = receiveServerResponse();
		Integer filesLength = Integer.parseInt(filesLengthString);

		FileChannel channel = null;
		RandomAccessFile raf = null;

		// Try to create a file output stream and byte buffer the size of the file length.
		// Read input from server into byte buffer then proceed to use 
		// (buffered) file output stream to write from byte buffer to the created file.
		// Inform user upon file transfer completion.
		// Catch possible errors.
		try {

			// String representation of full path name for file to be received
			String filePath = (currentDirectory + sysFileSeparator + arguments);

			// Create new file at specified path name
			File newFile = new File(filePath);

			raf = new RandomAccessFile(newFile, "rw");
			
			channel = raf.getChannel();

			FileLock lock = channel.tryLock();

			byte[] buffer = new byte[filesLength];

			messageServer("READY");
			
			// Fragment the file to receive
			int times = (filesLength / 1000);

			// IF file under 1000 receive whole thing
			if(filesLength < 1000) {

				nInputFromServer.read(buffer, 0, buffer.length);

				ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);

				channel.write(byteBuffer);						

			}
			for(int i = 0; i < times; i++) {


				if((1000*i) < (buffer.length - 1000)) {					
					nInputFromServer.read(buffer, (1000*i), 1000);					
				}
				else {
					nInputFromServer.read(buffer, (1000*i), buffer.length - (1000*i));
				}

			}

			ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
			channel.write(byteBuffer);

			newFile.setReadable(true, false);
			newFile.setWritable(true, false);

			// Release the lock - if it is not null!
			if( lock != null ) {
				lock.release();
			}
		} 
		catch (IOException e) {
			System.err.println("IOException creating file output streams: " + e + "\n" + e.getMessage());
		}     		
		finally {
			channel.close();
			raf.close();
		}

		if ((receiveServerResponse()).toUpperCase().equals("SENT")) {
			System.out.println("File " + arguments + " retrieving complete."); 
		}
	}	

	/**
	 * Command get retrieves a file from the server and copy
	 * it to the client.
	 * @param command String representation of command entered by user
	 * @param arguments String representation of file name entered by user
	 * @throws IOException 
	 */
	private void getCommandBackground(String command, String arguments) throws IOException {		

		// Send put command and filename to Server
		messageServer(command + " " + arguments + " &" );

		// If file is not found on server inform user and return from method   		
		if ((receiveServerResponse()).toUpperCase().equals("NOT FOUND")) {
			System.out.println("File not found.");
			return;
		}

		int getPortNumber;
		
		if((nClientSocket.getPort() + threadCount) != tPortNumber) {
			getPortNumber = (nClientSocket.getPort() + threadCount);
		}
		else {
			getPortNumber = (nClientSocket.getPort() + (threadCount + 1));
		}

		InetAddress hostName = nClientSocket.getInetAddress();


		String getPort = Integer.toString(getPortNumber);

		messageServer(getPort);

		String commandID = receiveServerResponse();

		// Put commandID and run status in hashmap
		statusMap.put(commandID, true);

		System.out.println("Command ID:  " + commandID);

		try {
			Thread.sleep(1000);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}

		getSocket = new Socket(hostName, getPortNumber);

		threadCount++;


		executorPool.submit(new FTPGetBackground(getSocket, arguments, commandID));

  
	}

	/**
	 * Command put sends a file to the server from the client.
	 * @param command command String representation of command entered by user
	 * @param arguments String representation of file name entered by user
	 * @throws IOException 
	 */
	private synchronized void putCommand(String command, String arguments) throws IOException {

		// Setup File object to prepare to send to server
		File fileToSend = new File(arguments);
		
		// If file name entered by user does not exist inform user
		// and return from method.
		if (!fileToSend.exists()) {
			System.out.println("File not found");
			
			return;
		}
		RandomAccessFile raf = new RandomAccessFile(fileToSend, "rw");
		FileChannel pChannel = raf.getChannel();

		FileLock lock = pChannel.tryLock();
		
		// Send put command and filename to server
		messageServer(command + " " + arguments);  

		// Send file length to server
		messageServer(Long.toString(fileToSend.length()));

		// Try to create a byte buffer the size of the file length.
		// Read from file into file input stream then proceed to read
		// from (buffered) file input stream into byte buffer. Write from
		// the byte buffer to the output stream to the Server.
		// Inform user upon file transfer completion.
		// Catch possible errors.
		try {

			byte[] buffer = new byte[(int) fileToSend.length()];

			ByteBuffer byteBuffer = ByteBuffer.allocate(buffer.length);

			pChannel.read(byteBuffer);

			buffer = byteBuffer.array();

			if((receiveServerResponse().toUpperCase().equals("READY"))) {
				// Fragment the file send
				int times = (buffer.length / 1000);

				// IF file under 1000 send whole thing
				if(buffer.length < 1000) {
					nOutputToServer.write(buffer, 0, buffer.length);
					nOutputToServer.flush();
				}
				for(int i = 0; i < times; i++) {

					if((1000*i) < (buffer.length - 1000)) {
						nOutputToServer.write(buffer, (1000*i), 1000);
					}
					else {
						nOutputToServer.write(buffer, (1000*i), buffer.length - (1000*i));
					}  

					nOutputToServer.flush();
				}
			}
			
			// Release the lock - if it is not null!
			if( lock != null ) {
				lock.release();
			}
		} 
		catch (FileNotFoundException e) {
			System.err.println("FileNotFoundException: " + e + "\n" + e.getMessage());
		} 
		catch (IOException e) {
			System.err.println("IOException: " + e + "\n" + e.getMessage());
		} 
		finally {
			pChannel.close(); 
			raf.close();
		}

		if ((receiveServerResponse()).toUpperCase().equals("RECEIVED")) {
			System.out.println("File " + arguments + " sending complete.");
		}
	}

	/**
	 * Command put sends a file to the server from the client.
	 * @param command command String representation of command entered by user
	 * @param arguments String representation of file name entered by user
	 * @throws IOException 
	 */
	private void putCommandBackground(String command, String arguments) throws IOException {

		// Setup File object to prepare to send to server
		File fileToSend = new File(arguments);

		// If file name entered by user does not exist inform user
		// and return from method.
		if (!fileToSend.exists()) {
			System.out.println("File not found");
			return;
		}

		int putPortNumber;

		if((nClientSocket.getPort() + threadCount) != tPortNumber) {
			putPortNumber = nClientSocket.getPort() + threadCount;
		}
		else {
			putPortNumber = nClientSocket.getPort() + (threadCount + 1);
		}

		InetAddress hostName = nClientSocket.getInetAddress();


		// Send put command and filename to Server
		messageServer(command + " " + arguments + " &" );  

		String putPort = Integer.toString(putPortNumber);

		messageServer(putPort);

		String commandID = receiveServerResponse();
		System.out.println("Command ID:  " + commandID);

		// Put commandID and run status in hashmap
		statusMap.put(commandID, true);

		putSocket = new Socket(hostName, putPortNumber);

		threadCount++;



		executorPool.submit(new FTPPutBackground(putSocket, arguments, commandID));


	}

	/**
	 * Command ls retrieves list of files and directories in the current
	 * directory on the server.
	 * @param commands String representation of the ls command entered by the user
	 */
	private void lsCommand(String commands) {

		// Send ls command to user
		messageServer(commands);

		// Receive as a string the number of filenames the server will be sending
		String numberOfFilesString = receiveServerResponse();

		// Parse the String to an int
		int numberOfFiles = Integer.parseInt(numberOfFilesString);

		// Create a List to store the filenames
		List<String> fileList = new ArrayList<String>();

		// Iterate for the number of filenames to be received 
		// and add each filename to the List
		for(int index = 0; index < numberOfFiles; index ++) {
			fileList.add(receiveServerResponse());
		}

		// Iterate over the List and print the filenames for the user
		for(String file : fileList) {        			
			System.out.println(file);
		}		
	}	

	/**
	 * Sends delete, cd, mkdir, or pwd command to the server.
	 * Receives server's response and prints for user.
	 * @param commands String representation of command entered by user
	 */
	private void remainingCommands(String commands) {
		messageServer(commands); 
		System.out.print(receiveServerResponse());
	}	


	/**
	 * Sends terminate command via tPort along with command to terminate
	 * @param command String representation of the command
	 * @param commandID String representation of the commandID to terminate
	 */
	private void terminateCommand(String command, String commandID) {

		messageServerTerminate(commandID);

		if(statusMap.containsKey(commandID)) {
			statusMap.replace(commandID, false);
		}

	}

	/**
	 * Sends quit command to server and sets quitCommand boolean
	 * to true to exit loop.
	 * @param commands String representation of quit command
	 */
	private void quitCommand(String commands) {
		messageServer(commands);
		messageServerTerminate("QUIT");
		quitCommand = true;
	}	

	/**
	 * Sends messages to the server and flushes the stream.
	 * @param message String representation of message to send to server
	 */
	private void messageServer(String message) {
		nOutputToServer.println(message);
	}	

	/**
	 * Receives server's response.
	 * @return String representation of server's response
	 */
	private String receiveServerResponse() {
		String serverResponse = null;

		// Tries to receive server's response and assign to String
		// and catches possible errors.
		try {
			serverResponse = nInputFromServerBuffered.readLine();
		} catch (IOException e) {
			System.err.println("IOException:  " + e + "\n" + e.getMessage());
		}
		return serverResponse;
	}

	/**
	 * Sends terminate message to the server and flushes the stream.
	 * @param message String representation of terminate message to send to server
	 */
	private void messageServerTerminate(String message) {
		tOutputToServer.println(message);
	}	

	/**
	 * Receives server's response to terminate command.
	 * @return String representation of server's response to terminate command
	 */
	private String receiveServerTerminateResponse() {
		String serverResponse = null;

		// Tries to receive server's response and assign to String
		// and catches possible errors.
		try {
			serverResponse = tInputFromServerBuffered.readLine();
		} catch (IOException e) {
			System.err.println("IOException:  " + e + "\n" + e.getMessage());
		}
		return serverResponse;
	}

	// Inner classes for background Get and Put


	/**
	 * Inner class to be run via separate thread to Get file from Server
	 */
	protected class FTPGetBackground extends Thread{

		private Socket gSocket;

		private String fileName;
		private String gCurrentDirectory;
		private String gSysFileSeparator;	

		private DataInputStream gInputFromServer = null;
		private BufferedReader gInputFromServerBuffered = null;
		private PrintStream gOutputToServer = null;

		private FileChannel gChannel;

		private Thread currentThread;

		private boolean cleanUp = false;

		FTPGetBackground (Socket socket, String fileName, String commandID){
			this.gSocket = socket;
			this.fileName = fileName;
			this.gCurrentDirectory = System.getProperty("user.dir");
			this.gSysFileSeparator = System.getProperty("file.separator");

			currentThread = Thread.currentThread();
			currentThread.setName(commandID);
		}

		@Override
		public void run(){

			try {

				gInputFromServer = new DataInputStream(gSocket.getInputStream());
				gInputFromServerBuffered = new BufferedReader(new InputStreamReader(gInputFromServer));

				gOutputToServer = new PrintStream(gSocket.getOutputStream(), true);

			}
			catch(IOException e) {
				System.err.println("IOException for command while loop:  " + e + "\n" + e.getMessage());
			}

			try {
				getFileFromServer();
			} catch (IOException e) {
				e.printStackTrace();
			}
			finally {

				// Close the input and output streams and the socket.
				try {

					if (gInputFromServer != null) {
						gInputFromServer.close();
					}
					if (gOutputToServer != null) {
						gOutputToServer.close();
					}
					if (gInputFromServerBuffered != null) {
						gInputFromServerBuffered.close();
					}
					if (gSocket != null) {
						gSocket.close();
					}
				} catch (IOException e) {
					System.err.println("IOException while trying to close streams:  " + e + "\n" + e.getMessage());
				}
			}

			statusMap.remove(currentThread.getName());
		}


		/**
		 * Get File from server
		 * @throws IOException 
		 */
		private synchronized void getFileFromServer() throws IOException {

			File fileToCreate = null;
			RandomAccessFile raf = null;

			try {

				// Create new file at specified path name
				fileToCreate = new File(gCurrentDirectory + gSysFileSeparator + fileName);			

				raf = new RandomAccessFile(fileToCreate, "rw");
				gChannel = raf.getChannel();

				FileLock lock = gChannel.tryLock();

				// Receive file length from Client
				String fileLengthFromServer = receiveServerResponseGet();

				// Parse String of file length to int
				Integer filesLength = Integer.parseInt(fileLengthFromServer);

				byte[] buffer = new byte[filesLength];

				messageServerGet("READY");

				// Fragment the file to receive
				int times = (filesLength / 1000);

				// IF file under 1000 receive whole thing
				if(filesLength < 1000) {
					gInputFromServer.read(buffer, 0, buffer.length);

					ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);

					gChannel.write(byteBuffer);	

				}

				for(int i = 0; i < times; i++) {


					if((1000*i) < (filesLength - 1000)) {
						gInputFromServer.read(buffer, (1000*i), 1000);
					}
					else {
						gInputFromServer.read(buffer, (1000*i), buffer.length - (1000*i));
					} 

					if(!statusMap.get(currentThread.getName())) {

						cleanUp = true;

						break;
					}
				}
				if(!cleanUp) {
					ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
					gChannel.write(byteBuffer);
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

				gChannel.close();
				raf.close();

				fileToCreate.setReadable(true, false);
				fileToCreate.setWritable(true, false);

				if(cleanUp) {
					fileToCreate.delete();
				}
			}
		}


		/**
		 * Outputs desired message to client via normal output stream
		 * @param message The message for the client
		 */
		private void messageServerGet(String message) {
			gOutputToServer.println(message);
			gOutputToServer.flush();
		}

		/**
		 * Receives clients response on normal communication socket
		 * and returns its string representation
		 * @return String representation of server's response
		 */
		private String receiveServerResponseGet() {
			String clientResponse = null;
			try {
				clientResponse = gInputFromServerBuffered.readLine();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return clientResponse;
		}		

	}

	/**
	 * @Inner class to be run via separate thread to Put file to Server
	 */
	protected class FTPPutBackground extends Thread {

		private Socket pClientSocket = null;

		private DataInputStream pInputFromServer = null;
		private BufferedReader pInputFromServerBuffered = null;
		private PrintStream pOutputToServer = null;

		private FileChannel pChannel;

		private String fileName = null;
		private Thread currentThread;

		FTPPutBackground (Socket socket, String fileName, String commandID){
			this.pClientSocket = socket;
			this.fileName = fileName;

			currentThread = Thread.currentThread();
			currentThread.setName(commandID);
		}

		@Override
		public void run(){
			// Try assigning input and output streams and starting while loop
			// catch possible errors and inform user. Finally close all streams
			// and socket.
			try {					

				pInputFromServer = new DataInputStream(pClientSocket.getInputStream());
				pInputFromServerBuffered = new BufferedReader(new InputStreamReader(pInputFromServer));

				pOutputToServer = new PrintStream(pClientSocket.getOutputStream(), true);

				putFile();

			}
			catch(IOException e) {
				System.err.println("Stream creation failed:  " + e + "\n" + e.getMessage());
			}
			finally {

				// Close the input and output streams and the socket.
				try {

					Thread.sleep(1000);

					if (pInputFromServer != null) {
						pInputFromServer.close();
					}

					if (pInputFromServerBuffered != null) {
						pInputFromServerBuffered.close();
					}

					if (pOutputToServer != null) {
						pOutputToServer.close();
					}

					if (pClientSocket != null) {
						pClientSocket.close();
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

		private synchronized void putFile() throws IOException {

			RandomAccessFile raf = null;
			try {

				// Setup File object to prepare to send to server
				File fileToSend = new File(fileName);	
				raf = new RandomAccessFile(fileToSend, "rw");
				pChannel = raf.getChannel();

				FileLock lock = pChannel.tryLock();

				// Parse Long of file length to a String to send to server
				String fileLengthString = Long.toString(fileToSend.length());

				// Send file length to server
				messageServerPut(fileLengthString);

				//ByteBuffer byteBuffer = ByteBuffer.allocate((int) fileToSend.length());

				byte[] buffer = new byte[(int) fileToSend.length()];

				ByteBuffer byteBuffer = ByteBuffer.allocate(buffer.length);

				pChannel.read(byteBuffer);

				buffer = byteBuffer.array();;

				if((receiveServerResponsePut().toUpperCase().equals("READY"))) {

					// Fragment the file send
					int times = (buffer.length / 1000);

					// IF file under 1000 send whole thing
					if(buffer.length < 1000) {
						pOutputToServer.write(buffer, 0, buffer.length);
						pOutputToServer.flush();
					}

					for(int i = 0; i < times; i++) {

						if((1000*i) < (buffer.length - 1000)) {
							pOutputToServer.write(buffer, (1000*i), 1000);
						}
						else {
							pOutputToServer.write(buffer, (1000*i), buffer.length - (1000*i));
						}  

						pOutputToServer.flush();

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
				e.printStackTrace();
			} 
			finally {

				pChannel.close();
				raf.close();

			}
		}

		/**
		 * Sends messages to the server and flushes the stream.
		 * @param message String representation of message to send to server
		 */
		private void messageServerPut(String message) {
			pOutputToServer.println(message);
		}	

		/**
		 * Receives server's response.
		 * @return String representation of server's response
		 */
		private String receiveServerResponsePut() {
			String serverResponse = null;

			// Tries to receive server's response and assign to String
			// and catches possible errors.
			try {
				serverResponse = pInputFromServerBuffered.readLine();
			} catch (IOException e) {
				System.err.println("IOException:  " + e + "\n" + e.getMessage());
			}
			return serverResponse;
		}
	}
}



