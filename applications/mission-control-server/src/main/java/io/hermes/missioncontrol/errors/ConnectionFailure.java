package io.hermes.missioncontrol.errors;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a failed outbound call into a reason an operator can act on.
 *
 * <p>The JDK HTTP client reports a failed connect as a bare {@link java.net.ConnectException}
 * whose own message is frequently null: an unresolvable host and a refused port both render
 * through {@code toString()} as the class name alone, which says nothing about which of the
 * two happened. The reason survives only further down the cause chain, so read it from there.
 */
public final class ConnectionFailure {

  /** Long enough for any real chain; a bound rather than a limit, so cycles cannot hang us. */
  private static final int MAX_DEPTH = 16;

  private static final String REFUSED = "connection refused — nothing is listening there";

  private ConnectionFailure() {}

  /** A short, lower-case reason phrase, safe to embed in a log line or an operator-facing note. */
  public static String describe(Throwable failure) {
    if (failure == null) return "no reason reported";
    List<Throwable> chain = chainOf(failure);

    // These arrive message-less, so they have to be recognised by type or they read as
    // "no reason reported" — the exact ambiguity this class exists to remove.
    for (Throwable t : chain) {
      if (t instanceof UnknownHostException || t instanceof UnresolvedAddressException) {
        return "the host name does not resolve";
      }
      if (t instanceof HttpConnectTimeoutException || t instanceof SocketTimeoutException) {
        return "the connection timed out";
      }
      if (t instanceof NoRouteToHostException) {
        return "no route to that host";
      }
    }

    // Linux loses the OS error on the client's async connect path: a refused port arrives as a
    // message-less ConnectException over a ClosedChannelException, where macOS reports
    // ConnectException("Connection refused"). A dead network or a blackholed address arrives as
    // HttpConnectTimeoutException instead, matched above — so a channel closed inside a failed
    // connect is the refusal, and naming it keeps the reason the same on either platform.
    if (containsType(chain, ConnectException.class)
        && containsType(chain, ClosedChannelException.class)) {
      return REFUSED;
    }

    for (Throwable t : chain) {
      String message = firstLineOf(t.getMessage());
      if (message != null) {
        return "Connection refused".equalsIgnoreCase(message) ? REFUSED : message;
      }
    }
    return chain.getLast().getClass().getSimpleName();
  }

  private static boolean containsType(List<Throwable> chain, Class<? extends Throwable> type) {
    for (Throwable t : chain) {
      if (type.isInstance(t)) return true;
    }
    return false;
  }

  /** The chain from {@code failure} down to its root cause, stopping at a repeat. */
  private static List<Throwable> chainOf(Throwable failure) {
    List<Throwable> chain = new ArrayList<>();
    Throwable t = failure;
    while (t != null && chain.size() < MAX_DEPTH && !containsIdentical(chain, t)) {
      chain.add(t);
      t = t.getCause();
    }
    return chain;
  }

  /** Identity, not {@code equals} — a self-referencing cause must stop the walk. */
  private static boolean containsIdentical(List<Throwable> chain, Throwable candidate) {
    for (Throwable seen : chain) {
      if (seen == candidate) return true;
    }
    return false;
  }

  private static String firstLineOf(String message) {
    if (message == null || message.isBlank()) return null;
    String firstLine = message.lines().findFirst().orElse(message).trim();
    if (firstLine.isEmpty()) return null;
    return firstLine.length() > 200 ? firstLine.substring(0, 200) : firstLine;
  }
}
