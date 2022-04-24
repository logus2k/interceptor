package ai.tech5.interceptor;

import java.util.Deque;
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
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
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

                String ldsQueryString = exchange.getQueryString();

                if (ldsQueryString.length() > 0) {
                    ldsQueryString = "?" + ldsQueryString;
                }

                CloseableHttpClient httpClient = HttpClients.custom().build();

                HttpUriRequest requestGet = RequestBuilder.get()
                    .setUri(ldsServiceAddress + exchange.getRequestURI() + ldsQueryString)
                    .build();

                CloseableHttpResponse response = httpClient.execute(requestGet);

                
                exchange.getResponseHeaders().clear();

                Header[] responseHeaders = response.getAllHeaders();
                
                for (int x = 0; x < responseHeaders.length; x++) {
                    exchange.getResponseHeaders().put(HttpString.tryFromString(responseHeaders[x].getName()), responseHeaders[x].getValue());
                }

                exchange.getResponseSender().send(new String(response.getEntity().getContent().readAllBytes()));



            }
            else if (exchange.getRequestMethod() == Methods.POST)
            {

                
                // Receive the multipart form-data request and extract the attached image(s)
                FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);

                Deque<FormValue> formValueQueue = attachment.get("Data");

                MultipartEntityBuilder multiPartEntityBuilder = MultipartEntityBuilder.create();
                int numberOfAttachments = formValueQueue.size();

                for (int x = 0; x < numberOfAttachments; x++) {
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
                org.apache.http.HttpResponse response = null;
                String responseText = "";

                CloseableHttpClient closeableHttpClient = HttpClientBuilder.create().build();

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

                
                // Call Billing service and swallow any exception raised
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


                // Return the answer from LDS service
                exchange.getResponseHeaders().clear();

                for (Header header : response.getAllHeaders()) {
                    
                    exchange.getResponseHeaders().put(HttpString.tryFromString(header.getName()), header.getValue());
                }

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
