package snapshots;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;

import javax.net.ssl.SSLSocketFactory;

import org.apache.http.HttpResponse;
import org.apache.http.ParseException;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.cookie.Cookie;
import org.apache.http.impl.client.BasicCookieStore;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.http.util.EntityUtils;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import kong.unirest.Unirest;
import kong.unirest.apache.ApacheClient;
import nl.altindag.ssl.SSLFactory;

public class UnifiProtectClient {

	private final String protocol = "https";
	
	private final String server;
	private final String username;
	private final String password;
	
	private CloseableHttpClient httpClient;
	private BasicCookieStore cookieStore = new BasicCookieStore();
	
	public UnifiProtectClient(String server, String username, String password) throws KeyManagementException, ClientProtocolException, NoSuchAlgorithmException, KeyStoreException, IOException {
		this.server = server;
		this.username = username;
		this.password = password;
		login();
	}
	
	private void login() throws ClientProtocolException, IOException, KeyManagementException, NoSuchAlgorithmException, KeyStoreException {
		SSLConnectionSocketFactory selfSignedCertTrust = new SSLConnectionSocketFactory(
				new SSLContextBuilder().loadTrustMaterial(null, new TrustSelfSignedStrategy()).build(),
				NoopHostnameVerifier.INSTANCE);
		
		httpClient = HttpClients.custom()
				.setDefaultCookieStore(cookieStore)
				.setSSLSocketFactory(selfSignedCertTrust)
				.build();
		
		HttpUriRequest loginRequest = RequestBuilder.post()
				.setUri(String.format("%s://%s/api/auth/login", protocol, server, username, password))
				.addParameter("username", username)
				.addParameter("password", password)
				.build();
		
		HttpResponse loginResponse = httpClient.execute(loginRequest);

//		System.out.println(EntityUtils.toString(loginResponse.getEntity()));
//		System.out.println("Login: " + loginResponse.getStatusLine());
		
		if(loginResponse.getStatusLine().getStatusCode() == 200) {
			final String TOKEN = "TOKEN";
			Optional<Cookie> tokenCookie = cookieStore.getCookies().stream()
				.filter(cookie -> cookie.getName().equals(TOKEN))
				.findFirst();
			if(!tokenCookie.isPresent()) {
				throw new RuntimeException("Could not find session token");
			}
		} else {
			throw new RuntimeException("Authentication failure");
		}
	}
	
	public void getSnapshot(String camera, String timestamp) throws ClientProtocolException, IOException, ParseException, org.json.simple.parser.ParseException, URISyntaxException, InterruptedException {
		String channel = "0";
		String format = "FMP4";
		
		String start = "0";
		String end = timestamp;

		// https://192.168.1.189/proxy/protect/api/ws/timelapse?camera=6084a66b01168603e701f998&channel=0&end=1625959382546&format=FMP4&reverse=true&start=0
		
		HttpUriRequest timelapseRequest = RequestBuilder.get()
				// TODO: what do these web sockets contain?
				.setUri(String.format("%s://%s/proxy/protect/api/ws/timelapse?camera=%s&channel=%s&format=%s&start=%s&end=%s", protocol, server, camera, channel, format, start, end))
//				.setUri(String.format("%s://%s/proxy/protect/api/ws/playback?camera=%s&channel=%s&format=%s&start=%s&end=%s", protocol, server, camera, channel, format, start, end))
				.build();
		
		HttpResponse timelapseResponse = httpClient.execute(timelapseRequest);
		
		System.out.println("Timelapse: " + timelapseResponse.getStatusLine());
		JSONParser parser = new JSONParser();
		JSONObject json = (JSONObject) parser.parse(EntityUtils.toString(timelapseResponse.getEntity()));
		String webSocketUrl = json.get("url").toString();
		System.out.println("Web Socket: " + webSocketUrl);
		
		WebSocketClient socket = new WebSocketClient(new URI(webSocketUrl)) {

			@Override
			public void onOpen(ServerHandshake handshakedata) {
				System.out.println("Connected");
			}

			@Override
			public void onClose(int code, String reason, boolean remote) {
				System.out.println("Closed with exit code " + code + " additional info: " + reason);
			}

			@Override
			public void onMessage(String message) {
				System.out.println("Received message: " + message);
			}

			@Override
			public void onMessage(ByteBuffer message) {
				System.out.println("Received ByteBuffer");
				System.out.println(StandardCharsets.UTF_8.decode(message).toString());
				
				// TODO ??? what is client reply???
				// chrome network ws parser shows non-ascii hex
				// send("hello");
			}

			@Override
			public void onError(Exception ex) {
				System.err.println("An error occurred:" + ex);
			}

		};
			
		// https://stackoverflow.com/a/67050581/475329
		SSLFactory sslFactory = SSLFactory.builder()
		    .withTrustingAllCertificatesWithoutValidation()
		    .build();
		SSLSocketFactory sslSocketFactory = sslFactory.getSslSocketFactory();
			
		socket.setSocketFactory(sslSocketFactory);
		
		socket.connect();
//		socket.connectBlocking();
	}
	
	public void download(String url, File output) {
		Unirest.config().httpClient(ApacheClient.builder(httpClient));
		Unirest.get(url).asFile(output.getAbsolutePath()).getBody();
		System.out.println("Downloaded: " + url);
	}

}
