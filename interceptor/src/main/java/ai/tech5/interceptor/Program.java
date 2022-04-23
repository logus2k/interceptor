package ai.tech5.interceptor;

import java.util.Iterator;
import java.util.Properties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.nio.file.Paths;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import javax.net.ssl.SSLContext;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.client.UndertowClient;
import io.undertow.util.Headers;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.handlers.form.FormData.FileItem;

import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.entity.ContentType;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.xnio.Options;
import org.xnio.Sequence;

import nl.altindag.ssl.SSLFactory;


public class Program {

    private static Properties config = new Properties();

    public static void main(final String[] args) throws Exception {
        
        // Load configuration file from .jar
        // InputStream configFile = Program.class.getResourceAsStream("/config.properties");

        // Get configuration file path from arguments
        FileInputStream configFile = new FileInputStream(args[0]);
        
        try {
            config.load(configFile);
        } catch (IOException e1) {
            e1.printStackTrace();
        }

        
        // Define an HTTP handler to process incoming requests
        HttpHandler multipartProcessorHandler = (exchange) -> {


            String ldsServiceAddress = config.getProperty("LDSServiceAddress");

            
            
            if (exchange.getRequestMethod() == Methods.GET) {

                int ldsTimeoutMilliseconds = Integer.parseInt(config.getProperty("LDSConnectionTimeoutInMilliseconds"));
                
                String ldsQueryString = exchange.getQueryString();

                if (ldsQueryString.length() > 0) {
                    ldsQueryString = "?" + ldsQueryString;
                }

                HttpClient httpAsynClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(ldsTimeoutMilliseconds))
                    .build();

                HttpRequest requestGet = HttpRequest.newBuilder()
                    .method("GET", HttpRequest.BodyPublishers.noBody())
                    .uri(URI.create(ldsServiceAddress + exchange.getRequestURI() + ldsQueryString))
                    .build();

                java.net.http.HttpResponse<byte[]> response = httpAsynClient.send(requestGet,
                        java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                
                exchange.getRequestHeaders().put(Headers.CONTENT_TYPE, "text/json");

                exchange.getResponseSender().send(new String(response.body()));



            }
            else if (exchange.getRequestMethod() == Methods.POST)
            {

                String responseText = "";

                // Receive the multipart form-data request and extracts the attached image
                FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
                FormData.FormValue formValue = attachment.get("Data").getFirst();
                
                FileItem fileItem = null;
                
                if (formValue.isFileItem()) {
                    fileItem = formValue.getFileItem();
                } 

                
                

                String ldsQueryString = exchange.getQueryString();

                if (ldsQueryString.length() > 0) {
                    ldsQueryString = "?" + ldsQueryString;
                }
                
                // Call LDS service using the image received
                HttpEntity entity = MultipartEntityBuilder
                    .create()
                    .addBinaryBody("file", fileItem.getFile().toFile(), ContentType.APPLICATION_OCTET_STREAM, fileItem.getFile().toFile().getName())
                    .build();
                
                HttpPost httpPost = new HttpPost(ldsServiceAddress + exchange.getRelativePath() + ldsQueryString);
                httpPost.setEntity(entity);

                String transactionStatusCode = "0";

                CloseableHttpClient closeableHttpClient = HttpClientBuilder.create().build();

                try {

                    org.apache.http.HttpResponse response = closeableHttpClient.execute(httpPost);
                    HttpEntity result = response.getEntity();
                    transactionStatusCode = Integer.toString(response.getStatusLine().getStatusCode());

                    InputStream stream = result.getContent();
                    byte[] bytes = stream.readAllBytes();

                    responseText = new String(bytes);

                } catch (IOException e) {
                    e.printStackTrace();
                }


                
                // Call Billing service and swallow any raised exception
                String billingServiceAddress = config.getProperty("BillingServiceAddress");
                String clientId = config.getProperty("ClientID");
                String applicationId = config.getProperty("ApplicationID");
                String transactionId = UUID.randomUUID().toString();
                String transactionTime = Instant.now().toString();

                String billingQueryString = "?tid=" + transactionId + "&time=" + transactionTime + "&cid=" + clientId + "&appId=" + applicationId + "&sc=" + transactionStatusCode;
                
                try {
                
                
                    int timeoutMilliseconds = Integer.parseInt(config.getProperty("BillingConnectionTimeoutInMilliseconds"));

                    HttpClient httpAsynClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(timeoutMilliseconds))
                        .build();

                    HttpRequest requestHead = HttpRequest.newBuilder()
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .uri(URI.create(billingServiceAddress + billingQueryString))
                        .build();

                    httpAsynClient.sendAsync(requestHead, java.net.http.HttpResponse.BodyHandlers.discarding());
                    /*
                        .whenCompleteAsync((t, u) -> {
                            System.out.println(t.headers().toString());
                            System.out.println("httpResponse statusCode = ${t.statusCode()}");
                        })
                        .join();
                    */


                } catch (Exception e) {
                    System.out.println(e.getMessage());
                }

                // Return the answer from LDS service
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                exchange.getResponseSender().send(responseText);
            }


 
        };
        


        SSLContext sslContext = SSLFactory.builder()
          .withIdentityMaterial(Paths.get("C:/Labs/Git/interceptor/interceptor/src/main/keystore/TECH5.keystore"), "Tech5!".toCharArray())
          .withTrustMaterial(Paths.get("C:/Labs/Git/interceptor/interceptor/src/main/keystore/TECH5.truststore"), "Tech5!".toCharArray())
          .build()
          .getSslContext();


        int httpServerPort = Integer.parseInt(config.getProperty("HTTPServerPort"));
        String httpServerIP = config.getProperty("HTTPServerIP");

        Undertow server = Undertow.builder()
                // .addHttpListener(httpServerPort, httpServerIP)
                .addHttpsListener(httpServerPort, httpServerIP, sslContext)
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .setSocketOption(Options.SSL_ENABLED_PROTOCOLS, Sequence.of("TLSv1.2", "TLSv1.3"))
                .setHandler(
                    new EagerFormParsingHandler(
                        FormParserFactory.builder()
                            .addParsers(new MultiPartParserDefinition())
                            .build()
                    ).setNext(multipartProcessorHandler)
                ).build();
        server.start();
    }



}
