package net.osmand.osm.util;

import info.bliki.wiki.filter.Encoder;
import info.bliki.wiki.filter.HTMLConverter;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import net.osmand.PlatformUtil;
import net.osmand.data.preparation.DBDialect;
import net.osmand.impl.ConsoleProgressImplementation;
import net.osmand.osm.util.GPXUtils.GPXFile;
import net.osmand.osm.util.GPXUtils.WptPt;
import net.osmand.osm.util.WikiDatabasePreparation.LatLon;

import org.apache.commons.logging.Log;
import org.apache.tools.bzip2.CBZip2InputStream;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

public class WikiVoyagePreparation {
	private static final Log log = PlatformUtil.getLog(WikiDatabasePreparation.class);	
	private static boolean uncompressed;
	
	private static String language;
	
	public enum WikivoyageTemplates {
		LOCATION("geo"),
		POI("poi"),
		PART_OF("part_of"),
		BANNER("pagebanner"),
		REGION_LIST("regionlist"), 
		WARNING("warningbox");
		
		private String type;
		WikivoyageTemplates(String s) {
			type = s;
		}
		
		public String getType() {
			return type;
		}
	}
	
	public static void main(String[] args) throws IOException, ParserConfigurationException, SAXException, SQLException {
		String lang = "";
		String folder = "";
		if(args.length == 0) {
			lang = "en";
			language = lang;
			folder = "/home/user/osmand/wikivoyage/";
			uncompressed = true;
		}
		if(args.length > 0) {
			lang = args[0];
			language = lang;
		}
		if(args.length > 1){
			folder = args[1];
		}
		if(args.length > 2){
			uncompressed = args[2].equals("uncompressed");
		}
		final String wikiPg = folder + lang + "wikivoyage-latest-pages-articles.xml.bz2";
		
		if (!new File(wikiPg).exists()) {
			System.out.println("Dump for " + lang + " doesn't exist");
			return;
		}
		final String sqliteFileName = folder + (uncompressed ? "full_" : "") + "wikivoyage.sqlite";	
		processWikivoyage(wikiPg, lang, sqliteFileName);
		System.out.println("Successfully generated.");
    }
	
	public static String getLanguage() {
		return language;
	}
	
	protected static void processWikivoyage(final String wikiPg, String lang, String sqliteFileName)
			throws ParserConfigurationException, SAXException, FileNotFoundException, IOException, SQLException {
		SAXParser sx = SAXParserFactory.newInstance().newSAXParser();
		InputStream streamFile = new BufferedInputStream(new FileInputStream(wikiPg), 8192 * 4);
		InputStream stream = streamFile;
		if (stream.read() != 'B' || stream.read() != 'Z') {
			stream.close();
			throw new RuntimeException(
					"The source stream must start with the characters BZ if it is to be read as a BZip2 stream."); //$NON-NLS-1$
		} 
		CBZip2InputStream zis = new CBZip2InputStream(stream);
		Reader reader = new InputStreamReader(zis,"UTF-8");
		InputSource is = new InputSource(reader);
		is.setEncoding("UTF-8");
		final WikiOsmHandler handler = new WikiOsmHandler(sx, streamFile, lang, new File(sqliteFileName));
		sx.parse(is, handler);
		handler.finish();
	}
	
	public static class WikiOsmHandler extends DefaultHandler {
		long id = 1;
		private final SAXParser saxParser;
		private boolean page = false;
		private boolean revision = false;
		
		private StringBuilder ctext = null;
		private long cid;
		private StringBuilder title = new StringBuilder();
		private StringBuilder text = new StringBuilder();
		private StringBuilder pageId = new StringBuilder();
		
		private boolean parseText = false;

		private final InputStream progIS;
		private ConsoleProgressImplementation progress = new ConsoleProgressImplementation();
		private DBDialect dialect = DBDialect.SQLITE;
		private Connection conn;
		private PreparedStatement prep;
		private int batch = 0;
		private final static int BATCH_SIZE = 500;
		final ByteArrayOutputStream bous = new ByteArrayOutputStream(64000);
		private String lang;
			
		WikiOsmHandler(SAXParser saxParser, InputStream progIS, String lang, File sqliteFile)
				throws IOException, SQLException {
			this.lang = lang;
			this.saxParser = saxParser;
			this.progIS = progIS;		
			progress.startTask("Parse wiki xml", progIS.available());

			conn = (Connection) dialect.getDatabaseConnection(sqliteFile.getAbsolutePath(), log);
			conn.createStatement()
					.execute("CREATE TABLE IF NOT EXISTS travel_articles(article_id text, title text, content_gz blob"
							+ (uncompressed ? ", content text" : "") + ", is_part_of text, lat double, lon double, image_title text, gpx_gz blob"
							+ (uncompressed ? ", gpx text" : "") + ", trip_id long, original_id long, lang text, contents_json text)");
			conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_title ON travel_articles(title);");
			conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_id ON travel_articles(trip_id);");
			conn.createStatement().execute("CREATE INDEX IF NOT EXISTS index_orig_id ON travel_articles(original_id);");
			conn.createStatement()
					.execute("CREATE INDEX IF NOT EXISTS index_part_of ON travel_articles(is_part_of);");
			prep = conn.prepareStatement("INSERT INTO travel_articles VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?" + (uncompressed ? ", ?, ?": "") + ")");
		}
		
		public void addBatch() throws SQLException {
			prep.addBatch();
			if(batch++ > BATCH_SIZE) {
				prep.executeBatch();
				batch = 0;
			}
		}
		
		public void finish() throws SQLException {
			prep.executeBatch();
			if(!conn.getAutoCommit()) {
				conn.commit();
			}
			prep.close();
			conn.close();
		}

		public int getCount() {
			return (int) (id - 1);
		}

		@Override
		public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			if (!page) {
				page = name.equals("page");
			} else {
				if (name.equals("title")) {
					title.setLength(0);
					ctext = title;
				} else if (name.equals("text")) {
					if(parseText) {
						text.setLength(0);
						ctext = text;
					}
				} else if (name.equals("revision")) {
					revision  = true;
				} else if (name.equals("id") && !revision) {
					pageId.setLength(0);
					ctext = pageId;
				}
			}
		}

		@Override
		public void characters(char[] ch, int start, int length) throws SAXException {
			if (page) {
				if (ctext != null) {
					ctext.append(ch, start, length);
				}
			}
		}

		@Override
		public void endElement(String uri, String localName, String qName) throws SAXException {
			String name = saxParser.isNamespaceAware() ? localName : qName;
			
			try {
				if (page) {
					if (name.equals("page")) {
						page = false;
						parseText = false;
						progress.remaining(progIS.available());
					} else if (name.equals("title")) {
						ctext = null;
					} else if (name.equals("revision")) {
						revision = false;
					} else if (name.equals("id") && !revision) {
						ctext = null;
						cid = Long.parseLong(pageId.toString());
						parseText = true;
					} else if (name.equals("text")) {
						if (parseText) {
							Map<String, List<String>> macroBlocks = new HashMap<>();
							String text = WikiDatabasePreparation.removeMacroBlocks(ctext.toString(), macroBlocks);
							try {
								if (!macroBlocks.isEmpty()) {
									LatLon ll = getLatLonFromGeoBlock(
											macroBlocks.get(WikivoyageTemplates.LOCATION.getType()));
									boolean accepted = !title.toString().contains(":");
									if(accepted) {
										int column = 1;
										String filename = getFileName(macroBlocks.get(WikivoyageTemplates.BANNER.getType()));
										filename = filename.startsWith("<!--") ? "" : filename;
										if (id++ % 500 == 0) {
											log.debug("Article accepted " + cid + " " + title.toString() + " " + ll.getLatitude()
													+ " " + ll.getLongitude() + " free: "
													+ (Runtime.getRuntime().freeMemory() / (1024 * 1024)));
										}
										final HTMLConverter converter = new HTMLConverter(false);
										CustomWikiModel wikiModel = new CustomWikiModel("https://upload.wikimedia.org/wikipedia/commons/${image}", 
												"https://"+lang+".wikivoyage.org/wiki/${title}", false);
										String plainStr = wikiModel.render(converter, text);
										plainStr = plainStr.replaceAll("<p>div class=&#34;content&#34;", "<div class=\"content\">\n<p>").replaceAll("<p>/div\n</p>", "</div>");
										prep.setString(column++, Encoder.encodeUrl(title.toString()));
										prep.setString(column++, title.toString());
										prep.setBytes(column++, stringToCompressedByteArray(bous, plainStr));
										if (uncompressed) {
											prep.setString(column++, plainStr);
										}
										// part_of
										prep.setString(column++, parsePartOf(macroBlocks.get(WikivoyageTemplates.PART_OF.getType())));
										if(ll.isZero()) {
											prep.setNull(column++, Types.DOUBLE);
											prep.setNull(column++, Types.DOUBLE); 
										} else {
											prep.setDouble(column++, ll.getLatitude());
											prep.setDouble(column++, ll.getLongitude());
										}
										// banner
										prep.setString(column++, Encoder.encodeUrl(filename).replaceAll("\\(", "%28")
												.replaceAll("\\)", "%29"));
										// gpx_gz
										String gpx = generateGpx(macroBlocks.get(WikivoyageTemplates.POI.getType()));
										prep.setBytes(column++, stringToCompressedByteArray(bous, gpx));
										if (uncompressed) {
											prep.setString(column++, gpx);
										}
										// skip trip_id column
										column++;
										prep.setLong(column++, cid);
										prep.setString(column++, lang);
										prep.setString(column++, wikiModel.getContentsJson());
										addBatch();
										
									}
								}
							} catch (SQLException e) {
								throw new SAXException(e);
							}
						}
						ctext = null;
					}
				}
			} catch (IOException e) {
				throw new SAXException(e);
			}
		}
		
		public static boolean isEmpty(String s) {
			return s == null || s.length() == 0;
		}
		public static String capitalizeFirstLetterAndLowercase(String s) {
			if (s != null && s.length() > 1) {
				// not very efficient algorithm
				return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
			} else {
				return s;
			}
		}
		
		private String generateGpx(List<String> list) {
			if (list != null && !list.isEmpty()) {
				GPXFile f = new GPXFile();
				List<WptPt> points = new ArrayList<>(); 
				for (String s : list) {
					String[] info = s.split("\\|");
					WptPt point = new WptPt();
					String category = info[0].replaceAll("\n", "").trim();
					point.category = (category.equalsIgnoreCase("vcard") 
							|| category.equalsIgnoreCase("listing")) ? transformCategory(info) : category;
					if(!isEmpty(point.category)) {
						point.category = capitalizeFirstLetterAndLowercase(point.category.trim());
					}
					String areaCode = "";
					for (int i = 1; i < info.length; i++) {
						String field = info[i].trim();
						String value = "";
						int index = field.indexOf("=");
						if (index != -1) {
							value = field.substring(index + 1, field.length()).trim();
							field = field.substring(0, index).trim();
						}
						if (!value.isEmpty() && !value.contains("{{")) {
							try {
								if (field.equalsIgnoreCase("name") || field.equalsIgnoreCase("nome") || field.equalsIgnoreCase("nom")
										|| field.equalsIgnoreCase("שם") || field.equalsIgnoreCase("نام")) {
									point.name = value.replaceAll("[\\]\\[]", "");
								} else if (field.equalsIgnoreCase("url") || field.equalsIgnoreCase("sito") || field.equalsIgnoreCase("האתר הרשמי")
										|| field.equalsIgnoreCase("نشانی اینترنتی")) {
									point.link = value;
								} else if (field.equalsIgnoreCase("intl-area-code")) {
									areaCode = value;
								} else if (field.equalsIgnoreCase("lat") || field.equalsIgnoreCase("latitude") || field.equalsIgnoreCase("عرض جغرافیایی")) {
									point.lat = Double.valueOf(value);
								} else if (field.equalsIgnoreCase("long") || field.equalsIgnoreCase("longitude") || field.equalsIgnoreCase("طول جغرافیایی")) {
									point.lon = Double.valueOf(value);
								} else if (field.equalsIgnoreCase("content") || field.equalsIgnoreCase("descrizione") 
										|| field.equalsIgnoreCase("description") || field.equalsIgnoreCase("sobre") || field.equalsIgnoreCase("תיאור")
										|| field.equalsIgnoreCase("متن")) {
									point.desc = point.desc = point.desc == null ? value : 
										point.desc + ". " + value;
								} else if (field.equalsIgnoreCase("email") || field.equalsIgnoreCase("מייל") || field.equalsIgnoreCase("پست الکترونیکی")) {
									point.desc = point.desc == null ? "Email: " + value : 
										point.desc + ". Email: " + value;
								} else if (field.equalsIgnoreCase("phone") || field.equalsIgnoreCase("tel") || field.equalsIgnoreCase("téléphone")
										|| field.equalsIgnoreCase("טלפון") || field.equalsIgnoreCase("تلفن")) {
									point.desc = point.desc == null ? "Phone: " + areaCode + value : 
										point.desc + ". Phone: " + areaCode + value;
								} else if (field.equalsIgnoreCase("price") || field.equalsIgnoreCase("prezzo") || field.equalsIgnoreCase("prix") 
										|| field.equalsIgnoreCase("מחיר") || field.equalsIgnoreCase("بها")) {
									point.desc = point.desc == null ? "Price: " + value : 
										point.desc + ". Price: " + value;
								} else if (field.equalsIgnoreCase("hours") || field.equalsIgnoreCase("orari") || field.equalsIgnoreCase("horaire") 
										|| field.equalsIgnoreCase("funcionamento") || field.equalsIgnoreCase("שעות") || field.equalsIgnoreCase("ساعت‌ها")) {
									point.desc = point.desc == null ? "Working hours: " + value : 
										point.desc + ". Working hours: " + value;
								} else if (field.equalsIgnoreCase("directions") || field.equalsIgnoreCase("direction") || field.equalsIgnoreCase("הוראות")
										|| field.equalsIgnoreCase("مسیرها")) {
									point.desc = point.desc == null ? "Directions: " + value : 
										point.desc + " Directions: " + value;
								}
							} catch (Exception e) {}
						}
					}
					if (point.hasLocation() && point.name != null && !point.name.isEmpty()) {
						point.setColor();
						points.add(point);
					}
				}
				if (!points.isEmpty()) {
					f.addPoints(points);
					return GPXUtils.asString(f);
				}
			}
			return "";
		}
		
		private String transformCategory(String[] info) {
			String type = "";
			for (int i = 1; i < info.length; i++) {
				if (info[i].trim().startsWith("type")) {
					type = info[i].substring(info[i].indexOf("=") + 1, info[i].length()).trim();
				}
			}
			return type;
		}
		
		private byte[] stringToCompressedByteArray(ByteArrayOutputStream baos, String toCompress) {
			baos.reset();
			try {
				GZIPOutputStream gzout = new GZIPOutputStream(baos);
				gzout.write(toCompress.getBytes("UTF-8"));
				gzout.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
			return baos.toByteArray();
		}

		private String getFileName(List<String> list) {
			if (list != null && !list.isEmpty()) {
				String bannerInfo = list.get(0);
				String[] infoSplit = bannerInfo.split("\\|");
				for (String s : infoSplit) {
					String toCompare = s.toLowerCase();
					if (toCompare.contains(".jpg") || toCompare.contains(".jpeg") 
							|| toCompare.contains(".png") || toCompare.contains(".gif")) {
						s = s.replace("https:", "");
						int equalInd = s.indexOf("=");
						int columnInd = s.indexOf(":");
						int index = (equalInd == -1 && columnInd == -1) ? -1 : (columnInd > equalInd ? columnInd : equalInd);
						if (index != -1) {
							return s.substring(index + 1, s.length()).trim();
						}
						return s.trim();
					}
				}
			}
			return "";
		}
		
		private LatLon getLatLonFromGeoBlock(List<String> list) {
			double lat = 0d;
			double lon = 0d;
			if (list != null && !list.isEmpty()) {
				String location = list.get(0);
				String[] parts = location.split("\\|");
				// skip malformed location blocks
				String regex_pl = "(\\d+)°.+?(\\d+).+?(\\d*).*?";
				if (location.toLowerCase().contains("geo|")) {
					if (parts.length >= 3) {
						if (parts[1].matches(regex_pl) && parts[2].matches(regex_pl)) {
							lat = toDecimalDegrees(parts[1], regex_pl);
							lon = toDecimalDegrees(parts[2], regex_pl);
						} else {
							try {
								lat = Double.valueOf(parts[1]);
								lon = Double.valueOf(parts[2]);
							} catch (Exception e) {	}
						}
					}
				} else {
					String latStr = "";
					String lonStr = "";
					String regex = "(\\d+).+?(\\d+).+?(\\d*).*?([N|E|W|S|n|e|w|s]+)";
					for (String part : parts) {
						part = part.replaceAll(" ", "").toLowerCase();
						if (part.startsWith("lat=") || part.startsWith("latitude=")) {
							latStr = part.substring(part.indexOf("=") + 1, part.length()).replaceAll("\n", "");
						} else if (part.startsWith("lon=") || part.startsWith("long=") || part.startsWith("longitude=")) {
							lonStr = part.substring(part.indexOf("=") + 1, part.length()).replaceAll("\n", "");
						}
					}
					if (latStr.matches(regex) && lonStr.matches(regex)) {
						lat = toDecimalDegrees(latStr, regex);
						lon = toDecimalDegrees(lonStr, regex);
					} else {
						try {
							lat = Double.valueOf(latStr.replaceAll("°", ""));
							lon = Double.valueOf(lonStr.replaceAll("°", ""));
						} catch (Exception e) {}
					}
				}
			}
			return new LatLon(lat, lon);
		}

		private double toDecimalDegrees(String str, String regex) {
			Pattern p = Pattern.compile(regex);
			Matcher m = p.matcher(str);
			m.find();
			double res = 0;
			double signe = 1.0;
			double degrees = 0;
			double minutes = 0;
			double seconds = 0;
			String hemisphereOUmeridien = "";
			try {
				degrees = Double.parseDouble(m.group(1));
		        minutes = Double.parseDouble(m.group(2));
		        seconds = m.group(3).isEmpty() ? 0 :Double.parseDouble(m.group(3));
		        hemisphereOUmeridien = m.group(4);
			} catch (Exception e) {
				// Skip malformed strings
			}
			if ((hemisphereOUmeridien.equalsIgnoreCase("W")) || (hemisphereOUmeridien.equalsIgnoreCase("S"))) {
				signe = -1.0;
			}
			res = signe * (Math.floor(degrees) + Math.floor(minutes) / 60.0 + seconds / 3600.0);
			return res;
		}

		private String parsePartOf(List<String> list) {
			if (list != null && !list.isEmpty()) {
				String partOf = list.get(0);
				String lowerCasePartOf = partOf.toLowerCase();
				if (lowerCasePartOf.contains("quickfooter")) {
					return parsePartOfFromQuickBar(partOf);
				} else if (lowerCasePartOf.startsWith("footer|")) {
					String part = "";
					try {
						int index = partOf.indexOf('|', partOf.indexOf('|') + 1);
						part = partOf.substring(partOf.indexOf("=") + 1, 
								index == -1 ? partOf.length() : index);
					} catch (Exception e) {
						System.out.println("Error parsing the partof: " + partOf);
					}
					return part.trim().replaceAll("_", " ");
				} else if (lowerCasePartOf.contains("קטגוריה")) {
					return partOf.substring(partOf.indexOf(":") + 1).trim().replaceAll("[_\\|\\*]", "");
				} else {
					return partOf.substring(partOf.indexOf("|") + 1).trim().replaceAll("_", " ");
				}
			}
			return "";
		}

		private String parsePartOfFromQuickBar(String partOf) {
			String[] info = partOf.split("\\|");
			String region = "";
			for (String s : info) {
				if (s.indexOf("=") != -1) {
					if (!s.toLowerCase().contains("livello")) {
						region = s.substring(s.indexOf("=") + 1, s.length()).trim();
					}
				}
			}
			return region;
		}
	}
}