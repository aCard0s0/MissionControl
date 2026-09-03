package io.hermes.missioncontrol.docker;

/**
 * One Docker daemon, as everything downstream of a request refers to it: the dashboard's
 * host id and the endpoint that id resolves to, carried together.
 *
 * <p>Before this type the same concept crossed layer boundaries as either a {@code hostId}
 * or a {@code url}, chosen per callee — {@code agents} and {@code docker} took the url,
 * {@code mcp} took the id and re-resolved internally, and the two methods that needed both
 * took them side by side ({@code deploy(url, hostId, …)}). Resolution was therefore
 * scattered: a single request could resolve the same host three times, and nothing in a
 * signature said whether the daemon had been probed first.
 *
 * <p>Lives in this package rather than {@code hosts} on purpose. {@code hosts} already
 * depends on {@code docker} ({@link DockerGateway}, {@link DockerClients}), so putting the
 * shared type there would close a package cycle. Everything that talks to a daemon already
 * depends on {@code docker}, so this is the one place all of them can see it from.
 *
 * <p>The only two ways to obtain one are {@code HostService.requireConnected} — which
 * refuses a daemon that did not answer — and {@code HostService.ref}, which does not probe
 * and is for background work that is not answering a request. Which of the two a call site
 * used is therefore visible at the call site, which is what the {@code hostId}/{@code url}
 * split made impossible.
 *
 * @param id  the dashboard's host id, for rows that reference a host
 * @param url the daemon endpoint — {@code unix://…} or {@code tcp://…}
 */
public record DockerHostRef(String id, String url) {

  public DockerHostRef {
    if (id == null || id.isBlank()) throw new IllegalArgumentException("host id is required");
    if (url == null || url.isBlank()) throw new IllegalArgumentException("host url is required");
  }
}
