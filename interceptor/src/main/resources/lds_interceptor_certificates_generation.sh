#!/bin/sh

# References:
## keytool - https://docs.oracle.com/en/java/javase/12/tools/keytool.html
## openssl - https://www.openssl.org/docs/man3.0/man1/


# LDS INTERCEPTOR WEB SERVER KEY, CERTIFICATE, KEY STORE, AND TRUST STORE GENERATION

## 1. Generate a private RSA key and a X509 certificate, in one step:
openssl req -x509 -newkey rsa:4096 -sha256 -days 10950 -nodes -keyout tech5lds.com.CA.key -out tech5lds.com.CA.crt -subj '/CN=tech5lds.com' -addext 'subjectAltName=DNS:tech5lds.com,DNS:www.tech5lds.com'

## 2. Create a PKCS12 keystore from the private key and the public certificate:
openssl pkcs12 -export -name tech5lds.com -in tech5lds.com.CA.crt -inkey tech5lds.com.CA.key -out tech5lds.com.keystore.p12 -passout pass:Tech5!

## 3. Convert a PKCS12 keystore into a JKS keystore:
keytool -importkeystore -srckeystore tech5lds.com.keystore.p12 -srcstoretype pkcs12 -srcstorepass Tech5! -destkeystore tech5lds.com.keystore.jks -deststoretype jks -deststorepass Tech5! -alias tech5lds.com

## 4. Import the server certificate to the server trust store:
keytool -import -alias tech5lds.com -file tech5lds.com.CA.crt -keystore tech5lds.com.truststore.p12 -storepass Tech5! -noprompt

## 5. Convert the PKCS12 truststore into a JKS truststore:
keytool -importkeystore -srckeystore tech5lds.com.truststore.p12 -srcstoretype pkcs12 -srcstorepass Tech5! -destkeystore tech5lds.com.truststore.jks -deststoretype jks -deststorepass Tech5! -alias tech5lds.com
