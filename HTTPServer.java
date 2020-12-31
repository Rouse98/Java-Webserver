
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.concurrent.*;
import java.util.*;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.nio.*;

public class HTTPServer {

  public static void main(String args[]) throws Exception {
    // Thread that limits the number of threads to 5 with a total number of 50
    // threads
    ExecutorService threadPool = new ThreadPoolExecutor(5, 49, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1));
    // Create Server Socket on port
    int port = Integer.parseInt(args[0]);
    ServerSocket s = new ServerSocket(port);
    System.out.println("waiting...");

    while (true) {

      Socket connectionSocket = s.accept();

      BufferedReader fromClient = new BufferedReader(new InputStreamReader(connectionSocket.getInputStream())); // Input
                                                                                                                // Stream
      DataOutputStream toClient = new DataOutputStream(connectionSocket.getOutputStream()); // Output Stream
      threading thread = new threading(connectionSocket, fromClient, toClient);

      try {
        threadPool.execute(thread);
      } catch (RejectedExecutionException e) {
        toClient.writeBytes("HTTP/1.0 503 Service Unavailable\r\n\r\n"); // There are over 50 threads so 503 is
                                                                         // outputted
        connectionSocket.close();
      }

    }
  }
}

class threading extends Thread {
  private Socket s;
  BufferedReader fromClient;
  DataOutputStream toClient;
  private String output;
  String html_response;
  boolean flag = false;

  public threading(Socket s, BufferedReader fromClient, DataOutputStream toClient) {
    this.s = s;
    this.fromClient = fromClient;
    this.toClient = toClient;
  }

  public boolean NotAllowedChar(char c) {

    boolean valid = false;
    if (c == ' ' || c == ']' || c == '[' || c == '#' || c == '?' || c == '/' || c == ',' || c == '+' || c == '$'
        || c == '@' || c == ':' || c == ';' || c == ')' || c == '(' || c == '\\' || c == '!' || c == '*' || c == '\'') {
      valid = true;
    }
    return valid;
  }

  public boolean valid_post(String res, HashMap<String, String> hmap) throws IOException, UnknownHostException {
    boolean valid = false; // Checks if the hmap for post are valid

    // boolean for a valid builder length and type

    boolean length = false;
    boolean type = false;
    String read = fromClient.readLine();
    while (read != null && !(read.equals(""))) {
      String[] hdr = read.split(": ");
      if (hdr.length == 2) {
        if (hdr[0].equals("Content-Type")) {
          if (hdr[1] != null && hdr[1].equals("application/x-www-form-urlencoded")) {
            type = true;
          } else if (hdr[1] != null && hdr[1].equals("text/html")) {
            hmap.put(hdr[0], hdr[1]);

          } else {

            output = "HTTP/1.0 500 Internal Server Error\r\n\r\n";// invalid builder type
            valid = false;
            break;
          }
        } else if (hdr[0].equals("If-Modified-Since")) {
          hmap.put("If-Modified-Since", hdr[1]);
        } else if (hdr[0].equals("User-Agent")) {
          hmap.put("HTTP_USER_AGENT", hdr[1]);
        } else if (hdr[0].equals("Cookie")) {
          hmap.put("Cookie", hdr[1]);

        } else if (hdr[0].equals("From")) {
          hmap.put("HTTP_FROM", hdr[1]);
        } else if (hdr[0].equals("Content-Length")) {
          if (hdr[1] != null) {
            try {
              int d = Integer.parseInt(hdr[1]);
            } catch (NumberFormatException nfe) {
              output = "HTTP/1.0 411 Length Required\r\n\r\n"; // invalid length
              valid = false;
              break;
            }
            length = true;
            hmap.put("CONTENT_LENGTH", hdr[1]);
          } else {
            output = "HTTP/1.0 411 Length Required\r\n\r\n"; // invalid length
            valid = false;
            break;
          }
        }

      }
      read = fromClient.readLine();
    }
    // putting env variables into map
    hmap.put("SCRIPT_NAME", res);
    hmap.put("SERVER_NAME", InetAddress.getLocalHost().getHostAddress().trim());
    hmap.put("SERVER_PORT", String.valueOf(s.getPort()));
    // valid builder type and length
    if (type == true && length == true)
      valid = true;
    // invalid builder type or length

    else if (length == false) {
      output = "HTTP/1.0 411 Length Required\r\n\r\n";
      valid = false;
    } else {
      output = "HTTP/1.0 500 Internal Server Error\r\n\r\n";
    }
    return valid;

  }

  public void post_main(String command, String res, String version, HashMap<String, String> hmap) throws IOException {

    if (!valid_post(res, hmap) && command.equals("POST")) {
      return;
    }

    // check for cgi
    boolean cgi = false;
    String ext = "";
    int res_i = res.lastIndexOf('.');
    if (res_i >= 0) {
      ext = res.substring(res_i + 1);
      if (ext.equals("cgi")) {
        cgi = true;
      }
    }
    // 405 if no cgi
    if (cgi == false) {
      output = "HTTP/1.0 405 Method Not Allowed\r\n\r\n";
      return;
    }

    // check if the file is executable
    boolean exec = false;
    File file_temp = new File("." + res);
    if (file_temp.canExecute()) {
      exec = true;
    }
    // 403 if not executable
    if (exec == false) {
      output = "HTTP/1.0 403 Forbidden\r\n\r\n";
      return;
    }

    hmap.remove("If-Modified-Since");

    StringBuilder payload = new StringBuilder();
    char[] c = new char[4096];
    while (fromClient.ready()) {
      int tmp = fromClient.read(c, 0, c.length);
      if (tmp < 0) {
        break;
      }
      payload.append(c, 0, tmp);
    }

    // decoding (p)arameters
    String pm = payload.toString();
    String result_s = "";
    int i;
    while ((i = pm.indexOf('!')) != -1 && NotAllowedChar(pm.charAt(i + 1)) && i < (pm.length() - 1)) {
      result_s += pm.substring(0, i) + pm.charAt(i + 1);
      pm = pm.substring(i + 2);
    }
    String p = result_s + pm;

    hmap.put("CONTENT_LENGTH", String.valueOf(p.length()));

    ProcessBuilder process = new ProcessBuilder("." + res);
    Map<String, String> env = process.environment();
    for (Map.Entry<String, String> emap : hmap.entrySet()) {
      if (!emap.getKey().equals("If-Modified-Since")) {
        env.put(emap.getKey(), emap.getValue());
      }
    }
    Process prcss = process.start();
    BufferedWriter b = null;
    try {
      b = new BufferedWriter(new OutputStreamWriter(prcss.getOutputStream()));
      b.write(p);
      b.flush();
      b.close();
    } catch (IOException IO) {
      IO.printStackTrace();
    }
    byte[] resByte = null;
    try {

      DataInputStream reader = new DataInputStream(prcss.getInputStream());
      ByteArrayOutputStream bstream = new ByteArrayOutputStream();
      byte[] tempByte = new byte[4096];
      while (true) {
        int spot = reader.read(tempByte);
        if (spot < 0) {

          break;
        }
        bstream.write(tempByte, 0, spot);
      }
      resByte = bstream.toByteArray();

    } catch (Exception e) {
      e.printStackTrace();
    }

    if (resByte.length == 0) {
      resByte = null;
    }

    byte[] result = resByte;
    if (result == null) {
      output = "HTTP/1.0 204 No Content\r\n\r\n";
      return;// 204 empty script output
    }

    String out = "HTTP/1.0 200 OK\r\n";

    out += "Allow: GET, POST, HEAD\r\n";
    out += "Expires: Sat, 21 Jul 2021 11:00:00 GMT\r\n";
    out += "Content-Length: " + result.length + "\r\n";
    out += "Content-Type: text/html\r\n\r\n";
    // writing output of cgi
    toClient.writeBytes(out);
    toClient.write(result, 0, result.length);
    return;

  }

  public void run() {
    String words = "";
    String[] input;
    output = " ";
    SimpleDateFormat date = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss");
    date.setTimeZone(TimeZone.getTimeZone("GMT"));
    LocalDateTime now = LocalDateTime.now();
    String cmd = "";
    String res = "";
    String ver = "";
    boolean valid = false;
    String content_type = "application/octet-stream";
    String outDate = "";

    try {

      // Setting up socket timeout to be 5000 milliseconds
      s.setSoTimeout(5000);
      try {
        words = fromClient.readLine();
      } catch (SocketTimeoutException ste) {
        toClient.writeBytes("HTTP/1.0 408 Request Timeout\r\n\r\n");
        s.close();
        return;
      }
      input = words.split(" ");
      if (input.length != 3) {

        output = "HTTP/1.0 400 Bad Request\r\n\r\n";
        valid = false;
      } else {
        cmd = input[0]; // Command part of the request
        res = input[1]; // The file part of the request
        ver = input[2]; // The HTTP version part of the rquest

        // Checks if the version of HTTP is valid. Anything over 1.0 is unvalid
        if (ver.compareTo("HTTP/1.0") != 0 && !ver.equals("HTTP/0.9")) {
          if (ver.length() >= 6) {
            String version = ver.substring(5, 8); // Extract version number
            Double versionNumber = Double.parseDouble(version); // Convert to double
            if (ver.substring(0, 5).compareTo("HTTP/") == 0 && versionNumber > 1.0) {
              output = "HTTP/1.0 505 HTTP Version Not Supported\r\n\r\n";
            }
            valid = false;
          } else {
            output = "HTTP/1.0 400 Bad Request\r\n\r\n";
            valid = false;
          }
        } else if (cmd.compareTo("GET") == 0) {
          valid = true;
        } else if (cmd.compareTo("HEAD") == 0) {
          valid = true;
        } else if (cmd.compareTo("POST") == 0) {
          valid = true;
        } else if (cmd.compareTo("GET") != 0 && cmd.compareTo("POST") != 0 && cmd.compareTo("HEAD") != 0) {
          // These are valid but not supported
          if (cmd.compareTo("LINK") == 0 || cmd.compareTo("PUT") == 0 || cmd.compareTo("DELETE") == 0
              || cmd.compareTo("UNLINK") == 0) {
            output = "HTTP/1.0 501 Not Implemented\r\n\r\n";
            valid = false;
            // Anything else are not allowed
          } else {
            output = "HTTP/1.0 400 Bad Request\r\n\r\n";
            valid = false;
          }

        }
      }

      // HTTP version is allowed
      if (valid) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        sdf.setTimeZone(TimeZone.getTimeZone("GMT"));

        boolean boo = true;
        String read = fromClient.readLine();
        String[] header = read.split(": ");

        HashMap<String, String> headers_map = new HashMap<String, String>();

        if (!valid_post(res, headers_map) && cmd.equals("POST")) {
          toClient.writeBytes(output);
        }
        if (cmd.equals("POST")) {

          post_main(input[0], input[1], input[2], headers_map);

        } else if (header.length == 2 && header[0].equals("If-Modified-Since") && !cmd.equals("HEAD")) {
          // Not been modified
          if (!ifMod(header[1], res)) {
            boo = false;

          }

          if (boo) {
            URL getRes = getClass().getResource(res);
            String decoded_path = URLDecoder.decode(getRes.getPath(), "UTF-8");
            decoded_path = cookies(res, headers_map, decoded_path);
            if (decoded_path.length() == 0) {
              toClient.writeBytes(output);
            }
            File file = new File(decoded_path);
            if (!file.canRead()) {
              output = ("HTTP/1.0 403 Forbidden\r\n\r\n");
            } else {

              // Gets the header

              String validHeader = "HTTP/1.0 200 OK\r\n";

              try {
                if (headers_map.containsKey("Set-Cookie")) {
                  validHeader = validHeader + "Set-Cookie: lasttime=" + headers_map.get("Set-Cookie") + "\r\n";
                }

                Date d = new Date(file.lastModified());

                if (Files.probeContentType(file.toPath()) != null) {
                  content_type = Files.probeContentType(file.toPath());

                }
                validHeader = validHeader + "Content-Type: " + content_type + "\r\n";
                validHeader = validHeader + "Content-Length: " + file.length() + "\r\n";
                outDate = sdf.format(d);
                validHeader = validHeader + "Last-Modified: " + outDate + "\r\n" + "Content-Encoding: identity" + "\r\n"
                    + "Allow: GET, POST, HEAD" + "\r\n" + "Expires: Sat, 21 Jul 2021 11:00:00 GMT" + "\r\n\r\n";

              } catch (IOException e) {

                validHeader = "HTTP/1.0 500 Internal Server Error\r\n\r\n"; // Can't retrieve info from files

              }

              String outHeader = validHeader;
              if (outHeader.equals("HTTP/1.0 500 Internal Server Error\r\n\r\n"))
                output = outHeader; // Error occurred while making hmap
              FileInputStream stream = new FileInputStream(file);
              toClient.writeBytes(outHeader);
              int write;
              while ((write = stream.read()) != -1) {
                toClient.write(write);
              }

            }
          }
        } else {
          URL getRes = getClass().getResource(res);
          if (getRes == null) {
            output = "HTTP/1.0 404 Not Found\r\n\r\n";
          } else {
            String decoded_path = URLDecoder.decode(getRes.getPath(), "UTF-8");
            decoded_path = cookies(res, headers_map, decoded_path);
            if (decoded_path.length() == 0) {
              toClient.writeBytes(output);
            }
            File file = new File(decoded_path);
            if (!file.canRead()) {
              output = "HTTP/1.0 403 Forbidden\r\n\r\n";
            } else {

              String validHeader = "HTTP/1.0 200 OK\r\n";

              try {

                if (headers_map.containsKey("Set-Cookie")) {
                  validHeader = validHeader + "Set-Cookie: lasttime=" + headers_map.get("Set-Cookie") + "\r\n";
                }

                Date d = new Date(file.lastModified());

                if (Files.probeContentType(file.toPath()) != null) {
                  content_type = Files.probeContentType(file.toPath());

                }
                validHeader = validHeader + "Content-Type: " + content_type + "\r\n";
                validHeader = validHeader + "Content-Length: " + file.length() + "\r\n";
                outDate = sdf.format(d);
                validHeader = validHeader + "Last-Modified: " + outDate + "\r\n" + "Content-Encoding: identity" + "\r\n"
                    + "Allow: GET, POST, HEAD" + "\r\n" + "Expires: Sat, 21 Jul 2021 11:00:00 GMT" + "\r\n\r\n";

              } catch (IOException e) {
                validHeader = "HTTP/1.0 500 Internal Server Error\r\n\r\n"; // Can't retrieve info from files
                e.printStackTrace();
              }

              String outHeader = validHeader;
              if (cmd.equals("HEAD") == false) {
                FileInputStream stream = new FileInputStream(file);
                toClient.writeBytes(outHeader);
                int b;
                while ((b = stream.read()) != -1) {
                  toClient.write(b);
                }
                stream.close();

              }

              output = outHeader;
            }
          }

        }

      }

      toClient.writeBytes(output);
      s.close();
    } catch (IOException i) {

      i.printStackTrace();

    }

  }

  // Checks if file has been modified
  public boolean ifMod(String d, String res) {

    try {
      URL resource = getClass().getResource(res);
      if (resource == null) {
        toClient.writeBytes("HTTP/1.0 404 Not Found\r\n\r\n");
        return false;
      } else {
        File file = new File(resource.getPath());
        SimpleDateFormat sdf = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
        Date date = sdf.parse(d);
        long modTime = date.getTime();
        if (modTime <= System.currentTimeMillis() && modTime > file.lastModified()) {
          toClient.writeBytes("HTTP/1.0 304 Not Modified\r\n" + "Expires: Sat, 21 Jul 2021 11:00:00 GMT\r\n\r\n");
          return false;
        }
      }
    } catch (ParseException pe) {
      return true;
    } catch (IOException io) {
      return true;
    }

    return true;
  }

  // The main method for dealing with cookies
  public String cookies(String res, HashMap<String, String> hmap, String path) throws IOException {

    if (hmap.containsKey("Cookie")) {
      // check for a cookie and get the time from the cookie
      String cookie_time = hmap.get("Cookie");

      LocalDateTime d = LocalDateTime.now();
      DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      String new_cookie_time = d.format(format); // new time for the cookie

      cookie_time = URLDecoder.decode(cookie_time, "UTF-8");
      new_cookie_time = URLEncoder.encode(new_cookie_time, "UTF-8");

      hmap.put("Set-Cookie", new_cookie_time);

      // Update the information in the seen html file
      String temp = cookie_time.substring(9);
      String[] time = temp.split("-| |:");

      path += "index_seen.html";
      StringBuilder builder = new StringBuilder();
      String updated_info = "";

      FileReader reader = new FileReader(path);
      int itr;
      while ((itr = reader.read()) != -1) {
        builder.append((char) itr);
      }
      updated_info = builder.toString();
      reader.close();

      int old = updated_info.indexOf("%YEAR");

      updated_info = updated_info.substring(0, old) + time[0] + updated_info.substring(old + 5);

      old = updated_info.indexOf("%MONTH");
      updated_info = updated_info.substring(0, old) + time[1] + updated_info.substring(old + 6);

      old = updated_info.indexOf("%DAY");
      updated_info = updated_info.substring(0, old) + time[2] + updated_info.substring(old + 4);

      old = updated_info.indexOf("%HOUR");
      updated_info = updated_info.substring(0, old) + time[3] + updated_info.substring(old + 5);

      old = updated_info.indexOf("%SECOND");
      updated_info = updated_info.substring(0, old) + time[5] + updated_info.substring(old + 7);

      // update output to the HTTP response for cookie
      output = "HTTP/2.0 200 OK\r\n" + "Content-Type: text/html\r\n";
      output += "Set-Cookie: lasttime=" + hmap.get("Set-Cookie") + "\r\n\r\n";
      output += updated_info;
      return "";

    } else { // First client visit and cookie has not been set

      LocalDateTime d = LocalDateTime.now();
      DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
      String first_cookie_time = d.format(format);
      first_cookie_time = URLEncoder.encode(first_cookie_time, "UTF-8");

      hmap.put("Set-Cookie", first_cookie_time);
      path += "index.html"; // return the intial index html page
    }

    return path;
  }

}