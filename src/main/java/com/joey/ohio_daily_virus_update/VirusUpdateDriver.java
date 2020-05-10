package com.joey.ohio_daily_virus_update;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.LinkedList;
import java.util.Map;
import java.util.Properties;
import java.util.Queue;
import java.util.Scanner;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import javax.activation.DataHandler;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.util.ByteArrayDataSource;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Chunk;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

public class VirusUpdateDriver {
		//data structures
		//holds complete data set parsed from the CSV file for every day starting 06Apr2020
		private static TreeMap<GregorianCalendar, TreeMap<String, County>> dataByDay = new TreeMap<>(new CustomGregorianCalendarComparator());
		//data recorded by older versions of the program, contains date and total case count for that date
		private static ArrayList<SingleDayCount> previousVersionData = new ArrayList<>();
		//contains log items for sending emails and updating data
		private static ArrayList<LogItem> log = new ArrayList<>();
		
		//email parameters
		private static String emailFrom;
		private static String password;
		private static ArrayList<InternetAddress> emails = new ArrayList<>();
		
		//data representation parameters
		//private static boolean totalOhioCount = true;
		private static boolean includeHospitalizedCount = false;
		private static boolean includeDeathCount = false;
		private static ArrayList<String> counties = new ArrayList<>();
		
		/*
		 * A table in a pdf is used to convey data to user in email for new update
		 * These two parameters will be used to adjust pdf formation around data preferences selected by user
		 * Preferences are stored in includeHospitalizedCount and includeDeathCount booleans
		 */
		private static String[] columnTitles = {"Date", "Count", "", ""};
		private static int columns = 2;
		
		private static Scanner in = new Scanner(System.in);
		
		final static char c = File.separatorChar;
		
		public static void main(String[] args) {
			
			/*
			 * Flow of the program
			 * First: welcome message
			 * second: enter email information
			 * third: parse in data from County_data_Over_Time.dat file
			 * fourth: enter data preferences for email body
			 * fifth: start scheduler to run daily emails
			 * sixth: cycle through menu options
			 */
			System.out.println("Welcome to my daily update program. This program will send you daily updates on "
					+ "COVID-19 related data in Ohio.\nWe will now set up the program by entering your email "
					+ "information, reading in historical data, and then choosing what data you would like to receive.\n"
					+ "If you would like to receive historical data starting from 01Mar2020, please make sure that "
					+ "\"County_Data_Over_Time.dat\" file is present and in the correct directory.");
			
			//Gather email information from user
			System.out.println("\nEmail set up: ");
			updateEmailInformation();
			
			System.out.println("Historical data will now be read. If you wish to receive data starting from 01Mar2020, please"
					+ "make sure that \"County_Data_Over_Time.dat\" file is present. Now reading data . . .");
			
			TreeMap<GregorianCalendar, TreeMap<String, County>> dataByDaySerialized = null;
			ArrayList<SingleDayCount> previousDataSerialized = null;
			
			//creates input stream for County_Data_Over_Time.dat file and creates objects to store data
			try{
				//read in new version data
				ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(new FileInputStream("County_Data_Over_Time.dat")));
				dataByDaySerialized = (TreeMap<GregorianCalendar, TreeMap<String, County>>)(in.readObject());
				previousDataSerialized = (ArrayList<SingleDayCount>)(in.readObject());
				in.close();
				System.out.println("Data was succesfully read. You will receive historical data.");
			} catch (FileNotFoundException e) {
				System.err.println("Could not open the file \"County_Data_Over_Time.dat\". No historical data will be included.");
			} catch (IOException e) {
				System.err.println("Could not de-serialize the object. No historical data will be included.");
			} catch (ClassNotFoundException e) {
				System.err.println("Could not cast the de-serialized object. No historical data will be included.");
			}
			
			//following lines initialize static variables to equal the serialized variables if they were success
			if(dataByDaySerialized != null) {
				dataByDay = dataByDaySerialized;
			}
			if(previousDataSerialized != null) {
				previousVersionData = previousDataSerialized;
			}
			
			System.out.println("\nNext, you will pick what parameters you would like to see in the emails. Total Case counts for "
					+ "all of Ohio will automatically be included.");
			//gather email body preferences
			updateEmailBodyPreferences();
			
			//task to run once a day at 2:05 pm after website updates
			ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
			Runnable task = new Runnable() {
				@Override
				public void run() {
					//create date and get case number for this specific day
					DateFormat dateFormat = new SimpleDateFormat("ddMMMyyyy HH:mm:ss");
					GregorianCalendar date = new GregorianCalendar();
					
					TreeMap<String, County> currentData = getDataFromCSV();
					dataByDay.put(date, currentData);
					
					String subject = "Ohio Virus Update: " + dateFormat.format(date.getTime());
					
					//build body of email with data
					String body = formEmailBody(includeHospitalizedCount, includeDeathCount, counties);
					//initialize the log item
					LogItem entry = new LogItem(date, body);
					//pass the email parameters to the email method
					sendEmail(subject, body.toString(), emailFrom, password, emails, entry);
					System.out.println("Email sent successfully on " + dateFormat.format(date.getTime()) + ". Enter \"1\" to terminate.");
					//add log entry to log
					log.add(entry);
				}
			};
			
			//sets delay and starts task to send email every day at 2:05pm according to Eastern Daylight Time
			long delay = ChronoUnit.SECONDS.between(ZonedDateTime.now(ZoneId.of("America/New_York")), ZonedDateTime.of(LocalDate.now(), LocalTime.of(14, 05), ZoneId.of("America/New_York")));
			scheduler.scheduleAtFixedRate(task, delay, 86400, TimeUnit.SECONDS);
			
			//iterate through the menu 
			int menuOption = 0;
			while (menuOption != 1) {
				System.out.println("\nMenu\n1 - Quit\n2 - Show log\n3 - Update email information\n4 - Choose data to send in email body\n5 - Update data"
						+ "\n6 - Print last email data to console\n7 - Remove an email from mailing list");
				
				menuOption = in.nextInt();
				
				switch (menuOption) {
					case 2:
						System.out.println("-------------Log-------------");
						for (LogItem l: log) {
							System.out.println(l.toString());
						}
						System.out.println("-----------End Log-----------");
						break;
					case 3:
						updateEmailInformation();
						break;
					case 4:
						updateEmailBodyPreferences();
						break;
					case 5:
						GregorianCalendar date = new GregorianCalendar();
						TreeMap<String, County> currentData = getDataFromCSV();
						dataByDay.put(date, currentData);
						LogItem entry = new LogItem(date);
						log.add(entry);
						System.out.println("Update successful");
						break;
					case 6:
						//find last regularly scheduled log item with a body and print log
						for (int i = log.size() - 1; i > -1; i--) {
							if (log.get(i).isScheduled()) {
								System.out.println(log.get(i).getBody());
							}
						}
						break;
					case 7:
						changeMailingList();
						break;
				}
			} //end while loop
			
			//exit procedure: shutdown scheduler, close scanner object, write data to file
			if (menuOption == 1) {
				scheduler.shutdownNow();
				in.close();
				//writes new data to file
				try {
					ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(new FileOutputStream("County_Data_Over_Time.dat")));
					out.writeObject(dataByDay);
					out.writeObject(previousVersionData);
					out.close();
				} catch (FileNotFoundException e) {
					System.err.println("Could not create the file \"County_Data_Over_Time.dat\"");
				} catch (IOException e) {
					System.err.println("Could not serialize the object");
				}
				System.out.println("Goodbye!");
				System.exit(0);
			}
		}
		
		/*
		 * Method to update what information is included in daily email body. Options are as follows:
		 * Total case count for Ohio is default
		 * Users may pick from counties stored in the latest day's data set (stored in static LinkedList variable counties)
		 * Total count for counties are included if user decides to include any counties
		 * User may pick to include hospitalized count for Ohio and counties or not (static boolean variable includeHospitalizedCount)
		 * User may pick to include death count for Ohio and counties or not (static boolean variable includeDeathCount)
		 * 
		 *  These options are saved in static boolean variables and a LinkedList to hold county names
		 */
		private static void updateEmailBodyPreferences() {
			
			//automatically include total counts for all of Ohio
			
			System.out.println("Total case count for specific counties? (Y/n)");
			if (in.next().toLowerCase().contains("y")) {
				//lists counties from most recent data collection and records what counties the user wishes to display
				//ArrayList to hold most up to date counties
				ArrayList<County> countyList = new ArrayList<>();
				//most recent day's data
				if (!dataByDay.isEmpty() && dataByDay != null) {
					TreeMap<String, County> s = dataByDay.get(dataByDay.floorKey(new GregorianCalendar()));
					
					//adds counties to ArrayList
					for (Map.Entry<String, County> e: s.entrySet()) {
						countyList.add(e.getValue());
					}
					//prints counties to console with numbers
					for (int i = 0; i < countyList.size(); i++) {
						if (i % 10 == 0) {
							System.out.print("\n" + (i + 1) + "-" + countyList.get(i).getName() + " ");
						} else {
							System.out.print((i + 1) + "-" + countyList.get(i).getName() + " ");
						}
					}
					//requests user input and adds county name to ArrayList to call during email body formation
					boolean addAnotherCounty = true;
					while (addAnotherCounty) {
						System.out.print("Please enter the number of a county to add: ");
						int countyToAdd = in.nextInt();
						if (countyToAdd >= 0 || countyToAdd < countyList.size()) {
							counties.add(countyList.get(countyToAdd - 1).getName());
						}
						System.out.println("Add another? (Y/n)");
						if (in.next().toLowerCase().contains("n")) {
							addAnotherCounty = false;
						}
					} //end while loop
				} else {
					//Will print in the case that no data has been gathered manually or from stored files
					System.out.println("There are no counties in the data collection to include. Please update "
							+ "manually and try again.");
				}
			}
			
			//include hospitalized count?
			System.out.println("Included hospitalized count for Ohio and counties in the email body? (Y/n): ");
			String input = in.next().toLowerCase();
			if (input.contains("y")) {
				//only add one to columns for table formation if it was previously false
				if (!includeHospitalizedCount) {
					columns++;
				}
				//change values according to user response
				includeHospitalizedCount = true;
				columnTitles[2] = "Hospitalized Count";
			} else if (input.contains("n")) {
				//only subtract from columns if value was previously true
				if (includeHospitalizedCount) {
					columns--;
				}
				//change values according to user response
				includeHospitalizedCount = false;
				columnTitles[2] = "";
			}
			
			//include death count?
			System.out.println("Included death count for Ohio and counties in the email body? (Y/n): ");
			input = in.next().toLowerCase();
			if (input.contains("y")) {
				//only add one to columns for table formation if it was previously false
				if (!includeDeathCount) {
					columns++;
				}
				//set new values according to user response
				includeDeathCount = true;
				columnTitles[3] = "Death Count";
				
			} else if (input.contains("n")) {
				//only subtract from columns if value was previously true
				if (includeDeathCount) {
					columns--;
				}
				//change values according to user response
				includeDeathCount = false;
				columnTitles[3] = "";
			}
			
		}
		
		/*
		 * Method to form email body according to user preferences
		 * 
		 * Parameters are set by user to decide what data will be included in the updateEmailBodyPreferences() method.
		 * Iterates through data structures and builds email body using StringBuilder
		 * Returns a formated string representation of the data by day in following format:
		 * "Day
		 * 		Data for Day
		 *  Day2
		 *  	Data for Day2"
		 */
		private static String formEmailBody(boolean includeHospitalizedCount, boolean includeDeathCount, ArrayList<String> counties) {
			
			DateFormat dateFormat = new SimpleDateFormat("ddMMMyyyy");
			
			StringBuilder body = new StringBuilder();
			
			int previousDayCount = 0;
			
			//append previous version data 01Mar2020 to 05Apr2020
			for (SingleDayCount d: previousVersionData) {
				body.append(dateFormat.format(d.getDate().getTime()) + ":\n");
				int currentDayCount = d.getCaseCount();
				if (previousDayCount == 0) {
					body.append("\tTotal Count: " + currentDayCount + "\n");
				} else {
					int newCases = currentDayCount - previousDayCount;
					body.append("\tTotal Count: " + currentDayCount + " (" + newCases + ")" + "\n");
				}
				previousDayCount = currentDayCount;
			}
			//print new version data 06Apr2020 and after
			for (Map.Entry<GregorianCalendar, TreeMap<String, County>> entry: dataByDay.entrySet()) {
				body.append(dateFormat.format(entry.getKey().getTime()) + ":\n");
				
				//get total case count for a single reporting day
				int totalCount = 0;
				int totalHospitalized = 0;
				int totalDeaths = 0;
				for (Map.Entry<String, County> subentry: entry.getValue().entrySet()) {
					totalCount += subentry.getValue().getCount();
					totalHospitalized += subentry.getValue().getHospitalizedCount();
					totalDeaths += subentry.getValue().getDeathCount();
				}
				if (previousDayCount == 0) {
					body.append("\tTotal Count: " + totalCount + "\n");
				} else {
					int newCases = totalCount - previousDayCount;
					body.append("\tTotal Count: " + totalCount + " (" + newCases + ")" + "\n");
				}
				previousDayCount = totalCount;
				
				//add hospitalizedCount and deathCount if necessary
				if (includeHospitalizedCount) {
					body.append("\tTotal Hospitalized Count: " + totalHospitalized + "\n");
				}
				if (includeDeathCount) {
					body.append("\tTotal Death Count: " + totalDeaths + "\n");
				}
					
				//add county information if necessary
				if (!counties.isEmpty()) {
					for (String s: counties) {
						
						//get specific county counts
						int countyCount = entry.getValue().get(s).getCount();
						body.append("\t" + s + " Case Count: " + countyCount + "\n");
						
						if (includeHospitalizedCount) {
							int hospitalized = entry.getValue().get(s).getHospitalizedCount();
							body.append("\t" + s + " Hospitalized Count: " + hospitalized + "\n");
						}
						
						if (includeDeathCount) {
							int deaths = entry.getValue().get(s).getDeathCount();
							body.append("\t" + s + " Death Count: " + deaths + "\n");
						}
						
					}
				}
			} //end for loop
			
			return body.toString();
		}
		
		/*
		 * Method to gather email address and password to send email and email address to send email to
		 * Modifies static String variables emailTo, password, and emailFrom
		 */
		private static void updateEmailInformation() {
			//Gather email information from user
			boolean verify = true;
			while (verify) {
				//Console console = System.console();
				System.out.println("Please enter the email address that will send the emails: ");
				emailFrom = in.next().trim();
				System.out.println("Please type the password for this email (note: less secure app access will likely need to be turned "
						+ "on in the google account settings): ");
				password = in.next().trim();
				boolean addAnother = true;
				//skips step to add emails to send to if there are already emails to send to
				if (!emails.isEmpty()) {
					addAnother = false;
				}
				while (addAnother) {
					System.out.println("Please enter an email address that will receive the emails: ");
					try {
						emails.add(new InternetAddress(in.next().trim()));
					} catch (AddressException e) {
						System.err.println("Could not parse the email address");
					}
					System.out.println("Would you like to add another email?(Y/n)");
					if (in.next().toLowerCase().contains("n")) {
						addAnother = false;
					}
				} //end while loop
				
				System.out.println("Would you like to verify the information? (Y/n)");
				if (in.next().toLowerCase().contains("y")) {
					System.out.print("Email address that will send the emails: " 
						+ emailFrom + "\nPassword: " + password + "\nEmail address(es) that will receive the emails: "
						+ emails.toString() + "\nIs the following information correct? (Y/n): ");
					if (in.next().toLowerCase().contains("y")) {
						verify = false;
					}
				} else {
					verify = false;
				}
			} //end while loop
		}
		
		/*
		 * Method to add or remove email addresses on the mailing list
		 */
		private static void changeMailingList() {
			
			System.out.println("Type \"add\" to add emails addresses and \"remove\" to remove emails addresses.");
			String input = in.next().toLowerCase();
			if (input.contains("add")) {
				boolean addAnother = true;
				while (addAnother) {
					System.out.println("Please enter an email address that will receive the emails: ");
					try {
						emails.add(new InternetAddress(in.next().trim()));
					} catch (AddressException e) {
						System.err.println("Could not parse the email address");
					}
					System.out.println("Would you like to add another email?(Y/n)");
					if (in.next().toLowerCase().contains("n")) {
						addAnother = false;
					}
				} //end while loop
			} else if (input.contains("remove")) {
				boolean removeAnother = true;
				while (removeAnother) {
					int i = 1;
					for (InternetAddress address: emails) {
						if (i % 10 != 0) {
							System.out.print(i + "-" + address.toString() + " ");
						} else {
							System.out.print(i + "-" + address.toString() + "\n");
						}
						i++;
					} 
					System.out.println("Please enter the number of the address to remove.");
					int index = in.nextInt() - 1;
					if (index >= 0 && index < emails.size()) {
						emails.remove(index);
					} else {
						System.out.println("Please enter a valid number next time.");
					}
					System.out.println("Remove another?(Y/n)");
					if (in.next().toLowerCase().contains("n")) {
						removeAnother = false;
					}
				} //end while loop
			}
		}
		
		/*
		 * This method returns collection of data stored by county
		 * Opens URL connection then gets input stream for csv file link for coronavirus.ohio.gov
		 * parses data in through input stream
		 * 
		 * Method returns a TreeMap with county name as its key and County objects as values
		 */
		private static TreeMap<String, County> getDataFromCSV() {
			
			TreeMap<String, County> currentDayData = new TreeMap<>();
			
			try {
				//open URL connection and create BufferedReader to read the input stream for the CSV file
				URL urlCSV = new URL("https://coronavirus.ohio.gov/static/COVIDSummaryData.csv");
				URLConnection connection = urlCSV.openConnection();
				BufferedReader inputCSV = new BufferedReader(new InputStreamReader(connection.getInputStream()));
				
				/*
				 * CSV file formatted as follows:
				 * first row: column titles
				 * data: county, sex, age range, onset date, death date, case count, death count, hospitalized count
				 * *see comment below for update made on 21Apr2020
				 * last row: totals
				 * so we must ignore the first and last lines during data collection from the CSV file
				 */
				
				//first line read first so the title row is not stored
				String line = inputCSV.readLine();
				while((line = inputCSV.readLine()) != null) {
					String[] s = line.split(",");
					String countyName = s[0];
					//following if statement skips the last row for the totals
					if (countyName.contains("Grand Total")) {
						break;
					}
					String sex = s[1];
					String ageRange = s[2];
					String onsetDate = s[3];
					String deathDate = s[4];
					/*
					 * reporting of data in CSV file changed on 21Apr2020 to include admission date between death date and count 
					 * this is ignored by shifting the index for the counts by positive one
					 */
					int count = Integer.parseInt(s[6]);
					int deathCount = Integer.parseInt(s[7]);
					int hospitalizedCount = Integer.parseInt(s[8]);
					CaseInstance newCase = new CaseInstance(sex, ageRange, onsetDate, deathDate, count, deathCount, hospitalizedCount);
					if (currentDayData.containsKey(countyName)) {
						currentDayData.get(countyName).addCaseInstance(newCase);
					} else {
						County newCounty = new County(countyName);
						newCounty.addCaseInstance(newCase);
						currentDayData.put(countyName, newCounty);
					}
				}
				
				inputCSV.close();
				
				
			} catch (MalformedURLException e) {
				e.printStackTrace();
			} catch (IOException e) {
				e.printStackTrace();
			}
			
			return currentDayData;
			
		}
		
		/*
		 * This method sends email according to entered parameters
		 * 
		 * Sets relevant properties for email transport such as host, port, protocol usage, etc.
		 * Creates a session with proper authentication method
		 * Constructs the message 
		 * Transports message
		 * Updates LogItem variables 
		 */
		private static void sendEmail(String subject, String body, final String emailFrom,  final String password, ArrayList<InternetAddress> emails, LogItem log) {
			//set properties 
			Properties props = new Properties();
			props.put("mail.smtp.auth", "true");
			props.put("mail.smtp.starttls.enable", "true");
			props.put("mail.smtp.host", "smtp.gmail.com");
			props.put("mail.smtp.port", "587");
			props.put("mail.smtp.ssl.trust", "smtp.gmail.com");
			props.put("mail.debug.auth", true);
			props.put("mail.debug", true);
			
			//create session
			Session session = Session.getInstance(props, new Authenticator() {
				@Override
				protected PasswordAuthentication getPasswordAuthentication() {
					return new PasswordAuthentication(emailFrom, password);
				}
			});
			
			//InternetAddress array is constructed from emails ArrayList to pass to setRecipients method
			InternetAddress[] addresses = new InternetAddress[emails.size()];
			for (int i = 0; i < emails.size(); i++) {
				addresses[i] = emails.get(i);
			}
			
			//create message
			Message message = new MimeMessage(session);
			try {
				message.setFrom(new InternetAddress(emailFrom));
				message.setRecipients(Message.RecipientType.TO, addresses);
				message.setSubject(subject);
				attachPDF(message);
				//message.setText(body);
				message.setFileName(subject);
			} catch (AddressException e) {
				e.printStackTrace();
			} catch (MessagingException e) {
				e.printStackTrace();
			}
			
			//message transport
			
			try {
				Transport.send(message);
				log.setSendSuccessful(true);
			} catch (MessagingException e) {
				log.setSendSuccessful(false);
			}
			
			log.setBody(body);
			log.setMessage((MimeMessage)message);
			log.setSession(session);
			
			
		}
		
		private static void attachPDF(Message message) {
			//attach PDF
			BodyPart m = new MimeBodyPart();
			Multipart m2 = new MimeMultipart();
			try {
				//body
				m.setText("Please see attached PDF for data.");
				m2.addBodyPart(m);
				
				//attachment
				m = new MimeBodyPart();
				byte[] d = formPDF();
				ByteArrayDataSource s = new ByteArrayDataSource(d, "application/pdf");
				m.setDataHandler(new DataHandler(s));
				m.setFileName("testPDF");
				m2.addBodyPart(m);
				message.setContent(m2);
				
			} catch (MessagingException e) {
				e.printStackTrace();
			}
			
		}
		
		/*
		 * This method pulls data and formats it in a PDF using the itext library
		 * It returns a byte[] that represents the formed PDF 
		 */
		private static byte[] formPDF() {
			//create document object which we will add the pdf table to
			Document doc = new Document();
			//byte stream to hold pdf 
			ByteArrayOutputStream o = new ByteArrayOutputStream();
			try {
				//additions to PDF will be written to byte stream
				PdfWriter.getInstance(doc, o);
			} catch (DocumentException e1) {
				e1.printStackTrace();
			}
			
			//create pdf table from data structures
			PdfPTable[] tables = formTable();
			
			//write table to pdf
			try {
				//open document to write data
				doc.open();
				//write table to pdf
				for (int i = 0; i < tables.length; i++) {
					if (i == 0) {
						Paragraph tableTitle = new Paragraph(15);
						tableTitle.setSpacingAfter(10);
						tableTitle.setSpacingBefore(50);
						tableTitle.setAlignment(Element.ALIGN_CENTER);
						Chunk title = new Chunk("Ohio Counts");
						tableTitle.add(title);
						doc.add(tableTitle);
					} else if (i >= 1) {
						Paragraph tableTitle = new Paragraph(15);
						tableTitle.setSpacingAfter(50);
						tableTitle.setSpacingBefore(50);
						tableTitle.setAlignment(Element.ALIGN_CENTER);
						Chunk title = new Chunk(counties.get(i - 1) + " County Counts");
						tableTitle.add(title);
						doc.add(tableTitle);
					}
					doc.add(tables[i]);
				} //end for loop
					
			} catch (DocumentException e) {
				e.printStackTrace();
			} finally {
				doc.close();
			}
			
			return o.toByteArray();
		}
		
		/*
		 * Method to form table from data structures
		 * Returns PdfPTable
		 */
		private static PdfPTable[] formTable() {
			//create table array, one for total Ohio count and one for each included county
			PdfPTable[] tables = new PdfPTable[1 + counties.size()];
			for (int i = 0; i < tables.length; i++) {
				tables[i] = new PdfPTable(columns);
			}
			
			//adds headers to all tables
			for (PdfPTable table: tables) {
				//this stream formats and adds header row of table
				Stream.of(columnTitles).forEach(columnTitle -> {
					//create new cell
					PdfPCell header = new PdfPCell();
					//sets formatting
					header.setBackgroundColor(BaseColor.LIGHT_GRAY);
					header.setBorderWidth(1);
					header.setHorizontalAlignment(Element.ALIGN_CENTER);
					header.setVerticalAlignment(Element.ALIGN_CENTER);
					//sets content
					header.setPhrase(new Phrase(columnTitle));
					//adds cell to table if it is not an empty string
					if (!columnTitle.isEmpty()) {
						table.addCell(header);
					}
					
			});
			}
			
			
			//for formatting date in cells
			DateFormat dateFormat = new SimpleDateFormat("ddMMMyyyy");
			//stores previous day count data
			int previousDayCount= 0;
			
			//following adds all data to cells
			//append previous version data 01Mar2020 to 05Apr2020
			for (SingleDayCount d: previousVersionData) {
				//format and add date cell
				PdfPCell dateCell = new PdfPCell();
				dateCell.setHorizontalAlignment(Element.ALIGN_CENTER);
				dateCell.setPhrase(new Phrase(dateFormat.format(d.getDate().getTime())));
				tables[0].addCell(dateCell);
				
				//format and add count cell
				int currentDayCount = d.getCaseCount();
				int newCases = currentDayCount - previousDayCount;
				PdfPCell countCell = new PdfPCell();
				countCell.setHorizontalAlignment(Element.ALIGN_CENTER);
				countCell.setPhrase(new Phrase(currentDayCount + " (" + newCases + ")"));
				tables[0].addCell(countCell);
				//set new previous days count for next iteration
				previousDayCount = currentDayCount;
				
				//add empty cells in hospitalized count and death count columns 
				int empty = columns - 2;
				for (int i = 0; i < empty; i++) {
					tables[0].addCell(new PdfPCell());
				}
			} //end for loop
			
			//to keep track of daily increase for hospitalizations and deaths
			int previousHospitalizedCount = 0;
			int previousDeathCount = 0;
			
			//print new version data 06Apr2020 and after
			for (Map.Entry<GregorianCalendar, TreeMap<String, County>> entry: dataByDay.entrySet()) {
				//format and add date cell
				PdfPCell dateCell = new PdfPCell();
				dateCell.setHorizontalAlignment(Element.ALIGN_CENTER);
				dateCell.setPhrase(new Phrase(dateFormat.format(entry.getKey().getTime())));
				tables[0].addCell(dateCell);
				
				//get total counts for a single reporting day
				int totalCount = 0;
				int totalHospitalized = 0;
				int totalDeaths = 0;
				for (Map.Entry<String, County> subentry: entry.getValue().entrySet()) {
					totalCount += subentry.getValue().getCount();
					totalHospitalized += subentry.getValue().getHospitalizedCount();
					totalDeaths += subentry.getValue().getDeathCount();
				}
				int newCases = totalCount - previousDayCount;
				int newHospitalized = totalHospitalized - previousHospitalizedCount;
				int newDeaths = totalDeaths - previousDeathCount;
				
				//add total count cell
				PdfPCell countCell = new PdfPCell();
				countCell.setHorizontalAlignment(Element.ALIGN_CENTER);
				countCell.setPhrase(new Phrase(totalCount + " (" + newCases + ")"));
				tables[0].addCell(countCell);
				
				//add hospitalizedCount and deathCount if necessary
				if (includeHospitalizedCount) {
					//add hospitalized count cell
					PdfPCell hospitalizedCell = new PdfPCell();
					hospitalizedCell.setHorizontalAlignment(Element.ALIGN_CENTER);
					hospitalizedCell.setPhrase(new Phrase(totalHospitalized + " (" + newHospitalized + ")"));
					tables[0].addCell(hospitalizedCell);
				}
				if (includeDeathCount) {
					//add death count cell
					PdfPCell deathCell = new PdfPCell();
					deathCell.setHorizontalAlignment(Element.ALIGN_CENTER);
					deathCell.setPhrase(new Phrase(totalDeaths + " (" + newDeaths + ")"));
					tables[0].addCell(deathCell);
				}
				
				previousDayCount = totalCount;
				previousHospitalizedCount = totalHospitalized;
				previousDeathCount = totalDeaths;
				
				
					
				//add county information if necessary
				if (!counties.isEmpty()) {
					
					//iterate through counties array to create county tables
					for (int i = 0; i < counties.size(); i++) {	
						int index = i + 1;
						
						//add date cell
						tables[index].addCell(dateCell);
						
						//get specific county counts
						int countyCount = entry.getValue().get(counties.get(i)).getCount();
						//add county count cell
						PdfPCell countyCountCell = new PdfPCell();
						countyCountCell.setHorizontalAlignment(Element.ALIGN_CENTER);
						countyCountCell.setPhrase(new Phrase(countyCount + ""));
						tables[index].addCell(countyCountCell);
						
						if (includeHospitalizedCount) {
							int countyHospitalized = entry.getValue().get(counties.get(i)).getHospitalizedCount();
							//add county hospitalized count cell
							PdfPCell countyHospitalizedCell = new PdfPCell();
							countyHospitalizedCell.setHorizontalAlignment(Element.ALIGN_CENTER);
							countyHospitalizedCell.setPhrase(new Phrase(countyHospitalized + ""));
							tables[index].addCell(countyHospitalizedCell);
						}
						
						if (includeDeathCount) {
							int countyDeaths = entry.getValue().get(counties.get(i)).getDeathCount();
							//add county death count cell
							PdfPCell countyDeathCell = new PdfPCell();
							countyDeathCell.setHorizontalAlignment(Element.ALIGN_CENTER);
							countyDeathCell.setPhrase(new Phrase(countyDeaths + ""));
							tables[index].addCell(countyDeathCell);
						}
					}
				}
			} //end for loop
			
			return tables;
		}
		
		@Deprecated
		/*
		 * This method returns daily case data as an integer from coronavirus.ohio.gov.
		 * 
		 * Parses HTML file for specific tags the precede data
		 * 
		 * Deprecated.
		 */
		private static int getCases() {
			URL url = null;
			try {
				url = new URL("https://coronavirus.ohio.gov/wps/portal/gov/covid-19/");
			} catch (MalformedURLException e) {
				e.printStackTrace();
			}
			
			//Get the input stream through URL Connection
	        URLConnection connection;
	        InputStream inputURL = null;
			try {
				connection = url.openConnection();
				inputURL = connection.getInputStream();
			} catch (IOException e) {
				e.printStackTrace();
			}
	        
			BufferedReader br = new BufferedReader(new InputStreamReader(inputURL));
			
			//store number and relative title data as displayed in the HTML file 
			Queue<String> numbers = new LinkedList<>();
			Queue<String> titles = new LinkedList<>();
			
				
			try {
				String line;
				while ((line = br.readLine()) != null) {
					//"<div class=\"odh-ads__item-title\">" line comes before data in the HTML document
					if (line.contains("<div class=\"odh-ads__item-title\">")) { 
						numbers.offer(br.readLine().trim());
						continue;
					}
					//"<div class=\"odh-ads__item-summary\">" line comes before title relative to stored data in the HTML document
					if (line.contains("<div class=\"odh-ads__item-summary\">")) {
						titles.offer(br.readLine().trim());
						continue;
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			int cases = -1;
			int size = numbers.size();
			for (int i = 0; i < size; i++) {
				String title = titles.remove();
				String number = numbers.remove();
				number = number.replace(",", "");
				//title and number lists will contain other data but only confirmed case number is returned in this implementation
				if (title.contains("Confirmed Cases")) { 
					cases = Integer.parseInt(number);
					return cases;
				}
			}
			return cases;
		}
		
		@Deprecated
		/*
		 * This method writes data to "tracker_data.txt" file according to format "ddMMMyyy~#"
		 * Writes day with case data to text file.
		 * 
		 * Deprecated.
		 */
		private static void exit(TreeMap<Date, Integer> map) {
			
			try {
				DateFormat dateFormat = new SimpleDateFormat("ddMMMyyyy HH:mm:ss");
				BufferedWriter output = new BufferedWriter(new FileWriter("tracker_data.txt", false));
				StringBuilder dataOut = new StringBuilder();
				for (Map.Entry<Date, Integer> entry: map.entrySet()) {
					dataOut.append(dateFormat.format(entry.getKey()) + "~" + entry.getValue() + "\n");
				}
				output.write(dataOut.toString());
				System.out.println("Successfully wrote data to \"tracker_data.txt\"");
				output.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
}
