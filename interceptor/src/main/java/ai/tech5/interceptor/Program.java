package ai.tech5.interceptor;

import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Properties;
import java.util.UUID;
import javax.net.ssl.SSLContext;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.methods.RequestBuilder;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;

import org.xnio.Options;
import org.xnio.Sequence;

import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormData.FormValue;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.util.HttpString;
import io.undertow.util.Methods;

import nl.altindag.ssl.SSLFactory;


public class Program {

    private static Properties config = new Properties();

    public static void main(final String[] args) throws Exception {
        
        // Load configuration file from .jar
        // InputStream configFile = Program.class.getResourceAsStream("/config.properties");

        // Get configuration file path from main() arguments
        FileInputStream configFile = new FileInputStream(args[0]);
        
        try {
            config.load(configFile);
        } catch (IOException e1) {
            e1.printStackTrace();
        }
        
        // HTTP handler to process incoming requests
        HttpHandler httpProcessorHandler = (exchange) -> {

            String ldsServiceAddress = config.getProperty("LDSServiceAddress");
            String ldsQueryString = exchange.getQueryString();

            if (ldsQueryString.length() > 0) {
                ldsQueryString = "?" + ldsQueryString;
            }
            
            if (exchange.getRequestMethod() == Methods.GET) {

                CloseableHttpClient httpClient = HttpClients.custom().build();

                HttpUriRequest requestGet = RequestBuilder.get()
                    .setUri(ldsServiceAddress + exchange.getRequestURI() + ldsQueryString)
                    .build();

                CloseableHttpResponse response = httpClient.execute(requestGet);

                exchange.setStatusCode(response.getStatusLine().getStatusCode());
                
                exchange.getResponseHeaders().clear();

                for (Header header : response.getAllHeaders()) {
                    
                    exchange.getResponseHeaders().put(HttpString.tryFromString(header.getName()), header.getValue());
                }

                exchange.getResponseSender().send(new String(response.getEntity().getContent().readAllBytes()));
            }
            else if (exchange.getRequestMethod() == Methods.POST)
            {
                org.apache.http.HttpResponse response = null;

                FormData attachments = exchange.getAttachment(FormDataParser.FORM_DATA);
                
                if (attachments != null) {
                                
                    try {

                        Deque<FormValue> formValueQueue = attachments.get(config.getProperty("FormDataAttachmentsFieldName"));

                        MultipartEntityBuilder multiPartEntityBuilder = MultipartEntityBuilder.create();
                        int numberOfAttachments = formValueQueue.size();
    
                        for (int x = 0; x < numberOfAttachments; x++) {
                            FormValue formValueItem = formValueQueue.pop();
                            multiPartEntityBuilder.addBinaryBody(formValueItem.getFileName(), formValueItem.getFileItem().getInputStream().readAllBytes(), ContentType.APPLICATION_OCTET_STREAM, formValueItem.getFileName());
                        }
    
                        HttpEntity entity = multiPartEntityBuilder.build();
    
                        HttpPost httpPost = new HttpPost(ldsServiceAddress + exchange.getRelativePath() + ldsQueryString);
                        httpPost.setEntity(entity);

                        CloseableHttpClient closeableHttpClient = HttpClientBuilder.create().build();
                        
                        response = closeableHttpClient.execute(httpPost);

                        if (response.getStatusLine().getStatusCode() == 200) {

                            // Call Billing service asynchronously and swallow any exception raised
                            String billingServiceAddress = config.getProperty("BillingServiceAddress");
                            String customerId = config.getProperty("CustomerID");
                            String projectId = config.getProperty("ProjectID");
                            String applicationId = config.getProperty("ApplicationID");
                            String transactionType = exchange.getRelativePath();
                            String transactionsCount = String.valueOf(numberOfAttachments);

                            String transactionId = UUID.randomUUID().toString();
                            String transactionTime = Instant.now().toString();
                            String envHostName = System.getenv("HOSTNAME");
                            String hostName = envHostName != null ? envHostName : InetAddress.getLocalHost().getHostName();
                            String clientIp = InetAddress.getLocalHost().getHostAddress();

                            String billingQueryString = "?tid=" + transactionId + "&time=" + transactionTime + "&cid=" + customerId + "&pid=" + projectId + "&appid=" + applicationId + "&type=" + transactionType + "&count=" + transactionsCount + "&host=" + hostName + "&cip=" + clientIp;
                            
                            try {
                            
                                int timeoutInMilliseconds = Integer.parseInt(config.getProperty("BillingConnectionTimeoutInMilliseconds"));

                                SSLContext sslContext = SSLFactory.builder()
                                    .withIdentityMaterial(Paths.get(config.getProperty("HTTPClientKeyStoreLocation")), config.getProperty("HTTPClientKeyStorePassword").toCharArray())
                                    .withTrustMaterial(Paths.get(config.getProperty("HTTPClientTrustStoreLocation")), config.getProperty("HTTPClientTrustStorePassword").toCharArray())
                                    .withSessionTimeout(timeoutInMilliseconds)
                                    .build()
                                    .getSslContext();

                                HttpClient httpClient = HttpClient.newBuilder()
                                    .connectTimeout(Duration.ofMillis(timeoutInMilliseconds))
                                    .sslContext(sslContext)
                                    .build();

                                HttpRequest requestHead = HttpRequest.newBuilder()
                                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                                    .uri(URI.create(billingServiceAddress + billingQueryString))
                                    .build();

                                httpClient.sendAsync(requestHead, java.net.http.HttpResponse.BodyHandlers.discarding());


                            } catch (Exception e) {
                                System.out.println(e.getMessage());
                            }
                        }
                        else {

                            exchange.setStatusCode(response.getStatusLine().getStatusCode());

                            exchange.getResponseHeaders().clear();

                            for (Header header : response.getAllHeaders()) {
                                
                                exchange.getResponseHeaders().put(HttpString.tryFromString(header.getName()), header.getValue());
                            }
            
                            exchange.getResponseSender().send(new String(response.getEntity().getContent().readAllBytes()));                            
                        }

                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
                else {

                    try {

                        HttpPost httpPost = new HttpPost(ldsServiceAddress + exchange.getRelativePath() + ldsQueryString);
                        CloseableHttpClient closeableHttpClient = HttpClientBuilder.create().build();
                        response = closeableHttpClient.execute(httpPost);

                        exchange.setStatusCode(response.getStatusLine().getStatusCode());

                    } catch (Exception e) {
                        System.out.println(e.getMessage());
                    }
                }

                exchange.getResponseHeaders().clear();

                for (Header header : response.getAllHeaders()) {
                    
                    exchange.getResponseHeaders().put(HttpString.tryFromString(header.getName()), header.getValue());
                }

                exchange.getResponseSender().send(new String(response.getEntity().getContent().readAllBytes()));
            }
        };

        SSLContext sslContext = SSLFactory.builder()
            .withIdentityMaterial(Paths.get(config.getProperty("HTTPServerKeyStoreLocation")), config.getProperty("HTTPServerKeyStorePassword").toCharArray())
            .withTrustMaterial(Paths.get(config.getProperty("HTTPServerTrustStoreLocation")), config.getProperty("HTTPServerTrustStorePassword").toCharArray())
            .build()
            .getSslContext();

        int httpServerPort = Integer.parseInt(config.getProperty("HTTPServerPort"));
        String httpServerIP = config.getProperty("HTTPServerIP");

        Undertow server = Undertow.builder()
            .addHttpsListener(httpServerPort, httpServerIP, sslContext)
            .setServerOption(UndertowOptions.ENABLE_HTTP2, Boolean.parseBoolean(config.getProperty("HTTPServerEnableHTTP2")))
            .setSocketOption(Options.SSL_ENABLED_PROTOCOLS, Sequence.of("TLSv1.2", "TLSv1.3"))
            .setHandler(
                new EagerFormParsingHandler(
                    FormParserFactory.builder()
                        .addParsers(new MultiPartParserDefinition())
                        .build()
                )
            .setNext(httpProcessorHandler))
            .build();
        server.start();
    }
}
