package ai.tech5.interceptor;

import java.util.Deque;
import java.util.List;
import java.util.Properties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Paths;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BiConsumer;
import javax.net.ssl.SSLContext;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.server.handlers.form.FormData.FormValue;
import io.undertow.server.handlers.form.EagerFormParsingHandler;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.entity.ContentType;
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


            class MyBiConsumer implements BiConsumer<String, List<String>> {

                public void accept(String headerName, List<String> headerValues)
                {
                    exchange.getResponseHeaders().put(HttpString.tryFromString(headerName), headerValues.get(0));
                }
            }


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


                java.net.http.HttpResponse<byte[]> response = httpAsynClient.send(requestGet, java.net.http.HttpResponse.BodyHandlers.ofByteArray());

                exchange.getResponseHeaders().clear();
                // BiConsumer<String, List<String>> action = new MyBiConsumer();
                // response.headers().map().forEach(action);
                response.headers().map().forEach(new MyBiConsumer());
                exchange.getResponseSender().send(new String(response.body()));



            }
            else if (exchange.getRequestMethod() == Methods.POST)
            {

                
                String responseText = "";

                // Receive the multipart form-data request and extract the attached image(s)
                FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);

                Deque<FormValue> formValueQueue = attachment.get("Data");

                MultipartEntityBuilder multiPartEntityBuilder = MultipartEntityBuilder.create();
                int numberOfAttachments = formValueQueue.size();

                for (int x = 0; x < numberOfAttachments + 1; x++) {
                    FormValue formValueItem = formValueQueue.pop();
                    multiPartEntityBuilder.addBinaryBody(formValueItem.getFileName(), formValueItem.getFileItem().getInputStream().readAllBytes(), ContentType.APPLICATION_OCTET_STREAM, formValueItem.getFileName());
                }

                HttpEntity entity = multiPartEntityBuilder.build();

                String ldsQueryString = exchange.getQueryString();

                if (ldsQueryString.length() > 0) {
                    ldsQueryString = "?" + ldsQueryString;
                }
                
                // Call LDS service using the image received
                HttpPost httpPost = new HttpPost(ldsServiceAddress + exchange.getRelativePath() + ldsQueryString);
                httpPost.setEntity(entity);

                String responseStatusCode = "0";

                CloseableHttpClient closeableHttpClient = HttpClientBuilder.create().build();

                org.apache.http.HttpResponse response = null;

                try {

                    response = closeableHttpClient.execute(httpPost);
                    HttpEntity result = response.getEntity();
                    responseStatusCode = Integer.toString(response.getStatusLine().getStatusCode());

                    InputStream stream = result.getContent();
                    byte[] bytes = stream.readAllBytes();

                    responseText = new String(bytes);

                } catch (IOException e) {
                    e.printStackTrace();
                }

                // Return the answer from LDS service
                exchange.getResponseHeaders().clear();

                for (Header header : response.getAllHeaders()) {
                    
                    exchange.getResponseHeaders().put(HttpString.tryFromString(header.getName()), header.getValue());
                }

                exchange.getResponseSender().send(responseText); 

                
                // Call Billing service and swallow any raised exception
                String billingServiceAddress = config.getProperty("BillingServiceAddress");
                String clientId = config.getProperty("ClientID");
                String applicationId = config.getProperty("ApplicationID");
                String transactionId = UUID.randomUUID().toString();
                String transactionTime = Instant.now().toString();
                String numberOfTransactions = String.valueOf(numberOfAttachments);

                String billingQueryString = "?tid=" + transactionId + "&time=" + transactionTime + "&cid=" + clientId + "&appId=" + applicationId + "&sc=" + responseStatusCode + "&nt=" + numberOfTransactions;
                
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
                .setServerOption(UndertowOptions.ENABLE_HTTP2, false)
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
