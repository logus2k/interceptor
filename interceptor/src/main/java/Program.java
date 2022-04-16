import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.io.InputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import io.undertow.Undertow;
import io.undertow.util.Headers;
import io.undertow.server.HttpHandler;
import io.undertow.server.handlers.form.FormData;
import io.undertow.server.handlers.form.FormDataParser;
import io.undertow.server.handlers.form.FormParserFactory;
import io.undertow.server.handlers.form.MultiPartParserDefinition;
import io.undertow.server.handlers.form.EagerFormParsingHandler;
import io.undertow.server.handlers.form.FormData.FileItem;

import org.apache.http.HttpEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.HttpClientBuilder;


public class Program {

    public static void main(final String[] args) {
        
        HttpHandler multipartProcessorHandler = (exchange) -> {

            
            // Receive the multipart form-data request and extracts the attached image
            FormData attachment = exchange.getAttachment(FormDataParser.FORM_DATA);
            FormData.FormValue formValue = attachment.get("Data").getFirst();
            
            FileItem fileItem = null;
            
            if (formValue.isFileItem()) {
                fileItem = formValue.getFileItem();
            } 

            
            
            
            // Call LDS service using the image received
            String url = "http://localhost:8080/check_liveness?data";
                        
            HttpEntity entity = MultipartEntityBuilder
                .create()
                .addBinaryBody("file", fileItem.getFile().toFile(), ContentType.APPLICATION_OCTET_STREAM, fileItem.getFile().toFile().getName())
                .build();
            
            HttpPost httpPost = new HttpPost(url);
            httpPost.setEntity(entity);

            String transactionStatusCode = "0";
            String responseText = "";

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
            String billingService = "http://localhost:5188/Billing";
            String clientId = "PAU";
            String applicationId = "LDS";
            String transactionId = UUID.randomUUID().toString();
            String transactionTime = Instant.now().toString();

            String billingQueryString = "?tid=" + transactionId + "&time=" + transactionTime + "&cid=" + clientId + "&appId=" + applicationId + "&sc=" + transactionStatusCode;
            
            try {
            
            
                HttpClient httpAsynClient = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(4))
                    .build();

                HttpRequest requestHead = HttpRequest.newBuilder()
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .uri(URI.create(billingService + billingQueryString))
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
        };
        
        Undertow server = Undertow.builder()
                .addHttpListener(9999, "127.0.0.1")
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
