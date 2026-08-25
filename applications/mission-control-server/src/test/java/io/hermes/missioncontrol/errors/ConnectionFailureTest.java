package io.hermes.missioncontrol.errors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.channels.UnresolvedAddressException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ConnectionFailureTest {

  @Test
  void aRefusedConnectionSaysNothingIsListeningRatherThanRepeatingTheClassName() {
    assertEquals("connection refused — nothing is listening there",
        ConnectionFailure.describe(new ConnectException("Connection refused")));
  }

  @Test
  void aMessagelessDnsFailureIsNamedFromItsCauseChain() {
    // the shape the JDK http client actually produces: an empty ConnectException over an
    // UnresolvedAddressException that carries no message of its own
    ConnectException failure = new ConnectException();
    failure.initCause(new UnresolvedAddressException());

    assertEquals("the host name does not resolve", ConnectionFailure.describe(failure));
  }

  @Test
  void anUnknownHostIsReportedAsDnsRatherThanAsItsMessage() {
    assertEquals("the host name does not resolve",
        ConnectionFailure.describe(new UnknownHostException("ollama.invalid")));
  }

  @Test
  void aTimeoutIsDistinguishedFromARefusal() {
    assertEquals("the connection timed out",
        ConnectionFailure.describe(new java.net.SocketTimeoutException()));
  }

  @Test
  void anUnrecognisedFailureFallsBackToTheFirstMessageInTheChain() {
    IOException failure = new IOException();
    failure.initCause(new IllegalStateException("tls handshake aborted"));

    assertEquals("tls handshake aborted", ConnectionFailure.describe(failure));
  }

  @Test
  void aChainWithNoMessageAtAllStillNamesTheRootCause() {
    IOException failure = new IOException();
    failure.initCause(new IllegalStateException());

    assertEquals("IllegalStateException", ConnectionFailure.describe(failure));
  }

  @Test
  void aMultiLineMessageIsReducedToItsFirstLine() {
    assertEquals("upstream said no",
        ConnectionFailure.describe(new IOException("upstream said no\nand here is a stack dump")));
  }

  @Test
  void aSelfReferencingCauseChainTerminates() {
    // a cycle must bound the walk rather than hang the probe thread
    IOException outer = new IOException();
    IOException inner = new IOException();
    outer.initCause(inner);
    inner.initCause(outer);

    assertEquals("IOException", ConnectionFailure.describe(outer));
  }

  @Test
  void aNullFailureIsDescribedRatherThanThrowing() {
    assertEquals("no reason reported", ConnectionFailure.describe(null));
  }

  @Test
  void aRealRefusedConnectIsDescribedConcretely() throws Exception {
    // guards the whole point of the class: java.net.ConnectException.toString() is what the
    // log used to carry, and on a refused port it is all the operator got
    int closedPort;
    try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
      closedPort = socket.getLocalPort();
    }
    HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + closedPort))
        .timeout(Duration.ofSeconds(2)).GET().build();

    try {
      client.send(request, BodyHandlers.ofString());
    } catch (IOException e) {
      String described = ConnectionFailure.describe(e);
      assertTrue(described.contains("refused") || described.contains("timed out"), described);
      assertTrue(!described.contains("java.net."), described);
    }
  }
}
